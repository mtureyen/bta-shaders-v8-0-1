#version 410 core
// BTA Shaders terrain fragment shader (based on BTA 8.0.1's terrain.fsh).
// Lighting/shadows: lib/lighting.glsl. Water (translucent pass): lib/water.glsl.

#include "lib/settings.glsl"
#include "lib/materials.glsl"

layout (location = 0) out vec4 FragColor;

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
#include "lib/gbuffer.glsl"
#include "lib/surface.glsl"
#include "lib/water.glsl"

uniform int btsShadowPass; // 1 while rendering into the shadow map: depth only, alpha-tested

void main() {
    if (btsShadowPass == 1) {
        if (texture(colortex, TexCoord).a < 0.5) discard;
        return;
    }
    vec3 geoNormal = geometricNormal(RelPos); // derivatives: keep in uniform control flow
    readPbr(TexCoord, RelPos, geoNormal);    // labPBR maps (derivatives too)
    if (pbrSurfaceMaterial(Material) && dot(btsPbrNormal, btsPbrNormal) > 0.5) btsShadeNormal = btsPbrNormal;
    vec3 btaLight = btaDirectionalLight(geoNormal, normalTransform, lightModelAmbient,
                                        lightSourcePosition1, lightSourcePosition2, lightSourceDiffuse1);

    vec2 emissiveBrightness = texture(emissivetex, TexCoord).xy;
    vec3 light = computeLight(LightMapCoord, emissiveBrightness, RelPos, geoNormal, Material);
    Surface surf = surfaceFor(Material, geoNormal, RelPos, skyFactor(LightMapCoord.y));
    writeGBuffer(surf.normal, surf.metal > 0.5 ? MAT_METAL : Material, LightMapCoord, btsDirectOut, surf.smoothness);
    vec4 lightColor = mix(vec4(1.0), vec4(light, 1.0), uLightMapStrength);

    vec4 texColor = texture(colortex, TexCoord);
    vec4 maskColor = texture(colormasktex, TexCoord);
    vec4 c = vec4(mix(vec3(1, 1, 1), Color.rgb, maskColor.r), 1) * uColor;

    float shadeEffect = 1 - max(emissiveBrightness.x, emissiveBrightness.y);
    float s = mix(1, bakedShade(Shade), shadeEffect) * mix(1.0, btsPbrAO, 0.85);

    vec3 albedo = wetAlbedo(pbrMetalAlbedo(texColor.rgb * c.rgb), surf);
    vec4 postColor = vec4(albedo, texColor.a * c.a) * vec4(s, s, s, 1) * lightColor;
    vec4 litColor = vec4(postColor.rgb * faceShading(btaLight, geoNormal), postColor.a);
    litColor.rgb = addSpecular(litColor.rgb, albedo, surf, RelPos, btsDirectOut);
    litColor.rgb = addPbrEmission(litColor.rgb, albedo);

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
    if (btsWaterPass == 1 && btsLightingOn == 1 && FragColor.a < 0.999
        && texelFetch(btsOpaqueDepth, ivec2(gl_FragCoord.xy), 0).r >= 1.0) {
        // Tinted glass with open sky behind it: our sky is drawn later, only behind depth == 1 pixels,
        // so the glass would blend over black. Blend it over the sky here and write it opaque.
        vec3 behind = skyReflectionColor(normalize(RelPos)) * mix(0.15, 1.0, skyFactor(LightMapCoord.y));
        FragColor = vec4(mix(behind, FragColor.rgb, FragColor.a), 1.0);
    }
}
