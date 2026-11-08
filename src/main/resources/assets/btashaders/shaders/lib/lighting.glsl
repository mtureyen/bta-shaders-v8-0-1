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
uniform vec3 btsCameraFract;        // fract(camera position)
uniform int btsEyeInWater;

#include "lib/atmosphere.glsl"

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

    // Golden-angle spiral of hardware-filtered compares, rotated per pixel; taps offset in undistorted
    // space and distorted one by one (constant softness in world units). TAA averages the noise.
    int samples = foliage ? FOLIAGE_SHADOW_SAMPLES : SHADOW_SAMPLES;
    float radius = SHADOW_FILTER_RADIUS * (foliage ? FOLIAGE_FILTER_MULT : 1.0);
    float clipStep = radius * btsShadowTexel * 2.0 * distortFactor(clip.xy);
    float rotation = interleavedNoise(gl_FragCoord.xy) * 6.2831853;
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
    float fade = 1.0 - smoothstep(0.8, 1.0, max(abs(clip.x), abs(clip.y)));
    return shadowed * fade * btsShadowStrength;
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
        if (material == MAT_LEAVES) {
            nDotL = mix(nDotL, 1.0, LEAF_TRANSLUCENCY);
        }
    }
    float sky2 = sky * sky;
    float sky8 = sky2 * sky2 * sky2 * sky2;
    float vis = nDotL * (1.0 - shadowAmount(relPos, geoNormal, foliage, sky)) * sky8;
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
    vec3 direct = activeLightColor(btsLightIsSun == 1) * directVisibility(relPos, geoNormal, material, sky);
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
