package io.github.mtureyen.btashaders.render;

import io.github.mtureyen.btashaders.BtaShaders;
import io.github.mtureyen.btashaders.config.ShaderSettingsScreen;
import io.github.mtureyen.btashaders.render.pass.CompositePass;
import io.github.mtureyen.btashaders.render.pass.PreparePass;
import io.github.mtureyen.btashaders.render.pass.TaaPass;
import io.github.mtureyen.btashaders.render.pass.TonemapPass;
import io.github.mtureyen.btashaders.render.pass.WaterPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScreenMainMenu;
import net.minecraft.client.option.GameSettings;
import net.minecraft.client.render.LightmapHelper;
import net.minecraft.client.render.OpenGLHelper;
import net.minecraft.client.render.camera.CameraUtil;
import net.minecraft.client.render.renderer.GLRenderer;
import net.minecraft.client.render.renderer.Shaders;
import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.shader.ShaderProvider;
import net.minecraft.client.render.shader.ShadersRenderer;
import net.minecraft.client.render.shader.framebuffer.FrameBuffer;
import net.minecraft.client.render.shader.framebuffer.FrameBufferAttachment;
import net.minecraft.client.render.shader.framebuffer.FrameBufferSingleSample;
import net.minecraft.client.render.shader.framebuffer.FrameBufferTexture;
import net.minecraft.client.render.shader.framebuffer.FrameBufferTextureSingleSample;
import net.minecraft.client.render.window.GameWindowGLFW;
import net.minecraft.client.render.worldtype.WorldTypeFXDispatcher;
import net.minecraft.core.block.material.Materials;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL41;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Replaces BTA's stock {@link ShadersRenderer} and orchestrates the frame; the passes themselves live
 * in {@code render.pass}.
 *
 * <p>Per frame:
 * <pre>
 *   beginRenderWorld   shadow pass (ShadowRenderer) -> shadow map; lighting uniforms to all lit shaders
 *   world pass         BTA draws into an HDR (RGBA16F) world buffer with a jittered projection; our
 *                      terrain/entity/world/item shaders light it and write the G-buffer; WaterPass
 *                      copies the opaque scene before translucents for water refraction/reflection
 *   endRenderWorld     PreparePass (SSAO, reflections, sky, clouds) -> TonemapPass (bloom, eye
 *                      adaptation, atmosphere, tone curve) -> TaaPass -> CompositePass (BTA post +
 *                      our grading/sharpening) -> game framebuffer
 * </pre>
 * Keys: F6 G-buffer views, F7 reload (Shift+F7 settings), F8 on/off (stock BTA), F9 shadow map,
 * F10 TAA. Shaders that fail to compile fall back to the stock path; never a black screen.
 */
public class BtaShadersRenderer extends ShadersRenderer {
	public static final ShaderProvider MOD_SHADERS = new ModShaderProvider("/assets/btashaders/shaders/");

	private static final int GL_DEPTH_COMPONENT = 6402;
	private static final int GL_DEPTH_ATTACHMENT = 36096;
	private static final int GL_LINEAR = 9729;
	private static final int GL_NEAREST = 9728;
	private static final int GL_TEXTURE_MIN_FILTER = 10241;
	private static final int GL_TEXTURE_MAG_FILTER = 10240;
	private static final int GL_TEXTURE_WRAP_S = 10242;
	private static final int GL_TEXTURE_WRAP_T = 10243;
	private static final int GL_CLAMP_TO_EDGE = 33071;

	private static final int KEY_RELOAD = GLFW.GLFW_KEY_F7;
	private static final int KEY_TOGGLE = GLFW.GLFW_KEY_F8;
	private static final int KEY_DEBUG_SHADOW = GLFW.GLFW_KEY_F9;
	private static final int KEY_TOGGLE_TAA = GLFW.GLFW_KEY_F10;
	private static final int KEY_DEBUG_VIEW = GLFW.GLFW_KEY_F6;
	private static final String[] DEBUG_VIEWS = {"off", "normals", "materials", "light levels", "sun visibility", "smoothness", "motion"};

