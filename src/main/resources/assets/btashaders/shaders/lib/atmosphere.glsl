// Atmosphere model: the single source of light colors for every pass, so sunrise, noon, sunset and
// night always look coherent. All colors are LINEAR and HDR (sunlight is well above 1).
// Needs: lib/settings.glsl.
#ifndef BTS_ATMOSPHERE
#define BTS_ATMOSPHERE

#include "lib/uniforms.glsl"

float sunVisibility() {
    return smoothstep(-0.06, 0.08, btsSunHeight);
}

// 0 at the horizon, 1 with the sun high in the sky.
float noonFactor() {
    return sqrt(clamp(btsSunHeight, 0.0, 1.0));
}

// Direct sunlight: warm white at noon, deep orange at sunrise/sunset.
vec3 sunLightColor() {
    float noon = noonFactor();
    vec3 noonColor = vec3(1.30, 1.12, 0.82);
    // Low sun: light travels through more atmosphere -> red/orange, and dimmer.
    vec3 sunsetColor = vec3(1.40, 0.52, 0.17) * (0.55 + 0.45 * noon);
    vec3 c = mix(sunsetColor, noonColor, smoothstep(0.0, 0.75, noon));
    return c * SUN_BRIGHTNESS * (1.0 - 0.85 * btsRain);
}

vec3 moonLightColor() {
    return vec3(0.11, 0.13, 0.20) * MOON_BRIGHTNESS * (1.0 - 0.7 * btsRain);
}

// Hue of the sky's ambient light (luminance ~1; brightness comes from BTA's own sky light curve).
vec3 ambientSkyHue() {
    float day = sunVisibility();
    float noon = noonFactor();
    vec3 noonHue = vec3(0.88, 0.96, 1.06);
    vec3 sunsetHue = vec3(1.00, 0.86, 0.95);
    vec3 nightHue = vec3(0.62, 0.74, 1.10);
    vec3 dayHue = mix(sunsetHue, noonHue, smoothstep(0.0, 0.6, noon));
    vec3 hue = mix(nightHue, dayHue, day);
    // Overcast: grey, flat light.
    hue = mix(hue, vec3(0.95, 0.98, 1.02), btsRain * 0.7);
    return hue * AMBIENT_HUE_STRENGTH + (1.0 - AMBIENT_HUE_STRENGTH);
}

// Color of the light that's currently casting shadows (sun by day, moon by night).
vec3 activeLightColor(bool lightIsSun) {
    return lightIsSun ? sunLightColor() : moonLightColor();
}

// Normalized hue of the active light, for glows / god rays / haze.
vec3 activeLightHue(bool lightIsSun) {
    vec3 c = activeLightColor(lightIsSun);
    return c / max(max(c.r, max(c.g, c.b)), 1e-4);
}

vec3 toLinear(vec3 c) {
    return pow(max(c, 0.0), vec3(2.2));
}

vec3 toGamma(vec3 c) {
    return pow(max(c, 0.0), vec3(1.0 / 2.2));
}

#endif
