#version 410 core
// BTA Shaders terrain vertex shader: BTA 8.0.1 terrain.vsh plus shadow coordinates.

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

uniform mat4 normalTransform;
uniform vec4 lightModelAmbient;
uniform vec4 lightSourcePosition1;
uniform vec4 lightSourcePosition2;
uniform vec4 lightSourceDiffuse1;
uniform vec4 lightSourceDiffuse2;

out vec4 Color;
out float Shade;
out vec2 TexCoord;
out vec2 LightMapCoord;
out vec3 Light;
out vec4 Norm;
out vec3 RelPos; // camera-relative world position

vec2 unpackLightCoord(vec2 lightShade) {
    int asInt = int(lightShade.y);
    return vec2(((asInt & 0xF) + 0.5)/16.0, (((asInt & 0xF0) >> 4) + 0.5)/16.0);
}

float unpackShade(vec2 lightShade) {
    return lightShade.x/256;
}

void main() {
    Norm = normalize(normalTransform * model * vec4(aNormal, 0));
    float dir1 = max(0.0, dot(Norm, lightSourcePosition1));
    float dir2 = max(0.0, dot(Norm, lightSourcePosition2));
    Light = (lightModelAmbient + lightSourceDiffuse1 * (dir1 + dir2)).xyz;

    Color = vec4(aColor, 1);
    Shade = unpackShade(aLightShade);
    TexCoord = aUV;
    LightMapCoord = unpackLightCoord(aLightShade);

    vec4 relPos = model * vec4(aPos, 1); // camera-relative world position
    gl_Position = matrices.projection * matrices.view * relPos;

    // Shadow lookup happens per fragment (terrain.fsh) using the geometric normal; the vertex
    // normal can't be trusted (crossed plants inherit whatever normal the previous block set).
    RelPos = relPos.xyz;
}
