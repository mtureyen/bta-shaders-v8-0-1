package io.github.mtureyen.btashaders.render;

import io.github.mtureyen.btashaders.BtaShaders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.texture.Texture;
import net.minecraft.client.render.texture.stitcher.AtlasStitcher;
import net.minecraft.client.render.texture.stitcher.IconCoordinate;
import net.minecraft.client.render.texture.stitcher.TextureRegistry;
import net.minecraft.client.render.texturepack.TexturePack;
import net.minecraft.client.util.helper.Textures;
import org.lwjgl.opengl.GL41;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * labPBR resource pack support: two extra layers on BTA's block atlas, stitched exactly like the color
 * layer (BTA's AtlasStitcher has layers for its own emissive / color-mask maps), filled from the
 * {@code <texture>_n.png} (normal + AO + height) and {@code <texture>_s.png} (smoothness, F0 / metal,
 * porosity, emission) files of the active resource packs.
 *
 * <p>Textures without PBR files get neutral defaults: a flat normal, and an all-zero specular texel
 * that the shader reads as "no data" (our own per-material values stay in charge there).
 */
public final class PbrAtlas {
	public static final String NORMAL_LAYER = "btsNormal";
	public static final String SPECULAR_LAYER = "btsSpecular";
	/** Flat normal (128, 128, 255), full AO, height 1 (ARGB). */
	private static final int DEFAULT_NORMAL = 0xFF8080FF;
	private static final int DEFAULT_SPECULAR = 0x00000000;

	private PbrAtlas() {
	}

	/** Setting LABPBR (on unless set to 0). Reads the config: the atlas is built before our renderer loads. */
	public static boolean wanted() {
		ShaderConfig.load();
		String setting = ShaderConfig.defines().get("LABPBR");
		return setting == null || !setting.trim().equals("0");
	}

	/**
	 * Before the world atlas is prepared (preinit: BTA creates its animated textures right after that,
	 * sized to the layer count, so the layers must exist by then): add or remove ours to match the setting.
	 */
	public static void prepareLayers(AtlasStitcher atlas) {
		boolean has = atlas.layerPostfixMap.containsKey(NORMAL_LAYER);
		boolean want = wanted();
		if (want && !has) {
			atlas.addLayer(NORMAL_LAYER, "_n", DEFAULT_NORMAL);
			atlas.addLayer(SPECULAR_LAYER, "_s", DEFAULT_SPECULAR);
		} else if (!want && has) {
			removeLayer(atlas, SPECULAR_LAYER);
			removeLayer(atlas, NORMAL_LAYER);
		}
	}

	/** Our layers were added last, so dropping them keeps every other layer's id. */
	private static void removeLayer(AtlasStitcher atlas, String layer) {
		Texture texture = atlas.layerTextureMap.remove(layer);
		if (texture != null && texture.isGenerated()) {
			GL41.glDeleteTextures(texture.id());
		}
		atlas.layerPostfixMap.remove(layer);
		atlas.layerImageMap.remove(layer);
		atlas.layerDefaultColorMap.removeInt(layer);
		atlas.layerToIdMap.removeInt(layer);
		atlas.idToLayerMap.remove(layer);
		atlas.layerCount--;
	}

	public static boolean isOurLayer(String layer) {
		return NORMAL_LAYER.equals(layer) || SPECULAR_LAYER.equals(layer);
	}

	/**
	 * Image of one atlas tile for our layers: {@code <source>_n.png} / {@code _s.png} from the highest
	 * priority pack that has it, scaled to the color texture's size if it differs; else the default.
	 */
	public static BufferedImage loadImage(AtlasStitcher atlas, String layer, IconCoordinate coordinate) {
		String source = atlas.getSourceImageId(coordinate.namespaceId);
		String path = source + (NORMAL_LAYER.equals(layer) ? "_n.png" : "_s.png");
		int width = Math.max(coordinate.sourceImageWidth, 1);
		int height = Math.max(coordinate.sourceImageHeight, 1);
		BufferedImage image = null;
		Minecraft mc = Minecraft.getMinecraft();
		TexturePack pack = mc.texturePackList.getHighestPriorityTexturePackWithFile(path);
		if (pack != null) {
			try (InputStream stream = pack.getResourceAsStream(path)) {
				if (stream != null) {
					image = Textures.readImageUnhandled(stream);
				}
			} catch (Exception e) {
				BtaShaders.LOGGER.warn("Failed to read PBR texture {}", path, e);
			}
		}
		if (image == null || image == Textures.missingTexture) {
			return filled(width, height, NORMAL_LAYER.equals(layer) ? DEFAULT_NORMAL : DEFAULT_SPECULAR);
		}
		if (image.getWidth() == width && image.getHeight() == height) {
			return image;
		}
		// Different resolution: fit it to the color texture (whole image: animated strips must keep all
		// their frames, BTA reads every frame of every layer).
		BufferedImage fitted = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = fitted.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(image, 0, 0, width, height, null);
		g.dispose();
		return fitted;
	}

	private static BufferedImage filled(int width, int height, int argb) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		int[] row = new int[width];
		java.util.Arrays.fill(row, argb);
		for (int y = 0; y < height; y++) {
			image.setRGB(0, y, width, 1, row, 0, width);
		}
		return image;
	}

	/** GL texture of a layer of the world atlas, 0 if it doesn't exist (yet). */
	public static int texture(String layer) {
		AtlasStitcher atlas = TextureRegistry.worldAtlas;
		if (atlas == null) {
			return 0;
		}
		Texture texture = atlas.layerTextureMap.get(layer);
		return texture != null && texture.isGenerated() ? texture.id() : 0;
	}
}
