package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.BtaShaders;
import io.github.mtureyen.btashaders.render.BtaShadersRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.Renderer;
import net.minecraft.client.render.shader.ShadersRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Swaps BTA's stock renderer for ours at startup. startGame() makes exactly one
 * setRenderer(new ShadersRenderer(this)) call; we replace that argument.
 */
@Mixin(value = Minecraft.class, remap = false)
public abstract class MinecraftMixin {
	@ModifyArg(
		method = "startGame",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setRenderer(Lnet/minecraft/client/render/Renderer;)V")
	)
	private Renderer btashaders$useOurRenderer(Renderer original) {
		if (original.getClass() == ShadersRenderer.class) {
			BtaShaders.LOGGER.info("Installing BTA Shaders renderer.");
			return new BtaShadersRenderer((Minecraft) (Object) this);
		}
		return original;
	}
}
