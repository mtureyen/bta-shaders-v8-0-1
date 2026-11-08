// BTA Shaders tunables. Override any of these in .minecraft/config/btashaders.properties with
// define.NAME=value, or edit here in the dev client and press F7.

// ---- Lighting (HDR, linear; colors themselves live in lib/atmosphere.glsl) ----
#ifndef SUN_BRIGHTNESS
#define SUN_BRIGHTNESS 1.0           // direct sunlight (noon ~1.1 luminance, ~2.5x the shade)
#endif
#ifndef MOON_BRIGHTNESS
#define MOON_BRIGHTNESS 1.0
#endif
#ifndef AMBIENT_STRENGTH
#define AMBIENT_STRENGTH 0.40        // sky light in the shade: lower = deeper shadows
#endif
#ifndef AMBIENT_HUE_STRENGTH
#define AMBIENT_HUE_STRENGTH 1.0     // how much the sky colors the shade (blue by day): 0 = neutral
#endif
#ifndef BLOCKLIGHT_STRENGTH
#define BLOCKLIGHT_STRENGTH 1.1      // torches, lava, glowstone
#endif
#ifndef BLOCKLIGHT_TINT
#define BLOCKLIGHT_TINT vec3(1.0, 0.88, 0.74)
#endif
#ifndef MIN_LIGHT
#define MIN_LIGHT 1.0                // BTA's minimum (cave) light
#endif
#ifndef FACE_SHADE_BOTTOM
#define FACE_SHADE_BOTTOM 0.70       // replaces BTA's fixed face shading (top 1.0, sides ~0.85, bottom this)
#endif
#ifndef BAKED_SHADE_STRENGTH
#define BAKED_SHADE_STRENGTH 0.7     // BTA's baked per-vertex corner darkening: 1 = as stock, 0 = off
#endif
#ifndef BOUNCE_LIGHT
#define BOUNCE_LIGHT 0.10            // warm sunlight bounced off the ground onto sides/undersides
#endif
#ifndef HAND_NDOTL
#define HAND_NDOTL 0.65              // how much direct light the first-person hand gets
#endif
#ifndef LEAF_SUBSURFACE
#define LEAF_SUBSURFACE 0.28         // sunlight that passes through leaves even in shadow (subsurface)
#endif
#ifndef PLANT_SUBSURFACE
#define PLANT_SUBSURFACE 0.15        // same for grass/flowers
#endif
#ifndef LEAF_TRANSLUCENCY
#define LEAF_TRANSLUCENCY 0.5        // leaves let light through: 0 = opaque, 1 = fully lit from behind
#endif

// ---- Sky (lib/sky.glsl) ----
#ifndef SKY_ZENITH_DAY
#define SKY_ZENITH_DAY vec3(0.09, 0.24, 0.72)   // deep blue overhead at noon (linear)
#endif
#ifndef SKY_HORIZON_DAY
#define SKY_HORIZON_DAY vec3(0.42, 0.60, 0.90)  // lighter blue at the horizon
#endif
#ifndef SKY_SUN_GLOW
#define SKY_SUN_GLOW 1.0
#endif
#ifndef SKY_BRIGHTNESS
#define SKY_BRIGHTNESS 1.0
#endif

