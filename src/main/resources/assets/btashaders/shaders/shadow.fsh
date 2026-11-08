#version 410 core
// BTA Shaders: shadow pass fragment shader.
// Opaque pass: depth only. Translucent pass (btsTranslucentPass = 1, colored shadow map): depth +
// the light this caster lets through. Water writes alpha 0 as a marker: receivers compute its
// absorption from the water depth and add caustics (lib/lighting.glsl).

#include "lib/materials.glsl"

layout (location = 0) out vec4 ShadowColor;

in vec2 TexCoord;
in vec3 VertexColor;
in vec3 RelPos;
flat in int Material;
uniform sampler2D colortex;
uniform int btsTranslucentPass;

void main() {
    vec4 tex = texture(colortex, TexCoord);
    if (btsTranslucentPass == 1) {
        if (Material == MAT_WATER) {
            ShadowColor = vec4(1.0, 1.0, 1.0, 0.0);
            return;
        }
        if (tex.a < 0.05) discard;
        // Tinted glass & co.: the more opaque the texel, the more its color filters the light.
        vec3 tint = clamp(tex.rgb * VertexColor * 1.15, 0.0, 1.0);
        ShadowColor = vec4(mix(vec3(1.0), tint, clamp(tex.a * 1.2, 0.0, 1.0)), 1.0);
        return;
    }
    // Small plants don't cast shadows (their tiny, shifting shadows are mostly noise). Fallback
    // geometric test for diagonal faces.
    vec3 n = cross(dFdx(RelPos), dFdy(RelPos));
    float len = length(n);
    bool diagonal = len > 0.0 && abs(n.x / len) > 0.3 && abs(n.z / len) > 0.3;
    if (Material == MAT_PLANT || diagonal) {
        discard;
    }
    // Cut-out holes (leaves) let light through.
    if (tex.a < 0.5) {
        discard;
    }
    ShadowColor = vec4(1.0);
}
