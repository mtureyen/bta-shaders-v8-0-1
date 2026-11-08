// Volumetric light (light shafts): march along the view ray through the shadow map. Shared by the
// half-resolution vl pass (default) and the tonemap pass (full resolution, VL_HALF_RES 0).
// Needs before inclusion: lib/settings.glsl, lib/clouds.glsl, ign(), isEyeInLiquid, btsFrame.
#ifndef BTS_VOLUMETRIC
#define BTS_VOLUMETRIC

// Volumetric light: the shadow map (same as the world shaders use).
uniform sampler2DShadow btsShadowTex;
uniform mat4 btsShadowMatrix;
uniform int btsShadowsOn;
uniform float btsShadowStrength;
uniform int btsColoredShadowsOn;                // translucent casters (tinted glass, water)
uniform sampler2DShadow btsShadowTexTranslucent;
uniform sampler2D btsShadowColor;              // rgb = light let through, a = 0 for water

// Real volumetric light (replaces the screen-space god rays): march along the view ray and ask the
// shadow map + cloud shadow how much sunlight reaches each point of air; forward scattering makes it
// strongest when looking towards the sun. Works with the sun off-screen, and through trees/windows.
// Returns colored light (tinted where the sun shines through glass or water before reaching the air).
vec3 volumetricLight(vec3 viewDir, float sceneDist, float range) {
    if (btsShadowsOn == 0) return vec3(0.0);
    float maxDist = min(sceneDist, range);
    float jitter = fract(ign(gl_FragCoord.xy) + 0.618034 * mod(btsFrame, 64.0));
    vec3 sum = vec3(0.0);
    for (int i = 0; i < VL_STEPS; i++) {
        float t = (float(i) + jitter) / float(VL_STEPS);
        float d = t * t * maxDist; // denser samples close to the camera
        vec3 p = viewDir * d;
        vec3 clip = (btsShadowMatrix * vec4(p, 1.0)).xyz;
        float lit = 1.0;
        vec3 tint = vec3(1.0);
        if (abs(clip.x) < 1.0 && abs(clip.y) < 1.0 && clip.z < 1.0) {
            vec2 uv = clip.xy / (length(clip.xy) * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT)) * 0.5 + 0.5;
            float ref = clip.z * 0.5 + 0.5 - 0.0002;
            lit = textureLod(btsShadowTex, vec3(uv, ref), 0.0);
#if COLORED_SHADOWS
            if (btsColoredShadowsOn == 1 && lit > 0.0) {
                float clearT = textureLod(btsShadowTexTranslucent, vec3(uv, ref), 0.0);
                if (clearT < 0.999) {
                    vec4 caster = textureLod(btsShadowColor, uv, 0.0);
                    vec3 c = caster.a < 0.5 ? UNDERWATER_VL_TINT : toLinear(caster.rgb);
                    tint = mix(c, vec3(1.0), clearT);
                }
            }
#endif
        }
        lit *= cloudShadow(p);
        // Each sample stands for a segment whose length grows with t^2.
        sum += lit * tint * (2.0 * t + 1.0 / float(VL_STEPS));
    }
    vec3 segments = sum / float(VL_STEPS);
    return segments * (maxDist / range);
}

// Henyey-Greenstein phase function: how much light scatters towards the camera.
float phaseHG(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * 3.14159265 * pow(max(1.0 + g2 - 2.0 * g * cosTheta, 1e-4), 1.5));
}

// Light added by the shafts in front of a pixel (linear HDR). dist: distance to the scene point
// (ignored for sky pixels, depth >= 1).
vec3 volumetricColor(vec3 viewDir, float dist, float depth) {
    if (depth <= 0.07 || isEyeInLiquid > 1) return vec3(0.0);
    // Under water: shorter, denser, blue-green shafts (the light came through the surface).
    bool underwater = isEyeInLiquid == 1;
    float range = underwater ? UNDERWATER_VL_DISTANCE : VL_DISTANCE;
    float sceneDist = depth >= 1.0 ? range : dist;
    vec3 vl = volumetricLight(viewDir, sceneDist, range);
    float cosTheta = dot(viewDir, btsLightDir);
    // Mostly forward scattering, plus a little in every direction.
    float phase = mix(phaseHG(cosTheta, 0.65), 1.0 / (4.0 * 3.14159265), 0.35) * 4.0 * 3.14159265;
    // Hazier air in the morning/evening, little at noon, more in rain (but no direct sun then).
    float sunset = (1.0 - smoothstep(0.0, 0.4, abs(btsSunHeight))) * step(-0.15, btsSunHeight);
    float density = VL_STRENGTH * (0.35 + 0.65 * sunset) * (1.0 - btsRain * 0.7) * btsShadowStrength;
    if (underwater) density = VL_STRENGTH * UNDERWATER_VL_STRENGTH * btsShadowStrength;
    vec3 lightColor = activeLightColor(btsLightIsSun == 1);
    return lightColor * (underwater ? vec3(1.0) : LIGHT_WARM_COLOR) * vl * phase * density;
}

#endif
