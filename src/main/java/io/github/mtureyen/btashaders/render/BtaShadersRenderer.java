package io.github.mtureyen.btashaders.render;

import io.github.mtureyen.btashaders.BtaShaders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.option.GameSettings;
import net.minecraft.client.render.LightmapHelper;
import net.minecraft.client.render.OpenGLHelper;
import net.minecraft.client.render.camera.CameraUtil;
import net.minecraft.client.render.renderer.GLRenderer;
import net.minecraft.client.render.renderer.Shaders;
import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.shader.ShaderHelper;
import net.minecraft.client.render.shader.ShaderProvider;
import net.minecraft.client.render.shader.ShadersRenderer;
import net.minecraft.client.render.shader.framebuffer.FrameBuffer;
import net.minecraft.client.render.shader.framebuffer.FrameBufferAttachment;
import net.minecraft.client.render.shader.framebuffer.FrameBufferSingleSample;
import net.minecraft.client.render.shader.framebuffer.FrameBufferTexture;
import net.minecraft.client.render.shader.framebuffer.FrameBufferTextureSingleSample;
import net.minecraft.client.render.window.GameWindowGLFW;
import net.minecraft.core.block.material.Materials;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL41;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Replaces BTA's stock {@link ShadersRenderer}.
 *
 * <p>Per frame:
 * <pre>
 *   beginRenderWorld   shadow pass -> shadow map; light/shadow uniforms uploaded to all lit shaders
 *   world pass         BTA draws into an HDR (RGBA16F) world buffer with a jittered projection; our
 *                      terrain/entity/world/item shaders do sun+sky lighting, shadows, waving foliage;
 *                      before translucents the opaque scene is copied so water can refract/reflect it
 *   endRenderWorld     prepare (SSAO + tone map) -> TAA -> BTA post (bloom, FXAA, color correction)
 *                      -> composite (sharpen, sky glow, aerial fog, god rays, grading) -> screen
 * </pre>
 * Keys: F7 reload shaders + config, F8 everything on/off (stock BTA), F9 shadow map, F10 TAA.
 * Shaders that fail to compile fall back to the stock path; never a black screen.
 */
public class BtaShadersRenderer extends ShadersRenderer {
	public static final ShaderProvider MOD_SHADERS = new ModShaderProvider("/assets/btashaders/shaders/");

	private static final int GL_RGBA = 6408;
	private static final int GL_COLOR_ATTACHMENT0 = 36064;
	private static final int GL_DEPTH_COMPONENT = 6402;
	private static final int GL_DEPTH_ATTACHMENT = 36096;
	private static final int GL_LINEAR = 9729;
	private static final int GL_NEAREST = 9728;
	private static final int GL_TEXTURE_MIN_FILTER = 10241;
	private static final int GL_TEXTURE_MAG_FILTER = 10240;
	private static final int GL_TEXTURE_WRAP_S = 10242;
	private static final int GL_TEXTURE_WRAP_T = 10243;
	private static final int GL_CLAMP_TO_EDGE = 33071;
	private static final int GL_TEXTURE_2D = 3553;
	private static final int GL_TEXTURE0 = 33984;

	private static final int KEY_RELOAD = GLFW.GLFW_KEY_F7;
	private static final int KEY_TOGGLE = GLFW.GLFW_KEY_F8;
	private static final int KEY_DEBUG_SHADOW = GLFW.GLFW_KEY_F9;
	private static final int KEY_TOGGLE_TAA = GLFW.GLFW_KEY_F10;

	/** Halton(2,3) sub-pixel offsets in [-0.5, 0.5] pixels. */
	private static final float[][] JITTER = buildHalton(8);
	/**
	 * Fraction of a pixel the projection is jittered by. Full-pixel jitter gives the smoothest edges but
	 * visibly softens the image once frames are averaged; half is the usual sharpness compromise.
	 */
	private static final float JITTER_SCALE = 0.5F;

	@Nullable
	private static BtaShadersRenderer instance = null;

	private final Shader compositeShader = new Shader();
	private final Shader taaShader = new Shader();
	private final Shader prepareShader = new Shader();
	private final Shader copyShader = new Shader();
	private final ShadowRenderer shadows;
	private final LightingState lighting;

	private FrameBuffer postBuffer = null;
	private int postWidth = -1;
	private int postHeight = -1;
	private final ColorTarget prepared = new ColorTarget(ColorTarget.GL_RGBA16F);
	private final OpaqueCopy opaqueCopy = new OpaqueCopy();
	private final FullscreenQuad quad = new FullscreenQuad();
	private boolean waterPassActive = false;

