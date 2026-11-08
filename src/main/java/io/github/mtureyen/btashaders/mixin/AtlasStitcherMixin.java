package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.render.PbrAtlas;
import net.minecraft.client.render.texture.stitcher.AtlasStitcher;
import net.minecraft.client.render.texture.stitcher.IconCoordinate;
import net.minecraft.client.render.texture.stitcher.TextureRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.image.BufferedImage;

/** labPBR: normal + specular layers on the block atlas, loaded from {@code _n} / {@code _s} files (PbrAtlas). */
@Mixin(value = AtlasStitcher.class, remap = false)
public abstract class AtlasStitcherMixin {
	@Inject(method = "preinit", at = @At("HEAD"))
	private void btashaders$pbrLayers(CallbackInfo ci) {
		if ((Object) this == TextureRegistry.worldAtlas) {
			PbrAtlas.prepareLayers((AtlasStitcher) (Object) this);
		}
	}

	@Inject(method = "getImage", at = @At("HEAD"), cancellable = true)
	private void btashaders$pbrImage(String layer, IconCoordinate coordinate, CallbackInfoReturnable<BufferedImage> cir) {
		if (PbrAtlas.isOurLayer(layer)) {
			cir.setReturnValue(PbrAtlas.loadImage((AtlasStitcher) (Object) this, layer, coordinate));
		}
	}
}
