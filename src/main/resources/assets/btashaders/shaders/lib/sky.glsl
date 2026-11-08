// Analytic sky (own design, in the spirit of Complementary's gradient sky): one function gives the
// sky color for any view direction, time of day and weather. Used to draw the sky (tonemap pass) and
// as the fog color of every world shader, so terrain always fades into exactly the sky behind it.
// LINEAR HDR. Needs: lib/settings.glsl.
#ifndef BTS_SKY
#define BTS_SKY

#include "lib/atmosphere.glsl"

vec3 sunDirection() {
    return btsLightIsSun == 1 ? btsLightDir : -btsLightDir;
}

vec3 skyColor(vec3 dir) {
    vec3 sunDir = sunDirection();
    float sunH = btsSunHeight;
    float day = smoothstep(-0.14, 0.10, sunH);
    // Sunrise/sunset strength: peaks with the sun at the horizon.
    float sunset = 1.0 - smoothstep(0.0, 0.38, abs(sunH + 0.02));
    float up = clamp(dir.y, 0.0, 1.0);
    float horizon = pow(1.0 - up, 4.0);
    float vDotS = dot(dir, sunDir);
    float towardSun = clamp(vDotS * 0.5 + 0.5, 0.0, 1.0);

    vec3 zenithDay = SKY_ZENITH_DAY;
    vec3 horizonDay = SKY_HORIZON_DAY;
    vec3 zenithSet = vec3(0.17, 0.16, 0.42);
    vec3 horizonSetSun = vec3(1.15, 0.50, 0.20);
    vec3 horizonSetAway = vec3(0.50, 0.36, 0.52);
    vec3 zenithNight = vec3(0.004, 0.007, 0.020);
    vec3 horizonNight = vec3(0.012, 0.018, 0.038);

    vec3 horizonSet = mix(horizonSetAway, horizonSetSun, pow(towardSun, 3.0));
    vec3 zenith = mix(zenithDay, zenithSet, sunset);
    vec3 hor = mix(horizonDay, horizonSet, sunset);
    vec3 skyDay = mix(zenith, hor, horizon);
    vec3 skyNight = mix(zenithNight, horizonNight, horizon);
    vec3 sky = mix(skyNight, skyDay, day);

    // Glow around the sun (forward scattering), much stronger when the sun is low.
    float s = max(vDotS, 0.0);
    float glow = pow(s, 6.0) * 0.30 + pow(s, 40.0) * 0.9;
    sky += sunLightColor() * glow * day * (0.35 + 0.65 * sunset) * SKY_SUN_GLOW;
    // Faint moon halo at night.
    float moon = pow(max(dot(dir, -sunDir), 0.0), 30.0);
    sky += moonLightColor() * moon * 0.6 * (1.0 - day);

    // Below the horizon: darker, like looking towards the ground through haze.
    if (dir.y < 0.0) {
        sky *= mix(1.0, 0.45, smoothstep(0.0, 0.3, -dir.y));
    }
    // Overcast: grey and darker.
    float l = dot(sky, vec3(0.2126, 0.7152, 0.0722));
    sky = mix(sky, vec3(l) * vec3(0.88, 0.92, 1.0) * 0.75, btsRain * 0.85);
    return sky * SKY_BRIGHTNESS;
}

// Fog color: the sky at (just above) the horizon in this direction, gamma-encoded like the scene.
vec3 skyFogColor(vec3 worldDir) {
    vec3 d = normalize(vec3(worldDir.x, clamp(worldDir.y, 0.02, 0.25), worldDir.z));
    return toGamma(skyColor(d));
}

#endif
