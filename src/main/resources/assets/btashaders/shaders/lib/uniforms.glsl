// Per-frame world uniforms shared by every pass that needs the sun/sky state (uploaded by Java:
// LightingState for world shaders, BtaShadersRenderer for the tonemap pass).
#ifndef BTS_UNIFORMS
#define BTS_UNIFORMS

uniform vec3 btsLightDir;    // towards the active light (sun by day, moon by night)
uniform int btsLightIsSun;
uniform float btsSunHeight;  // the sun's own height (-1..1)
uniform float btsRain;       // 0..1

#endif
