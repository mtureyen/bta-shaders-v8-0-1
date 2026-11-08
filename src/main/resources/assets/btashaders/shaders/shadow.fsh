#version 410 core
// BTA Shaders: shadow pass fragment shader. Depth only.

#include "lib/materials.glsl"

in vec2 TexCoord;
in vec3 RelPos;
flat in int Material;
uniform sampler2D colortex;

void main() {
    // Small plants don't cast shadows (their tiny, shifting shadows are mostly noise); water doesn't
    // either for now (it's not in the solid pass anyway). Fallback geometric test for diagonal faces.
    vec3 n = cross(dFdx(RelPos), dFdy(RelPos));
    float len = length(n);
    bool diagonal = len > 0.0 && abs(n.x / len) > 0.3 && abs(n.z / len) > 0.3;
    if (Material == MAT_PLANT || diagonal) {
        discard;
    }
    // Cut-out holes (leaves) let light through.
    if (texture(colortex, TexCoord).a < 0.5) {
        discard;
    }
}
