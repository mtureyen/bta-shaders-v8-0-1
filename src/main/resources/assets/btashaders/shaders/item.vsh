#version 410 core
// BTA Shaders item vertex shader (BTA 8.0.1 item.vsh + camera-relative position for lighting).
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec4 aColor;
layout (location = 2) in vec2 aUV;
layout (location = 3) in float aLightmap;
layout (location = 4) in vec3 aNormal;

out vec4 Color;
out vec2 TexCoord;
out vec2 LightMapCoord;
out vec3 Light;
out vec3 Norm;
out vec3 RelPos;

uniform mat4 normalTransform;
uniform vec4 lightModelAmbient;
uniform vec4 lightSourcePosition1;
uniform vec4 lightSourcePosition2;
uniform vec4 lightSourceDiffuse1;
uniform vec4 lightSourceDiffuse2;

uniform mat4 model;

layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

vec2 unpackLightCoord() {
    int asInt = int(aLightmap);
    return vec2(((asInt & 0xF) + 0.5)/16.0, (((asInt & 0xF0) >> 4) + 0.5)/16.0);
}

void main() {
    vec4 rel = model * vec4(aPos, 1);
    RelPos = rel.xyz;
    gl_Position = matrices.projection * matrices.view * rel;
    TexCoord = aUV;
    Color = aColor;
    LightMapCoord = unpackLightCoord();

    Norm = normalize(aNormal);
    vec4 norm = normalize(normalTransform * model * vec4(aNormal, 0));
    float dir1 = max(0.0, dot(norm, lightSourcePosition1));
    float dir2 = max(0.0, dot(norm, lightSourcePosition2));
    Light = (lightModelAmbient + lightSourceDiffuse1 * (dir1 + dir2)).xyz;
}
