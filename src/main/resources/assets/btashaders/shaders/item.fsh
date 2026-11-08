#version 410 core
// BTA Shaders item fragment shader (BTA 8.0.1 item.fsh + sun/sky lighting and shadows).
// Dropped items in the world, and the held item (lighting without shadows, "hand mode").

#include "lib/settings.glsl"
#include "lib/materials.glsl"

out vec4 FragColor;

in vec4 Color;
in vec2 TexCoord;
in vec2 LightMapCoord;
in vec3 Light;
in vec3 Norm;
in vec3 RelPos;

uniform float uLightMapStrength;
uniform float uAlphaTest;
uniform vec4 uColor;
uniform vec2 uViewPortSize;

layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

uniform sampler2D colortex;
uniform sampler2D lighttex;
uniform sampler2D emissivetex;
uniform sampler2D colormasktex;

#include "lib/fog.glsl"
#include "lib/lighting.glsl"
#include "lib/sky.glsl"

void main() {
    vec3 geoNormal = geometricNormal(RelPos);
    vec2 emissiveBrightness = texture(emissivetex, TexCoord).xy;
    vec3 light = computeLight(LightMapCoord, emissiveBrightness, RelPos, geoNormal, MAT_TRANSLUCENT);
    vec4 lightColor = mix(vec4(1.0), vec4(light, 1.0), uLightMapStrength);
    vec4 texColor = texture(colortex, TexCoord);
    vec4 maskColor = texture(colormasktex, TexCoord);

    vec4 c = vec4(mix(vec3(1, 1, 1), Color.rgb, maskColor.r), 1) * uColor;

    vec4 postColor = texColor * c * lightColor;
    vec4 litColor = vec4(postColor.rgb * faceShading(Light, geoNormal), postColor.a);

    FragColor = computeFog(litColor);
    if (FragColor.a < uAlphaTest) {
        discard;
    }
}
