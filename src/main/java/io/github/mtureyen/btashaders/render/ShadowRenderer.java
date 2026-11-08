package io.github.mtureyen.btashaders.render;

import io.github.mtureyen.btashaders.BtaShaders;
import io.github.mtureyen.btashaders.mixin.ChunkRendererAccessor;
import io.github.mtureyen.btashaders.mixin.RenderGlobalAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.OpenGLHelper;
import net.minecraft.client.render.renderer.GLRenderer;
import net.minecraft.client.render.renderer.Shaders;
import net.minecraft.client.render.renderer.State;
import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.shader.ShaderProvider;
import net.minecraft.client.render.terrain.ChunkRenderer;
import net.minecraft.client.render.terrain.ChunkRendererMultiDraw;
import net.minecraft.client.render.terrain.RenderList;
import net.minecraft.client.render.terrain.RenderRegion;
import net.minecraft.client.render.terrain.TerrainRendererMultiDraw;
import net.minecraft.client.render.terrain.VertexBuffer;
import net.minecraft.client.render.tessellator.TessellatorTerrainImpl;
import net.minecraft.client.render.texture.stitcher.TextureRegistry;
import net.minecraft.client.render.uniform.UniformBlock;
import net.minecraft.client.render.uniform.UniformBlockManager;
import net.minecraft.core.world.World;
import net.minecraft.client.render.EntityRendererDispatcher;
import net.minecraft.client.render.TileEntityRenderDispatcher;
import net.minecraft.client.render.camera.ICamera;
import net.minecraft.client.render.tessellator.TessellatorGeneral;
import net.minecraft.core.block.entity.TileEntity;
import net.minecraft.core.entity.Entity;
import java.util.List;
import net.minecraft.core.world.pos.TilePos;
import net.minecraft.core.world.weather.IPrecipitation;
import net.minecraft.core.world.weather.Weather;
import net.minecraft.core.world.weather.WeatherManager;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL41;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders solid terrain from the sun's (or moon's) point of view into a depth texture,
 * and exposes the matrices + texture so the terrain shader can look shadows up.
 *
 * <p>The shadow pass builds its <b>own</b> list of chunks to draw, culled in light space, and
 * issues its own glMultiDrawArrays. It never touches the main camera's visible set, and never
 * triggers chunk rebuilds.
 */
public class ShadowRenderer {
	// Tunables (will move to a config/pack file later).
	public static final int RESOLUTION = 2048;
	/** Half-size of the shadow box in blocks: shadows render within this radius of the camera. */
	public static final float DISTANCE = 96.0F;
	/** Depth range of the shadow camera in blocks (both directions), covers tall casters. */
	public static final float DEPTH_RANGE = 256.0F;
	/** Bounding radius of a 16^3 chunk around its center. */
	private static final float CHUNK_RADIUS = 13.86F;
	private static final float MOON_STRENGTH = 0.5F;
	// The sun is followed continuously (stepping it looked like a stop-motion animation). Edge crawl
	// from the rotating texel grid is hidden instead by shadow distortion (more resolution near the
	// player, see shadow.vsh / terrain.fsh) and noise-rotated soft filtering.

	private static final int GL_TEXTURE_2D = 3553;
	private static final int GL_FRAMEBUFFER = 36160;
	private static final int GL_DEPTH_ATTACHMENT = 36096;
	private static final int GL_DEPTH_COMPONENT = 6402;
	private static final int GL_DEPTH_COMPONENT32F = 36012;
	private static final int GL_FLOAT = 5126;
	private static final int GL_NEAREST = 9728;
	private static final int GL_CLAMP_TO_BORDER = 33069;
	private static final int GL_TEXTURE_BORDER_COLOR = 4100;
	private static final int GL_FRAMEBUFFER_COMPLETE = 36053;
	private static final int GL_DEPTH_BUFFER_BIT = 256;
	private static final int GL_TEXTURE0 = 33984;

	private final Minecraft mc;
	private final Shader shadowShader = new Shader();

