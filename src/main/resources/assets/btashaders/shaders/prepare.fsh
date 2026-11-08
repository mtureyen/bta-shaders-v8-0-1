#version 410 core
// BTA Shaders prepare pass (HDR, before bloom / eye adaptation / tone mapping):
//   screen-space ambient occlusion, our sky behind BTA's sun/moon/stars, and the clouds.
// Noise is rotated every frame; TAA averages it out.

#include "lib/settings.glsl"

in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D colortex0;   // HDR world color (sky pixels: only BTA's sun/moon/stars over black)
uniform sampler2D depthtex0;   // world depth
uniform mat4 btsProjInv;       // inverse (jittered) projection
uniform mat4 btsProj;
uniform mat4 btsViewInv;
uniform vec2 btsViewSize;
uniform float btsFrame;
uniform int btsOwnSky;         // 1 = BTA's sky dome was suppressed: draw ours here
uniform int isEyeInLiquid;
uniform sampler2D btsGNormal;  // G-buffer: world normal + material (lib/gbuffer.glsl)
uniform sampler2D btsGData;    // G-buffer: sky light, block light, sun visibility, written flag
uniform mat4 btsView;
uniform int btsGBufferOn;

#include "lib/materials.glsl"
#define GBUFFER_READ_ONLY
#include "lib/gbuffer.glsl"
#include "lib/sky.glsl"
#include "lib/clouds.glsl"

#define HAND_DEPTH 0.07

vec3 viewPosAt(vec2 uv) {
    float d = texture(depthtex0, uv).r;
    vec4 v = btsProjInv * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    return v.xyz / v.w;
}

#include "lib/reflections.glsl"

// Normal-oriented horizon-ish AO (in the spirit of Complementary's depth-only SSAO).
float ambientOcclusion(vec3 p, vec3 n) {
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))
                        + 0.618034 * mod(btsFrame, 64.0));
    float rotation = noise * 6.2831853;
    // Project the world radius to screen space at this depth.
    float screenRadius = SSAO_RADIUS * btsProj[1][1] / max(-p.z, 0.1) * 0.5;
    vec2 aspect = vec2(btsViewSize.y / btsViewSize.x, 1.0);
    float occlusion = 0.0;
    for (int i = 0; i < SSAO_SAMPLES; i++) {
        float r = sqrt((float(i) + noise) / float(SSAO_SAMPLES));
        float a = rotation + float(i) * 2.3999632;
        vec2 suv = TexCoords + vec2(cos(a), sin(a)) * r * screenRadius * aspect;
        vec3 s = viewPosAt(suv);
        vec3 v = s - p;
        float dist = length(v);
        if (dist < 1e-4) continue;
        float range = clamp(1.0 - dist / (SSAO_RADIUS * 2.0), 0.0, 1.0);
        occlusion += max(dot(n, v / dist) - 0.15, 0.0) * range;
    }
    float ao = 1.0 - occlusion / float(SSAO_SAMPLES) * SSAO_STRENGTH * 1.6;
    return clamp(ao, 0.0, 1.0);
}

void main() {
    vec3 color = texture(colortex0, TexCoords).rgb;
    float depth = texture(depthtex0, TexCoords).r;

    vec3 p = viewPosAt(TexCoords);
    vec3 n = normalize(cross(dFdx(p), dFdy(p)));
    if (dot(n, p) > 0.0) n = -n;

#if SSAO
    if (depth < 1.0 && depth > HAND_DEPTH) {
        float strength = 1.0;
        vec4 gData = btsGBufferOn == 1 ? texture(btsGData, TexCoords) : vec4(0.0);
        if (gData.a > 0.5) {
            // Material-aware: the G-buffer's real normal instead of the noisy depth-derived one,
            // weaker on foliage (cut-out holes look like crevices) and in direct sunlight
            // (occlusion only darkens ambient light, not the sun).
            vec4 gNormal = texture(btsGNormal, TexCoords);
            n = normalize(mat3(btsView) * gbufferNormal(gNormal));
            int code = gbufferCode(gNormal);
            if (code == MAT_LEAVES || code == MAT_PLANT) strength *= SSAO_FOLIAGE;
            if (code == MAT_WATER) strength *= 0.5;
            strength *= 1.0 - SSAO_SUN_REDUCTION * gData.b;
        }
        color *= mix(1.0, ambientOcclusion(p, n), strength);
    }
#endif

#if REFLECTIONS
    // Smooth and wet surfaces reflect the scene / sky (lib/reflections.glsl).
    if (depth < 1.0 && depth > HAND_DEPTH && btsGBufferOn == 1 && isEyeInLiquid == 0) {
        vec4 gData = texture(btsGData, TexCoords);
        if (gData.a > 0.5) {
            vec4 gNormal = texture(btsGNormal, TexCoords);
            color = toGamma(applyReflections(toLinear(color), p, gNormal, gData));
        }
    }
#endif

    if (btsOwnSky == 1) {
        vec3 relPos = (btsViewInv * vec4(p, 1.0)).xyz;
        vec3 dir = normalize(relPos);
        vec3 linear = toLinear(color);
        if (depth >= 1.0) {
            // Our sky behind whatever BTA drew there (sun, moon, stars, rainbow, aurora: additive).
            linear += skyColor(dir);
        }
#if CLOUDS
        if (depth > HAND_DEPTH && isEyeInLiquid == 0) {
            float sceneDist = depth >= 1.0 ? 1e9 : length(relPos);
            vec4 clouds = renderClouds(dir, sceneDist, gl_FragCoord.xy + mod(btsFrame, 64.0) * 7.0);
            linear = linear * (1.0 - clouds.a) + clouds.rgb;
        }
#endif
        color = toGamma(linear);
    }

    // Still HDR (gamma-encoded); bloom + tone mapping happen in the next passes.
    FragColor = vec4(color, 1.0);
}
