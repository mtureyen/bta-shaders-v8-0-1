// Per-frame world uniforms shared by every pass that needs the sun/sky/cloud state (uploaded by Java:
// LightingState for world shaders, BtaShadersRenderer for the post passes).
#ifndef BTS_UNIFORMS
#define BTS_UNIFORMS

uniform vec3 btsLightDir;     // towards the active light (sun by day, moon by night)
uniform int btsLightIsSun;
uniform float btsSunHeight;   // the sun's own height (-1..1)
uniform float btsRain;        // 0..1
uniform float frameTimeCounter;

// Camera + cloud layer (clouds, cloud shadows, waving phase).
uniform vec3 btsCameraMod;    // camera position mod 1024
uniform float btsCameraY;
uniform float btsCloudHeight; // bottom of the cloud layer (world y)
uniform int btsCloudsOn;

#endif
