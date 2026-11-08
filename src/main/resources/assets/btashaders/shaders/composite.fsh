#version 410 core
// BTA Shaders composite pass. Runs after TAA and BTA's own post shader (bloom, FXAA, color correction):
// sharpening, sky glow, aerial perspective, underwater tint, god rays, color grading, vignette.

#include "lib/settings.glsl"

in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D colortex0;   // post-processed scene color
uniform sampler2D depthtex0;   // world depth (1.0 = sky)
uniform sampler2D shadowtex0;  // shadow map (debug overlay only)
uniform bool btsDebugShadowMap;
uniform bool guiOpened;
uniform int isEyeInLiquid;
uniform ivec2 windowSize;

uniform vec2 btsSunScreen;       // sun/moon position in screen UV
uniform float btsGodRayStrength; // 0 = off
uniform vec3 btsGodRayColor;     // light hue
uniform mat4 btsProjInv;         // un-jittered
uniform mat4 btsViewInv;
uniform vec3 btsLightDir;
uniform float btsSunHeight;
uniform float btsRain;
uniform int btsWorldValid;

layout (std140) uniform FogState {
    vec4 color;
    int mode;
    float start;
    float end;
    float density;
} fogState;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// Contrast-adaptive sharpening (the idea behind AMD's CAS, own implementation): a negative-lobe
// cross filter whose strength adapts per pixel. Low-contrast areas (softened by TAA) get sharpened
// most; strong edges and near-black/near-white areas less, so there are no halos.
vec3 sharpen(vec3 center) {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 maxP = windowSize - 1;
    vec3 n = texelFetch(colortex0, clamp(p + ivec2(0, 1), ivec2(0), maxP), 0).rgb;
    vec3 s = texelFetch(colortex0, clamp(p - ivec2(0, 1), ivec2(0), maxP), 0).rgb;
    vec3 e = texelFetch(colortex0, clamp(p + ivec2(1, 0), ivec2(0), maxP), 0).rgb;
    vec3 w = texelFetch(colortex0, clamp(p - ivec2(1, 0), ivec2(0), maxP), 0).rgb;
    vec3 mn = min(center, min(min(n, s), min(e, w)));
    vec3 mx = max(center, max(max(n, s), max(e, w)));
    // Per-channel headroom: how far we can push before clipping relative to local contrast.
    vec3 amp = clamp(min(mn, 1.0 - mx) / max(mx, vec3(1e-4)), 0.0, 1.0);
    amp = sqrt(amp);
    float peak = -1.0 / mix(8.0, 4.6, clamp(SHARPEN, 0.0, 1.0));
    vec3 weight = amp * peak;
    vec3 result = (center + (n + s + e + w) * weight) / (1.0 + 4.0 * weight);
    return clamp(result, 0.0, 1.0);
}

// The light's hue pushed towards warm gold (the plain sun color is almost white at noon, which
// reads as cold/cyan when added on top of a blue sky).
vec3 warmLight() {
    return btsGodRayColor * LIGHT_WARM_COLOR;
}

// Screen-space light shafts: march towards the sun counting unobstructed sky (depth == 1).
vec3 godRays() {
    vec2 delta = (btsSunScreen - TexCoords) / float(GODRAY_SAMPLES) * 0.9;
    vec2 uv = TexCoords + delta * ign(gl_FragCoord.xy);
    float accum = 0.0;
    float weight = 1.0;
    float weightSum = 0.0;
    for (int i = 0; i < GODRAY_SAMPLES; i++) {
        float sky = texture(depthtex0, clamp(uv, vec2(0.0), vec2(1.0))).r >= 0.99999 ? 1.0 : 0.0;
        accum += sky * weight;
        weightSum += weight;
        weight *= GODRAY_DECAY;
        uv += delta;
    }
    vec2 aspect = vec2(float(windowSize.x) / float(windowSize.y), 1.0);
    float falloff = exp(-length((TexCoords - btsSunScreen) * aspect) * GODRAY_FALLOFF);
    return warmLight() * (accum / weightSum) * falloff * btsGodRayStrength * GODRAY_INTENSITY;
}

