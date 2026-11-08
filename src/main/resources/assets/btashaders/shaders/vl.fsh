#version 410 core
// BTA Shaders light-shaft pass: volumetric light at half resolution (it is soft and noisy anyway;
// TAA hides the rest). The tonemap pass adds the result. See lib/volumetric.glsl.

#include "lib/settings.glsl"

in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D depthtex0;    // world depth (full resolution)
uniform mat4 btsProjInv;        // un-jittered
uniform mat4 btsViewInv;
uniform int btsWorldValid;
uniform int isEyeInLiquid;
uniform float btsFrame;

#include "lib/clouds.glsl"

float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

#include "lib/volumetric.glsl"

void main() {
    if (btsWorldValid != 1) {
        FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    // Farthest depth of the 2x2 full-resolution pixels under this one: shafts behind a thin edge
    // still reach the background (no dark outlines around foliage against bright shafts).
    ivec2 maxPx = textureSize(depthtex0, 0) - 1;
    ivec2 base = min(ivec2(gl_FragCoord.xy) * 2, maxPx);
    ivec2 far = min(base + 1, maxPx);
    float depth = max(max(texelFetch(depthtex0, base, 0).r, texelFetch(depthtex0, ivec2(far.x, base.y), 0).r),
                      max(texelFetch(depthtex0, ivec2(base.x, far.y), 0).r, texelFetch(depthtex0, far, 0).r));
    vec4 viewPos = btsProjInv * vec4(TexCoords * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    viewPos /= viewPos.w;
    vec3 relPos = (btsViewInv * viewPos).xyz;
    FragColor = vec4(volumetricColor(normalize(relPos), length(relPos), depth), 1.0);
}
