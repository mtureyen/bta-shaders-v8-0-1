#version 410 core
// Draws nothing. Substituted for BTA's stock shaders (name-tag font, colored lines, ...) while
// entities are rendered into the shadow map: those shaders don't know the shadow-map distortion and
// would put stray shadows in the wrong places.
layout (location = 0) in vec3 aPos;

void main() {
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0); // outside the clip volume
}
