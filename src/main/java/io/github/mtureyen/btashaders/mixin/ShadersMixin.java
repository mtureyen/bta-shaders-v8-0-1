package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.BtaShaders;
import io.github.mtureyen.btashaders.render.BtaShadersRenderer;
import io.github.mtureyen.btashaders.render.ShadowRenderer;
import org.lwjgl.opengl.GL41;
import net.minecraft.client.render.renderer.Shaders;
import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.shader.ShaderProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Compiles our shadow-receiving terrain shader instead of BTA's. If ours fails to compile,
 * falls back to BTA's (asset-pack aware) source so the world still renders.
 */
@Mixin(value = Shaders.class, remap = false)
public abstract class ShadersMixin {
	private static final String TERRAIN = "terrain";

	@Redirect(
		method = {"init", "reload"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/shader/Shader;compile(Lnet/minecraft/client/render/shader/ShaderProvider;Ljava/lang/String;)Lnet/minecraft/client/render/shader/Shader;")
	)
	private static Shader btashaders$compileTerrain(Shader shader, ShaderProvider provider, String name) {
		if (!TERRAIN.equals(name)) {
			return shader.compile(provider, name);
		}
		shader.compile(BtaShadersRenderer.MOD_SHADERS, name);
		if (shader.isEnabled()) {
			// The shadow sampler is a sampler2DShadow; GL forbids it sharing a texture unit with the
			// sampler2Ds (default unit 0 = colortex), so point it at its own unit right away.
			shader.bind();
			shader.uniformInt("btsShadowTex", ShadowRenderer.SHADOW_TEXTURE_UNIT);
			shader.uniformInt("btsShadowsOn", 0);
			GL41.glUseProgram(0);
			BtaShaders.LOGGER.info("Using BTA Shaders terrain shader (shadow receiving).");
			return shader;
		}
		BtaShaders.LOGGER.warn("BTA Shaders terrain shader failed to compile; falling back to the stock one.");
		return shader.compile(provider, name);
	}
}
