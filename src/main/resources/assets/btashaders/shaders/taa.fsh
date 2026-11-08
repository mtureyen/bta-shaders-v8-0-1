#version 410 core
// BTA Shaders: temporal anti-aliasing.
// The world projection is jittered by a sub-pixel Halton offset every frame. Here we reproject last
// frame's resolved image onto this frame using depth + camera matrices (no velocity buffer, like
// Complementary/Photon), clamp it to this frame's 3x3 neighbourhood to reject stale history, and blend.
// This smooths edges and averages away the noise of the soft shadow filter.

in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D colortex0;   // this frame's world color (jittered)
uniform sampler2D depthtex0;   // this frame's world depth
uniform sampler2D historytex;  // last frame's TAA output

uniform mat4 btsProjInv;       // inverse of this frame's (jittered) projection
uniform mat4 btsViewInv;       // inverse of this frame's view
uniform mat4 btsPrevViewProj;  // last frame's (un-jittered) projection * view
uniform vec3 btsCameraDelta;   // camera position now - camera position last frame
uniform vec2 btsViewSize;      // render size in pixels
uniform bool btsHistoryValid;

// Depth of the first-person hand is squeezed into [0, 0.05] by BTA's hand projection; it moves with
// the camera, so camera reprojection is wrong for it. Leave it alone.
#define HAND_DEPTH 0.07

// Bicubic (Catmull-Rom) history fetch using 5 bilinear taps. Plain bilinear reads soften the image
// a little every frame and the softness accumulates; Catmull-Rom keeps it sharp.
vec3 sampleHistory(vec2 uv) {
    vec2 texSize = btsViewSize;
    vec2 samplePos = uv * texSize;
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;

    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);

    vec2 w12 = w1 + w2;
    vec2 offset12 = w2 / w12;

    vec2 texPos0 = (texPos1 - 1.0) / texSize;
    vec2 texPos3 = (texPos1 + 2.0) / texSize;
    vec2 texPos12 = (texPos1 + offset12) / texSize;

    vec3 result = vec3(0.0);
    float weight = 0.0;
    result += texture(historytex, vec2(texPos12.x, texPos0.y)).rgb * (w12.x * w0.y);  weight += w12.x * w0.y;
    result += texture(historytex, vec2(texPos0.x, texPos12.y)).rgb * (w0.x * w12.y);  weight += w0.x * w12.y;
    result += texture(historytex, vec2(texPos12.x, texPos12.y)).rgb * (w12.x * w12.y); weight += w12.x * w12.y;
    result += texture(historytex, vec2(texPos3.x, texPos12.y)).rgb * (w3.x * w12.y);  weight += w3.x * w12.y;
    result += texture(historytex, vec2(texPos12.x, texPos3.y)).rgb * (w12.x * w3.y);  weight += w12.x * w3.y;
    return max(result / weight, vec3(0.0));
}

vec3 clipAABB(vec3 history, vec3 boxMin, vec3 boxMax) {
    vec3 center = 0.5 * (boxMax + boxMin);
    vec3 extent = 0.5 * (boxMax - boxMin) + 1e-6;
    vec3 v = history - center;
    vec3 unit = abs(v / extent);
    float m = max(unit.x, max(unit.y, unit.z));
    return m > 1.0 ? center + v / m : history;
}

void main() {
    vec3 current = texture(colortex0, TexCoords).rgb;
    float depth = texture(depthtex0, TexCoords).r;
    if (!btsHistoryValid || depth < HAND_DEPTH) {
        FragColor = vec4(current, 1.0);
        return;
    }

    // Neighbourhood min/max for history clamping.
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 maxPixel = ivec2(btsViewSize) - 1;
    vec3 boxMin = current;
    vec3 boxMax = current;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            if (x == 0 && y == 0) continue;
            vec3 c = texelFetch(colortex0, clamp(pixel + ivec2(x, y), ivec2(0), maxPixel), 0).rgb;
            boxMin = min(boxMin, c);
            boxMax = max(boxMax, c);
        }
    }

    // Reproject: screen -> camera-relative world (this frame) -> last frame's screen.
    vec4 viewPos = btsProjInv * vec4(TexCoords * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    viewPos /= viewPos.w;
    vec3 relPos = (btsViewInv * viewPos).xyz;
    vec4 prevClip = btsPrevViewProj * vec4(relPos + btsCameraDelta, 1.0);
    if (prevClip.w <= 0.0) {
        FragColor = vec4(current, 1.0);
        return;
    }
    vec2 prevUV = prevClip.xy / prevClip.w * 0.5 + 0.5;
    if (prevUV.x < 0.0 || prevUV.x > 1.0 || prevUV.y < 0.0 || prevUV.y > 1.0) {
        FragColor = vec4(current, 1.0);
        return;
    }

    vec3 history = clipAABB(sampleHistory(prevUV), boxMin, boxMax);

    // History weight: ~0.9 when still, less when moving fast (less ghosting/blur in motion).
    vec2 velocityPx = (prevUV - TexCoords) * btsViewSize;
    float blend = max(exp(-dot(velocityPx, velocityPx) * 0.1) * 0.2 + 0.7, 0.4);

    FragColor = vec4(mix(current, history, blend), 1.0);
}
