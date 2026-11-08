package io.github.mtureyen.btashaders.render;

import org.lwjgl.opengl.GL41;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GPU time per render pass, measured with timestamp queries (they may nest/overlap, unlike
 * GL_TIME_ELAPSED). Results are read back a few frames later so the CPU never waits on the GPU,
 * then smoothed. Shown in the F3 overlay (HudIngameMixin).
 */
public final class GpuProfiler {
	public enum Section {
		SHADOW("shadow"),
		WORLD("world"),
		PREPARE("prepare"),
		BLOOM("bloom+exposure"),
		VL("light shafts"),
		TONEMAP("tonemap"),
		TAA("taa"),
		POST("post+composite");

		final String label;

		Section(String label) {
			this.label = label;
		}
	}

	private static final int GL_TIMESTAMP = 0x8E28;
	private static final int GL_QUERY_RESULT = 0x8866;
	private static final int GL_QUERY_RESULT_AVAILABLE = 0x8867;
	/** Frames in flight before a query is read back. */
	private static final int LATENCY = 4;
	private static final int SECTIONS = Section.values().length;

	private int[] queries = null; // [frame][section][start/end]
	private final boolean[][] started = new boolean[LATENCY][SECTIONS];
	private final boolean[][] ended = new boolean[LATENCY][SECTIONS];
	private final double[] smoothedMs = new double[SECTIONS];
	private final boolean[] seen = new boolean[SECTIONS];
	private int slot = 0;

	private int query(int frame, Section section, int end) {
		return this.queries[(frame * SECTIONS + section.ordinal()) * 2 + end];
	}

	private boolean ensureQueries() {
		if (this.queries == null) {
			this.queries = new int[LATENCY * SECTIONS * 2];
			GL41.glGenQueries(this.queries);
		}
		return true;
	}

	public void begin(Section section) {
		if (!this.ensureQueries()) {
			return;
		}
		GL41.glQueryCounter(this.query(this.slot, section, 0), GL_TIMESTAMP);
		this.started[this.slot][section.ordinal()] = true;
		this.ended[this.slot][section.ordinal()] = false;
	}

	public void end(Section section) {
		if (this.queries == null || !this.started[this.slot][section.ordinal()]) {
			return;
		}
		GL41.glQueryCounter(this.query(this.slot, section, 1), GL_TIMESTAMP);
		this.ended[this.slot][section.ordinal()] = true;
	}

	/** Call once per frame after the last pass: advance and collect the oldest frame's results. */
	public void endFrame() {
		if (this.queries == null) {
			return;
		}
		this.slot = (this.slot + 1) % LATENCY;
		// The slot we are about to reuse holds the results from LATENCY - 1 frames ago.
		for (Section section : Section.values()) {
			int i = section.ordinal();
			if (this.started[this.slot][i] && this.ended[this.slot][i]) {
				int endQuery = this.query(this.slot, section, 1);
				if (GL41.glGetQueryObjecti(endQuery, GL_QUERY_RESULT_AVAILABLE) != 0) {
					long start = GL41.glGetQueryObjecti64(this.query(this.slot, section, 0), GL_QUERY_RESULT);
					long end = GL41.glGetQueryObjecti64(endQuery, GL_QUERY_RESULT);
					double ms = Math.max(0L, end - start) / 1.0e6;
					this.smoothedMs[i] = this.seen[i] ? this.smoothedMs[i] * 0.92 + ms * 0.08 : ms;
					this.seen[i] = true;
				}
			}
			this.started[this.slot][i] = false;
			this.ended[this.slot][i] = false;
		}
	}

	/** Lines for the debug overlay. */
	public List<String> lines() {
		List<String> out = new ArrayList<>();
		StringBuilder sb = new StringBuilder("BTA Shaders GPU ms:");
		double total = 0.0;
		int perLine = 0;
		for (Section section : Section.values()) {
			int i = section.ordinal();
			if (!this.seen[i]) {
				continue;
			}
			total += this.smoothedMs[i];
			if (perLine == 4) {
				out.add(sb.toString());
				sb = new StringBuilder("  ");
				perLine = 0;
			}
			sb.append(' ').append(section.label).append(' ')
				.append(String.format(Locale.ROOT, "%.2f", this.smoothedMs[i]));
			perLine++;
		}
		if (total <= 0.0) {
			return out;
		}
		sb.append(String.format(Locale.ROOT, "  (sum %.2f)", total));
		out.add(sb.toString());
		return out;
	}

	public void delete() {
		if (this.queries != null) {
			GL41.glDeleteQueries(this.queries);
			this.queries = null;
		}
		for (int f = 0; f < LATENCY; f++) {
			for (int i = 0; i < SECTIONS; i++) {
				this.started[f][i] = false;
				this.ended[f][i] = false;
			}
		}
	}
}
