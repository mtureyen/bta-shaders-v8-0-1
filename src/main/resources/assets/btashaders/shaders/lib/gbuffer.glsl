// G-buffer outputs of our world shaders (see GBuffer.java). Written alongside the lit color; read by
// screen-space passes (SSAO now; reflections, specular, volumetrics later) and the F6 debug view.
//   location 1, gNormal: xy = octahedral world normal * 0.5 + 0.5, z = material code / 16, w = 1
//   location 2, gData:   r = sky light, g = block light, b = direct sun visibility, w = 1 (written)
// Alpha is always 1: BTA draws entities with alpha blending on, which also applies to these targets,
// and SRC_ALPHA/ONE_MINUS_SRC_ALPHA with alpha 1 simply replaces the old value.
// Material codes: 0-6 terrain materials (lib/materials.glsl), 8 entity, 9 item/world object, 10 other.
#ifndef BTS_GBUFFER
#define BTS_GBUFFER

#define GCODE_ENTITY 8
#define GCODE_OBJECT 9
#define GCODE_OTHER 10

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
    return int(floor(gNormal.z * 16.0 + 0.5));
}

#ifndef GBUFFER_READ_ONLY
layout (location = 1) out vec4 GNormal;
layout (location = 2) out vec4 GData;

void writeGBuffer(vec3 worldNormal, int code, vec2 lightCoord, float directVis) {
    GNormal = vec4(octEncode(worldNormal) * 0.5 + 0.5, float(code) / 16.0, 1.0);
    float sky = clamp((lightCoord.y * 16.0 - 0.5) / 15.0, 0.0, 1.0);
    float block = clamp((lightCoord.x * 16.0 - 0.5) / 15.0, 0.0, 1.0);
    GData = vec4(sky, block, clamp(directVis, 0.0, 1.0), 1.0);
}
#endif

#endif
