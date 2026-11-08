package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.render.BtaShadersRenderer;
import net.minecraft.client.render.RenderGlobal;
import net.minecraft.client.render.camera.ICamera;
import net.minecraft.client.render.worldtype.WorldTypeFX;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With our own sky active: BTA's sky dome draws transparent (our color_world shader, sky mode), the
 * orange "sunrise fan" is skipped (our sky has its own sunset glow) and BTA's flat clouds are replaced
 * by ours. Sun, moon, stars, rainbow and aurora are still drawn by BTA, on top of our sky.
 */
@Mixin(value = RenderGlobal.class, remap = false)
public abstract class RenderGlobalSkyMixin {
	@Inject(method = "renderSky", at = @At("HEAD"))
	private void btashaders$beginSky(float partialTick, CallbackInfo ci) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null) {
			renderer.setSkyMode(true);
		}
	}

	@Inject(method = "renderSky", at = @At("RETURN"))
	private void btashaders$endSky(float partialTick, CallbackInfo ci) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null) {
			renderer.setSkyMode(false);
		}
	}

	@Redirect(
		method = "renderSky",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/worldtype/WorldTypeFX;getSunriseColor(FF)[F")
	)
	private float[] btashaders$skipSunriseFan(WorldTypeFX fx, float timeOfDay, float partialTick) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null && renderer.isOwnSkyActive()) {
			return null;
		}
		return fx.getSunriseColor(timeOfDay, partialTick);
	}

	/** Entities, block entities and dropped items write the G-buffer too. */
	@Inject(method = "renderEntities", at = @At("HEAD"))
	private void btashaders$beginEntities(ICamera camera, float partialTick, CallbackInfo ci) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null) {
			renderer.beginEntities();
		}
	}

	@Inject(method = "renderEntities", at = @At("RETURN"))
	private void btashaders$endEntities(ICamera camera, float partialTick, CallbackInfo ci) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null) {
			renderer.endEntities();
		}
	}

	@Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
	private void btashaders$replaceClouds(float partialTick, CallbackInfo ci) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null && renderer.isOwnCloudsActive()) {
			ci.cancel();
		}
	}
}
