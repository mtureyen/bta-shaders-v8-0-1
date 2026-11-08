package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.render.MaterialContext;
import net.minecraft.client.render.block.model.BlockModel;
import net.minecraft.client.render.terrain.ChunkRendererMultiDraw;
import net.minecraft.core.world.WorldSource;
import net.minecraft.core.world.pos.TilePos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells {@link MaterialContext} which block the chunk mesher is currently drawing. */
@Mixin(value = ChunkRendererMultiDraw.class, remap = false)
public abstract class ChunkRendererMultiDrawMixin {
	@Inject(method = "renderBlock", at = @At("HEAD"))
	private void btashaders$beginBlock(WorldSource worldSource, BlockModel<?> model, TilePos tilePos, CallbackInfoReturnable<Boolean> cir) {
		MaterialContext.begin(model, tilePos);
	}

	@Inject(method = "renderBlock", at = @At("RETURN"))
	private void btashaders$endBlock(WorldSource worldSource, BlockModel<?> model, TilePos tilePos, CallbackInfoReturnable<Boolean> cir) {
		MaterialContext.end();
	}
}
