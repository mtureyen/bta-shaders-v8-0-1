#version 410 core
// BTA Shaders: shadow pass vertex shader. BTA's terrain vertex layout; normal bytes = material IDs.

#include "lib/settings.glsl"
#include "lib/materials.glsl"

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec3 aColor;
layout (location = 2) in vec3 aNormal;
layout (location = 3) in vec2 aUV;

uniform mat4 model;
layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

uniform float frameTimeCounter;
uniform vec3 btsCameraMod;

#include "lib/waving.glsl"

out vec2 TexCoord;
out vec3 VertexColor;
out vec3 RelPos;
flat out int Material;

void main() {
    TexCoord = aUV;
    VertexColor = aColor;
    Material = decodeMaterial(aNormal);
    vec3 rel = (model * vec4(aPos, 1.0)).xyz;
    rel = applyWaving(rel, Material, decodeTopVertex(aNormal)); // shadows sway with the plants
    RelPos = rel;
    vec4 pos = matrices.projection * matrices.view * vec4(rel, 1.0);
    // Distortion: squeeze the map towards the player (texels near the player ~6.7x smaller).
    pos.xy /= length(pos.xy) * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
    gl_Position = pos;
}
