#version 410 core
// Bloom upsample: 3x3 tent filter of the smaller level, added onto this level's downsample.
// Walking back up the chain accumulates a wide, smooth glow (linear HDR).

in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D btsSrc;      // smaller (already accumulated) level
uniform sampler2D btsBase;     // this level's downsample
uniform vec2 btsSrcTexel;      // 1 / smaller level size

void main() {
    vec2 t = btsSrcTexel;
    vec3 sum = texture(btsSrc, TexCoords).rgb * 4.0;
    sum += (texture(btsSrc, TexCoords + vec2(-t.x, 0.0)).rgb + texture(btsSrc, TexCoords + vec2(t.x, 0.0)).rgb
          + texture(btsSrc, TexCoords + vec2(0.0, -t.y)).rgb + texture(btsSrc, TexCoords + vec2(0.0, t.y)).rgb) * 2.0;
    sum += texture(btsSrc, TexCoords + vec2(-t.x, -t.y)).rgb + texture(btsSrc, TexCoords + vec2(t.x, -t.y)).rgb
         + texture(btsSrc, TexCoords + vec2(-t.x, t.y)).rgb + texture(btsSrc, TexCoords + vec2(t.x, t.y)).rgb;
    vec3 upsampled = sum / 16.0;
    FragColor = vec4(texture(btsBase, TexCoords).rgb + upsampled, 1.0);
}
