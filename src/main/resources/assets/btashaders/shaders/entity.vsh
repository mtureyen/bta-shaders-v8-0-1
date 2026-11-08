#version 410 core
// BTA Shaders entity vertex shader (BTA 8.0.1 entity.vsh + camera-relative position for lighting).
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aUV;
layout (location = 2) in vec3 aNormal;
layout (location = 3) in float abone;

out vec2 TexCoord;
out vec3 Light;
out vec3 RelPos;

uniform mat4 normalTransform;
uniform vec4 lightModelAmbient;
uniform vec4 lightSourcePosition1;
uniform vec4 lightSourcePosition2;
uniform vec4 lightSourceDiffuse1;
uniform vec4 lightSourceDiffuse2;

uniform mat4 model;
uniform mat4 texture;

layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

layout (std140) uniform Bones {
    mat4 transforms[128];
} bones;

void main() {
    mat4 bone = bones.transforms[int(abone)];
    vec4 rel = model * bone * vec4(aPos, 1);
    RelPos = rel.xyz;
    gl_Position = matrices.projection * matrices.view * rel;
    TexCoord = (texture * vec4(aUV, 0.0, 1.0)).xy;

    vec4 norm = normalize(normalTransform * model * bone * vec4(aNormal, 0));
    float dir1 = max(0.0, dot(norm, lightSourcePosition1));
    float dir2 = max(0.0, dot(norm, lightSourcePosition2));
    Light = (lightModelAmbient + lightSourceDiffuse1 * (dir1 + dir2)).xyz;
}