void main() {
    vec3 color = texture(colortex0, TexCoords).rgb;
    if (SHARPEN > 0.0) {
        color = sharpen(color);
    }

    float depth = texture(depthtex0, TexCoords).r;
    if (btsWorldValid == 1) {
        vec4 viewPos = btsProjInv * vec4(TexCoords * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        viewPos /= viewPos.w;
        vec3 relPos = (btsViewInv * viewPos).xyz;
        vec3 viewDir = normalize(relPos);
        float dist = length(relPos);
        float sunDot = max(dot(viewDir, btsLightDir), 0.0);
        // Sunrise/sunset: strongest when the sun is low; none at night.
        float sunset = (1.0 - smoothstep(0.0, 0.4, abs(btsSunHeight))) * step(-0.15, btsSunHeight);
        float clear = 1.0 - btsRain;

        if (depth >= 1.0) {
            // Sky: warm glow around the low sun, spreading along the horizon.
            float horizon = 1.0 - clamp(abs(viewDir.y) * 2.5, 0.0, 1.0);
            float glow = pow(sunDot, 3.0) * (0.4 + 0.6 * horizon * horizon);
            color += warmLight() * glow * sunset * clear * SKY_GLOW;
            // Warm halo around the sun at any time of day (daytime only).
            float halo = pow(sunDot, 24.0) * step(0.0, btsSunHeight);
            color += warmLight() * halo * clear * SUN_GLOW;
        } else if (depth > 0.07 && isEyeInLiquid == 0) {
            // Aerial perspective: distant terrain picks up sky color, sun-colored towards the sun.
            float amount = (1.0 - exp(-dist * ATMOS_DENSITY)) * ATMOS_STRENGTH;
            vec3 skyCol = fogState.color.rgb;
            vec3 sunCol = warmLight() * luma(skyCol) * 1.6;
            vec3 tint = mix(skyCol, sunCol, pow(sunDot, 6.0) * clear * (0.3 + 0.7 * sunset));
            color = mix(color, tint, amount);
        }
        if (isEyeInLiquid == 1 && depth > 0.07) {
            // Underwater: light fades to blue-green with distance.
            float amount = (1.0 - exp(-dist / 14.0)) * UNDERWATER_FOG;
            color = mix(color, vec3(0.05, 0.20, 0.28) * (0.3 + 0.7 * luma(fogState.color.rgb)), amount);
        }
    }

    if (btsGodRayStrength > 0.001 && isEyeInLiquid == 0) {
        color += godRays();
    }

    // Warm white balance: a touch more red, less blue, mostly in the midtones and highlights.
    float warmMask = smoothstep(0.05, 0.6, luma(color));
    color *= mix(vec3(1.0), vec3(1.05, 1.0, 0.90), WARMTH * warmMask);

    // Vibrance: lift saturation of dull colors more than vivid ones.
    float mx = max(color.r, max(color.g, color.b));
    float mn = min(color.r, min(color.g, color.b));
    color = mix(vec3(luma(color)), color, 1.0 + VIBRANCE * (1.0 - (mx - mn)));

    // Gentle S-curve contrast.
    color = clamp(color, 0.0, 1.0);
    color = mix(color, color * color * (3.0 - 2.0 * color), CONTRAST);

    if (!guiOpened) {
        vec2 p = TexCoords - 0.5;
        color *= 1.0 - dot(p, p) * VIGNETTE;
    }

    // F9: shadow map overlay in the bottom-right corner.
    if (btsDebugShadowMap) {
        float side = float(windowSize.y) * 0.4;
        vec2 origin = vec2(float(windowSize.x) - side - 12.0, 12.0);
        vec2 uv = (gl_FragCoord.xy - origin) / side;
        if (uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0) {
            float d = texture(shadowtex0, uv).r;
            color = vec3(clamp((d - 0.5) * 6.0 + 0.5, 0.0, 1.0));
        }
    }

    FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
