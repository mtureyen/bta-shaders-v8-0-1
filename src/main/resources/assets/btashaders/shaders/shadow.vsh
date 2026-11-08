#version 410 core
// BTA Shaders: shadow pass vertex shader. Uses BTA's terrain vertex layout (see BTA terrain.vsh).
layout (location = 0) in vec3 aPos;
layout (location = 3) in vec2 aUV;

uniform mat4 model;
layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

out vec2 TexCoord;
out vec3 RelPos; // camera-relative world position, for the geometric normal in shadow.fsh

// Must match terrain.fsh. Squeezes the map towards the player so nearby shadows get most of the
// resolution (texels near the player are ~1/(1-0.85) = 6.7x smaller).
#define SHADOW_DISTORT 0.85

void main() {
    TexCoord = aUV;
    vec4 rel = model * vec4(aPos, 1.0);
    RelPos = rel.xyz;
    vec4 pos = matrices.projection * matrices.view * rel;
    pos.xy /= length(pos.xy) * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
    gl_Position = pos;
}
