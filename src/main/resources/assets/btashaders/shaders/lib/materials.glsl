// Material IDs written into the terrain vertex normal bytes by MaterialContext.java (keep in sync).
#ifndef BTS_MATERIALS
#define BTS_MATERIALS

#define MAT_DEFAULT 0
#define MAT_PLANT 1
#define MAT_LEAVES 2
#define MAT_WATER 3
#define MAT_LAVA 4
#define MAT_EMISSIVE 5
#define MAT_TRANSLUCENT 6   // glass, ice
#define MAT_METAL 7         // iron / gold / steel blocks
#define MAT_POLISHED 8      // polished stone, gems, quartz, marble
#define MAT_SNOW 9          // snow, layers of snow (never wet)
#define MAT_COUNT 10

// aNormal is the normalized signed-byte normal attribute (byte / 127).
int decodeMaterial(vec3 packedNormal) {
    int id = int(floor(packedNormal.x * 127.0 + 0.5));
    return (id >= 0 && id < MAT_COUNT) ? id : MAT_DEFAULT;
}

bool decodeTopVertex(vec3 packedNormal) {
    return packedNormal.y > 0.5;
}

float decodeEmission(vec3 packedNormal) {
    return clamp(floor(packedNormal.z * 127.0 + 0.5) / 15.0, 0.0, 1.0);
}

#endif
