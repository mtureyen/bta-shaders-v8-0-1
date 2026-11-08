package io.github.mtureyen.btashaders.mixin;

import net.minecraft.client.render.RenderGlobal;
import net.minecraft.client.render.terrain.ChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = RenderGlobal.class, remap = false)
public interface RenderGlobalAccessor {
	@Accessor("chunkRenderers")
	ChunkRenderer[] btashaders$getChunkRenderers();
}