	private int fbo = 0;
	private int depthTexture = 0;
	/**
	 * Sampler object used when the terrain shader reads the map: hardware depth compare + linear
	 * filtering = smooth bilinear PCF. The texture itself stays plain (no compare) so the F9 debug
	 * overlay can read raw depth from it.
	 */
	private int compareSampler = 0;
	private boolean ready = false;

	/** Camera-relative position -> light view space. */
	private final Matrix4f shadowView = new Matrix4f();
	private final Matrix4f shadowProjection = new Matrix4f();
	/** projection * view, handed to the terrain shader. */
	private final Matrix4f shadowMatrix = new Matrix4f();
	/** Direction pointing towards the active light (sun or moon), world space. */
	private final Vector3f lightDirection = new Vector3f(0, 1, 0);
	/** 0..1, how strong shadows are right now (fades at sunrise/sunset, weaker under the moon). */
	private float shadowStrength = 0.0F;
	/** True if the active light is the sun (false = moon). */
	private boolean lightIsSun = true;
	/** Height of the sun itself (-1..1), independent of which light is active. */
	private float sunHeight = 1.0F;
	/** 0..1 rain/storm strength. */
	private float rain = 0.0F;
	/** Direct light color * intensity (already faded at the horizon and by rain). */
	private final Vector3f directColor = new Vector3f();
	/** Tint applied to the sky-lit ambient part. */
	private final Vector3f ambientTint = new Vector3f(1.0F);
	/** Whether light/world state was computed this frame. */
	private boolean lightValid = false;
	/** True once a shadow map has been rendered this frame and may be sampled. */
	private boolean shadowMapValid = false;

	private final Vector3d cameraPos = new Vector3d();
	private final Vector3f scratch = new Vector3f();
	private final Map<RenderRegion<?>, IntBuffer[]> regionBatches = new HashMap<>();

	public ShadowRenderer(Minecraft mc) {
		this.mc = mc;
		instance = this;
	}

	// ---------------------------------------------------------------------------------------------
	// Entity shadows

	private static ShadowRenderer instance = null;
	/** True while entities are being drawn into the shadow map (see GLRendererMixin). */
	private static boolean entityPass = false;
	/** Draws nothing; replaces BTA's stock shaders during the entity shadow pass. */
	private final Shader nullShader = new Shader();
	private boolean entityShadowsBroken = false;

	/**
	 * GLRenderer.setShader / globalSetUniforms hook: during the entity shadow pass, every shader that
	 * isn't one of ours (they know the shadow-map distortion) is swapped for the null shader, so name
	 * tags, lines etc. don't leave stray shadows.
	 */
	public static Shader substituteShader(Shader shader) {
		if (!entityPass || instance == null || !instance.nullShader.isEnabled()) {
			return shader;
		}
		if (shader == Shaders.ENTITY || shader == Shaders.WORLD || shader == Shaders.ITEM
			|| shader == Shaders.COLOR_WORLD || shader == Shaders.TERRAIN) {
			return shader;
		}
		return instance.nullShader;
	}

	/** Real entity shadows are being drawn this frame (BTA's blob shadows can be skipped). */
	public boolean entityShadowsActive() {
		return this.shadowMapValid && this.entityShadowsEnabled();
	}

	private boolean entityShadowsEnabled() {
		if (this.entityShadowsBroken) {
			return false;
		}
		String setting = ShaderConfig.defines().get("ENTITY_SHADOWS");
		return setting == null || !setting.trim().equals("0");
	}

	private static Shader[] shadowPassShaders() {
		return new Shader[]{Shaders.ENTITY, Shaders.WORLD, Shaders.ITEM, Shaders.COLOR_WORLD, Shaders.TERRAIN};
	}

	private static void setShadowPassUniform(boolean on) {
		for (Shader shader : shadowPassShaders()) {
			if (shader.isEnabled()) {
				shader.bind();
				shader.uniformInt("btsShadowPass", on ? 1 : 0);
			}
		}
		GL41.glUseProgram(0);
	}

