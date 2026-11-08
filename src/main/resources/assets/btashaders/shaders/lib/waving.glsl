// Wind animation for plants and leaves. Included by terrain.vsh AND shadow.vsh so shadows move with
// the plants. Needs: lib/settings.glsl, lib/materials.glsl, uniform float frameTimeCounter,
// uniform vec3 btsCameraMod (camera position mod 1024, for a stable world-space phase).

vec3 applyWaving(vec3 relPos, int material, bool topVertex) {
    vec3 world = relPos + btsCameraMod;
    float t = frameTimeCounter * WAVING_SPEED;
#if WAVING_PLANTS
    if (material == MAT_PLANT && topVertex) {
        // Two layered gusts with world-space phase so neighbouring plants don't move in lockstep.
        float gust = sin(t * 0.6 + world.x * 0.05 + world.z * 0.04) * 0.5 + 0.5;
        float x = sin(t * 1.7 + world.x * 0.9 + world.z * 0.4) * 0.055 + sin(t * 3.3 + world.z * 1.3) * 0.02;
        float z = sin(t * 1.4 + world.z * 0.8 + world.x * 0.5) * 0.045 + sin(t * 2.9 + world.x * 1.1) * 0.02;
        relPos.xz += vec2(x, z) * (0.6 + 0.8 * gust) * WAVING_STRENGTH;
    }
#endif
#if WAVING_LEAVES
    if (material == MAT_LEAVES) {
        float x = sin(t * 1.3 + world.x * 0.7 + world.y * 0.3 + world.z * 0.5) * 0.025;
        float y = sin(t * 1.9 + world.x * 0.4 + world.z * 0.8) * 0.015;
        float z = sin(t * 1.1 + world.z * 0.6 + world.y * 0.4) * 0.025;
        relPos += vec3(x, y, z) * WAVING_STRENGTH;
    }
#endif
    return relPos;
}
