#version 410 core
// BTA Shaders: shadow pass fragment shader. Depth only.
in vec2 TexCoord;
in vec3 RelPos;
uniform sampler2D colortex;

void main() {
    // Crossed-quad plants (the only diagonal faces) don't cast shadows: their tiny, constantly
    // shifting shadows are mostly noise, and terrain.fsh shades plants from their block center.
    vec3 n = cross(dFdx(RelPos), dFdy(RelPos));
    float len = length(n);
    if (len > 0.0) {
        n /= len;
        if (abs(n.x) > 0.3 && abs(n.z) > 0.3) {
            discard;
        }
    }
    // Cut-out holes (leaves, flowers) let light through.
    if (texture(colortex, TexCoord).a < 0.5) {
        discard;
    }
}
