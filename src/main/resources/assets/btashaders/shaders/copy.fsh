#version 410 core
// Copies the opaque scene (color + depth) right before the translucent pass, for water refraction/SSR.

in vec2 TexCoords;
layout (location = 0) out vec4 OutColor;
layout (location = 1) out vec4 OutDepth;

uniform sampler2D btsSrcColor;
uniform sampler2D btsSrcDepth;

void main() {
    OutColor = texture(btsSrcColor, TexCoords);
    OutDepth = vec4(texture(btsSrcDepth, TexCoords).r);
}