// ---- Clouds (lib/clouds.glsl) ----
#ifndef CLOUDS
#define CLOUDS 1
#endif
#ifndef CLOUD_COVERAGE
#define CLOUD_COVERAGE 0.42          // fraction of the sky covered
#endif
#ifndef CLOUD_CELL
#define CLOUD_CELL 16.0              // blocks per cloud cell (keep 1024 divisible by it)
#endif
#ifndef CLOUD_THICKNESS
#define CLOUD_THICKNESS 10.0
#endif
#ifndef CLOUD_DENSITY
#define CLOUD_DENSITY 0.9
#endif
#ifndef CLOUD_STEPS
#define CLOUD_STEPS 14
#endif
#ifndef CLOUD_DISTANCE
#define CLOUD_DISTANCE 700.0
#endif
#ifndef CLOUD_SPEED
#define CLOUD_SPEED 0.6              // blocks per second
#endif
#ifndef CLOUD_SUN
#define CLOUD_SUN 1.0                // how strongly the sun lights the clouds
#endif
#ifndef CLOUD_SHADOW_STRENGTH
#define CLOUD_SHADOW_STRENGTH 0.75   // how much sunlight a cloud blocks on the ground
#endif
#ifndef CLOUD_AMBIENT
#define CLOUD_AMBIENT 1.5            // brightness of the clouds' shaded parts
#endif
#ifndef CLOUD_ROUNDNESS
#define CLOUD_ROUNDNESS 0.06         // corner rounding of the cloud boxes (0.15 = blobby)
#endif
#ifndef CLOUD_BOTTOM_SHADE
#define CLOUD_BOTTOM_SHADE 0.45      // brightness of cloud undersides relative to tops
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
#ifndef PCSS
#define PCSS 1                       // contact-hardening shadows: sharp near the caster, soft further away
#endif
#ifndef PCSS_SOFTNESS
#define PCSS_SOFTNESS 0.035          // penumbra width per block of distance to the caster
#endif
#ifndef PCSS_MIN_RADIUS
#define PCSS_MIN_RADIUS 0.8          // texels
#endif
#ifndef PCSS_MAX_RADIUS
#define PCSS_MAX_RADIUS 9.0
#endif
#ifndef PCSS_SEARCH_RADIUS
#define PCSS_SEARCH_RADIUS 6.0
#endif
#ifndef PCSS_BLOCKER_SAMPLES
#define PCSS_BLOCKER_SAMPLES 6
#endif
#ifndef ENTITY_SHADOWS
#define ENTITY_SHADOWS 1
#endif
#ifndef COLORED_SHADOWS
#define COLORED_SHADOWS 1            // tinted glass and water color the light passing through them
#endif
#ifndef CAUSTICS
#define CAUSTICS 1                   // moving light patterns under water
#endif
#ifndef CAUSTICS_STRENGTH
#define CAUSTICS_STRENGTH 1.0
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
#define WATER_WAVE_STRENGTH 0.6
#endif
#ifndef WATER_ABSORPTION
#define WATER_ABSORPTION 1.0
#endif
#ifndef WATER_SPECULAR
#define WATER_SPECULAR 2.5
#endif

// ---- Materials: specular, reflections, wetness (lib/surface.glsl, lib/reflections.glsl) ----
#ifndef SPECULAR
#define SPECULAR 1                   // GGX sun/moon highlights on smooth and wet surfaces
#endif
#ifndef SPECULAR_STRENGTH
#define SPECULAR_STRENGTH 1.0
#endif
#ifndef REFLECTIONS
#define REFLECTIONS 1                // screen-space + sky reflections on smooth/wet blocks
#endif
#ifndef SSR_STEPS
#define SSR_STEPS 20
#endif
#ifndef REFLECTION_STRENGTH
#define REFLECTION_STRENGTH 1.0
#endif
#ifndef SMOOTH_POLISHED
#define SMOOTH_POLISHED 0.62         // polished stone, gem and quartz blocks
#endif
#ifndef SMOOTH_METAL
#define SMOOTH_METAL 0.72            // iron, gold, steel blocks
#endif
#ifndef SMOOTH_GLASS
#define SMOOTH_GLASS 0.9             // glass frames, ice
#endif
#ifndef RAIN_WETNESS
#define RAIN_WETNESS 1               // surfaces under open sky get wet in rain
#endif
#ifndef RAIN_PUDDLES
#define RAIN_PUDDLES 1
#endif
#ifndef PUDDLE_COVERAGE
#define PUDDLE_COVERAGE 0.5          // 0..1 share of flat ground covered in heavy rain
#endif
#ifndef WET_DARKEN
#define WET_DARKEN 0.35              // how much darker wet porous surfaces get
#endif
#ifndef WET_SMOOTHNESS
#define WET_SMOOTHNESS 0.45          // smoothness of wet (non-puddle) surfaces
#endif
#ifndef PRECIPITATION_OPACITY
#define PRECIPITATION_OPACITY 0.55   // how visible falling rain/snow streaks are
#endif
#ifndef RAIN_RIPPLES
#define RAIN_RIPPLES 1
#endif
#ifndef LABPBR
#define LABPBR 1                     // read normal + specular maps of labPBR resource packs (_n / _s textures)
#endif
#ifndef NORMAL_MAP_STRENGTH
#define NORMAL_MAP_STRENGTH 1.0
#endif
#ifndef PBR_EMISSION
#define PBR_EMISSION 1.0             // brightness of glowing parts from PBR specular maps
#endif
#ifndef RIPPLE_STRENGTH
#define RIPPLE_STRENGTH 1.0          // how strongly raindrop rings bend puddle reflections
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
#define SSAO_STRENGTH 0.5
#endif

