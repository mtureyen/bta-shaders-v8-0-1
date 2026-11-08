// BTA's fog (from its own world shaders), shared. Needs the Matrices uniform block and
// uniform vec2 uViewPortSize declared before inclusion, and lib/sky.glsl included somewhere after
// (defines skyFogColor). With btsSkyFog on, fog takes the color of our sky in the view direction,
// so terrain fades seamlessly into the sky behind it.

layout (std140) uniform FogState {
    vec4 color;
    int mode;
    float start;
    float end;
    float density;
} fogState;

uniform int btsSkyFog;          // 1 = use our sky as the fog color (normal overworld fog only)

vec3 skyFogColor(vec3 worldDir); // lib/sky.glsl

vec3 fogViewPos() {
    vec4 fragPos = matrices.projectionInv * vec4((gl_FragCoord.xy / uViewPortSize) * 2.0 - 1.0, gl_FragCoord.z * 2.0 - 1.0, 1.0);
    return fragPos.xyz / fragPos.w;
}

float fogDistance() {
    return length(fogViewPos());
}

// From https://github.com/hughsk/glsl-fog (as in BTA's shaders)
vec4 computeFog(vec4 color) {
    vec3 viewPos = fogViewPos();
    float d = length(viewPos);
    float f = 0.0;
    if (fogState.mode == 0) {
        f = 1.0 - clamp((fogState.end - d) / (fogState.end - fogState.start), 0.0, 1.0);
    } else if (fogState.mode == 1) {
        f = 1.0 - clamp(exp(-fogState.density * d), 0.0, 1.0);
    } else if (fogState.mode == 2) {
        float x = fogState.density * d;
        f = 1.0 - clamp(exp2(x * x * -1.442695), 0.0, 1.0);
    }
    vec3 fogColor = fogState.color.rgb;
    if (btsSkyFog == 1 && fogState.mode == 0) {
        fogColor = skyFogColor(normalize(mat3(matrices.viewInv) * viewPos));
    }
    return vec4(mix(color.rgb, fogColor, f), color.a);
}
