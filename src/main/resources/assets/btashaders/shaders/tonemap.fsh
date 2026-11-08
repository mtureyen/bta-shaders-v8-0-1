#version 410 core
// BTA Shaders tonemap pass (render resolution, before TAA):
//   HDR scene incl. sky + clouds (gamma-encoded) -> linear -> aerial haze, underwater, god rays
//   -> + bloom -> exposure (eye adaptation) -> Lottes-style tone curve -> gamma.

#include "lib/settings.glsl"

in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D colortex0;    // HDR scene (gamma-encoded, after SSAO)
uniform sampler2D depthtex0;    // world depth (1.0 = sky)
uniform sampler2D btsBloom;     // linear HDR bloom
uniform int btsBloomOn;
uniform float btsBloomScale;    // 1 / number of accumulated bloom levels
uniform sampler2D btsExposure;  // 1x1 eye-adaptation exposure (r)
uniform int btsExposureOn;
uniform vec2 btsViewSize;

uniform int btsWorldValid;
uniform mat4 btsProjInv;        // un-jittered
uniform mat4 btsViewInv;
uniform vec2 btsSunScreen;
uniform float btsGodRayStrength;
uniform int isEyeInLiquid;

layout (std140) uniform FogState {
    vec4 color;
    int mode;
    float start;
    float end;
    float density;
} fogState;

#include "lib/sky.glsl"

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// Light hue pushed towards warm gold (the plain light hue reads cold on top of a blue sky).
vec3 warmLight() {
    return activeLightHue(btsLightIsSun == 1) * LIGHT_WARM_COLOR;
}

// Screen-space light shafts: march towards the light counting unobstructed sky (depth == 1).
float godRays() {
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
    vec2 aspect = vec2(btsViewSize.x / btsViewSize.y, 1.0);
    float falloff = exp(-length((TexCoords - btsSunScreen) * aspect) * GODRAY_FALLOFF);
    return (accum / weightSum) * falloff;
}

// Lottes 2016 "Advanced Techniques and Optimization of HDR Color Pipelines" curve (own implementation).
vec3 lottes(vec3 x) {
    const float midIn = 0.18;
    const float midOut = 0.18;
    const float d = 0.97;
    float a = TONEMAP_CONTRAST;
    float hdrMax = TONEMAP_WHITE;
    float ad = a * d;
    float midInA = pow(midIn, a);
    float midInAD = pow(midIn, ad);
    float hdrMaxA = pow(hdrMax, a);
    float hdrMaxAD = pow(hdrMax, ad);
    float denom = (hdrMaxAD - midInAD) * midOut;
    float b = (-midInA + hdrMaxA * midOut) / denom;
    float c = (hdrMaxAD * midInA - hdrMaxA * midInAD * midOut) / denom;
    vec3 xa = pow(max(x, 0.0), vec3(a));
    vec3 xad = pow(max(x, 0.0), vec3(ad));
    return clamp(xa / (xad * b + c), 0.0, 1.0);
}

void main() {
    vec3 color = toLinear(texture(colortex0, TexCoords).rgb);
    float depth = texture(depthtex0, TexCoords).r;

    if (btsWorldValid == 1) {
        vec4 viewPos = btsProjInv * vec4(TexCoords * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        viewPos /= viewPos.w;
        vec3 relPos = (btsViewInv * viewPos).xyz;
        vec3 viewDir = normalize(relPos);
        float dist = length(relPos);
        float lightDot = max(dot(viewDir, btsLightDir), 0.0);
        float clear = 1.0 - btsRain;
        float sunset = (1.0 - smoothstep(0.0, 0.4, abs(btsSunHeight))) * step(-0.15, btsSunHeight);
        vec3 lightColor = activeLightColor(btsLightIsSun == 1);
        vec3 hue = warmLight();

        // (The sky itself and the clouds are drawn in the prepare pass, so bloom and eye adaptation
        // see them.)
        if (depth < 1.0 && depth > 0.07 && isEyeInLiquid == 0) {
            // Aerial perspective: distant terrain takes on the color of the sky behind it.
            float amount = (1.0 - exp(-dist * ATMOS_DENSITY)) * ATMOS_STRENGTH;
            vec3 horizonDir = normalize(vec3(viewDir.x, clamp(viewDir.y, 0.02, 0.3), viewDir.z));
            color = mix(color, skyColor(horizonDir), amount);
        }
        if (isEyeInLiquid == 1 && depth > 0.07) {
            float amount = (1.0 - exp(-dist / 14.0)) * UNDERWATER_FOG;
            color = mix(color, toLinear(vec3(0.05, 0.20, 0.28)) * (0.3 + 0.7 * luma(toLinear(fogState.color.rgb))), amount);
        }
        if (btsGodRayStrength > 0.001 && isEyeInLiquid == 0) {
            color += hue * luma(lightColor) * godRays() * btsGodRayStrength * GODRAY_INTENSITY;
        }
    }

#if BLOOM
    if (btsBloomOn == 1) {
        // Thresholded glow, added (a plain mix would darken everything that isn't bright).
        color += texture(btsBloom, TexCoords).rgb * btsBloomScale * BLOOM_STRENGTH;
    }
#endif

    color *= TONEMAP_EXPOSURE;
#if AUTO_EXPOSURE
    if (btsExposureOn == 1) {
        color *= texelFetch(btsExposure, ivec2(0), 0).r;
    }
#endif
    // Per-channel curve: very bright colors drift towards white ("shine"); then restore a little
    // saturation lost in the shoulder.
    vec3 mapped = lottes(color);
    float l = luma(mapped);
    mapped = clamp(mix(vec3(l), mapped, TONEMAP_SATURATION), 0.0, 1.0);
    FragColor = vec4(toGamma(mapped), 1.0);
}
