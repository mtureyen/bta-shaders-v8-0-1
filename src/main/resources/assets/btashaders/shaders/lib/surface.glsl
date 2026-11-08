// Surface properties of lit terrain: per-material smoothness / reflectance, rain wetness with puddles
// and ripples, and GGX highlights from the sun/moon. Reflections of the environment happen later in
// the prepare pass (lib/reflections.glsl), driven by the smoothness written to the G-buffer.
// Needs: lib/settings.glsl, lib/materials.glsl, lib/lighting.glsl (uniforms, atmosphere).
#ifndef BTS_SURFACE
#define BTS_SURFACE

uniform float btsWetness;   // 0..1: rises while it rains, dries slowly afterwards (ShadowRenderer)
#ifdef DEBUG_WETNESS
#define WETNESS float(DEBUG_WETNESS) // config: define.DEBUG_WETNESS=1 previews rain-soaked ground
#else
#define WETNESS btsWetness
#endif

struct Surface {
    float smoothness;   // 0 = rough, 1 = mirror
    float f0;           // reflectance at normal incidence (dielectrics)
    float metal;        // 1 = reflections tinted by the albedo
    float wet;          // 0..1 wet film
    float puddle;       // 0..1 standing water
    vec3 normal;        // shading normal (normal map, puddle ripples)
};

// ---- labPBR resource packs (PbrAtlas.java: extra layers of the block atlas) ----
//   normal  (_n): rg = tangent-space normal xy (OpenGL style, green = up), b = AO, a = height
//   specular(_s): r = perceptual smoothness, g = F0 (0-229) or metal (230-255), b = porosity (0-64),
//                 a = emission (0-254, 255 = none)
// An all-zero specular texel means "no PBR data": our per-material values apply.
#if LABPBR
uniform int btsPbrOn;
uniform sampler2D btsNormalTex;
uniform sampler2D btsSpecularTex;
#endif
vec4 btsPbrSpecular = vec4(0.0);
vec3 btsPbrNormal = vec3(0.0);
float btsPbrAO = 1.0;

bool pbrSurfaceMaterial(int material) {
    return material == MAT_DEFAULT || material == MAT_METAL || material == MAT_POLISHED || material == MAT_SNOW;
}

bool hasPbrSpecular() {
    return btsPbrSpecular.r + btsPbrSpecular.g > 0.0;
}

// Reads the PBR maps at uv. The tangent frame comes from screen-space derivatives (cotangent frame),
// so no tangents are needed in the vertex data. Call in uniform control flow (derivatives).
void readPbr(vec2 uv, vec3 relPos, vec3 geoNormal) {
#if LABPBR
    vec3 dp1 = dFdx(relPos);
    vec3 dp2 = dFdy(relPos);
    vec2 duv1 = dFdx(uv);
    vec2 duv2 = dFdy(uv);
    if (btsPbrOn != 1) return;
    vec4 normalTex = texture(btsNormalTex, uv);
    btsPbrSpecular = texture(btsSpecularTex, uv);
    btsPbrAO = normalTex.b;
    vec3 dp2perp = cross(dp2, geoNormal);
    vec3 dp1perp = cross(geoNormal, dp1);
    vec3 t = dp2perp * duv1.x + dp1perp * duv2.x;
    vec3 b = dp2perp * duv1.y + dp1perp * duv2.y;
    float invMax = inversesqrt(max(max(dot(t, t), dot(b, b)), 1e-30));
    vec2 xy = (normalTex.rg * 2.0 - 1.0) * NORMAL_MAP_STRENGTH;
    float z = sqrt(max(1.0 - dot(xy, xy), 0.0));
    // Atlas v grows downwards while the map's green points up, hence -b.
    vec3 n = t * invMax * xy.x - b * invMax * xy.y + geoNormal * z;
    btsPbrNormal = dot(n, n) > 1e-6 ? normalize(n) : geoNormal;
#endif
}

