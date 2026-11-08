// Deferred reflections for smooth / wet opaque surfaces (prepare pass). The G-buffer tells us where
// and how smooth; we trace the reflected ray through the depth buffer (screen-space reflection) and
// fall back to our sky, dimmed where the surface sees little sky (caves, under roofs).
// Rough surfaces jitter the ray per pixel and frame; TAA turns the noise into blur.
// Needs (prepare.fsh): colortex0, depthtex0, btsProj, btsView, btsViewInv, viewPosAt(), HAND_DEPTH,
// lib/materials.glsl, lib/gbuffer.glsl, lib/sky.glsl.
#ifndef BTS_REFLECTIONS
#define BTS_REFLECTIONS

// March in view space, test against the depth buffer. Returns hit color (linear) + confidence.
vec4 traceReflection(vec3 origin, vec3 dir, float jitter) {
    if (dir.z > 0.6) return vec4(0.0); // pointing back at the camera: nothing on screen to hit
    float dist = max(-origin.z, 0.5);
    float stepLen = 0.08 + dist * 0.035;
    vec3 prev = origin;
    vec3 p = origin + dir * stepLen * (0.5 + jitter);
    for (int i = 0; i < SSR_STEPS; i++) {
        vec4 clip = btsProj * vec4(p, 1.0);
        if (clip.w <= 0.0) break;
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) break;
        float d = texture(depthtex0, uv).r;
        if (d < 1.0 && d > HAND_DEPTH) {
            float sceneZ = viewPosAt(uv).z;
            float behind = sceneZ - p.z; // > 0: the ray went behind the visible surface
            if (behind > 0.0) {
                if (behind > stepLen * 2.0 + 0.4) {
                    // Passed behind a foreground object: no valid hit along this part of the ray.
                    prev = p;
                    p += dir * stepLen;
                    stepLen *= 1.22;
                    continue;
                }
                // Refine between the last two points.
                vec3 a = prev;
                vec3 b = p;
                for (int j = 0; j < 4; j++) {
                    vec3 m = (a + b) * 0.5;
                    vec4 mc = btsProj * vec4(m, 1.0);
                    vec2 muv = mc.xy / mc.w * 0.5 + 0.5;
                    if (viewPosAt(muv).z > m.z) b = m; else a = m;
                }
                vec4 hc = btsProj * vec4(b, 1.0);
                vec2 huv = hc.xy / hc.w * 0.5 + 0.5;
                vec2 edge = smoothstep(vec2(0.0), vec2(0.08), huv) * (1.0 - smoothstep(vec2(0.92), vec2(1.0), huv));
                float facing = 1.0 - smoothstep(0.2, 0.6, dir.z);
                return vec4(toLinear(texture(colortex0, huv).rgb), edge.x * edge.y * facing);
            }
        }
        prev = p;
        p += dir * stepLen;
        stepLen *= 1.22;
    }
    return vec4(0.0);
}

// Environment seen in direction dir (world space): our sky above the horizon, a dim ground below.
vec3 environmentColor(vec3 dir, float skyLight) {
    vec3 horizon = skyColor(normalize(vec3(dir.x, 0.03, dir.z)));
    vec3 sky = dir.y > 0.0 ? skyColor(normalize(vec3(dir.x, max(dir.y, 0.03), dir.z))) : horizon * 0.35;
    float s2 = skyLight * skyLight;
    return sky * s2 * s2;
}

vec2 reflectionNoise() {
    float a = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))
                    + 0.618034 * mod(btsFrame, 64.0));
    float b = fract(52.9829189 * fract(dot(gl_FragCoord.xy + 37.0, vec2(0.00583715, 0.06711056)))
                    + 0.754878 * mod(btsFrame, 64.0));
    return vec2(a, b);
}

// linearColor: lit surface color (linear HDR). viewPos: surface position in view space.
vec3 applyReflections(vec3 linearColor, vec3 viewPos, vec4 gNormal, vec4 gData) {
    float smoothness = gbufferSmoothness(gNormal);
    if (smoothness < 0.05) return linearColor;
    int code = gbufferCode(gNormal);
    bool metal = code == MAT_METAL;
    vec3 n = gbufferNormal(gNormal);
    vec3 relPos = (btsViewInv * vec4(viewPos, 1.0)).xyz;
    vec3 v = -normalize(relPos);

    // Rough surfaces: tilt the normal randomly (roughly GGX-shaped lobe), TAA averages.
    float roughness = 1.0 - smoothness;
    vec2 xi = reflectionNoise();
    float spread = roughness * roughness * 0.9;
    float phi = xi.x * 6.2831853;
    float r = sqrt(xi.y) * spread;
    vec3 t = normalize(abs(n.y) < 0.99 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
    vec3 bt = cross(n, t);
    vec3 nj = normalize(n + (t * cos(phi) + bt * sin(phi)) * r);
    if (dot(nj, v) < 0.05) nj = n;
    vec3 dir = reflect(-v, nj);

    float nv = clamp(dot(nj, v), 0.0, 1.0);
    float f0 = metal ? 0.9 : (code == MAT_POLISHED ? 0.045 : (code == MAT_TRANSLUCENT ? 0.04 : 0.02));
    float fresnel = f0 + (1.0 - f0) * pow(1.0 - nv, 5.0);
    // Rough surfaces lose the strong grazing-angle mirror.
    fresnel = mix(f0, fresnel, smoothness * smoothness) * smoothness;

    vec3 reflection = environmentColor(dir, gData.r);
    vec4 hit = traceReflection(viewPos, normalize(mat3(btsView) * dir), xi.x);
    reflection = mix(reflection, hit.rgb, hit.a);

    if (metal) {
        // Metals: the reflection takes the surface's own color and replaces most of the diffuse.
        float peak = max(max(linearColor.r, linearColor.g), max(linearColor.b, 1e-4));
        vec3 hue = clamp(linearColor / peak, 0.0, 1.0);
        // Linearized block textures can drop a channel to ~0 (a yellow gold texture keeps almost no
        // blue, so blue sky would turn green); real metal reflectance never does (gold ~0.3 blue).
        vec3 tint = max(hue, vec3(0.3));
        // Keep some of the diffuse look where the environment is dark (indoors) so metal blocks
        // don't turn black without anything to reflect.
        vec3 metalColor = max(reflection * tint, linearColor * 0.45);
        return mix(linearColor, metalColor, clamp(fresnel * REFLECTION_STRENGTH, 0.0, 0.8));
    }
    return mix(linearColor, reflection, clamp(fresnel * REFLECTION_STRENGTH, 0.0, 1.0));
}

#endif
