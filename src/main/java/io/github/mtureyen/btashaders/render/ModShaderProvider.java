package io.github.mtureyen.btashaders.render;

import io.github.mtureyen.btashaders.BtaShaders;
import net.minecraft.client.render.shader.ShaderProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Loads our GLSL sources. Lookup order for every file (including includes):
 * <ol>
 *   <li>dev directory, if {@code -Dbtashaders.devShaderDir} is set (runClient sets it to src/), so
 *       edits + F7 apply live;</li>
 *   <li>the selected shaderpack folder ({@link ShaderConfig});</li>
 *   <li>the copy bundled in the mod jar.</li>
 * </ol>
 * Supports {@code #include "lib/file.glsl"} (relative to the shader root) and injects the
 * {@code define.*} settings from the config right after the {@code #version} line.
 */
public class ModShaderProvider implements ShaderProvider {
	private static final Path DEV_DIR = devDir();

	private final String dir;

	public ModShaderProvider(String dir) {
		this.dir = dir.endsWith("/") ? dir : dir + "/";
	}

	private static Path devDir() {
		String prop = System.getProperty("btashaders.devShaderDir");
		if (prop == null || prop.isBlank()) {
			return null;
		}
		Path path = Path.of(prop);
		if (Files.isDirectory(path)) {
			BtaShaders.LOGGER.info("Dev shader directory: {} (F7 reloads from here)", path);
			return path;
		}
		BtaShaders.LOGGER.warn("btashaders.devShaderDir {} is not a directory; using bundled shaders.", path);
		return null;
	}

	@Override
	public String getShaderSource(String name) {
		// Post passes share one fullscreen vertex shader unless they ship their own.
		if (name.endsWith(".vsh") && !this.exists(name)) {
			name = "fullscreen.vsh";
		}
		String source = this.readRaw(name);
		if (source == null) {
			return null;
		}
		StringBuilder out = new StringBuilder(source.length() + 1024);
		if (!this.expand(source, name, out, new HashSet<>())) {
			return null;
		}
		return injectDefines(out.toString(), ShaderConfig.defines());
	}

	private boolean expand(String source, String name, StringBuilder out, Set<String> stack) {
		if (!stack.add(name)) {
			BtaShaders.LOGGER.error("Recursive #include of {}", name);
			return false;
		}
		for (String line : source.split("\r?\n", -1)) {
			String trimmed = line.trim();
			if (trimmed.startsWith("#include")) {
				int first = trimmed.indexOf('"');
				int last = trimmed.lastIndexOf('"');
				if (first < 0 || last <= first) {
					BtaShaders.LOGGER.error("Malformed #include in {}: {}", name, trimmed);
					return false;
				}
				String include = trimmed.substring(first + 1, last);
				String included = this.readRaw(include);
				if (included == null) {
					BtaShaders.LOGGER.error("{} includes missing file {}", name, include);
					return false;
				}
				out.append("// begin ").append(include).append('\n');
				if (!this.expand(included, include, out, stack)) {
					return false;
				}
				out.append("// end ").append(include).append('\n');
			} else {
				out.append(line).append('\n');
			}
		}
		stack.remove(name);
		return true;
	}

	private static String injectDefines(String source, Map<String, String> defines) {
		if (defines.isEmpty()) {
			return source;
		}
		int versionLine = source.indexOf("#version");
		int insertAt = versionLine < 0 ? 0 : source.indexOf('\n', versionLine) + 1;
		StringBuilder defs = new StringBuilder();
		for (Map.Entry<String, String> e : defines.entrySet()) {
			defs.append("#define ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
		}
		return source.substring(0, insertAt) + defs + source.substring(insertAt);
	}

	private boolean exists(String name) {
		for (Path root : new Path[]{DEV_DIR, ShaderConfig.packDir()}) {
			if (root != null && Files.isRegularFile(root.resolve(name))) {
				return true;
			}
		}
		return BtaShaders.class.getResource(this.dir + name) != null;
	}

	private String readRaw(String name) {
		for (Path root : new Path[]{DEV_DIR, ShaderConfig.packDir()}) {
			if (root == null) {
				continue;
			}
			Path file = root.resolve(name);
			if (Files.isRegularFile(file)) {
				try {
					return Files.readString(file, StandardCharsets.UTF_8);
				} catch (IOException e) {
					BtaShaders.LOGGER.error("Failed to read shader {}", file, e);
				}
			}
		}
		String path = this.dir + name;
		try (InputStream in = BtaShaders.class.getResourceAsStream(path)) {
			if (in == null) {
				BtaShaders.LOGGER.error("Shader source {} not found", path);
				return null;
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			BtaShaders.LOGGER.error("Failed to read shader source {}", path, e);
			return null;
		}
	}
}
