// Shared sun/sky lighting + shadows for all lit world shaders (terrain, entity, world, item).
// Needs before inclusion: lib/settings.glsl, lib/materials.glsl, uniform sampler2D lighttex,
// uniform float uLightMapStrength.
//
// HDR light model (Complementary-style), computed in LINEAR space and returned gamma-encoded so the
// rest of each shader (albedo * light) stays as in BTA:
//   BTA's lightmap is additive (sky part + torch part, then gamma), so we split it:
//     skyLm   = lightmap(block 0, sky s)          BTA's sky brightness: time of day, weather,
//                                                 brightness setting, dimension, lightning
//     blockLm = lightmap(b, 0) - lightmap(0, 0)   BTA's torch color + falloff
//   scene  = skyLm luminance * atmosphere sky hue  +  sun/moon (HDR) * N.L * shadow * sky^8
//   light  = sqrt(scene^2 + block^2 + min^2)      quadrature sum (no blow-out where sun meets torch)

uniform int btsLightingOn;          // 0 = stock BTA lighting, 1 = world, 2 = first-person hand
uniform int btsShadowsOn;
uniform sampler2DShadow btsShadowTex;
uniform float btsShadowStrength;
uniform float btsShadowTexel;       // 1 / shadow map resolution
uniform float btsShadowWorldTexel;  // one shadow texel in blocks (before distortion)
uniform mat4 btsShadowMatrix;       // camera-relative position -> shadow clip space
uniform sampler2D btsShadowDepthRaw; // same shadow map without hardware compare (PCSS blocker search)
uniform float btsShadowDepthSpan;   // blocks covered by the shadow map's 0..1 depth range
uniform vec3 btsCameraFract;        // fract(camera position)
uniform int btsEyeInWater;

#include "lib/clouds.glsl"          // cloud shadows (includes sky + atmosphere)

// Direct sun visibility of the last computeLight() call (for the G-buffer).
float btsDirectOut = 0.0;
// Optional shading normal (labPBR normal maps) for the sun's N.L; zero = use the geometric normal.
vec3 btsShadeNormal = vec3(0.0);

// ---- Colored shadows (ShadowRenderer's translucent map) ----
// Light let through by translucent casters between the sun and the last shadowAmount() point
// (white = nothing in the way). Multiplies the direct light in computeLight().
vec3 btsShadowTint = vec3(1.0);

#if COLORED_SHADOWS
uniform int btsColoredShadowsOn;
uniform sampler2DShadow btsShadowTexTranslucent; // translucent casters only, hardware compare
uniform sampler2D btsShadowDepthTranslucent;     // same, raw (water depth)
uniform sampler2D btsShadowColor;                // rgb = light let through, a = 0 for water

float causticHash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float causticNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(causticHash(i), causticHash(i + vec2(1.0, 0.0)), u.x),
               mix(causticHash(i + vec2(0.0, 1.0)), causticHash(i + vec2(1.0, 1.0)), u.x), u.y);
}

// Thin bright lines where the noise crosses its middle value.
float causticLines(vec2 p) {
    float r = 1.0 - abs(causticNoise(p) * 2.0 - 1.0);
    float r2 = r * r;
    return r2 * r2 * r2;
}

// Underwater light pattern: two domain-warped line networks drifting against each other. Averages
// to about 1, so caustics move light around rather than adding any.
float caustics(vec2 p, float t) {
    vec2 warp = vec2(causticNoise(p * 0.45 + t * 0.12), causticNoise(p * 0.45 - t * 0.10 + 7.3)) * 2.2;
    float a = causticLines(p * 1.25 + warp + vec2(t * 0.18, 0.0));
    float b = causticLines(p * 1.6 - warp + vec2(3.1, -t * 0.22));
    return 0.55 + 1.6 * (a + b);
}