	/** Dev runs: -Dbtashaders.openSettings=true shows the settings screen over the main menu once. */
	private static final boolean OPEN_SETTINGS_ON_START = Boolean.getBoolean("btashaders.openSettings");

	@Nullable
	private static BtaShadersRenderer instance = null;

	private final ShadowRenderer shadows;
	private final LightingState lighting;
	private final GBuffer gbuffer = new GBuffer();
	private final FrameState frame = new FrameState();
	private final GpuProfiler profiler = new GpuProfiler();

	private final WaterPass water = new WaterPass();
	private final PreparePass prepare = new PreparePass();
	private final TonemapPass tonemap = new TonemapPass();
	private final TaaPass taa = new TaaPass();
	private final CompositePass composite = new CompositePass();

	private boolean jitterApplied = false;
	private boolean enabled = true;
	private boolean taaEnabled = true;
	private boolean debugShadowMap = false;
	/** F6 debug view, index into DEBUG_VIEWS. */
	private int debugView = 0;
	private final boolean[] keyDown = new boolean[5];
	private boolean settingsOpenedOnStart = false;

	public BtaShadersRenderer(Minecraft minecraft) {
		super(minecraft);
		this.shadows = new ShadowRenderer(minecraft);
		this.lighting = new LightingState(this.shadows);
		instance = this;
	}

	/** The renderer if it is currently the active one (photo mode temporarily swaps in its own). */
	@Nullable
	public static BtaShadersRenderer active() {
		BtaShadersRenderer r = instance;
		return r != null && r.mc.renderer == r ? r : null;
	}

	/** Around BTA's rain/snow draw (world shader, see world.fsh). glProgramUniform: BTA's bound program stays. */
	public static void setPrecipitation(boolean on) {
		Shader shader = Shaders.WORLD;
		if (active() == null || !shader.isEnabled()) {
			return;
		}
		int location = shader.getUniform("btsPrecipitation");
		if (location >= 0) {
			GL41.glProgramUniform1i(shader.id(), location, on ? 1 : 0);
		}
	}

	public ShadowRenderer getShadows() {
		return this.shadows;
	}

