#version 410 core
// BTA Shaders terrain fragment shader (based on BTA 8.0.1's terrain.fsh).
// Lighting/shadows: lib/lighting.glsl. Water (translucent pass): lib/water.glsl.

#include "lib/settings.glsl"
#include "lib/materials.glsl"

out vec4 FragColor;

in vec4 Color;
in float Shade;
in vec2 TexCoord;
in vec2 LightMapCoord;
in vec3 RelPos;
flat in int Material;
in float Emission;

uniform float uLightMapStrength;
uniform float uAlphaTest;
uniform vec4 uColor;
uniform vec2 uViewPortSize;
uniform float frameTimeCounter;
uniform vec3 btsCameraMod;
uniform int btsWaterPass;          // 1 while the translucent pass runs with a valid opaque copy

uniform mat4 normalTransform;
uniform vec4 lightModelAmbient;
uniform vec4 lightSourcePosition1;
uniform vec4 lightSourcePosition2;
uniform vec4 lightSourceDiffuse1;

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
#include "lib/water.glsl"

void main() {
    vec3 geoNormal = geometricNormal(RelPos); // derivatives: keep in uniform control flow
    vec3 btaLight = btaDirectionalLight(geoNormal, normalTransform, lightModelAmbient,
                                        lightSourcePosition1, lightSourcePosition2, lightSourceDiffuse1);

    vec2 emissiveBrightness = texture(emissivetex, TexCoord).xy;
    vec3 light = computeLight(LightMapCoord, emissiveBrightness, RelPos, geoNormal, Material);
    vec4 lightColor = mix(vec4(1.0), vec4(light, 1.0), uLightMapStrength);

    vec4 texColor = texture(colortex, TexCoord);
    vec4 maskColor = texture(colormasktex, TexCoord);
    vec4 c = vec4(mix(vec3(1, 1, 1), Color.rgb, maskColor.r), 1) * uColor;

    float shadeEffect = 1 - max(emissiveBrightness.x, emissiveBrightness.y);
    float s = mix(1, bakedShade(Shade), shadeEffect);

    vec4 postColor = texColor * c * vec4(s, s, s, 1) * lightColor;
    vec4 litColor = vec4(postColor.rgb * faceShading(btaLight, geoNormal), postColor.a);

    if (btsWaterPass == 1 && btsLightingOn == 1 && Material == MAT_WATER) {
        float sky = skyFactor(LightMapCoord.y);
        float directVis = directVisibility(RelPos, geoNormal, MAT_WATER, sky);
        vec3 water = shadeWater(RelPos, geoNormal, litColor.rgb, sky, directVis);
        FragColor = computeFog(vec4(water, 1.0));
        return;
    }

    FragColor = computeFog(litColor);
    if (FragColor.a < uAlphaTest) {
        discard;
    }
}