vec3 translucentTint(vec3 samplePos, vec2 uv, float refDepth) {
    float clear = textureLod(btsShadowTexTranslucent, vec3(uv, refDepth), 0.0);
    if (clear > 0.999) return vec3(1.0);
    vec4 caster = textureLod(btsShadowColor, uv, 0.0);
    vec3 tint;
    if (caster.a < 0.5) {
        // Under water: absorbed along the way down (same colors as looking through water), plus caustics.
        float surface = textureLod(btsShadowDepthTranslucent, uv, 0.0).r;
        float depthBlocks = max(refDepth - surface, 0.0) * btsShadowDepthSpan;
        tint = exp(-depthBlocks * WATER_ABSORPTION * vec3(0.30, 0.09, 0.06) * 0.6);
#if CAUSTICS
        vec3 world = samplePos + btsCameraMod;
        // Follow the light ray back up to the surface, so the pattern slants with the sun.
        vec2 p = world.xz + btsLightDir.xz / max(btsLightDir.y, 0.25) * depthBlocks;
        float amount = CAUSTICS_STRENGTH * smoothstep(0.0, 1.2, depthBlocks);
        tint *= mix(1.0, caustics(p, frameTimeCounter * 0.8), amount);
#endif
    } else {
        tint = toLinear(caster.rgb);
    }
    return mix(tint, vec3(1.0), clear);
}
#endif

float distortFactor(vec2 clipXY) {
    return length(clipXY) * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
}

float interleavedNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

// Geometric face normal from screen-space derivatives, facing the camera. Must be called in uniform
// control flow (top of main()).
vec3 geometricNormal(vec3 relPos) {
    vec3 n = normalize(cross(dFdx(relPos), dFdy(relPos)));
    return dot(n, relPos) > 0.0 ? -n : n;
}

// Crossed-quad plants are the only diagonal faces in the world (fallback when no material ID).
bool isDiagonalFace(vec3 n) {
    return abs(n.x) > 0.3 && abs(n.z) > 0.3;
}