	// TAA
	private final ColorTarget[] history = {new ColorTarget(ColorTarget.GL_RGBA16F), new ColorTarget(ColorTarget.GL_RGBA16F)};
	private int historyIndex = 0;
	private boolean historyValid = false;
	private int frameIndex = 0;
	private boolean jitterApplied = false;

	// This frame's world matrices (captured at the main terrain draw) and the previous frame's.
	private final Matrix4f projection = new Matrix4f();         // un-jittered
	private final Matrix4f projectionJittered = new Matrix4f(); // as used for drawing
	private final Matrix4f view = new Matrix4f();
	private final Vector3d cameraPos = new Vector3d();
	private final Matrix4f prevProjection = new Matrix4f();
	private final Matrix4f prevView = new Matrix4f();
	private final Vector3d prevCameraPos = new Vector3d();
	private boolean matricesCaptured = false;

	private boolean enabled = true;
	private boolean taaEnabled = true;
	private boolean debugShadowMap = false;
	private final boolean[] keyDown = new boolean[4];

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

	public ShadowRenderer getShadows() {
		return this.shadows;
	}

	public LightingState getLighting() {
		return this.lighting;
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	private boolean taaActive() {
		return this.enabled && this.taaEnabled && this.taaShader.isEnabled() && this.compositeShader.isEnabled();
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
			this.historyValid = false;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Hooks called from mixins

	/** WorldRenderer.getProjectionMatrix RETURN: remember the clean matrix, then apply the TAA jitter. */
	public void onWorldProjection(Matrix4f matrix) {
		this.projection.set(matrix);
		this.jitterApplied = false;
		if (!this.taaActive() || this.renderWidth <= 0 || this.renderHeight <= 0) {
			return;
		}
		float[] j = JITTER[this.frameIndex % JITTER.length];
		matrix.translateLocal(j[0] * JITTER_SCALE * 2.0F / this.renderWidth, j[1] * JITTER_SCALE * 2.0F / this.renderHeight, 0.0F);
		this.jitterApplied = true;
	}

	/** Start of the main solid terrain draw: world matrices are final, sky is done, lighting goes on. */
	public void captureWorldMatrices(float partialTicks) {
		this.projectionJittered.set(GLRenderer.projectionM4f());
		this.view.set(GLRenderer.viewM4f());
		if (this.mc.activeCamera != null) {
			this.mc.activeCamera.getPosition(partialTicks, this.cameraPos);
		}
		if (!this.jitterApplied) {
			this.projection.set(this.projectionJittered);
		}
		this.matricesCaptured = true;
		if (this.enabled) {
			this.lighting.enterWorldGeometry();
		}
	}

	/** Before translucent terrain: copy the opaque scene for water, enable the water branch. */
	public void beforeTranslucentTerrain() {
		if (!this.enabled || !this.copyShader.isEnabled() || this.lighting.getMode() != LightingState.WORLD
			|| this.worldFrameBuffer == null || !Shaders.TERRAIN.isEnabled()) {
			return;
		}
		FrameBuffer worldOut = this.worldFrameBuffer.getOutputBuffer();
		FrameBufferAttachment worldColor = worldOut.getAttachment(FrameBuffer.AttachmentType.COLOR);
		FrameBufferAttachment worldDepth = worldOut.getAttachment(FrameBuffer.AttachmentType.DEPTH);
		if (worldColor == null || worldDepth == null) {
			return;
		}
		// This runs in the middle of BTA's rendering: save and restore raw GL state ourselves.
		boolean depthTest = GL41.glIsEnabled(GL41.GL_DEPTH_TEST);
		boolean blend = GL41.glIsEnabled(GL41.GL_BLEND);
		boolean cull = GL41.glIsEnabled(GL41.GL_CULL_FACE);
		int program = GL41.glGetInteger(GL41.GL_CURRENT_PROGRAM);
		int activeTexture = GL41.glGetInteger(GL41.GL_ACTIVE_TEXTURE);
		int vao = GL41.glGetInteger(GL41.GL_VERTEX_ARRAY_BINDING);
		GL41.glDisable(GL41.GL_DEPTH_TEST);
		GL41.glDisable(GL41.GL_BLEND);
		GL41.glDisable(GL41.GL_CULL_FACE);

		this.opaqueCopy.ensureSize(this.renderWidth, this.renderHeight);
		this.opaqueCopy.bindForWriting();
		this.copyShader.bind();
		GL41.glActiveTexture(GL_TEXTURE0 + LightingState.OPAQUE_COLOR_UNIT);
		worldColor.bindForOutput();
		GL41.glActiveTexture(GL_TEXTURE0 + LightingState.OPAQUE_DEPTH_UNIT);
		worldDepth.bindForOutput();
		this.quad.draw();

		// Leave the copies bound on those units for the water shader.
		GL41.glActiveTexture(GL_TEXTURE0 + LightingState.OPAQUE_COLOR_UNIT);
		GL41.glBindTexture(GL_TEXTURE_2D, this.opaqueCopy.getColor());
		GL41.glActiveTexture(GL_TEXTURE0 + LightingState.OPAQUE_DEPTH_UNIT);
		GL41.glBindTexture(GL_TEXTURE_2D, this.opaqueCopy.getDepth());
		GL41.glActiveTexture(activeTexture);

		this.worldFrameBuffer.bind();
		GL41.glViewport(0, 0, this.renderWidth, this.renderHeight);
		Shaders.TERRAIN.bind();
		Shaders.TERRAIN.uniformInt("btsWaterPass", 1);
		this.waterPassActive = true;

		GL41.glBindVertexArray(vao);
		GL41.glUseProgram(program);
		if (depthTest) GL41.glEnable(GL41.GL_DEPTH_TEST);
		if (blend) GL41.glEnable(GL41.GL_BLEND);
		if (cull) GL41.glEnable(GL41.GL_CULL_FACE);
		OpenGLHelper.checkError("btashaders opaque copy");
	}

	public void afterTranslucentTerrain() {
		if (!this.waterPassActive) {
			return;
		}
		int program = GL41.glGetInteger(GL41.GL_CURRENT_PROGRAM);
		Shaders.TERRAIN.bind();
		Shaders.TERRAIN.uniformInt("btsWaterPass", 0);
		GL41.glUseProgram(program);
		this.waterPassActive = false;
	}

	// ---------------------------------------------------------------------------------------------

	@Override
	public void beginRenderGame(float partialTicks) {
		this.pollKeys();
		super.beginRenderGame(partialTicks);
	}

	@Override
	public void beginRenderWorld(float partialTicks) {
		this.matricesCaptured = false;
		if (this.enabled) {
			this.shadows.render(partialTicks);
		}
		this.lighting.beginWorld(this.enabled, this.isEyeInWater(partialTicks));
		super.beginRenderWorld(partialTicks);
	}

	private boolean isEyeInWater(float partialTicks) {
		return this.mc.activeCamera != null && this.mc.currentWorld != null
			&& CameraUtil.isUnderLiquid(this.mc.activeCamera, this.mc.currentWorld, Materials.WATER, partialTicks);
	}

	@Override
	public void endRenderWorld(float partialTicks) {
		this.lighting.endWorld();
		this.afterTranslucentTerrain();
		if (!this.enabled || !this.compositeShader.isEnabled()) {
			this.historyValid = false;
			super.endRenderWorld(partialTicks);
			return;
		}

		OpenGLHelper.checkError("pre btashaders end render world");
		FrameBuffer worldFb = Objects.requireNonNull(this.worldFrameBuffer);
		FrameBuffer gameFb = Objects.requireNonNull(this.gameFrameBuffer);
		this.ensurePostBuffer();

		worldFb.blit();
		FrameBuffer worldOut = worldFb.getOutputBuffer();
		FrameBufferAttachment worldColor = Objects.requireNonNull(worldOut.getAttachment(FrameBuffer.AttachmentType.COLOR));
		FrameBufferAttachment worldDepth = Objects.requireNonNull(worldOut.getAttachment(FrameBuffer.AttachmentType.DEPTH));

		// Pass 0: prepare (SSAO + tone map) -> prepared. Falls back to the raw world color.
		int sceneTexture = -1;
		if (this.prepareShader.isEnabled() && this.matricesCaptured) {
			sceneTexture = this.runPrepare(worldColor, worldDepth);
		}

		// Pass 1: TAA. Output (resolved color) also becomes next frame's history.
		if (this.taaActive() && this.matricesCaptured) {
			sceneTexture = this.runTaa(worldColor, sceneTexture, worldDepth);
		} else {
			this.historyValid = false;
		}

		// Pass 2: stock post shader -> postBuffer.
		this.postBuffer.bind();
		GL41.glViewport(0, 0, this.fbWidth, this.fbHeight);
		boolean postRan = false;
		if (this.postShader.isEnabled()) {
			this.postShader.bind();
			GL41.glActiveTexture(GL_TEXTURE0);
			bindScene(worldColor, sceneTexture);
			this.postShader.uniformInt("colortex0", 0);
			GL41.glActiveTexture(GL_TEXTURE0 + 1);
			worldDepth.bindForOutput();
			this.postShader.uniformInt("depthtex0", 1);
			GL41.glActiveTexture(GL_TEXTURE0);
			this.mc.ppm.enabled = true;
			ShaderHelper.setUniforms(this.mc, this.postShader, partialTicks);
			this.mc.ppm.enabled = false;
			if (this.taaActive() && this.historyValid) {
				// TAA already anti-aliases; FXAA on top only blurs the image a second time.
				this.postShader.uniformBool("fxaa", false);
			}
			ShaderHelper.drawFullscreenRect();
			postRan = true;
		}

		// Pass 3: our composite -> game framebuffer (default framebuffer when the final shader is off).
		if (this.finalShader.isEnabled()) {
			gameFb.bind();
		} else {
			gameFb.unbind();
		}
		GL41.glViewport(0, 0, this.fbWidth, this.fbHeight);

		this.compositeShader.bind();
		GL41.glActiveTexture(GL_TEXTURE0);
		if (postRan) {
			Objects.requireNonNull(this.postBuffer.getAttachment(FrameBuffer.AttachmentType.COLOR)).bindForOutput();
		} else {
			bindScene(worldColor, sceneTexture);
		}
		this.compositeShader.uniformInt("colortex0", 0);
		GL41.glActiveTexture(GL_TEXTURE0 + 1);
		worldDepth.bindForOutput();
		this.compositeShader.uniformInt("depthtex0", 1);
		boolean showShadowMap = this.debugShadowMap && this.shadows.isShadowMapValid();
		if (showShadowMap) {
			GL41.glActiveTexture(GL_TEXTURE0 + 2);
			GL41.glBindTexture(GL_TEXTURE_2D, this.shadows.getDepthTexture());
			this.compositeShader.uniformInt("shadowtex0", 2);
		}
		GL41.glActiveTexture(GL_TEXTURE0);
		ShaderHelper.setUniforms(this.mc, this.compositeShader, partialTicks);
		this.compositeShader.uniformBool("btsDebugShadowMap", showShadowMap);
		this.setCompositeUniforms();
		ShaderHelper.drawFullscreenRect();

		LightmapHelper.instance.enableLightmapRendering();
		GL41.glUseProgram(0);
		OpenGLHelper.checkError("btashaders end render world");

		if (this.matricesCaptured) {
			this.prevProjection.set(this.projection);
			this.prevView.set(this.view);
			this.prevCameraPos.set(this.cameraPos);
		}
		this.frameIndex++;
	}

	private static void bindScene(FrameBufferAttachment worldColor, int sceneTexture) {
		if (sceneTexture >= 0) {
			GL41.glBindTexture(GL_TEXTURE_2D, sceneTexture);
		} else {
			worldColor.bindForOutput();
		}
	}

	private int runPrepare(FrameBufferAttachment worldColor, FrameBufferAttachment worldDepth) {
		this.prepared.ensureSize(this.renderWidth, this.renderHeight);
		this.prepared.bindForWriting();
		this.prepareShader.bind();
		GL41.glActiveTexture(GL_TEXTURE0);
		worldColor.bindForOutput();
		this.prepareShader.uniformInt("colortex0", 0);
		GL41.glActiveTexture(GL_TEXTURE0 + 1);
		worldDepth.bindForOutput();
		this.prepareShader.uniformInt("depthtex0", 1);
		GL41.glActiveTexture(GL_TEXTURE0);
		this.prepareShader.uniformMat4f("btsProjInv", new Matrix4f(this.projectionJittered).invert());
		this.prepareShader.uniformMat4f("btsProj", this.projectionJittered);
		this.prepareShader.uniformVec2f("btsViewSize", this.renderWidth, this.renderHeight);
		this.prepareShader.uniformFloat("btsFrame", (float) (this.frameIndex % 1024));
		ShaderHelper.drawFullscreenRect();
		return this.prepared.getTexture();
	}

	private int runTaa(FrameBufferAttachment worldColor, int sceneTexture, FrameBufferAttachment worldDepth) {
		for (ColorTarget target : this.history) {
			if (target.ensureSize(this.renderWidth, this.renderHeight)) {
				this.historyValid = false;
			}
		}
		ColorTarget read = this.history[this.historyIndex];
		ColorTarget write = this.history[1 - this.historyIndex];

		write.bindForWriting();
		this.taaShader.bind();
		GL41.glActiveTexture(GL_TEXTURE0);
		bindScene(worldColor, sceneTexture);
		this.taaShader.uniformInt("colortex0", 0);
		GL41.glActiveTexture(GL_TEXTURE0 + 1);
		worldDepth.bindForOutput();
		this.taaShader.uniformInt("depthtex0", 1);
		GL41.glActiveTexture(GL_TEXTURE0 + 2);
		GL41.glBindTexture(GL_TEXTURE_2D, read.getTexture());
		this.taaShader.uniformInt("historytex", 2);
		GL41.glActiveTexture(GL_TEXTURE0);

		this.taaShader.uniformMat4f("btsProjInv", new Matrix4f(this.projectionJittered).invert());
		this.taaShader.uniformMat4f("btsViewInv", new Matrix4f(this.view).invert());
		this.taaShader.uniformMat4f("btsPrevViewProj", new Matrix4f(this.prevProjection).mul(this.prevView));
		this.taaShader.uniformVec3f("btsCameraDelta",
			(float) (this.cameraPos.x - this.prevCameraPos.x),
			(float) (this.cameraPos.y - this.prevCameraPos.y),
			(float) (this.cameraPos.z - this.prevCameraPos.z));
		this.taaShader.uniformVec2f("btsViewSize", this.renderWidth, this.renderHeight);
		this.taaShader.uniformBool("btsHistoryValid", this.historyValid);
		ShaderHelper.drawFullscreenRect();

		this.historyIndex = 1 - this.historyIndex;
		this.historyValid = true;
		return write.getTexture();
	}

	private void setCompositeUniforms() {
		boolean worldValid = this.shadows.isLightValid() && this.matricesCaptured;
		float strength = 0.0F;
		float sunU = 0.5F;
		float sunV = 0.5F;
		if (worldValid) {
			Vector3f dir = this.shadows.getLightDirection();
			Vector4f clip = new Vector4f(dir.x, dir.y, dir.z, 0.0F);
			this.view.transform(clip);
			this.projection.transform(clip);
			if (clip.w > 0.0001F) {
				sunU = clip.x / clip.w * 0.5F + 0.5F;
				sunV = clip.y / clip.w * 0.5F + 0.5F;
				float elevation = dir.y;
				// Strongest around sunrise/sunset, subtle at noon, faint under the moon.
				float lowSun = 1.0F - smoothstep(0.15F, 0.7F, elevation);
				float horizonFade = smoothstep(0.0F, 0.08F, elevation);
				strength = (this.shadows.isLightSun() ? 0.35F + 0.65F * lowSun : 0.25F) * horizonFade;
				strength *= 1.0F - this.shadows.getRain();
				float off = Math.max(Math.abs(sunU - 0.5F), Math.abs(sunV - 0.5F));
				strength *= 1.0F - smoothstep(0.6F, 1.1F, off);
			}
			this.compositeShader.uniformMat4f("btsProjInv", new Matrix4f(this.projection).invert());
			this.compositeShader.uniformMat4f("btsViewInv", new Matrix4f(this.view).invert());
			this.compositeShader.uniformVec3f("btsLightDir", this.shadows.getLightDirection());
			this.compositeShader.uniformFloat("btsSunHeight", this.shadows.getSunHeight());
			this.compositeShader.uniformFloat("btsRain", this.shadows.getRain());
		}
		Vector3f color = new Vector3f(this.shadows.getDirectColor());
		float max = Math.max(color.x, Math.max(color.y, color.z));
		if (max > 0.0001F) {
			color.div(max); // hue only
		} else {
			color.set(1.0F);
		}
		this.compositeShader.uniformInt("btsWorldValid", worldValid ? 1 : 0);
		this.compositeShader.uniformVec2f("btsSunScreen", sunU, sunV);
		this.compositeShader.uniformFloat("btsGodRayStrength", strength);
		this.compositeShader.uniformVec3f("btsGodRayColor", color);
	}

	// ---------------------------------------------------------------------------------------------

	private void pollKeys() {
		if (!(this.mc.gameWindow instanceof GameWindowGLFW window)) {
			return;
		}
		long handle = window.getHandle();
		boolean inWorld = this.mc.currentScreen == null;

		if (pressed(handle, KEY_TOGGLE, 0) && inWorld) {
			this.enabled = !this.enabled;
			this.historyValid = false;
			BtaShaders.LOGGER.info("BTA Shaders {}", this.enabled ? "enabled" : "disabled (stock BTA rendering)");
		}
		if (pressed(handle, KEY_DEBUG_SHADOW, 1) && inWorld) {
			this.debugShadowMap = !this.debugShadowMap;
			BtaShaders.LOGGER.info("Shadow map overlay {}", this.debugShadowMap ? "on" : "off");
		}
		if (pressed(handle, KEY_TOGGLE_TAA, 2) && inWorld) {
			this.taaEnabled = !this.taaEnabled;
			this.historyValid = false;
			BtaShaders.LOGGER.info("TAA {}", this.taaEnabled ? "on" : "off");
		}
		if (pressed(handle, KEY_RELOAD, 3) && inWorld) {
			BtaShaders.LOGGER.info("Reloading BTA Shaders...");
			this.reload();
			Shaders.reload(); // recompiles the world shaders, incl. ours
		}
	}

	private boolean pressed(long handle, int key, int slot) {
		boolean down = GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
		boolean edge = down && !this.keyDown[slot];
		this.keyDown[slot] = down;
		return edge;
	}

	private void ensurePostBuffer() {
		if (this.postBuffer != null && this.postWidth == this.fbWidth && this.postHeight == this.fbHeight) {
			return;
		}
		if (this.postBuffer != null) {
			this.postBuffer.delete();
		}
		this.postWidth = this.fbWidth;
		this.postHeight = this.fbHeight;
		this.postBuffer = new FrameBufferSingleSample(new FrameBufferTextureSingleSample(
			GL_RGBA, GL_COLOR_ATTACHMENT0, new Vector2i(this.fbWidth, this.fbHeight), parameters -> {
				parameters.set(GL_TEXTURE_MIN_FILTER, GL_LINEAR);
				parameters.set(GL_TEXTURE_MAG_FILTER, GL_LINEAR);
				parameters.set(GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
				parameters.set(GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			}));
	}

	@Override
	public void reload() {
		ShaderConfig.load();
		super.reload();
		compileOrWarn(this.compositeShader, "composite", "using stock BTA post-processing");
		compileOrWarn(this.taaShader, "taa", "TAA disabled");
		compileOrWarn(this.prepareShader, "prepare", "no SSAO / tone mapping");
		compileOrWarn(this.copyShader, "copy", "no water reflections/refraction");
		if (this.compositeShader.isEnabled()) {
			ShadowRenderer.bindUniformBlocks(this.compositeShader);
		}
		if (this.copyShader.isEnabled()) {
			this.copyShader.bind();
			this.copyShader.uniformInt("btsSrcColor", LightingState.OPAQUE_COLOR_UNIT);
			this.copyShader.uniformInt("btsSrcDepth", LightingState.OPAQUE_DEPTH_UNIT);
			GL41.glUseProgram(0);
		}
		this.historyValid = false;
		this.shadows.reload(MOD_SHADERS);
		OpenGLHelper.checkError("btashaders reload");
	}

	private static void compileOrWarn(Shader shader, String name, String consequence) {
		shader.delete();
		shader.compile(MOD_SHADERS, name);
		if (shader.isEnabled()) {
			BtaShaders.LOGGER.info("Shader '{}' compiled.", name);
		} else {
			BtaShaders.LOGGER.warn("Shader '{}' failed to compile; {}.", name, consequence);
		}
	}

	@Override
	public void delete() {
		super.delete();
		if (this.postBuffer != null) {
			this.postBuffer.delete();
			this.postBuffer = null;
		}
		this.postWidth = -1;
		this.postHeight = -1;
		for (ColorTarget target : this.history) {
			target.delete();
		}
		this.prepared.delete();
		this.opaqueCopy.delete();
		this.quad.delete();
		this.historyValid = false;
		this.shadows.delete();
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.max(0.0F, Math.min(1.0F, (x - edge0) / (edge1 - edge0)));
		return t * t * (3.0F - 2.0F * t);
	}

	private static float halton(int index, int base) {
		float f = 1.0F;
		float r = 0.0F;
		int i = index;
		while (i > 0) {
			f /= base;
			r += f * (i % base);
			i /= base;
		}
		return r;
	}

	private static float[][] buildHalton(int count) {
		float[][] out = new float[count][2];
		for (int i = 0; i < count; i++) {
			out[i][0] = halton(i + 1, 2) - 0.5F;
			out[i][1] = halton(i + 1, 3) - 0.5F;
		}
		return out;
	}
}
