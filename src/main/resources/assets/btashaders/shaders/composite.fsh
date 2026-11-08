#version 410 core
// BTA Shaders composite pass: the very last step (after tone mapping, TAA and BTA's own post shader).
// Sharpening, white balance, vibrance, contrast, vignette, debug overlays. All LDR.

#include "lib/settings.glsl"

in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D colortex0;   // post-processed scene color
uniform sampler2D shadowtex0;  // shadow map (debug overlay only)
uniform bool btsDebugShadowMap;
uniform bool guiOpened;
uniform ivec2 windowSize;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
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
    vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(1e-4)), 0.0, 1.0));
    float peak = -1.0 / mix(8.0, 4.6, clamp(SHARPEN, 0.0, 1.0));
    vec3 weight = amp * peak;
    return clamp((center + (n + s + e + w) * weight) / (1.0 + 4.0 * weight), 0.0, 1.0);
}

void main() {
    vec3 color = texture(colortex0, TexCoords).rgb;
    if (SHARPEN > 0.0) {
        color = sharpen(color);
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