	/**
	 * Mobs, the player (also in first person: you see your own shadow), dropped items and block
	 * entities, culled against the shadow box rather than the camera, so things behind you still cast
	 * shadows into view. The shadow framebuffer and matrices are bound by the caller.
	 */
	private void renderEntityShadows(float partialTicks) {
		World world = this.mc.currentWorld;
		ICamera camera = this.mc.activeCamera;
		if (world == null || camera == null) {
			return;
		}
		setShadowPassUniform(true);
		entityPass = true;
		try {
			EntityRendererDispatcher.instance.cacheActiveRenderInfo(camera, partialTicks);
			TileEntityRenderDispatcher.instance.cacheActiveRenderInfo(camera, partialTicks);
			EntityRendererDispatcher.renderPosX = this.cameraPos.x;
			EntityRendererDispatcher.renderPosY = this.cameraPos.y;
			EntityRendererDispatcher.renderPosZ = this.cameraPos.z;
			TileEntityRenderDispatcher.renderPosX = this.cameraPos.x;
			TileEntityRenderDispatcher.renderPosY = this.cameraPos.y;
			TileEntityRenderDispatcher.renderPosZ = this.cameraPos.z;
			TessellatorGeneral tessellator = GLRenderer.getTessellator();
			float limit = DISTANCE + 4.0F;
			List<Entity> entities = world.getLoadedEntityList();
			for (int i = 0; i < entities.size(); i++) {
				Entity entity = entities.get(i);
				if (this.inShadowBox(entity.x, entity.y + 1.0, entity.z, limit)) {
					EntityRendererDispatcher.instance.renderEntity(tessellator, entity, partialTicks);
				}
			}
			List<TileEntity> tileEntities = this.mc.renderGlobal.renderableTileEntities;
			for (int i = 0; i < tileEntities.size(); i++) {
				TileEntity te = tileEntities.get(i);
				if (this.inShadowBox(te.tilePos.x + 0.5, te.tilePos.y + 0.5, te.tilePos.z + 0.5, limit)) {
					TileEntityRenderDispatcher.instance.renderTileEntity(tessellator, camera, world, te, partialTicks);
				}
			}
		} catch (Throwable t) {
			// Never let a misbehaving entity renderer take the whole frame down: turn the feature off.
			this.entityShadowsBroken = true;
			BtaShaders.LOGGER.error("Entity shadow pass failed; entity shadows disabled.", t);
		} finally {
			entityPass = false;
			setShadowPassUniform(false);
		}
	}

	private boolean inShadowBox(double x, double y, double z, float limit) {
		this.scratch.set((float) (x - this.cameraPos.x), (float) (y - this.cameraPos.y), (float) (z - this.cameraPos.z));
		this.shadowView.transformPosition(this.scratch);
		return Math.abs(this.scratch.x) < limit && Math.abs(this.scratch.y) < limit;
	}

	// ---------------------------------------------------------------------------------------------
	// Lifecycle

	public void reload(ShaderProvider provider) {
		this.shadowShader.delete();
		this.shadowShader.compile(provider, "shadow");
		if (this.shadowShader.isEnabled()) {
			bindUniformBlocks(this.shadowShader);
			this.shadowShader.bind();
			this.shadowShader.uniformInt("colortex", 0);
			GL41.glUseProgram(0);
		} else {
			BtaShaders.LOGGER.warn("Shadow shader failed to compile; shadows disabled.");
		}
		this.nullShader.delete();
		this.nullShader.compile(provider, "null");
		this.entityShadowsBroken = false;
		if (this.fbo == 0) {
			this.createFramebuffer();
		}
	}