// ---- Bloom (HDR glow of bright areas only, added on top) ----
#ifndef BLOOM
#define BLOOM 1
#endif
#ifndef BLOOM_STRENGTH
#define BLOOM_STRENGTH 0.35
#endif
#ifndef BLOOM_THRESHOLD
#define BLOOM_THRESHOLD 0.9          // linear brightness where glow starts (sky ~0.7, sunlit ~0.5-1)
#endif
#ifndef BLOOM_KNEE
#define BLOOM_KNEE 0.6               // soft transition around the threshold
#endif

// ---- Tone mapping (Lottes-style curve, linear in / gamma out) ----
#ifndef SSAO_FOLIAGE
#define SSAO_FOLIAGE 0.4             // AO strength on leaves/plants (their holes look like crevices)
#endif
#ifndef SSAO_SUN_REDUCTION
#define SSAO_SUN_REDUCTION 0.6       // less AO where the sun shines directly
#endif

#ifndef TONEMAP_EXPOSURE
#define TONEMAP_EXPOSURE 1.1
#endif
#ifndef AUTO_EXPOSURE
#define AUTO_EXPOSURE 1              // eye adaptation: brighter in shade/indoors, normal in open sun
#endif
#ifndef AUTO_EXPOSURE_KEY
#define AUTO_EXPOSURE_KEY 0.22       // target average (linear) scene brightness
#endif
#ifndef AUTO_EXPOSURE_STRENGTH
#define AUTO_EXPOSURE_STRENGTH 0.5   // 0 = fixed exposure, 1 = full adaptation
#endif
#ifndef AUTO_EXPOSURE_MIN
#define AUTO_EXPOSURE_MIN 0.75
#endif
#ifndef AUTO_EXPOSURE_MAX
#define AUTO_EXPOSURE_MAX 1.4        // limit so caves/night don't turn into daylight
#endif
#ifndef AUTO_EXPOSURE_SPEED
#define AUTO_EXPOSURE_SPEED 1.5      // adaptation speed (1/seconds)
#endif
#ifndef TONEMAP_CONTRAST
#define TONEMAP_CONTRAST 1.15        // curve contrast ("a")
#endif
#ifndef TONEMAP_WHITE
#define TONEMAP_WHITE 6.0            // input value that maps to white
#endif
#ifndef TONEMAP_SATURATION
#define TONEMAP_SATURATION 1.15
#endif

// ---- Composite ----
#ifndef VOLUMETRIC_LIGHT
#define VOLUMETRIC_LIGHT 1           // shadow-map raymarched light shafts (0 = old screen-space god rays)
#endif
#ifndef VL_STEPS
#define VL_STEPS 14
#endif
#ifndef VL_DISTANCE
#define VL_DISTANCE 96.0             // blocks
#endif
#ifndef VL_STRENGTH
#define VL_STRENGTH 0.06
#endif
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
#ifndef BORDER_FOG
#define BORDER_FOG 1                 // fade terrain into the sky at the view-distance edge
#endif
#ifndef BORDER_FOG_START
#define BORDER_FOG_START 0.65        // where the fade starts (fraction of the view distance)
#endif
#ifndef ATMOS_DENSITY
#define ATMOS_DENSITY 0.004          // aerial perspective (distant terrain picks up sky/sun color)
#endif
#ifndef ATMOS_STRENGTH
#define ATMOS_STRENGTH 0.08
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
