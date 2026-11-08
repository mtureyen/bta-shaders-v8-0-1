#version 410 core
// Eye adaptation: measures the scene's average brightness (center-weighted, from a small mip of the
// HDR image) and moves the exposure towards a target, smoothly over time. Output: 1x1, r = exposure.

#include "lib/settings.glsl"

in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D btsScene;        // HDR scene (gamma-encoded), mipmapped
uniform sampler2D btsPrevExposure; // 1x1, last frame's exposure
uniform float btsSceneLod;         // a mip level with only a few pixels left
uniform float btsDeltaTime;        // seconds since last frame
uniform int btsExposureValid;

void main() {
    // 3x3 center-weighted average: what's in front of you matters more than the screen edges.
    float sum = 0.0;
    float weightSum = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 uv = vec2(0.5) + vec2(x, y) * 0.3;
            float w = (x == 0 && y == 0) ? 4.0 : (x == 0 || y == 0 ? 2.0 : 1.0);
            vec3 c = pow(max(textureLod(btsScene, uv, btsSceneLod).rgb, 0.0), vec3(2.2));
            sum += dot(c, vec3(0.2126, 0.7152, 0.0722)) * w;
            weightSum += w;
        }
    }
    float average = max(sum / weightSum, 1e-4);
    // Partial adaptation (full adaptation would make every scene equally bright and look flat).
    float target = clamp(pow(AUTO_EXPOSURE_KEY / average, AUTO_EXPOSURE_STRENGTH), AUTO_EXPOSURE_MIN, AUTO_EXPOSURE_MAX);
    float previous = btsExposureValid == 1 ? texelFetch(btsPrevExposure, ivec2(0), 0).r : target;
    float rate = 1.0 - exp(-btsDeltaTime * AUTO_EXPOSURE_SPEED);
    FragColor = vec4(mix(previous, target, rate), 0.0, 0.0, 1.0);
}