	private void createFramebuffer() {
		this.depthTexture = GL41.glGenTextures();
		GL41.glBindTexture(GL_TEXTURE_2D, this.depthTexture);
		GL41.glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, RESOLUTION, RESOLUTION, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0L);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
		// Outside the map = max depth = "lit".
		GL41.glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new float[]{1.0F, 1.0F, 1.0F, 1.0F});
		GL41.glBindTexture(GL_TEXTURE_2D, 0);

		this.compareSampler = GL41.glGenSamplers();
		GL41.glSamplerParameteri(this.compareSampler, GL41.GL_TEXTURE_MIN_FILTER, GL41.GL_LINEAR);
		GL41.glSamplerParameteri(this.compareSampler, GL41.GL_TEXTURE_MAG_FILTER, GL41.GL_LINEAR);
		GL41.glSamplerParameteri(this.compareSampler, GL41.GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
		GL41.glSamplerParameteri(this.compareSampler, GL41.GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
		GL41.glSamplerParameterfv(this.compareSampler, GL_TEXTURE_BORDER_COLOR, new float[]{1.0F, 1.0F, 1.0F, 1.0F});
		GL41.glSamplerParameteri(this.compareSampler, GL41.GL_TEXTURE_COMPARE_MODE, GL41.GL_COMPARE_REF_TO_TEXTURE);
		GL41.glSamplerParameteri(this.compareSampler, GL41.GL_TEXTURE_COMPARE_FUNC, GL41.GL_LEQUAL);

		this.fbo = GL41.glGenFramebuffers();
		GL41.glBindFramebuffer(GL_FRAMEBUFFER, this.fbo);
		GL41.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, this.depthTexture, 0);
		GL41.glDrawBuffer(0);
		GL41.glReadBuffer(0);
		int status = GL41.glCheckFramebufferStatus(GL_FRAMEBUFFER);
		GL41.glBindFramebuffer(GL_FRAMEBUFFER, 0);
		this.ready = status == GL_FRAMEBUFFER_COMPLETE;
		if (this.ready) {
			BtaShaders.LOGGER.info("Shadow map created ({}x{}).", RESOLUTION, RESOLUTION);
		} else {
			BtaShaders.LOGGER.error("Shadow framebuffer incomplete (status {}); shadows disabled.", status);
		}
		OpenGLHelper.checkError("btashaders shadow framebuffer");
	}

	public void delete() {
		if (this.fbo != 0) {
			GL41.glDeleteFramebuffers(this.fbo);
			this.fbo = 0;
		}
		if (this.depthTexture != 0) {
			GL41.glDeleteTextures(this.depthTexture);
			this.depthTexture = 0;
		}
		if (this.compareSampler != 0) {
			GL41.glDeleteSamplers(this.compareSampler);
			this.compareSampler = 0;
		}
		this.ready = false;
		this.shadowMapValid = false;
	}

	static void bindUniformBlocks(Shader shader) {
		for (UniformBlock<?> block : UniformBlockManager.nameToBlockMap.values()) {
			shader.uniformBlockBinding(block.name, block.index);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Per frame

	/** Renders the shadow map. Call before the main world pass binds its framebuffer. */
	public void render(float partialTicks) {
		this.shadowMapValid = false;
		this.lightValid = false;
		if (this.mc.currentWorld == null || this.mc.activeCamera == null) {
			return;
		}
		// Lighting environment is needed even when no shadow map is drawn (night/twilight).
		this.mc.activeCamera.getPosition(partialTicks, this.cameraPos);
		this.updateLight(partialTicks);
		this.lightValid = true;
		if (!this.ready || !this.shadowShader.isEnabled()) {
			return;
		}
		if (!(this.mc.terrainRenderer instanceof TerrainRendererMultiDraw terrain)) {
			return;
		}
		// RenderGlobal is final, so javac needs the Object hop before casting to the mixin accessor.
		ChunkRenderer[] chunks = ((RenderGlobalAccessor) (Object) this.mc.renderGlobal).btashaders$getChunkRenderers();
		if (chunks == null) {
			return;
		}

		if (this.shadowStrength <= 0.001F) {
			return;
		}
		this.updateMatrices();

		OpenGLHelper.checkError("pre btashaders shadow pass");
		this.collectChunks(terrain, chunks);

		GLRenderer.pushFrame();
		GL41.glBindFramebuffer(GL_FRAMEBUFFER, this.fbo);
		GL41.glViewport(0, 0, RESOLUTION, RESOLUTION);
		GLRenderer.setDepthMask(true);
		GL41.glClear(GL_DEPTH_BUFFER_BIT);
		GLRenderer.enableState(State.DEPTH_TEST);
		// Draw both faces so thin geometry (leaves, glass panes, fences) casts shadows from either side.
		GLRenderer.disableState(State.CULL_FACE);
		GLRenderer.disableState(State.BLEND);

		GLRenderer.projectionM4f().set(this.shadowProjection);
		GLRenderer.viewM4f().set(this.shadowView);

		GL41.glActiveTexture(GL_TEXTURE0);
		TextureRegistry.worldAtlas.bind();
		// Waving plants/leaves must move identically in the shadow pass.
		this.shadowShader.bind();
		this.applyCameraUniforms(this.shadowShader);

		TessellatorTerrainImpl.TerrainVertexConfig vertexConfig = null;
		for (Map.Entry<RenderRegion<?>, IntBuffer[]> batch : this.regionBatches.entrySet()) {
			IntBuffer positions = batch.getValue()[0];
			IntBuffer sizes = batch.getValue()[1];
			if (positions.limit() == 0) {
				continue;
			}
			RenderRegion<?> region = batch.getKey();
			if (vertexConfig == null) {
				@SuppressWarnings("unchecked")
				RenderList<TessellatorTerrainImpl.TerrainVertexConfig> list =
					(RenderList<TessellatorTerrainImpl.TerrainVertexConfig>) region.renderLists[0];
				vertexConfig = list.getVertexConfig();
				vertexConfig.enable(terrain.vertexBuffer.getVao(), terrain.vertexBuffer.getVbo());
			}
			GLRenderer.modelM4f().identity().translate(
				(float) ((region.posX << 9) - this.cameraPos.x),
				(float) (-this.cameraPos.y),
				(float) ((region.posZ << 9) - this.cameraPos.z));
			GLRenderer.globalSetUniforms(this.shadowShader);
			GL41.glMultiDrawArrays(vertexConfig.drawMode().cap, positions, sizes);
		}
		if (vertexConfig != null) {
			vertexConfig.disable();
		}

		if (this.entityShadowsEnabled()) {
			GLRenderer.modelM4f().identity();
			this.renderEntityShadows(partialTicks);
		}

		GL41.glBindFramebuffer(GL_FRAMEBUFFER, 0);
		GLRenderer.popFrame();
		// popFrame restores the matrices but doesn't mark the Matrices UBO dirty; touch it so the
		// main pass re-uploads the camera matrices instead of drawing with ours.
		GLRenderer.projectionM4f();
		GL41.glUseProgram(0);
		OpenGLHelper.checkError("btashaders shadow pass");
		this.shadowMapValid = true;
	}

	private void updateLight(float partialTicks) {
		float celestial = this.mc.currentWorld.getCelestialAngle(partialTicks);
		double theta = celestial * Math.PI * 2.0;
		// Matches RenderGlobal.renderSky: sun quad at +Y rotated around Z by celestialAngle.
		this.lightDirection.set((float) -Math.sin(theta), (float) Math.cos(theta), 0.0F);
		this.sunHeight = this.lightDirection.y;
		this.lightIsSun = this.lightDirection.y >= 0.0F;
		float strength = 1.0F;
		if (!this.lightIsSun) {
			this.lightDirection.negate(); // moon
			strength = MOON_STRENGTH;
		}
		this.rain = computeRain();
		// Fade out as the light approaches the horizon (long, unstable shadows) and swap sun<->moon smoothly.
		float elevation = this.lightDirection.y;
		float horizonFade = smoothstep(0.02F, 0.15F, elevation);
		this.shadowStrength = strength * horizonFade * (1.0F - this.rain);

		// Direct light color by time of day (values in the spirit of Complementary's light colors,
		// tuned for BTA's LDR lightmap; fine-tune later).
		if (this.lightIsSun) {
			float noon = smoothstep(0.0F, 0.45F, elevation);
			// sunset: deep orange, noon: warm white
			float r = lerp(1.00F, 1.00F, noon);
			float g = lerp(0.55F, 0.93F, noon);
			float b = lerp(0.28F, 0.82F, noon);
			float intensity = lerp(0.45F, 0.55F, noon) * horizonFade;
			this.directColor.set(r, g, b).mul(intensity);
		} else {
			this.directColor.set(0.40F, 0.48F, 0.70F).mul(0.10F * horizonFade);
		}
		this.directColor.mul(1.0F - 0.85F * this.rain);

		// Ambient (sky light) tint: slightly blue by day, warmer around sunset, bluer at night.
		float day = smoothstep(-0.1F, 0.25F, this.sunHeight);
		float sunsetness = 1.0F - smoothstep(0.0F, 0.35F, Math.abs(this.sunHeight));
		this.ambientTint.set(
			lerp(0.80F, 0.90F, day) + 0.08F * sunsetness,
			lerp(0.88F, 0.95F, day),
			lerp(1.12F, 1.05F, day) - 0.08F * sunsetness);
		// Rain: flatter, greyer ambient.
		this.ambientTint.lerp(new Vector3f(0.95F, 0.97F, 1.0F), this.rain * 0.6F);
	}

	private float computeRain() {
		World world = this.mc.currentWorld;
		Weather weather = world.getCurrentWeather();
		if (!(weather instanceof IPrecipitation)) {
			return 0.0F;
		}
		WeatherManager manager = world.getWeatherManager();
		float power = manager.getWeatherIntensity() * manager.getWeatherPower();
		return Math.max(0.0F, Math.min(1.0F, power));
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private void updateMatrices() {
		// Rotation: look along -lightDirection. Sun path is in the XY plane, so +Z is never parallel.
		Matrix4d rotation = new Matrix4d().lookAlong(-this.lightDirection.x, -this.lightDirection.y, -this.lightDirection.z, 0.0, 0.0, 1.0);

		// Snap the shadow camera to whole shadow-map texels in light space so edges don't shimmer as you move.
		double texel = (2.0 * DISTANCE) / RESOLUTION;
		Vector3d lightSpaceCam = rotation.transformPosition(new Vector3d(this.cameraPos));
		double snappedX = Math.floor(lightSpaceCam.x / texel) * texel;
		double snappedY = Math.floor(lightSpaceCam.y / texel) * texel;

		// view(p) = R * (p + cam) - (snappedX, snappedY, lightSpaceCam.z)  for camera-relative p
		this.shadowView.identity()
			.translate((float) (lightSpaceCam.x - snappedX), (float) (lightSpaceCam.y - snappedY), 0.0F)
			.mul(new Matrix4f(rotation));
		this.shadowProjection.setOrtho(-DISTANCE, DISTANCE, -DISTANCE, DISTANCE, -DEPTH_RANGE, DEPTH_RANGE);
		this.shadowProjection.mul(this.shadowView, this.shadowMatrix);
	}

	private void collectChunks(TerrainRendererMultiDraw terrain, ChunkRenderer[] chunks) {
		for (IntBuffer[] buffers : this.regionBatches.values()) {
			buffers[0].clear();
			buffers[1].clear();
		}
		float limit = DISTANCE + CHUNK_RADIUS;
		for (ChunkRenderer chunk : chunks) {
			if (!(chunk instanceof ChunkRendererMultiDraw multiDraw)) {
				continue;
			}
			VertexBuffer.Entry<TessellatorTerrainImpl.TerrainVertexConfig> entry = multiDraw.renderListEntries[0];
			if (entry == null || entry.size <= 0) {
				continue;
			}
			TilePos pos = ((ChunkRendererAccessor) chunk).btashaders$getPos();
			// Chunk center, camera-relative, into light space; cull against the shadow box (x/y only,
			// depth range covers everything above/below).
			this.scratch.set(
				(float) (pos.x + 8 - this.cameraPos.x),
				(float) (pos.y + 8 - this.cameraPos.y),
				(float) (pos.z + 8 - this.cameraPos.z));
			this.shadowView.transformPosition(this.scratch);
			if (Math.abs(this.scratch.x) > limit || Math.abs(this.scratch.y) > limit) {
				continue;
			}
			RenderRegion<TessellatorTerrainImpl.TerrainVertexConfig> region = terrain.getRenderRegionAtBlockPos(pos.x, pos.z);
			IntBuffer[] buffers = this.regionBatches.computeIfAbsent(region, r -> new IntBuffer[]{
				BufferUtils.createIntBuffer(1024), BufferUtils.createIntBuffer(1024)});
			if (!buffers[0].hasRemaining()) {
				buffers[0] = grow(buffers[0]);
				buffers[1] = grow(buffers[1]);
			}
			int vertexSize = entry.config.getVertexSize();
			buffers[0].put((int) (entry.position / vertexSize));
			buffers[1].put((int) (entry.size / vertexSize));
		}
		for (IntBuffer[] buffers : this.regionBatches.values()) {
			buffers[0].flip();
			buffers[1].flip();
		}
		// Regions can disappear when the world changes; drop empty ones so stale keys don't pile up.
		this.regionBatches.values().removeIf(b -> b[0].limit() == 0);
	}

	private static IntBuffer grow(IntBuffer buffer) {
		IntBuffer bigger = BufferUtils.createIntBuffer(buffer.capacity() * 2);
		buffer.flip();
		bigger.put(buffer);
		return bigger;
	}

	// ---------------------------------------------------------------------------------------------
	// Receiving side: called around the main solid terrain draw

	/** Uploads this frame's light + shadow uniforms to a (bound) lit shader. See LightingState. */
	public void applyUniforms(Shader shader) {
		shader.uniformInt("btsShadowsOn", this.shadowMapValid ? 1 : 0);
		shader.uniformVec3f("btsLightDir", this.lightDirection);
		shader.uniformVec3f("btsDirectColor", this.directColor);
		shader.uniformVec3f("btsAmbientTint", this.ambientTint);
		shader.uniformFloat("btsRain", this.rain);
		shader.uniformInt("btsLightIsSun", this.lightIsSun ? 1 : 0);
		shader.uniformFloat("btsSunHeight", this.sunHeight);
		this.applyCameraUniforms(shader);
		shader.uniformMat4f("btsShadowMatrix", this.shadowMatrix);
		shader.uniformFloat("btsShadowStrength", this.shadowStrength);
		shader.uniformFloat("btsShadowTexel", 1.0F / RESOLUTION);
		shader.uniformFloat("btsShadowWorldTexel", (2.0F * DISTANCE) / RESOLUTION);
		shader.uniformFloat("btsShadowDepthSpan", 2.0F * DEPTH_RANGE);
	}

	/** Camera position split for float precision: fract (block centers) and mod 1024 (waving phase). */
	private void applyCameraUniforms(Shader shader) {
		shader.uniformVec3f("btsCameraFract",
			(float) (this.cameraPos.x - Math.floor(this.cameraPos.x)),
			(float) (this.cameraPos.y - Math.floor(this.cameraPos.y)),
			(float) (this.cameraPos.z - Math.floor(this.cameraPos.z)));
		shader.uniformVec3f("btsCameraMod",
			(float) (this.cameraPos.x - Math.floor(this.cameraPos.x / 1024.0) * 1024.0),
			(float) (this.cameraPos.y - Math.floor(this.cameraPos.y / 1024.0) * 1024.0),
			(float) (this.cameraPos.z - Math.floor(this.cameraPos.z / 1024.0) * 1024.0));
	}

	public int getCompareSampler() {
		return this.compareSampler;
	}

	public Matrix4f getShadowMatrix() {
		return this.shadowMatrix;
	}

	public float getShadowStrength() {
		return this.shadowStrength;
	}

	public float getSunHeight() {
		return this.sunHeight;
	}

	public int getDepthTexture() {
		return this.depthTexture;
	}

	public boolean isShadowMapValid() {
		return this.shadowMapValid;
	}

	public boolean isLightValid() {
		return this.lightValid;
	}

	public Vector3f getLightDirection() {
		return this.lightDirection;
	}

	public boolean isLightSun() {
		return this.lightIsSun;
	}

	public Vector3f getDirectColor() {
		return this.directColor;
	}

	public float getRain() {
		return this.rain;
	}

	public Vector3d getCameraPos() {
		return this.cameraPos;
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.max(0.0F, Math.min(1.0F, (x - edge0) / (edge1 - edge0)));
		return t * t * (3.0F - 2.0F * t);
	}
}