// labPBR hardcoded metals (specular green 230-237): their real reflectance color replaces the texture's
// albedo (metals have no diffuse color of their own); the texture keeps its brightness detail.
vec3 pbrMetalAlbedo(vec3 albedoGamma) {
#if LABPBR
    int id = int(btsPbrSpecular.g * 255.0 + 0.5) - 230;
    if (id < 0 || id > 7 || !hasPbrSpecular()) return albedoGamma;
    const vec3 F0[8] = vec3[8](
        vec3(0.56, 0.57, 0.58),  // iron
        vec3(1.00, 0.71, 0.29),  // gold
        vec3(0.91, 0.92, 0.92),  // aluminum
        vec3(0.55, 0.56, 0.55),  // chrome
        vec3(0.95, 0.64, 0.54),  // copper
        vec3(0.63, 0.63, 0.64),  // lead
        vec3(0.67, 0.64, 0.59),  // platinum
        vec3(0.95, 0.93, 0.88)); // silver
    float detail = clamp(dot(albedoGamma, vec3(0.2126, 0.7152, 0.0722)) * 1.4, 0.5, 1.15);
    return toGamma(F0[id]) * detail;
#else
    return albedoGamma;
#endif
}

// Glow from the specular map's alpha, added to a lit gamma color.
vec3 addPbrEmission(vec3 litGamma, vec3 albedoGamma) {
#if LABPBR
    float a = btsPbrSpecular.a * 255.0;
    if (a < 254.5 && a > 0.5) {
        return toGamma(toLinear(litGamma) + toLinear(albedoGamma) * (a / 254.0) * PBR_EMISSION);
    }
#endif
    return litGamma;
}

float surfHash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float surfNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(surfHash(i), surfHash(i + vec2(1.0, 0.0)), u.x),
               mix(surfHash(i + vec2(0.0, 1.0)), surfHash(i + vec2(1.0, 1.0)), u.x), u.y);
}

// Where water collects on flat ground: low-frequency blobs, growing with wetness.
float puddleMask(vec2 xz) {
    float n = surfNoise(xz * 0.13) * 0.65 + surfNoise(xz * 0.47 + 17.0) * 0.35;
    float threshold = 1.0 - PUDDLE_COVERAGE * WETNESS;
    return smoothstep(threshold, threshold + 0.07, n);
}

// Raindrop rings on standing water: one drop per cell per cycle, three offset grids. Returns the
// horizontal normal offset.
vec2 rainRipples(vec2 xz, float t) {
    vec2 sum = vec2(0.0);
    for (int layer = 0; layer < 3; layer++) {
        vec2 q = xz * 1.4 + float(layer) * vec2(0.37, 0.71);
        vec2 cell = floor(q);
        vec2 center = cell + 0.25 + 0.5 * vec2(surfHash(cell), surfHash(cell + 7.13));
        float phase = fract(t * 1.1 + surfHash(cell + 3.71) + float(layer) * 0.33);
        vec2 d = q - center;
        float dist = length(d) + 1e-4;
        float x = (dist - phase * 0.34) * 20.0;
        float ring = abs(x) < 1.0 ? sin(x * 3.14159265) : 0.0;
        float fade = (1.0 - phase) * (1.0 - phase);
        sum += d / dist * ring * fade;
    }
    return sum * 0.55 * RIPPLE_STRENGTH;
}