// 0 = lit, 1 = fully shadowed. sky: 0..1 sky light at the fragment.
float shadowAmount(vec3 relPos, vec3 geoNormal, bool foliage, float sky) {
    btsShadowTint = vec3(1.0);
    if (btsShadowsOn == 0) {
        return 0.0;
    }
    vec3 world = relPos + btsCameraFract;
    vec3 blockCenter = floor(world + geoNormal * 0.01) + 0.5 - btsCameraFract;
    vec3 samplePos;
    if (foliage) {
        // Plants: no normal offset (meaningless normals); pulled towards the block center in the dark
        // so they never pick up light leaking through walls.
        samplePos = mix(blockCenter, relPos, 0.5 + 0.5 * sky) + vec3(0.0, 0.05, 0.0);
    } else {
        // Normal offset against acne, scaled by the local (distorted) texel size; more at grazing angles.
        float nDotL = clamp(dot(geoNormal, btsLightDir), 0.0, 1.0);
        float texelHere = btsShadowWorldTexel * distortFactor((btsShadowMatrix * vec4(relPos, 1.0)).xy);
        samplePos = relPos + geoNormal * texelHere * (0.8 + 1.6 * (1.0 - nDotL));
        // Cave light-leak fix: in the dark, pull towards the block center.
        float sky2 = sky * sky;
        samplePos = mix(samplePos, blockCenter, 0.2 * (1.0 - sky2 * sky2));
    }
    vec3 clip = (btsShadowMatrix * vec4(samplePos, 1.0)).xyz;
    if (abs(clip.x) >= 1.0 || abs(clip.y) >= 1.0 || clip.z >= 1.0) {
        return 0.0;
    }
    float refDepth = (clip.z * 0.5 + 0.5) - SHADOW_DEPTH_BIAS - (foliage ? 0.0001 : 0.0);
    float edgeFade = 1.0 - smoothstep(0.8, 1.0, max(abs(clip.x), abs(clip.y)));
#if COLORED_SHADOWS
    if (btsColoredShadowsOn == 1) {
        vec2 centerUV = clip.xy / distortFactor(clip.xy) * 0.5 + 0.5;
        btsShadowTint = mix(vec3(1.0), translucentTint(samplePos, centerUV, refDepth), edgeFade * btsShadowStrength);
    }
#endif

    // Golden-angle spiral of hardware-filtered compares, rotated per pixel; taps offset in undistorted
    // space and distorted one by one (constant softness in world units). TAA averages the noise.
    int samples = foliage ? FOLIAGE_SHADOW_SAMPLES : SHADOW_SAMPLES;
    float radius = SHADOW_FILTER_RADIUS * (foliage ? FOLIAGE_FILTER_MULT : 1.0);
    float texelClip = btsShadowTexel * 2.0 * distortFactor(clip.xy); // one texel here, in clip units
    float rotation = interleavedNoise(gl_FragCoord.xy) * 6.2831853;
#if PCSS
    if (!foliage) {
        // Contact-hardening (PCSS): find the average depth of whatever blocks the light nearby; the
        // further the shadow falls from its caster, the softer it gets (like a real sun's penumbra).
        float blockerSum = 0.0;
        float blockers = 0.0;
        float searchClip = PCSS_SEARCH_RADIUS * texelClip;
        for (int i = 0; i < PCSS_BLOCKER_SAMPLES; i++) {
            float r = sqrt((float(i) + 0.5) / float(PCSS_BLOCKER_SAMPLES));
            float a = rotation + float(i) * 2.3999632;
            vec2 tap = clip.xy + vec2(cos(a), sin(a)) * r * searchClip;
            float d = textureLod(btsShadowDepthRaw, tap / distortFactor(tap) * 0.5 + 0.5, 0.0).r;
            if (d < refDepth) {
                blockerSum += d;
                blockers += 1.0;
            }
        }
        if (blockers < 0.5) {
            return 0.0; // nothing in the way: fully lit, skip the filter
        }
        float blockerDistance = (refDepth - blockerSum / blockers) * btsShadowDepthSpan; // blocks
        float texelWorld = btsShadowWorldTexel * distortFactor(clip.xy);
        radius = clamp(blockerDistance * PCSS_SOFTNESS / texelWorld, PCSS_MIN_RADIUS, PCSS_MAX_RADIUS);
    }
#endif
    float clipStep = radius * texelClip;
    float lit = 0.0;
    for (int i = 0; i < FOLIAGE_SHADOW_SAMPLES; i++) {
        if (i >= samples) break;
        float r = sqrt((float(i) + 0.5) / float(samples));
        float a = rotation + float(i) * 2.3999632;
        vec2 tap = clip.xy + vec2(cos(a), sin(a)) * r * clipStep;
        vec2 uv = tap / distortFactor(tap) * 0.5 + 0.5;
        lit += textureLod(btsShadowTex, vec3(uv, refDepth), 0.0);
    }
    float shadowed = 1.0 - lit / float(samples);
    return shadowed * edgeFade * btsShadowStrength;
}

float skyFactor(float lightmapSky) {
    return clamp((lightmapSky * 16.0 - 0.5) / 15.0, 0.0, 1.0);
}

// Direct-light visibility (N.L * shadow * open sky) as used by computeLight; exposed for specular.
float directVisibility(vec3 relPos, vec3 geoNormal, int material, float sky) {
    bool foliage = material == MAT_PLANT;
    float nDotL;
    if (btsLightingOn == 2) {
        return HAND_NDOTL * pow(sky, 8.0);
    }
    if (foliage) {
        nDotL = 1.0;
    } else {
        nDotL = max(dot(geoNormal, btsLightDir), 0.0);
        if (nDotL > 0.0 && dot(btsShadeNormal, btsShadeNormal) > 0.5) {
            // Normal-mapped bumps; faces turned away from the light stay unlit.
            nDotL = max(dot(btsShadeNormal, btsLightDir), 0.0);
        }
        if (material == MAT_LEAVES) {
            nDotL = mix(nDotL, 1.0, LEAF_TRANSLUCENCY);
        }
    }
    float sky2 = sky * sky;
    float sky8 = sky2 * sky2 * sky2 * sky2;
    float vis = nDotL * (1.0 - shadowAmount(relPos, geoNormal, foliage, sky)) * sky8;
    vis *= cloudShadow(relPos);
    // Subsurface: thin foliage always lets some sunlight through, even inside its own (or a
    // neighbouring) canopy's shadow. Keeps trees green instead of black.
    if (material == MAT_LEAVES) {
        vis = max(vis, LEAF_SUBSURFACE * sky8);
    } else if (foliage) {
        vis = max(vis, PLANT_SUBSURFACE * sky8);
    }
    return vis;
}

