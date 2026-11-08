#version 410 core
// BTA Shaders composite pass. Runs after TAA and BTA's own post shader (bloom, FXAA, color correction).

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

// ---- Tunables (edit + F7 in the dev client) ----
#define GODRAY_SAMPLES 40
#define GODRAY_INTENSITY 0.45
#define GODRAY_DECAY 0.96
#define GODRAY_FALLOFF 2.2       // how fast rays fade with screen distance from the sun
#define VIBRANCE 0.25            // boosts dull colors more than already-saturated ones
#define CONTRAST 0.15            // gentle S-curve
#define VIGNETTE 0.45
#define SHARPEN 0.45             // counteracts TAA/FXAA softness; 0 = off

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Screen-space light shafts: march from this pixel towards the sun and count how much unobstructed
// sky we pass (depth == 1). Terrain, trees and clouds in front of the sun cut the rays.
vec3 godRays() {
    vec2 delta = (btsSunScreen - TexCoords) / float(GODRAY_SAMPLES) * 0.9;
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec2 uv = TexCoords + delta * noise;
    float accum = 0.0;
    float weight = 1.0;
    float weightSum = 0.0;
    for (int i = 0; i < GODRAY_SAMPLES; i++) {
        vec2 c = clamp(uv, vec2(0.0), vec2(1.0));
        float sky = texture(depthtex0, c).r >= 0.99999 ? 1.0 : 0.0;
        accum += sky * weight;
        weightSum += weight;
        weight *= GODRAY_DECAY;
        uv += delta;
    }
    float rays = accum / weightSum;
    vec2 aspect = vec2(float(windowSize.x) / float(windowSize.y), 1.0);
    float dist = length((TexCoords - btsSunScreen) * aspect);
    float falloff = exp(-dist * GODRAY_FALLOFF);
    return btsGodRayColor * rays * falloff * btsGodRayStrength * GODRAY_INTENSITY;
}

// Neighbour-clamped unsharp mask: pushes each pixel away from its 4-neighbour average, but never
// outside the local min/max, so edges get crisper without bright/dark halos.
vec3 sharpen(vec3 center) {
    vec2 texel = 1.0 / vec2(windowSize);
    vec3 n = texture(colortex0, TexCoords + vec2(0.0, texel.y)).rgb;
    vec3 s = texture(colortex0, TexCoords - vec2(0.0, texel.y)).rgb;
    vec3 e = texture(colortex0, TexCoords + vec2(texel.x, 0.0)).rgb;
    vec3 w = texture(colortex0, TexCoords - vec2(texel.x, 0.0)).rgb;
    vec3 mn = min(center, min(min(n, s), min(e, w)));
    vec3 mx = max(center, max(max(n, s), max(e, w)));
    vec3 average = (n + s + e + w) * 0.25;
    return clamp(center + (center - average) * SHARPEN, mn, mx);
}

void main() {
    vec3 color = texture(colortex0, TexCoords).rgb;
    if (SHARPEN > 0.0) {
        color = sharpen(color);
    }

    if (btsGodRayStrength > 0.001 && isEyeInLiquid == 0) {
        color += godRays();
    }

    // Vibrance: lift saturation of dull colors more than vivid ones.
    float mx = max(color.r, max(color.g, color.b));
    float mn = min(color.r, min(color.g, color.b));
    float saturation = mx - mn;
    color = mix(vec3(luma(color)), color, 1.0 + VIBRANCE * (1.0 - saturation));

    // Gentle S-curve contrast.
    color = clamp(color, 0.0, 1.0);
    color = mix(color, color * color * (3.0 - 2.0 * color), CONTRAST);

    // Soft vignette (off while a GUI is open so menus stay clean).
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
            // Terrain sits around mid-depth; stretch contrast so height differences are visible.
            color = vec3(clamp((d - 0.5) * 6.0 + 0.5, 0.0, 1.0));
        }
    }

    FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
