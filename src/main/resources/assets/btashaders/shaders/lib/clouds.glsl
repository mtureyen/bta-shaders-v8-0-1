// Blocky, sun-lit clouds (own implementation, the idea of Complementary's "reimagined" clouds):
// a layer of cloud cells on a grid, with rounded edges (thresholded bilinear occupancy), raymarched
// in the tonemap pass and lit from the sun with a cheap 2-tap shadow. Replaces BTA's flat clouds.
// LINEAR HDR. Needs: lib/settings.glsl, lib/sky.glsl, uniforms below, frameTimeCounter.

uniform vec3 btsCameraMod;    // camera position mod 1024 (x/z used)
uniform float btsCameraY;
uniform float btsCloudHeight; // bottom of the cloud layer (world y)
uniform int btsCloudsOn;

// Periodic hash: the cell grid repeats every 64 cells (= 1024 blocks with 16-block cells), matching
// the camera's mod-1024 position so there's no seam.
float cellHash(vec2 cell) {
    cell = mod(cell, 64.0);
    return fract(sin(dot(cell, vec2(127.1, 311.7))) * 43758.5453);
}

float cellNoise(vec2 cell) {
    // Low-frequency clumping + per-cell variation.
    vec2 big = floor(cell / 4.0);
    vec2 f = fract(cell / 4.0);
    f = f * f * (3.0 - 2.0 * f);
    float a = cellHash(big + vec2(0.0, 0.0) + 17.0);
    float b = cellHash(big + vec2(1.0, 0.0) + 17.0);
    float c = cellHash(big + vec2(0.0, 1.0) + 17.0);
    float d = cellHash(big + vec2(1.0, 1.0) + 17.0);
    float clump = mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
    return clump * 0.7 + cellHash(cell) * 0.3;
}

float cellOccupied(vec2 cell) {
    float coverage = CLOUD_COVERAGE + btsRain * 0.25;
    return step(1.0 - coverage, cellNoise(cell));
}

// 0..1 cloud density at a world-ish position (x/z wrapped, y absolute).
float cloudDensity(vec3 p) {
    float h = (p.y - btsCloudHeight) / CLOUD_THICKNESS;
    if (h < 0.0 || h > 1.0) return 0.0;
    vec2 xz = p.xz + vec2(frameTimeCounter * CLOUD_SPEED, 0.0);
    vec2 g = xz / CLOUD_CELL - 0.5;
    vec2 cell = floor(g);
    vec2 f = fract(g);
    // Bilinear blend of the 4 neighbouring cells' occupancy, then threshold: blocky shapes with
    // rounded corners and soft edges.
    float o = mix(mix(cellOccupied(cell), cellOccupied(cell + vec2(1.0, 0.0)), f.x),
                  mix(cellOccupied(cell + vec2(0.0, 1.0)), cellOccupied(cell + vec2(1.0, 1.0)), f.x), f.y);
    // Narrow threshold: box-shaped clouds with only slightly rounded corners (CLOUD_ROUNDNESS widens it).
    float shape = smoothstep(0.5 - CLOUD_ROUNDNESS, 0.5 + CLOUD_ROUNDNESS, o);
    // Flat-ish bottom and top with a small rounding. (smoothstep needs edge0 < edge1, hence 1 - ...)
    float vertical = smoothstep(0.0, 0.12, h) * (1.0 - smoothstep(0.82, 1.0, h));
    return shape * vertical;
}

// Returns premultiplied cloud color (rgb) and opacity (a) along a view ray, up to maxDist.
vec4 renderClouds(vec3 dir, float maxDist, vec2 pixel) {
    if (btsCloudsOn == 0 || abs(dir.y) < 0.002) return vec4(0.0);
    float bottom = btsCloudHeight;
    float top = bottom + CLOUD_THICKNESS;
    float t0 = (bottom - btsCameraY) / dir.y;
    float t1 = (top - btsCameraY) / dir.y;
    float tEnter = max(min(t0, t1), 0.0);
    float tExit = min(max(t0, t1), min(maxDist, CLOUD_DISTANCE));
    // Grazing rays cross a lot of layer; cap the marched length so steps stay small (the cloud is
    // opaque long before that anyway).
    tExit = min(tExit, tEnter + float(CLOUD_STEPS) * 3.0);
    if (tExit <= tEnter) return vec4(0.0);

    vec3 camPos = vec3(btsCameraMod.x, btsCameraY, btsCameraMod.z);
    vec3 sunDir = sunDirection();
    vec3 lightColor = activeLightColor(btsLightIsSun == 1);
    // Ambient: the sky's light, but much whiter and brighter than the sky color itself (light bounces
    // around inside a cloud many times), otherwise cloud interiors take on the sky's blue and vanish.
    vec3 skyUp = skyColor(vec3(0.0, 1.0, 0.0));
    vec3 ambient = mix(skyUp, vec3(dot(skyUp, vec3(0.2126, 0.7152, 0.0722))), 0.55) * CLOUD_AMBIENT;

    float dt = (tExit - tEnter) / float(CLOUD_STEPS);
    float jitter = fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
    float t = tEnter + dt * jitter;
    float transmittance = 1.0;
    vec3 color = vec3(0.0);
    for (int i = 0; i < CLOUD_STEPS; i++) {
        vec3 p = camPos + dir * t;
        float d = cloudDensity(p);
        if (d > 0.01) {
            float shadow = cloudDensity(p + sunDir * 2.0) + cloudDensity(p + sunDir * 6.0);
            // Multiple scattering, approximated: sunlight never fully disappears inside a cloud.
            float lightT = mix(exp(-shadow * 0.9), 1.0, 0.35);
            float h = clamp((p.y - bottom) / CLOUD_THICKNESS, 0.0, 1.0);
            // Volume: bright sunlit tops/sides, darker and slightly sky-blue undersides.
            vec3 ambientHere = mix(skyUp * CLOUD_AMBIENT * 0.7, ambient, h) * (CLOUD_BOTTOM_SHADE + (1.0 - CLOUD_BOTTOM_SHADE) * h);
            vec3 lit = lightColor * lightT * (0.4 + 0.6 * h) * CLOUD_SUN + ambientHere;
            float a = 1.0 - exp(-d * dt * CLOUD_DENSITY);
            color += transmittance * a * lit;
            transmittance *= 1.0 - a;
            if (transmittance < 0.03) break;
        }
        t += dt;
    }
    // Fade out with distance into the sky.
    float fade = 1.0 - smoothstep(CLOUD_DISTANCE * 0.55, CLOUD_DISTANCE, tEnter);
    float opacity = (1.0 - transmittance) * fade;
    return vec4(color * fade, opacity);
}
