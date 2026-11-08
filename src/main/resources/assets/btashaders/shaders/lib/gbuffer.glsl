// G-buffer outputs of our world shaders (see GBuffer.java). Written alongside the lit color; read by
// screen-space passes (SSAO, reflections) and the F6 debug view.
//   location 1, gNormal (RGBA16F): xy = octahedral world normal * 0.5 + 0.5,
//                                  z = (material code + smoothness * 0.9) / 16, w = 1
//   location 2, gData (RGBA8):     r = sky light, g = block light, b = direct sun visibility, w = 1 (written)
//   location 3, gMotion (RGBA16F):  xyz = object motion since last frame in blocks (entities), w = 1
// Alpha is always 1: BTA draws entities with alpha blending on, which also applies to these targets,
// and SRC_ALPHA/ONE_MINUS_SRC_ALPHA with alpha 1 simply replaces the old value.
// Material codes: 0-9 terrain materials (lib/materials.glsl), 12 entity, 13 item/world object, 14 other.
// Smoothness: 0 = rough (no reflections), 1 = mirror. Kept in the fraction of z: fp16 has 7+ bits of
// fraction left at codes < 16, and the 0.9 scale keeps rounding from ever reaching the next code.
#ifndef BTS_GBUFFER
#define BTS_GBUFFER

#define GCODE_ENTITY 12
#define GCODE_OBJECT 13
#define GCODE_OTHER 14

// Octahedral normal encoding (unit vector <-> [-1,1]^2).
vec2 octEncode(vec3 n) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    vec2 e = n.xy;
    if (n.z < 0.0) {
        e = (1.0 - abs(n.yx)) * vec2(e.x >= 0.0 ? 1.0 : -1.0, e.y >= 0.0 ? 1.0 : -1.0);
    }
    return e;
}

vec3 octDecode(vec2 e) {
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

vec3 gbufferNormal(vec4 gNormal) {
    return octDecode(gNormal.xy * 2.0 - 1.0);
}

int gbufferCode(vec4 gNormal) {
    return int(floor(gNormal.z * 16.0 + 0.004));
}

float gbufferSmoothness(vec4 gNormal) {
    float z = gNormal.z * 16.0;
    return clamp((z - floor(z + 0.004)) / 0.9, 0.0, 1.0);
}

#ifndef GBUFFER_READ_ONLY
layout (location = 1) out vec4 GNormal;
layout (location = 2) out vec4 GData;
layout (location = 3) out vec4 GMotion;
uniform vec3 btsObjectMotion;   // this entity's movement since last frame (EntityMotion.java), else 0

void writeGBuffer(vec3 worldNormal, int code, vec2 lightCoord, float directVis, float smoothness) {
    float codeAndSmooth = (float(code) + clamp(smoothness, 0.0, 1.0) * 0.9) / 16.0;
    GNormal = vec4(octEncode(worldNormal) * 0.5 + 0.5, codeAndSmooth, 1.0);
    float sky = clamp((lightCoord.y * 16.0 - 0.5) / 15.0, 0.0, 1.0);
    float block = clamp((lightCoord.x * 16.0 - 0.5) / 15.0, 0.0, 1.0);
    GData = vec4(sky, block, clamp(directVis, 0.0, 1.0), 1.0);
    GMotion = vec4(btsObjectMotion, 1.0);
}

void writeGBuffer(vec3 worldNormal, int code, vec2 lightCoord, float directVis) {
    writeGBuffer(worldNormal, code, lightCoord, directVis, 0.0);
}
#endif

#endif
