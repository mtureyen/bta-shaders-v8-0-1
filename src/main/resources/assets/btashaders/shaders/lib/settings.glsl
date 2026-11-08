// BTA Shaders tunables. Override any of these in .minecraft/config/btashaders.properties with
// define.NAME=value, or edit here in the dev client and press F7.

// ---- Lighting ----
#ifndef AMBIENT_SKY_LIGHT
#define AMBIENT_SKY_LIGHT 0.84       // sky light kept as ambient (rest comes from direct sun)
#endif
#ifndef DIRECT_LIGHT_STRENGTH
#define DIRECT_LIGHT_STRENGTH 1.15
#endif
#ifndef HAND_NDOTL
#define HAND_NDOTL 0.65              // how much direct light the first-person hand gets
#endif
#ifndef SUN_TINT
#define SUN_TINT vec3(1.00, 0.86, 0.66)   // multiplies the direct sunlight color: golden
#endif
#ifndef AMBIENT_TINT_STRENGTH
#define AMBIENT_TINT_STRENGTH 0.35        // how much the sky tints shadows (blue): 0 = neutral
#endif
#ifndef AMBIENT_WARMTH
#define AMBIENT_WARMTH vec3(1.04, 1.0, 0.94) // warm bounce light in shadows
#endif
#ifndef LEAF_TRANSLUCENCY
#define LEAF_TRANSLUCENCY 0.5        // leaves let light through: 0 = opaque, 1 = fully lit from behind
#endif

// ---- Shadows ----
#ifndef SHADOW_DEPTH_BIAS
#define SHADOW_DEPTH_BIAS 0.00012
#endif
#ifndef SHADOW_DISTORT
#define SHADOW_DISTORT 0.85          // must match between shadow pass and lookups (both include this file)
#endif
#ifndef SHADOW_SAMPLES
#define SHADOW_SAMPLES 10
#endif
#ifndef FOLIAGE_SHADOW_SAMPLES
#define FOLIAGE_SHADOW_SAMPLES 16
#endif
#ifndef SHADOW_FILTER_RADIUS
#define SHADOW_FILTER_RADIUS 1.6     // in shadow-map texels (near the player)
#endif
#ifndef FOLIAGE_FILTER_MULT
#define FOLIAGE_FILTER_MULT 3.0
#endif

// ---- Waving ----
#ifndef WAVING_PLANTS
#define WAVING_PLANTS 1
#endif
#ifndef WAVING_LEAVES
#define WAVING_LEAVES 1
#endif
#ifndef WAVING_STRENGTH
#define WAVING_STRENGTH 1.0
#endif
#ifndef WAVING_SPEED
#define WAVING_SPEED 1.0
#endif

// ---- Water ----
#ifndef WATER_REFLECTIONS
#define WATER_REFLECTIONS 1
#endif
#ifndef WATER_SSR_STEPS
#define WATER_SSR_STEPS 24
#endif
#ifndef WATER_REFRACTION
#define WATER_REFRACTION 0.025
#endif
#ifndef WATER_WAVE_STRENGTH
#define WATER_WAVE_STRENGTH 1.0
#endif
#ifndef WATER_ABSORPTION
#define WATER_ABSORPTION 1.0
#endif
#ifndef WATER_SPECULAR
#define WATER_SPECULAR 2.5
#endif

// ---- Ambient occlusion (prepare pass) ----
#ifndef SSAO
#define SSAO 1
#endif
#ifndef SSAO_SAMPLES
#define SSAO_SAMPLES 8
#endif
#ifndef SSAO_RADIUS
#define SSAO_RADIUS 0.7              // blocks
#endif
#ifndef SSAO_STRENGTH
#define SSAO_STRENGTH 0.6
#endif

// ---- Tone mapping (prepare pass) ----
#ifndef TONEMAP_EXPOSURE
#define TONEMAP_EXPOSURE 1.0
#endif
#ifndef TONEMAP_SHOULDER
#define TONEMAP_SHOULDER 0.8         // values below this stay untouched; above roll off smoothly to 1
#endif

// ---- Composite ----
#ifndef GODRAY_SAMPLES
#define GODRAY_SAMPLES 40
#endif
#ifndef GODRAY_INTENSITY
#define GODRAY_INTENSITY 0.45
#endif
#ifndef GODRAY_DECAY
#define GODRAY_DECAY 0.96
#endif
#ifndef GODRAY_FALLOFF
#define GODRAY_FALLOFF 2.2
#endif
#ifndef SKY_GLOW
#define SKY_GLOW 0.35                // sunrise/sunset glow around the sun
#endif
#ifndef SUN_GLOW
#define SUN_GLOW 0.18                // warm halo around the sun at any time of day
#endif
#ifndef LIGHT_WARM_COLOR
#define LIGHT_WARM_COLOR vec3(1.0, 0.78, 0.50) // tint for god rays / sun glow / haze near the sun
#endif
#ifndef ATMOS_DENSITY
#define ATMOS_DENSITY 0.004          // aerial perspective (distant terrain picks up sky/sun color)
#endif
#ifndef ATMOS_STRENGTH
#define ATMOS_STRENGTH 0.2
#endif
#ifndef WARMTH
#define WARMTH 0.5                   // final white balance towards warm: 0 = neutral, 1 = strong
#endif
#ifndef UNDERWATER_FOG
#define UNDERWATER_FOG 0.5
#endif
#ifndef VIBRANCE
#define VIBRANCE 0.25
#endif
#ifndef CONTRAST
#define CONTRAST 0.15
#endif
#ifndef VIGNETTE
#define VIGNETTE 0.45
#endif
#ifndef SHARPEN
#define SHARPEN 0.7                  // contrast-adaptive sharpening: 0 = off, 1 = max
#endif
