package io.github.mtureyen.btashaders.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.mtureyen.btashaders.render.BtaShadersRenderer;
import net.minecraft.client.gui.hud.HudIngame;
import net.minecraft.client.render.tessellator.TessellatorGeneral;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** F3 overlay: our per-pass GPU timings below BTA's chunk provider line (left column). */
@Mixin(value = HudIngame.class, remap = false)
public abstract class HudIngameMixin {
	@Unique
	private String btashaders$anchorLine = null;

	@ModifyExpressionValue(method = "renderGameOverlay", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/Minecraft;getChunkProviderInfoString()Ljava/lang/String;"))
	private String btashaders$rememberAnchor(String line) {
		this.btashaders$anchorLine = line;
		return line;
	}

	@WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/hud/HudIngame;drawDebugScreenLine(Lnet/minecraft/client/render/tessellator/TessellatorGeneral;Ljava/lang/CharSequence;)V"))
	private void btashaders$appendTimings(HudIngame hud, TessellatorGeneral tessellator, CharSequence line,
										  Operation<Void> original) {
		original.call(hud, tessellator, line);
		if (line != null && line == this.btashaders$anchorLine) {
			this.btashaders$anchorLine = null;
			BtaShadersRenderer renderer = BtaShadersRenderer.active();
			if (renderer != null) {
				for (String extra : renderer.debugLines()) {
					original.call(hud, tessellator, extra);
				}
			}
		}
	}
}
