#version 410 core
// BTA Shaders: first composite pass (proof of concept).
// Runs after BTA's own post shader, so bloom/FXAA/color correction are already applied.

in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D colortex0;   // post-processed scene color
uniform sampler2D depthtex0;   // world depth (non-linear, 0..1)
uniform float near;
uniform float far;
uniform float celestialAngle;  // 0..1, 0 = noon
uniform int isEyeInLiquid;     // 0 none, 1 water, 2 lava
uniform bool guiOpened;

float linearDepth(float d) {
    float z = d * 2.0 - 1.0;
    return (2.0 * near * far) / (far + near - z * (far - near));
}

vec3 adjustSaturation(vec3 c, float s) {
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return mix(vec3(l), c, s);
}

void main() {
    vec3 color = texture(colortex0, TexCoords).rgb;
    float depth = texture(depthtex0, TexCoords).r;
    bool isSky = depth >= 0.99999;

    // 1. Distance haze: blend distant terrain toward a soft sky-blue (proves depth access works).
    if (!isSky && isEyeInLiquid == 0) {
        float dist = linearDepth(depth);
        float haze = smoothstep(far * 0.15, far * 0.5, dist) * 0.35;
        // Sun height from celestial angle: 1 at noon, -1 at midnight.
        float sunHeight = cos(celestialAngle * 6.2831853);
        vec3 hazeColor = mix(vec3(0.05, 0.07, 0.12), vec3(0.62, 0.74, 0.90), clamp(sunHeight * 0.5 + 0.5, 0.0, 1.0));
        color = mix(color, hazeColor, haze);
    }

    // 2. Gentle grading: slight saturation lift, warm highlights, cool shadows.
    color = adjustSaturation(color, 1.12);
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color += mix(vec3(-0.010, 0.000, 0.020), vec3(0.025, 0.012, -0.010), luma);

    // 3. Soft vignette (off while a GUI is open so menus stay clean).
    if (!guiOpened) {
        vec2 p = TexCoords - 0.5;
        float vignette = 1.0 - dot(p, p) * 0.55;
        color *= vignette;
    }

    FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
