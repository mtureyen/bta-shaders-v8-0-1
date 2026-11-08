// BTA's fog (from its own world shaders), shared. Needs the Matrices uniform block and
// uniform vec2 uViewPortSize declared before inclusion.

layout (std140) uniform FogState {
    vec4 color;
    int mode;
    float start;
    float end;
    float density;
} fogState;

float fogDistance() {
    vec4 fragPos = matrices.projectionInv * vec4((gl_FragCoord.xy / uViewPortSize) * 2.0 - 1.0, gl_FragCoord.z * 2.0 - 1.0, 1.0);
    fragPos /= fragPos.w;
    return length(fragPos.xyz);
}

// From https://github.com/hughsk/glsl-fog (as in BTA's shaders)
vec4 computeFog(vec4 color) {
    float d = fogDistance();
    float f = 0.0;
    if (fogState.mode == 0) {
        f = 1.0 - clamp((fogState.end - d) / (fogState.end - fogState.start), 0.0, 1.0);
    } else if (fogState.mode == 1) {
        f = 1.0 - clamp(exp(-fogState.density * d), 0.0, 1.0);
    } else if (fogState.mode == 2) {
        float x = fogState.density * d;
        f = 1.0 - clamp(exp2(x * x * -1.442695), 0.0, 1.0);
    }
    return vec4(mix(color.rgb, fogState.color.rgb, f), color.a);
}
