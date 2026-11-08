#version 410 core
// BTA Shaders terrain vertex shader (based on BTA 8.0.1's terrain.vsh).
// The vertex normal bytes carry material ID / top-vertex flag / emission (MaterialContext.java);
// normals for lighting come from the geometry in the fragment shader.

#include "lib/settings.glsl"
#include "lib/materials.glsl"

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec3 aColor;
layout (location = 2) in vec3 aNormal;
layout (location = 3) in vec2 aUV;
layout (location = 4) in vec2 aLightShade;

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

out vec4 Color;
out float Shade;
out vec2 TexCoord;
out vec2 LightMapCoord;
out vec3 RelPos;
flat out int Material;
out float Emission;

vec2 unpackLightCoord(vec2 lightShade) {
    int asInt = int(lightShade.y);
    return vec2(((asInt & 0xF) + 0.5)/16.0, (((asInt & 0xF0) >> 4) + 0.5)/16.0);
}

float unpackShade(vec2 lightShade) {
    return lightShade.x/256;
}

void main() {
    Material = decodeMaterial(aNormal);
    Emission = decodeEmission(aNormal);
    Color = vec4(aColor, 1);
    Shade = unpackShade(aLightShade);
    TexCoord = aUV;
    LightMapCoord = unpackLightCoord(aLightShade);

    vec3 relPos = (model * vec4(aPos, 1)).xyz; // camera-relative world position
    relPos = applyWaving(relPos, Material, decodeTopVertex(aNormal));
    RelPos = relPos;
    gl_Position = matrices.projection * matrices.view * vec4(relPos, 1.0);
}
