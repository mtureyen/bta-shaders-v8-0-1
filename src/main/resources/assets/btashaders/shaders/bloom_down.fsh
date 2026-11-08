#version 410 core
// Bloom downsample: 13-tap filter (the "dual box" pattern from Jimenez 2014, own implementation).
// The first level also converts the gamma-encoded HDR scene to linear and tames single-pixel
// fireflies with a luminance-weighted average, so bloom doesn't flicker.

#include "lib/settings.glsl"

in vec2 TexCoords;
out vec4 FragColor;

// Soft threshold: only bright areas glow, with a smooth knee instead of a hard cut. Dark areas
// (tree canopies next to bright sky) no longer get a milky veil.
vec3 softThreshold(vec3 c) {
    float brightness = max(c.r, max(c.g, c.b));
    float knee = BLOOM_THRESHOLD * BLOOM_KNEE;
    float soft = clamp(brightness - BLOOM_THRESHOLD + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-4);
    float contribution = max(soft, brightness - BLOOM_THRESHOLD) / max(brightness, 1e-4);
    return c * contribution;
}

uniform sampler2D btsSrc;
uniform vec2 btsSrcTexel;     // 1 / source size
uniform int btsFirstLevel;

vec3 fetch(vec2 uv) {
    vec3 c = texture(btsSrc, uv).rgb;
    return btsFirstLevel == 1 ? pow(max(c, 0.0), vec3(2.2)) : c;
}

float karisWeight(vec3 c) {
    return 1.0 / (1.0 + dot(c, vec3(0.2126, 0.7152, 0.0722)));
}

void main() {
    vec2 t = btsSrcTexel;
    vec3 a = fetch(TexCoords + t * vec2(-2.0,  2.0));
    vec3 b = fetch(TexCoords + t * vec2( 0.0,  2.0));
    vec3 c = fetch(TexCoords + t * vec2( 2.0,  2.0));
    vec3 d = fetch(TexCoords + t * vec2(-2.0,  0.0));
    vec3 e = fetch(TexCoords);
    vec3 f = fetch(TexCoords + t * vec2( 2.0,  0.0));
    vec3 g = fetch(TexCoords + t * vec2(-2.0, -2.0));
    vec3 h = fetch(TexCoords + t * vec2( 0.0, -2.0));
    vec3 i = fetch(TexCoords + t * vec2( 2.0, -2.0));
    vec3 j = fetch(TexCoords + t * vec2(-1.0,  1.0));
    vec3 k = fetch(TexCoords + t * vec2( 1.0,  1.0));
    vec3 l = fetch(TexCoords + t * vec2(-1.0, -1.0));
    vec3 m = fetch(TexCoords + t * vec2( 1.0, -1.0));

    vec3 result;
    if (btsFirstLevel == 1) {
        // Five 4-tap boxes, each weighted by 1/(1+luma) (Karis average) against fireflies.
        vec3 b0 = (j + k + l + m) * 0.25;
        vec3 b1 = (a + b + d + e) * 0.25;
        vec3 b2 = (b + c + e + f) * 0.25;
        vec3 b3 = (d + e + g + h) * 0.25;
        vec3 b4 = (e + f + h + i) * 0.25;
        float w0 = karisWeight(b0) * 0.5;
        float w1 = karisWeight(b1) * 0.125;
        float w2 = karisWeight(b2) * 0.125;
        float w3 = karisWeight(b3) * 0.125;
        float w4 = karisWeight(b4) * 0.125;
        result = (b0 * w0 + b1 * w1 + b2 * w2 + b3 * w3 + b4 * w4) / (w0 + w1 + w2 + w3 + w4);
        result = softThreshold(result);
    } else {
        result = e * 0.125;
        result += (a + c + g + i) * 0.03125;
        result += (b + d + f + h) * 0.0625;
        result += (j + k + l + m) * 0.125;
    }
    FragColor = vec4(max(result, 0.0), 1.0);
}
