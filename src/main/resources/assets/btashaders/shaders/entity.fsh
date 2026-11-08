#version 410 core
// BTA Shaders entity fragment shader (BTA 8.0.1 entity.fsh + sun/sky lighting and shadows).

#include "lib/settings.glsl"
#include "lib/materials.glsl"

out vec4 FragColor;

in vec2 TexCoord;
in vec3 Light;
in vec3 RelPos;

uniform float uLightMapStrength;
uniform float uAlphaTest;
uniform vec4 uColor;
uniform vec2 uLightmap;
uniform vec2 uViewPortSize;

layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

uniform sampler2D colortex;
uniform sampler2D lighttex;

#include "lib/fog.glsl"
#include "lib/lighting.glsl"
#include "lib/sky.glsl"

void main() {
    vec3 geoNormal = geometricNormal(RelPos);
    // MAT_TRANSLUCENT = "no plant detection" (rotated mob faces can look diagonal).
    vec3 light = computeLight(uLightmap, vec2(0.0), RelPos, geoNormal, MAT_TRANSLUCENT);
    vec4 lightColor = mix(vec4(1.0), vec4(light, 1.0), uLightMapStrength);
    vec4 texColor = texture(colortex, TexCoord);

    vec4 oColor = texColor * lightColor * uColor;
    vec4 litColor = vec4(oColor.rgb * faceShading(Light, geoNormal), oColor.a);

    FragColor = computeFog(litColor);
    if (FragColor.a < uAlphaTest) {
        discard;
    }
}
