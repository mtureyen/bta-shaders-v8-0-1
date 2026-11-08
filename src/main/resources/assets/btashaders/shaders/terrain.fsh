#version 410 core
// BTA Shaders terrain fragment shader: BTA 8.0.1 terrain.fsh plus sun/moon shadows.
// Shadows only reduce the SKY part of the lightmap, so torches, lava and emissive blocks still light shadowed areas.

out vec4 FragColor;

in vec4 Color;
in float Shade;
in vec2 TexCoord;
in vec2 LightMapCoord;
in vec3 Light;
in vec4 Norm;
in vec3 RelPos; // camera-relative world position

uniform float uLightMapStrength;
uniform float uAlphaTest;
uniform vec4 uColor;
uniform vec2 uViewPortSize;

layout (std140) uniform FogState {
    vec4 color;
    int mode;
    float start;
    float end;
    float density;
} fogState;

layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

uniform sampler2D colortex;
uniform sampler2D lighttex;
uniform sampler2D emissivetex;
uniform sampler2D colormasktex;

// BTA Shaders
uniform int btsLightingOn;       // our lighting model (else stock BTA lightmap)
uniform int btsShadowsOn;        // shadow map available this frame
uniform sampler2DShadow btsShadowTex; // hardware depth compare + linear filter = bilinear PCF per tap
uniform float btsShadowStrength; // 0..1 (fades at dawn/dusk, weaker under the moon, off in rain)
uniform float btsShadowTexel;    // 1 / shadow map resolution
uniform mat4 btsShadowMatrix;    // camera-relative position -> shadow clip space
uniform vec3 btsLightDir;        // towards sun/moon, world space
uniform float btsShadowWorldTexel; // size of one shadow-map texel in blocks (before distortion)
uniform vec3 btsCameraFract;     // fract(camera world position), to find block centers
uniform vec3 btsDirectColor;     // sun/moon light color * intensity (time of day, rain)
uniform vec3 btsAmbientTint;     // sky ambient tint (time of day)

// ---- Tunables (edit + F7 in the dev client to see changes live) ----
// Sky light kept as "ambient" (lightmap sky coordinate multiplier); the rest comes from direct sun.
#define AMBIENT_SKY_LIGHT 0.78
#define SHADOW_DEPTH_BIAS 0.00012
#define SHADOW_DISTORT 0.85       // must match shadow.vsh
#define SHADOW_SAMPLES 10
#define FOLIAGE_SHADOW_SAMPLES 16
#define SHADOW_FILTER_RADIUS 1.6  // in shadow-map texels (near the player)
#define FOLIAGE_FILTER_MULT 3.0   // plants get a much softer filter (Complementary does the same)

float distance() {
    vec4 fragPos = matrices.projectionInv * vec4((gl_FragCoord.xy / uViewPortSize) * 2.0 - 1.0, gl_FragCoord.z * 2.0 - 1.0, 1.0);
    fragPos /= fragPos.w;

    return length(fragPos.xyz);
}

// Taken from https://github.com/hughsk/glsl-fog
float fogFactorLinear() {
    return 1.0 - clamp(((fogState.end - (distance())) / (fogState.end - fogState.start)), 0.0, 1.0);
}

float fogFactorExp() {
    return 1.0 - clamp(exp(-fogState.density * (distance())), 0.0, 1.0);
}

float fogFactorExp2() {
    float LOG2 = -1.442695;
    float d = fogState.density * (distance());
    return 1.0 - clamp(exp2(d * d * LOG2), 0.0, 1.0);
}

vec4 computeFog(vec4 color) {
    float f = 0;
    if (fogState.mode == 0) { // Linear
        f = fogFactorLinear();
    }
    else if (fogState.mode == 1) { // EXP
        f = fogFactorExp();
    }
    else if (fogState.mode == 2) { // EXP2
        f = fogFactorExp2();
    }
    return vec4(mix(color.rgb, fogState.color.rgb, f), color.a);
}

float distortFactor(vec2 clipXY) {
    return length(clipXY) * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
}

