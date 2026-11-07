# BTA Shaders (v8.0.1)

A shader pipeline mod for [Better than Adventure!](https://www.betterthanadventure.net/) 8.0.1 (Fabric / Babric).

BTA 8 already lets asset packs replace its built-in shaders. This mod extends the renderer with what packs can't do on their own:

- [ ] Shadow pass (terrain depth from the sun's view)
- [ ] G-buffers (normals, material IDs) written by terrain shaders
- [ ] Chain of composite / post-processing passes
- [ ] Extra uniforms (sun direction, camera position, matrices, rain, time)
- [ ] Shaderpack folder + in-game selection menu

## Building

```sh
./gradlew build        # jar lands in build/libs/
./gradlew runClient    # launch a dev client with the mod
```

Requires a JDK; Gradle fetches the right toolchains (Java 21 for Gradle, Java 17 target) automatically.

## Reading the game code

`./gradlew genSources` decompiles BTA into Loom's cache for IDE navigation.
Decompiled game code is BTA's, not ours. Never commit it (`decompiled/` is gitignored).

## Credits

Project scaffold from [Turnip-Labs/bta-example-mod](https://github.com/Turnip-Labs/bta-example-mod) (CC0).
