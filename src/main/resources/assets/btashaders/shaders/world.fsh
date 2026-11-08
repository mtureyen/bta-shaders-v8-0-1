#version 410 core
// BTA Shaders world fragment shader (BTA 8.0.1 world.fsh + sun/sky lighting and shadows).
// Used for block entities (chests, signs), dropped blocks, rain and other world geometry.

#include "lib/settings.glsl"
#include "lib/materials.glsl"

layout (location = 0) out vec4 FragColor;

in vec4 Color;
in vec2 TexCoord;
in vec2 LightMapCoord;
in vec3 Light;
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

#include "lib/fog.glsl"
#include "lib/lighting.glsl"
#include "lib/sky.glsl"
#include "lib/gbuffer.glsl"

uniform int btsShadowPass; // 1 while rendering into the shadow map: depth only, alpha-tested
uniform int btsPrecipitation; // 1 while BTA draws rain/snow

void main() {
    if (btsShadowPass == 1) {
        if (texture(colortex, TexCoord).a < 0.5) discard;
        return;
    }
    if (btsPrecipitation == 1) {
        // Rain/snow: light, see-through streaks lit like the sky around them (BTA passes full light
        // levels, so the lightmap carries day/night + weather), never shaded like a solid wall.
        vec4 tex = texture(colortex, TexCoord);
        vec3 sky = texture(lighttex, LightMapCoord).rgb;
        vec4 streak = tex * Color * uColor * vec4(sky * 1.1, PRECIPITATION_OPACITY);
        FragColor = computeFog(streak);
        if (FragColor.a < uAlphaTest) discard;
        return;
    }
    vec3 geoNormal = geometricNormal(RelPos);
    vec3 light = computeLight(LightMapCoord, vec2(0.0), RelPos, geoNormal, MAT_TRANSLUCENT);
    writeGBuffer(geoNormal, GCODE_OBJECT, LightMapCoord, btsDirectOut);
    vec4 lightColor = mix(vec4(1.0), vec4(light, 1.0), uLightMapStrength);
    vec4 texColor = texture(colortex, TexCoord);

    vec4 oColor = texColor * lightColor * Color * uColor;
    vec4 litColor = vec4(oColor.rgb * faceShading(Light, geoNormal), oColor.a);

    FragColor = computeFog(litColor);
    if (FragColor.a < uAlphaTest) {
        discard;
    }
}
