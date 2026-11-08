// Water shading for the translucent terrain pass. Needs: lib/settings.glsl, lib/lighting.glsl, lib/surface.glsl,
// lib/fog.glsl, the Matrices block, uViewPortSize, frameTimeCounter, btsCameraMod.
// The opaque scene was copied right before the translucent pass, so water can look "through" itself
// (refraction + absorption) and reflect what's on screen (SSR), Complementary/Photon style.

uniform sampler2D btsOpaqueColor;   // opaque scene color (before water)
uniform sampler2D btsOpaqueDepth;   // opaque scene depth (R32F copy)

float linearDepth(float depth) {
    float zNdc = depth * 2.0 - 1.0;
    return matrices.projection[3][2] / (zNdc + matrices.projection[2][2]);
}

float waveHeight(vec2 p, float t) {
    float h = 0.0;
    h += sin(dot(p, vec2(0.80, 0.60)) * 1.3 + t * 1.6) * 0.50;
    h += sin(dot(p, vec2(-0.55, 0.85)) * 2.1 + t * 2.1) * 0.30;
    h += sin(dot(p, vec2(0.95, -0.30)) * 3.7 + t * 2.9) * 0.14;
    h += sin(dot(p, vec2(-0.20, -0.98)) * 6.3 + t * 3.7) * 0.06;
    return h;
}

// World-space normal of the animated water surface.
vec3 waterNormal(vec2 worldXZ, float t) {
    const float e = 0.08;
    float h0 = waveHeight(worldXZ, t);
    float hx = waveHeight(worldXZ + vec2(e, 0.0), t);
    float hz = waveHeight(worldXZ + vec2(0.0, e), t);
    float strength = 0.08 * WATER_WAVE_STRENGTH;
    return normalize(vec3(-(hx - h0) / e * strength, 1.0, -(hz - h0) / e * strength));
}

vec3 skyReflectionColor(vec3 dir) {
    // BTA's fog color is its horizon sky color; brighten towards the zenith a bit.
    vec3 horizon = fogState.color.rgb;
    vec3 zenith = horizon * vec3(0.75, 0.85, 1.05);
    vec3 fallback = mix(horizon, zenith, clamp(dir.y, 0.0, 1.0));
    // With our own sky active (btsSkyFog), reflect that sky instead of BTA's fog color.
    return btsSkyFog == 1 ? toGamma(skyColor(normalize(vec3(dir.x, max(dir.y, 0.02), dir.z)))) : fallback;
}

// Screen-space reflection against the opaque scene. Returns rgb + hit confidence in a.
vec4 screenSpaceReflection(vec3 startRel, vec3 dir) {
    mat4 viewProj = matrices.projection * matrices.view;
    float stepLen = 0.4;
    vec3 prev = startRel;
    vec3 p = startRel + dir * 0.15;
    for (int i = 0; i < WATER_SSR_STEPS; i++) {
        prev = p;
        p += dir * stepLen;
        stepLen *= 1.32;
        vec4 clip = viewProj * vec4(p, 1.0);
        if (clip.w <= 0.0) break;
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) break;
        float sceneDepth = texture(btsOpaqueDepth, uv).r;
        if (sceneDepth >= 1.0) continue;
        float sceneLin = linearDepth(sceneDepth);
        if (clip.w > sceneLin + 0.02) {
            // Refine between the last two points.
            vec3 a = prev;
            vec3 b = p;
            for (int j = 0; j < 5; j++) {
                vec3 m = (a + b) * 0.5;
                vec4 mc = viewProj * vec4(m, 1.0);
                vec2 muv = mc.xy / mc.w * 0.5 + 0.5;
                if (mc.w > linearDepth(texture(btsOpaqueDepth, muv).r)) b = m; else a = m;
            }
            vec4 hc = viewProj * vec4(b, 1.0);
            vec2 huv = hc.xy / hc.w * 0.5 + 0.5;
            float thickness = hc.w - linearDepth(texture(btsOpaqueDepth, huv).r);
            if (thickness > stepLen * 1.5 + 0.5) break; // passed behind an object, not a real hit
            vec2 edge = smoothstep(vec2(0.0), vec2(0.12), huv) * (1.0 - smoothstep(vec2(0.88), vec2(1.0), huv));
            return vec4(texture(btsOpaqueColor, huv).rgb, edge.x * edge.y);
        }
    }
    return vec4(0.0);
}

// waterBase: the water's own (BTA-textured, lit) color. Returns final opaque color.
vec3 shadeWater(vec3 relPos, vec3 geoNormal, vec3 waterBase, float sky, float directVis) {
    vec2 screenUV = gl_FragCoord.xy / uViewPortSize;
    float waterLin = linearDepth(gl_FragCoord.z);
    vec3 world = relPos + btsCameraMod;
    bool topSurface = geoNormal.y > 0.5;
    vec3 n = topSurface ? waterNormal(world.xz, frameTimeCounter) : geoNormal;
#if RAIN_RIPPLES
    if (topSurface && btsRain > 0.01 && sky > 0.9) {
        vec2 ripple = rainRipples(world.xz, frameTimeCounter) * btsRain * 0.6;
        n = normalize(n + vec3(ripple.x, 0.0, ripple.y));
    }
#endif
    vec3 v = normalize(-relPos);
    bool underwater = btsEyeInWater == 1;
    if (underwater) n = -n;

    // Refraction: offset into the opaque copy, rejected if the offset pixel is in front of the water.
    vec2 refrUV = screenUV + n.xz * WATER_REFRACTION / (1.0 + waterLin * 0.05);
    float opaqueDepth = texture(btsOpaqueDepth, refrUV).r;
    if (linearDepth(opaqueDepth) < waterLin) {
        refrUV = screenUV;
        opaqueDepth = texture(btsOpaqueDepth, refrUV).r;
    }
    vec3 below = texture(btsOpaqueColor, refrUV).rgb;
    if (opaqueDepth >= 1.0) {
        // Open sky behind the water (looking up from below): the opaque copy is taken before our sky is
        // drawn, so those pixels are still black. Use the sky itself.
        below = skyReflectionColor(normalize(relPos)) * mix(0.15, 1.0, sky);
    }
    float thickness = opaqueDepth >= 1.0 ? 64.0 : max(linearDepth(opaqueDepth) - waterLin, 0.0);
    if (underwater) thickness = 0.0; // looking up through the surface: no extra absorption here
    vec3 transmittance = exp(-thickness * WATER_ABSORPTION * vec3(0.30, 0.09, 0.06));
    // Deep water takes on the water texture's color, shallow water shows the ground.
    vec3 refracted = below * transmittance + waterBase * (1.0 - transmittance);

    // Reflection: SSR, falling back to the sky color (scaled by sky light so caves don't glow).
    float fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
    vec3 r = reflect(-v, n);
    vec3 reflection = skyReflectionColor(r) * mix(0.15, 1.0, sky);
#if WATER_REFLECTIONS
    if (!underwater) {
        vec4 ssr = screenSpaceReflection(relPos, r);
        reflection = mix(reflection, ssr.rgb, ssr.a);
    }
#endif
    vec3 color = mix(refracted, reflection, underwater ? 0.0 : fresnel);

    // Sun glint.
    float spec = pow(max(dot(r, btsLightDir), 0.0), 220.0);
    color += toGamma(activeLightColor(btsLightIsSun == 1)) * spec * WATER_SPECULAR * directVis;
    return color;
}