	public LightingState getLighting() {
		return this.lighting;
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	/** Lines for the F3 overlay. */
	public List<String> debugLines() {
		return this.enabled ? this.profiler.lines() : List.of("BTA Shaders off (F8)");
	}

	private boolean taaActive() {
		return this.enabled && this.taaEnabled && this.taa.isEnabled() && this.composite.isEnabled();
	}

	// ---------------------------------------------------------------------------------------------
	// HDR world framebuffer

	@Override
	public void setupFramebuffer() {
		FrameBuffer before = this.worldFrameBuffer;
		super.setupFramebuffer();
		if (this.worldFrameBuffer != null && this.worldFrameBuffer != before) {
			// Same as BTA's, but with an RGBA16F color attachment.
			this.worldFrameBuffer.delete();
			int filter = GameSettings.RENDER_SCALE.value.useLinearFiltering ? GL_LINEAR : GL_NEAREST;
			Consumer<FrameBufferTexture.Parameters> params = p -> {
				p.set(GL_TEXTURE_MIN_FILTER, filter);
				p.set(GL_TEXTURE_MAG_FILTER, filter);
				p.set(GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
				p.set(GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			};
			Vector2ic renderSize = new Vector2i(this.renderWidth, this.renderHeight);
			this.worldFrameBuffer = new FrameBufferSingleSample(
				new HdrColorTexture(renderSize, params),
				new FrameBufferTextureSingleSample(GL_DEPTH_COMPONENT, GL_DEPTH_ATTACHMENT, renderSize, params));
			this.gbuffer.invalidate();
			this.taa.invalidate();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Hooks called from mixins

	/** WorldRenderer.getProjectionMatrix RETURN: remember the clean matrix, then apply the TAA jitter. */
	public void onWorldProjection(Matrix4f matrix) {
		this.frame.projection.set(matrix);
		this.jitterApplied = false;
		if (!this.taaActive() || this.renderWidth <= 0 || this.renderHeight <= 0) {
			return;
		}
		TaaPass.jitter(matrix, this.frame.frameIndex, this.renderWidth, this.renderHeight);
		this.jitterApplied = true;
	}

	/** Start of the main solid terrain draw: world matrices are final, sky is done, lighting goes on. */
	public void captureWorldMatrices(float partialTicks) {
		FrameState f = this.frame;
		f.projectionJittered.set(GLRenderer.projectionM4f());
		f.view.set(GLRenderer.viewM4f());
		if (this.mc.activeCamera != null) {
			this.mc.activeCamera.getPosition(partialTicks, f.cameraPos);
		}
		if (!this.jitterApplied) {
			f.projection.set(f.projectionJittered);
		}
		f.matricesCaptured = true;
		if (this.enabled) {
			this.gbuffer.setMrt(true); // solid terrain writes the G-buffer
			this.lighting.enterWorldGeometry();
		}
	}

	/** End of the solid terrain draw / start + end of entities: G-buffer writes only around our draws. */
	public void endSolidTerrain() {
		this.gbuffer.setMrt(false);
	}

	public void beginEntities() {
		if (this.enabled && this.lighting.getMode() == LightingState.WORLD) {
			this.gbuffer.setMrt(true);
		}
	}

	public void endEntities() {
		this.gbuffer.setMrt(false);
	}

	/** Before translucent terrain: copy the opaque scene for water, enable the water branch. */
	public void beforeTranslucentTerrain() {
		if (this.enabled && this.lighting.getMode() == LightingState.WORLD) {
			this.water.begin(this.worldFrameBuffer, this.renderWidth, this.renderHeight, this.gbuffer);
		}
	}

	public void afterTranslucentTerrain() {
		this.water.end(this.gbuffer);
	}

	/** RenderGlobal.renderSky HEAD/RETURN: our color_world shader draws BTA's sky dome transparent. */
	public void setSkyMode(boolean inSky) {
		Shader shader = Shaders.COLOR_WORLD;
		if (!shader.isEnabled()) {
			return;
		}
		int program = GL41.glGetInteger(GL41.GL_CURRENT_PROGRAM);
		shader.bind();
		shader.uniformInt("btsSkyMode", inSky && this.frame.ownSky ? 1 : 0);
		GL41.glUseProgram(program);
	}

	public boolean isOwnSkyActive() {
		return this.frame.ownSky;
	}

	public boolean isOwnCloudsActive() {
		return this.frame.ownSky;
	}

	// ---------------------------------------------------------------------------------------------
	// Frame

	@Override
	public void beginRenderGame(float partialTicks) {
		this.pollKeys();
		super.beginRenderGame(partialTicks);
	}

	@Override
	public void beginRenderWorld(float partialTicks) {
		FrameState f = this.frame;
		f.matricesCaptured = false;
		f.setSize(this.renderWidth, this.renderHeight);
		EntityMotion.beginFrame(f.frameIndex, this.enabled && this.taaActive() && this.gbuffer.isValid());
		if (this.enabled) {
			this.profiler.begin(GpuProfiler.Section.SHADOW);
			this.shadows.render(partialTicks);
			this.profiler.end(GpuProfiler.Section.SHADOW);
		}
		f.ownSky = this.enabled && this.prepare.isEnabled() && this.tonemap.isEnabled()
			&& Shaders.COLOR_WORLD.isEnabled() && this.mc.currentWorld != null
			&& WorldTypeFXDispatcher.getInstance().getDispatch(this.mc.currentWorld.getWorldType()).hasSky();
		this.lighting.beginWorld(this.enabled, this.isEyeInWater(partialTicks), f.ownSky);
		if (this.enabled) {
			// Cloud layer state for cloud shadows in every lit shader.
			for (Shader shader : LightingState.litShaders()) {
				if (shader.isEnabled()) {
					shader.bind();
					SceneUniforms.clouds(shader, this.mc, this.shadows, f.ownSky);
				}
			}
			GL41.glUseProgram(0);
			this.profiler.begin(GpuProfiler.Section.WORLD);
		}
		super.beginRenderWorld(partialTicks);
		if (f.ownSky) {
			// Sky pixels must start black: only BTA's sun/moon/stars land there; our sky goes behind
			// them in the prepare pass. (BTA clears to its fog color right after this.)
			GL41.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
		}
		if (this.enabled && this.worldFrameBuffer != null) {
			this.gbuffer.attach(this.worldFrameBuffer.getId(), this.renderWidth, this.renderHeight);
			this.gbuffer.clear();
		}
	}

	private boolean isEyeInWater(float partialTicks) {
		return this.mc.activeCamera != null && this.mc.currentWorld != null
			&& CameraUtil.isUnderLiquid(this.mc.activeCamera, this.mc.currentWorld, Materials.WATER, partialTicks);
	}

	@Override
	public void endRenderWorld(float partialTicks) {
		this.gbuffer.setMrt(false);
		this.lighting.endWorld();
		this.afterTranslucentTerrain();
		this.profiler.end(GpuProfiler.Section.WORLD);
		if (!this.enabled || !this.composite.isEnabled()) {
			this.taa.invalidate();
			super.endRenderWorld(partialTicks);
			this.profiler.endFrame();
			return;
		}

		OpenGLHelper.checkError("pre btashaders end render world");
		FrameState f = this.frame;
		f.setSize(this.renderWidth, this.renderHeight);
		FrameBuffer worldFb = Objects.requireNonNull(this.worldFrameBuffer);
		FrameBuffer gameFb = Objects.requireNonNull(this.gameFrameBuffer);

		worldFb.blit();
		FrameBuffer worldOut = worldFb.getOutputBuffer();
		FrameBufferAttachment worldColor = Objects.requireNonNull(worldOut.getAttachment(FrameBuffer.AttachmentType.COLOR));
		FrameBufferAttachment worldDepth = Objects.requireNonNull(worldOut.getAttachment(FrameBuffer.AttachmentType.DEPTH));

		// Prepare (SSAO, reflections, sky, clouds; HDR). Falls back to the raw world color.
		int scene = -1;
		ColorTarget prepared = null;
		if (this.prepare.isEnabled() && f.matricesCaptured) {
			this.profiler.begin(GpuProfiler.Section.PREPARE);
			scene = this.prepare.run(this.mc, f, this.shadows, this.gbuffer, worldColor, worldDepth);
			prepared = this.prepare.target();
			this.profiler.end(GpuProfiler.Section.PREPARE);
		}

		// Bloom + eye adaptation + tone map (HDR -> LDR).
		boolean tonemapped = false;
		if (this.tonemap.isEnabled()) {
			scene = this.tonemap.run(this.mc, f, this.shadows, this.profiler, worldColor, worldDepth, scene, prepared);
			tonemapped = true;
		}

		// TAA; its output is also next frame's history.
		if (this.taaActive() && f.matricesCaptured) {
			this.profiler.begin(GpuProfiler.Section.TAA);
			scene = this.taa.run(f, worldColor, worldDepth, scene, this.gbuffer);
			this.profiler.end(GpuProfiler.Section.TAA);
		} else {
			this.taa.invalidate();
		}

		// BTA post + our composite -> game framebuffer (default framebuffer when the final shader is off).
		this.profiler.begin(GpuProfiler.Section.POST);
		boolean noFxaa = this.taaActive() && this.taa.isHistoryValid();
		this.composite.run(this.mc, partialTicks, this.postShader, gameFb, this.finalShader.isEnabled(),
			this.fbWidth, this.fbHeight, worldColor, worldDepth, scene, noFxaa, tonemapped, this.shadows,
			this.gbuffer, new CompositePass.Debug(this.debugShadowMap, this.debugView));
		this.profiler.end(GpuProfiler.Section.POST);

		LightingState.clearUnits();
		LightmapHelper.instance.enableLightmapRendering();
		GL41.glUseProgram(0);
		OpenGLHelper.checkError("btashaders end render world");
		f.advance();
		this.profiler.endFrame();
	}

	// ---------------------------------------------------------------------------------------------
	// Keys

	private void pollKeys() {
		if (OPEN_SETTINGS_ON_START && !this.settingsOpenedOnStart && this.mc.currentScreen instanceof ScreenMainMenu) {
			this.settingsOpenedOnStart = true;
			this.mc.displayScreen(new ShaderSettingsScreen(this.mc.currentScreen));
		}
		if (!(this.mc.gameWindow instanceof GameWindowGLFW window)) {
			return;
		}
		long handle = window.getHandle();
		boolean inWorld = this.mc.currentScreen == null;

		if (this.pressed(handle, KEY_TOGGLE, 0) && inWorld) {
			this.enabled = !this.enabled;
			this.tonemap.invalidate();
			this.taa.invalidate();
			BtaShaders.LOGGER.info("BTA Shaders {}", this.enabled ? "enabled" : "disabled (stock BTA rendering)");
		}
		if (this.pressed(handle, KEY_DEBUG_SHADOW, 1) && inWorld) {
			this.debugShadowMap = !this.debugShadowMap;
			BtaShaders.LOGGER.info("Shadow map overlay {}", this.debugShadowMap ? "on" : "off");
		}
		if (this.pressed(handle, KEY_TOGGLE_TAA, 2) && inWorld) {
			this.taaEnabled = !this.taaEnabled;
			this.taa.invalidate();
			BtaShaders.LOGGER.info("TAA {}", this.taaEnabled ? "on" : "off");
		}
		if (this.pressed(handle, KEY_DEBUG_VIEW, 4) && inWorld) {
			this.debugView = (this.debugView + 1) % DEBUG_VIEWS.length;
			BtaShaders.LOGGER.info("G-buffer debug view: {}", DEBUG_VIEWS[this.debugView]);
		}
		if (this.pressed(handle, KEY_RELOAD, 3) && inWorld) {
			boolean shift = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
				|| GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
			if (shift) {
				this.mc.displayScreen(new ShaderSettingsScreen(null));
			} else {
				BtaShaders.LOGGER.info("Reloading BTA Shaders...");
				this.applySettings();
			}
		}
	}

	private boolean pressed(long handle, int key, int slot) {
		boolean down = GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
		boolean edge = down && !this.keyDown[slot];
		this.keyDown[slot] = down;
		return edge;
	}

	// ---------------------------------------------------------------------------------------------
	// Lifecycle

	/** Re-reads the config and recompiles everything (ours + BTA's world shaders). */
	public void applySettings() {
		this.reload();
		Shaders.reload(); // recompiles the world shaders, incl. ours
		// labPBR layers live in the block atlas: rebuild it when the setting flipped.
		boolean pbrLoaded = PbrAtlas.texture(PbrAtlas.NORMAL_LAYER) != 0;
		if (pbrLoaded != PbrAtlas.wanted() && this.mc.textureManager != null) {
			this.mc.textureManager.refreshTexturesAndDisplayErrors();
		}
	}

	@Override
	public void reload() {
		ShaderConfig.load();
		this.taaEnabled = ShaderConfig.taa();
		super.reload();
		this.composite.reload();
		this.taa.reload();
		this.prepare.reload();
		this.water.reload();
		this.tonemap.reload();
		this.shadows.reload(MOD_SHADERS);
		OpenGLHelper.checkError("btashaders reload");
	}

	@Override
	public void delete() {
		super.delete();
		this.composite.delete();
		this.taa.delete();
		this.prepare.delete();
		this.tonemap.delete();
		this.water.delete();
		this.gbuffer.delete();
		this.profiler.delete();
		this.shadows.delete();
	}
}
