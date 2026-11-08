# BTA Shaders (v8.0.1)

Warm, sunny shaders for [Better than Adventure!](https://www.betterthanadventure.net/) 8.0.1, in the spirit
of Complementary Reimagined, built straight into the game as a Fabric mod (no OptiFine or Iris needed).

## Features

- **Sun and moon shadows**: soft, contact-hardening shadows from terrain, mobs, the player and block
  entities; cloud shadows; colored shadows through tinted glass and water; light shadows under ice
- **Light shafts**: volumetric light through trees, windows and tinted glass, under water too
- **Sky**: own sky colors for every time of day, volumetric blocky clouds, border fog at the view
  distance, BTA's stars and auroras on top
- **Water**: reflections, refraction, absorption, waves, caustics on the ground below
- **Rain**: surfaces get wet, puddles form on flat ground, raindrops ripple puddles and water
- **Materials**: highlights and reflections on metal, polished, glassy and wet blocks;
  waving plants and leaves; glowing blocks
- **labPBR resource packs**: normal maps, smoothness, metals, porosity and emission from `_n` / `_s`
  textures (nothing changes without such a pack)
- **Camera**: HDR lighting, eye adaptation, bloom, Lottes-style tone mapping, ambient occlusion,
  temporal anti-aliasing with motion vectors for moving mobs, sharpening
- **Settings menu** with Low / Medium / High / Ultra presets (Mod Menu or Shift+F7), GPU timings per
  render pass in F3

## Install

1. A Better than Adventure! 8.0.1 Fabric instance (e.g. Prism Launcher; Fabric loader
   `0.18.4-bta.11` or newer, Java 17+).
2. Download the jar from [Releases](https://github.com/mtureyen/bta-shaders-v8-0-1/releases) and put it
   into the instance's `mods` folder.
3. Optional: Mod Menu 5.0.2+ for a "Config" button.

## Keys

| Key | Action |
| --- | --- |
| Shift+F7 | Settings menu |
| F8 | Shaders on/off (stock BTA rendering) |
| F7 | Reload shaders and settings |
| F10 | Temporal anti-aliasing on/off |
| F3 | Debug screen, incl. GPU time per pass |
| F6 | G-buffer debug views (normals, materials, light, sun, smoothness, motion) |
| F9 | Shadow map overlay |

BTA binds F8 to "cinematic camera" by default; unbind it there if both trigger.

## Settings

Everything is in the settings menu. Presets only touch performance options; the look (sun brightness,
saturation, ripples, fog, ...) stays as you set it. Apply recompiles in about a second.

Settings live in `.minecraft/config/btashaders.properties` (only values that differ from the
defaults). Every tunable is a `#define` in
[`lib/settings.glsl`](src/main/resources/assets/btashaders/shaders/lib/settings.glsl) and can be
overridden there with `define.NAME=value`.

**Shader packs:** a folder in `.minecraft/shaderpacks/` containing any of the mod's shader files (same
paths as under `assets/btashaders/shaders/`) is used when `pack=<folder>` is set; missing files fall
back to the built-in ones.

## Performance

Roughly 6-10 ms of GPU time per frame at 1080p on the High preset with a modern GPU (F3 shows the
breakdown). The biggest costs are the world itself, the shadow map (Shadows page: resolution,
samples, contact hardening) and reflections; the Low preset turns off the expensive effects.

## Known limits

- Tuned for the overworld; the Nether and the Drift render correctly.
- Limb animation of mobs is smoothed by TAA rather than motion-tracked (whole-body movement is).
- If the game ever freezes with the shaders on, please open an issue with what you were doing;
  turning off "Colored Shadows" (Shadows page) is the first thing to try.

## How it works (short)

The mod replaces BTA's `ShadersRenderer` with its own pipeline: a shadow pass (opaque + translucent
casters), the world rendered into an HDR framebuffer with extra G-buffer targets (normals, materials,
light, motion), a prepare pass (ambient occlusion, reflections, sky, clouds), light shafts at half
resolution, bloom + eye adaptation + tone mapping, TAA, then BTA's own post shader and a final
composite. Material IDs are written into the terrain vertex data while chunks are built.

## Building

```sh
./gradlew build        # jar lands in build/libs/
./gradlew runClient    # launch a dev client with the mod
```

Requires a JDK; Gradle fetches the right toolchains (Java 21 for Gradle, Java 17 target)
automatically. In the dev client, F7 reloads the shaders straight from `src/main/resources`.

Decompiled game code is BTA's, not ours. Never commit it (`decompiled/` is gitignored).

## License and credits

GPL-3.0-only. Look and feel inspired by Complementary Reimagined (no code taken from it).
Project scaffold from [Turnip-Labs/bta-example-mod](https://github.com/Turnip-Labs/bta-example-mod) (CC0).