// lightCoord: BTA lightmap coordinates (x = block light, y = sky light); emissive: from emissivetex.
// material: MAT_* (use MAT_DEFAULT when unknown; plants are also detected from the geometry).
// Returns the light multiplier, gamma-encoded, may exceed 1 (HDR).
vec3 computeLight(vec2 lightCoord, vec2 emissive, vec3 relPos, vec3 geoNormal, int material) {
    vec2 coord = max(lightCoord, emissive);
    vec3 stock = texture(lighttex, coord).rgb;
    if (btsLightingOn == 0) {
        return stock;
    }
    if (material == MAT_DEFAULT && btsLightingOn == 1 && isDiagonalFace(geoNormal)) {
        material = MAT_PLANT;
    }
    const float MIN_COORD = 0.5 / 16.0;
    float sky = skyFactor(lightCoord.y);

    // Split BTA's (additive) lightmap into its parts, in linear space.
    vec3 minLm = toLinear(texture(lighttex, vec2(MIN_COORD)).rgb);
    vec3 skyLm = toLinear(texture(lighttex, vec2(MIN_COORD, coord.y)).rgb);
    vec3 blockLm = max(toLinear(texture(lighttex, vec2(coord.x, MIN_COORD)).rgb) - minLm, 0.0);
    float skyBrightness = max(dot(skyLm - minLm, vec3(0.2126, 0.7152, 0.0722)), 0.0);

    vec3 ambient = skyBrightness * ambientSkyHue() * AMBIENT_STRENGTH;
    // Bounce light: sunlit ground reflects warm light onto sides and undersides.
    float facingDown = btsLightingOn == 2 ? 0.5 : clamp(0.5 - 0.5 * geoNormal.y, 0.0, 1.0);
    ambient += sunLightColor() * sunVisibility() * BOUNCE_LIGHT * facingDown * sky * sky;
    float directVis = directVisibility(relPos, geoNormal, material, sky);
    btsDirectOut = directVis;
    vec3 direct = activeLightColor(btsLightIsSun == 1) * directVis * btsShadowTint;
    vec3 scene = ambient + direct;
    vec3 block = blockLm * BLOCKLIGHT_STRENGTH * BLOCKLIGHT_TINT;

    vec3 light = sqrt(scene * scene + block * block) + minLm * MIN_LIGHT;
    vec3 result = toGamma(light);
    if (material == MAT_EMISSIVE || material == MAT_LAVA) {
        result = max(result, stock); // glowing blocks never get darker than stock
    }
    return result;
}

// Face shading. Stock: BTA's fixed two-light shading. With our lighting, real sunlight already
// shades by direction, so BTA's (strong: bottoms ~50%) shading on top would darken twice; use a mild
// up/down gradient instead (Complementary does the same with vanilla's).
vec3 faceShading(vec3 btaLight, vec3 geoNormal) {
    if (btsLightingOn == 0) {
        return clamp(btaLight, 0.0, 1.0);
    }
    return vec3(mix(FACE_SHADE_BOTTOM, 1.0, geoNormal.y * 0.5 + 0.5));
}

// BTA's baked per-vertex shade (corner darkening), softened under our lighting.
float bakedShade(float shade) {
    return btsLightingOn == 0 ? shade : mix(1.0, shade, BAKED_SHADE_STRENGTH);
}

// BTA's fixed two-light directional shading, computed from the geometric normal (the vertex
// normal bytes carry material IDs now).
vec3 btaDirectionalLight(vec3 n, mat4 normalTransform, vec4 ambient, vec4 pos1, vec4 pos2, vec4 diffuse1) {
    vec4 norm = normalize(normalTransform * vec4(n, 0.0));
    float d1 = max(0.0, dot(norm, pos1));
    float d2 = max(0.0, dot(norm, pos2));
    return (ambient + diffuse1 * (d1 + d2)).xyz;
}
