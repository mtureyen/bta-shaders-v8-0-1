#version 410 core
// BTA Shaders color_world fragment shader (BTA 8.0.1 color_world.fsh).
// While BTA draws its sky dome (btsSkyMode = 1) we output nothing: the world buffer then only holds
// BTA's sun/moon/stars over black, and the tonemap pass draws our own sky behind them.
// Otherwise (fishing line, lightning, TNT flash, ...) it behaves like stock, with sky-colored fog.

#include "lib/settings.glsl"

layout (location = 0) out vec4 FragColor;

in vec4 Color;
in vec2 LightMapCoord;
in vec3 Light;
in vec3 RelPos;

uniform float uLightMapStrength;
uniform float uAlphaTest;
uniform vec4 uColor;
uniform vec2 uViewPortSize;
uniform int btsSkyMode;

layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

uniform sampler2D lighttex;

#include "lib/fog.glsl"
#include "lib/sky.glsl"
#include "lib/gbuffer.glsl"

uniform int btsShadowPass;

void main() {
    if (btsShadowPass == 1) return;
    if (btsSkyMode == 1) {
        FragColor = vec4(0.0, 0.0, 0.0, 0.0);
        return;
    }
    vec3 n = normalize(cross(dFdx(RelPos), dFdy(RelPos)));
    writeGBuffer(dot(n, RelPos) > 0.0 ? -n : n, GCODE_OTHER, LightMapCoord, 0.0);
    vec4 lightColor = mix(vec4(1, 1, 1, 1), texture(lighttex, LightMapCoord), uLightMapStrength);
    vec4 oColor = lightColor * Color * uColor;

    FragColor = computeFog(oColor);
    if (FragColor.a < uAlphaTest) {
        discard;
    }
}