Surface surfaceFor(int material, vec3 geoNormal, vec3 relPos, float sky) {
    Surface s;
    s.smoothness = 0.0;
    s.f0 = 0.03;
    s.metal = 0.0;
    s.wet = 0.0;
    s.puddle = 0.0;
    s.normal = geoNormal;
    float porosity = 1.0;
    bool pbrSurface = pbrSurfaceMaterial(material) && dot(btsPbrNormal, btsPbrNormal) > 0.5;
    if (pbrSurface) s.normal = btsPbrNormal;
    if (pbrSurface && hasPbrSpecular()) {
        // The resource pack knows best.
        s.smoothness = btsPbrSpecular.r;
        float b = btsPbrSpecular.b * 255.0;
        porosity = b < 64.5 ? b / 64.0 : 0.0;
        if (btsPbrSpecular.g * 255.0 > 229.5) {
            s.metal = 1.0;
            porosity = 0.0;
        } else {
            s.f0 = btsPbrSpecular.g;
        }
        if (material == MAT_SNOW) return s;
    } else if (material == MAT_METAL) {
        s.smoothness = SMOOTH_METAL;
        s.metal = 1.0;
        porosity = 0.0;
    } else if (material == MAT_POLISHED) {
        s.smoothness = SMOOTH_POLISHED;
        s.f0 = 0.045;
        porosity = 0.25;
    } else if (material == MAT_TRANSLUCENT) {
        s.smoothness = SMOOTH_GLASS;
        s.f0 = 0.04;
        return s;
    } else if (material != MAT_DEFAULT) {
        return s; // plants, leaves, water, lava, emissive, snow: no wetness / specular here
    }
    if (btsLightingOn != 1) return s;

#if RAIN_WETNESS
    if (WETNESS > 0.001) {
        // Only where rain actually falls: full sky light. Walls get a lighter film than the ground.
        float exposed = smoothstep(0.9, 0.99, sky);
        float up = smoothstep(0.7, 0.95, geoNormal.y);
        s.wet = WETNESS * exposed * mix(0.6, 1.0, up);
#if RAIN_PUDDLES
        if (up > 0.0 && material != MAT_METAL) {
            vec3 world = relPos + btsCameraMod;
            s.puddle = puddleMask(world.xz) * up * exposed;
            s.normal = normalize(mix(s.normal, geoNormal, s.puddle)); // standing water is flat
        }
#endif
        s.smoothness = mix(s.smoothness, max(s.smoothness, WET_SMOOTHNESS), s.wet);
        s.smoothness = mix(s.smoothness, 0.96, s.puddle);
        s.f0 = mix(s.f0, 0.02, s.puddle);
        s.metal *= 1.0 - s.puddle;
        s.wet = max(s.wet * porosity, s.puddle);
#if RAIN_RIPPLES
        if (s.puddle > 0.01 && btsRain > 0.01) {
            vec3 world = relPos + btsCameraMod;
            vec2 ripple = rainRipples(world.xz, frameTimeCounter) * btsRain * s.puddle;
            s.normal = normalize(vec3(ripple.x, 1.0, ripple.y));
        }
#endif
    }
#endif
    return s;
}

// Wet materials soak up water: darker and a little more saturated.
vec3 wetAlbedo(vec3 albedo, Surface s) {
    if (s.wet <= 0.0) return albedo;
    float l = dot(albedo, vec3(0.2126, 0.7152, 0.0722));
    vec3 saturated = mix(vec3(l), albedo, 1.0 + 0.25 * s.wet);
    return clamp(saturated, 0.0, 1.0) * (1.0 - WET_DARKEN * s.wet);
}

// GGX / Smith (Schlick-approximated) highlight of a light with direction l.
float ggxSpecular(vec3 n, vec3 v, vec3 l, float smoothness, float f0) {
    float roughness = max(1.0 - smoothness, 0.14); // the sun isn't a point: no razor-thin peaks
    float a = roughness * roughness;
    float a2 = a * a;
    vec3 h = normalize(l + v);
    float nh = max(dot(n, h), 0.0);
    float nl = max(dot(n, l), 0.0);
    float nv = max(dot(n, v), 1e-3);
    float vh = max(dot(v, h), 0.0);
    float d = nh * nh * (a2 - 1.0) + 1.0;
    float D = a2 / (3.14159265 * d * d);
    float k = a * 0.5;
    float vis = 0.25 / ((nl * (1.0 - k) + k) * (nv * (1.0 - k) + k));
    float F = f0 + (1.0 - f0) * pow(1.0 - vh, 5.0);
    return min(D * vis * F, 24.0);
}

// Adds the sun/moon highlight to a lit (gamma-encoded) color. directVis: N.L * shadow * open sky.
vec3 addSpecular(vec3 litGamma, vec3 albedoGamma, Surface s, vec3 relPos, float directVis) {
#if SPECULAR
    if (s.smoothness < 0.05 || directVis <= 0.001 || btsLightingOn != 1) return litGamma;
    vec3 v = normalize(-relPos);
    float f0 = mix(s.f0, 1.0, s.metal);
    float spec = ggxSpecular(s.normal, v, btsLightDir, s.smoothness, f0);
    vec3 tint = mix(vec3(1.0), toLinear(albedoGamma), s.metal);
    vec3 highlight = activeLightColor(btsLightIsSun == 1) * directVis * spec * tint * SPECULAR_STRENGTH;
    return toGamma(toLinear(litGamma) + highlight);
#else
    return litGamma;
#endif
}

#endif