// 0 = fully lit, 1 = fully in shadow.
// geoNormal: geometric face normal (from screen-space derivatives, facing the camera).
// sky: 0..1 sky light at this fragment.
float shadowAmount(vec3 geoNormal, bool foliage, float sky) {
    if (btsShadowsOn == 0) {
        return 0.0;
    }
    vec3 world = RelPos + btsCameraFract;
    vec3 blockCenter = floor(world + geoNormal * 0.01) + 0.5 - btsCameraFract;
    vec3 samplePos;
    if (foliage) {
        // Plants: no normal offset (their normals are meaningless). Pulled towards the block center
        // when it's dark, so they never pick up light leaking through walls (Complementary's approach).
        samplePos = mix(blockCenter, RelPos, 0.5 + 0.5 * sky) + vec3(0.0, 0.05, 0.0);
    } else {
        // Normal offset: sample slightly off the surface (more at grazing angles) to avoid acne
        // without a big depth bias; scaled by the local texel size (smaller near the player).
        float nDotL = clamp(dot(geoNormal, btsLightDir), 0.0, 1.0);
        float texelHere = btsShadowWorldTexel * distortFactor((btsShadowMatrix * vec4(RelPos, 1.0)).xy);
        samplePos = RelPos + geoNormal * texelHere * (0.8 + 1.6 * (1.0 - nDotL));
        // Cave light-leak fix: in the dark, pull the sample towards the block center.
        samplePos = mix(samplePos, blockCenter, 0.2 * (1.0 - sky * sky * sky * sky));
    }
    vec3 clip = (btsShadowMatrix * vec4(samplePos, 1.0)).xyz;
    if (abs(clip.x) >= 1.0 || abs(clip.y) >= 1.0 || clip.z >= 1.0) {
        return 0.0;
    }
    float refDepth = (clip.z * 0.5 + 0.5) - SHADOW_DEPTH_BIAS - (foliage ? 0.0001 : 0.0);

    // Soft filter: a golden-angle spiral of hardware-filtered depth compares, rotated per pixel by
    // interleaved gradient noise (TAA then averages the noise away). Like Photon, the taps are offset
    // in undistorted space and each one is distorted separately, so the softness has a constant size
    // in the world instead of depending on where the distortion puts this pixel.
    int samples = foliage ? FOLIAGE_SHADOW_SAMPLES : SHADOW_SAMPLES;
    float radius = SHADOW_FILTER_RADIUS * (foliage ? FOLIAGE_FILTER_MULT : 1.0);
    // One shadow-map texel at this spot, expressed in undistorted clip units.
    float clipStep = radius * btsShadowTexel * 2.0 * distortFactor(clip.xy);
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float rotation = noise * 6.2831853;
    float lit = 0.0;
    for (int i = 0; i < FOLIAGE_SHADOW_SAMPLES; i++) {
        if (i >= samples) break;
        float r = sqrt((float(i) + 0.5) / float(samples));
        float a = rotation + float(i) * 2.3999632;
        vec2 tap = clip.xy + vec2(cos(a), sin(a)) * r * clipStep;
        vec2 uv = tap / distortFactor(tap) * 0.5 + 0.5;
        lit += textureLod(btsShadowTex, vec3(uv, refDepth), 0.0);
    }
    float shadowed = 1.0 - lit / float(samples);

    // Fade out towards the edge of the shadow distance instead of a hard cut-off.
    float fade = 1.0 - smoothstep(0.8, 1.0, max(abs(clip.x), abs(clip.y)));
    return shadowed * fade * btsShadowStrength;
}

void main() {
    // Derivatives must be taken in uniform control flow, so compute the face normal up front.
    vec3 geoNormal = normalize(cross(dFdx(RelPos), dFdy(RelPos)));
    if (dot(geoNormal, RelPos) > 0.0) {
        geoNormal = -geoNormal; // make it face the camera
    }

    vec2 emissiveBrightness = texture(emissivetex, TexCoord).xy;
    vec2 lightCoord = vec2(max(LightMapCoord.x, emissiveBrightness.x), max(LightMapCoord.y, emissiveBrightness.y));
    vec3 light = texture(lighttex, lightCoord).rgb; // stock BTA: lightmap(block, sky)

    if (btsLightingOn == 1) {
        // Complementary-style split: ambient sky light + direct sun/moon light.
        //  - ambient: BTA's own lightmap (keeps its day/night curve, torch color, brightness setting,
        //    dimensions) with part of the sky light removed, tinted like the sky.
        //  - direct: sun/moon color * N.L * shadow, only where there's open sky (sky^8, no sun in caves).
        float sky = clamp((LightMapCoord.y * 16.0 - 0.5) / 15.0, 0.0, 1.0);
        // Crossed-quad plants (grass, flowers, saplings) are the only diagonal faces in the world.
        bool foliage = abs(geoNormal.x) > 0.3 && abs(geoNormal.z) > 0.3;

        float ambientSky = max(mix(0.5 / 16.0, LightMapCoord.y, AMBIENT_SKY_LIGHT), emissiveBrightness.y);
        vec3 ambient = texture(lighttex, vec2(lightCoord.x, ambientSky)).rgb;
        ambient *= mix(vec3(1.0), btsAmbientTint, sky);

        float nDotL = foliage ? 1.0 : max(dot(geoNormal, btsLightDir), 0.0);
        float sky2 = sky * sky;
        float skyDirect = sky2 * sky2 * sky2 * sky2; // sky^8
        float shadow = shadowAmount(geoNormal, foliage, sky);
        float direct = nDotL * (1.0 - shadow) * skyDirect;

        light = ambient + btsDirectColor * direct;
    }
    vec4 lightColor = mix(vec4(1, 1, 1, 1), vec4(light, 1.0), uLightMapStrength);

    vec4 texColor = texture(colortex, TexCoord);
    vec4 maskColor = texture(colormasktex, TexCoord);

    vec4 c = vec4(mix(vec3(1, 1, 1), Color.rgb, maskColor.r), 1) * uColor;

    float shadeEffect = 1 - max(emissiveBrightness.x, emissiveBrightness.y);
    float s = mix(1, Shade, shadeEffect);

    vec4 postColor = texColor * c * vec4(s, s, s, 1) * lightColor;
    vec4 litColor = vec4(postColor.rgb * clamp(Light, 0, 1), postColor.a);

    vec4 fColor = computeFog(litColor);

    FragColor = fColor;
    if (FragColor.a < uAlphaTest) {
        discard;
    }
}
