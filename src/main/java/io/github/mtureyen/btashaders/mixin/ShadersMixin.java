package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.BtaShaders;
import io.github.mtureyen.btashaders.render.BtaShadersRenderer;
import io.github.mtureyen.btashaders.render.LightingState;
import io.github.mtureyen.btashaders.render.MaterialContext;
import net.minecraft.client.render.renderer.Shaders;
import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.shader.ShaderProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

/**
 * Compiles our lit versions of BTA's world shaders (terrain, entity, world, item) instead of the stock
 * ones. If one of ours fails to compile, falls back to BTA's (asset-pack aware) source for that shader,
 * so the world always renders.
 */
@Mixin(value = Shaders.class, remap = false)
public abstract class ShadersMixin {
	private static final Set<String> OURS = Set.of("terrain", "entity", "world", "item");

	@Redirect(
		method = {"init", "reload"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/shader/Shader;compile(Lnet/minecraft/client/render/shader/ShaderProvider;Ljava/lang/String;)Lnet/minecraft/client/render/shader/Shader;")
	)
	private static Shader btashaders$compileOurs(Shader shader, ShaderProvider provider, String name) {
		if (!OURS.contains(name)) {
			return shader.compile(provider, name);
		}
		shader.compile(BtaShadersRenderer.MOD_SHADERS, name);
		boolean ours = shader.isEnabled();
		if (ours) {
			LightingState.initSamplers(shader);
			BtaShaders.LOGGER.info("Using BTA Shaders '{}' shader.", name);
		} else {
			BtaShaders.LOGGER.warn("BTA Shaders '{}' shader failed to compile; falling back to the stock one.", name);
			shader.compile(provider, name);
		}
		if ("terrain".equals(name)) {
			// Material IDs replace the vertex normals; only our terrain shader understands that.
			MaterialContext.enabled = ours;
		}
		return shader;
	}
}
