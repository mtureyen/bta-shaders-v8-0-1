package io.github.mtureyen.btashaders.config;

import io.github.mtureyen.btashaders.BtaShaders;
import io.github.mtureyen.btashaders.render.BtaShadersRenderer;
import io.github.mtureyen.btashaders.render.ShaderConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.SliderElement;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game shader settings: quality presets, pages of sliders/toggles, Apply (save + recompile).
 * Opened from Mod Menu or with Shift+F7. Edits change the in-memory config right away; Apply/Done
 * write btashaders.properties and reload the shaders, Esc discards unsaved edits.
 */
public class ShaderSettingsScreen extends Screen {
	private static final int ROW = 24;
	private static final int WIDGET_W = 150;
	private static final int WIDGET_H = 20;

	/** Remembered while the game runs. */
	private static int page = 0;
	private static int subPage = 0;

	private final List<OptionWidget> widgets = new ArrayList<>();
	private boolean dirty = false;
	private String status = "";

	public ShaderSettingsScreen(Screen parent) {
		super(parent);
		ShaderOptions.refreshDefaults();
	}

	private interface OptionWidget {
		ShaderOption option();

		ButtonElement element();

		void refresh();
	}

	@Override
	public void init() {
		this.widgets.clear();
		int center = this.width / 2;

		// Presets.
		ShaderOptions.Preset current = ShaderOptions.currentPreset();
		ShaderOptions.Preset[] presets = ShaderOptions.Preset.values();
		int presetW = 60;
		int presetX = center - (presets.length * (presetW + 4) - 4) / 2 + 22;
		for (int i = 0; i < presets.length; i++) {
			ShaderOptions.Preset preset = presets[i];
			ButtonElement b = this.add(new ButtonElement(100 + i, presetX + i * (presetW + 4), 20, presetW, WIDGET_H,
				preset == current ? "[" + preset.label + "]" : preset.label));
			b.listener = btn -> {
				ShaderOptions.applyPreset(preset);
				this.changed();
			};
		}

		// Page tabs.
		List<ShaderOptions.Page> pages = ShaderOptions.PAGES;
		page = Math.max(0, Math.min(page, pages.size() - 1));
		int tabW = Math.min(80, (this.width - 20) / pages.size() - 4);
		int tabX = center - (pages.size() * (tabW + 4) - 4) / 2;
		for (int i = 0; i < pages.size(); i++) {
			int index = i;
			ButtonElement b = this.add(new ButtonElement(200 + i, tabX + i * (tabW + 4), 44, tabW, WIDGET_H,
				pages.get(i).title()));
			b.enabled = i != page;
			b.listener = btn -> {
				page = index;
				subPage = 0;
				this.rebuild();
			};
		}

		// Options in two columns; split into sub-pages if the window is too short.
		int top = 72;
		int bottom = this.height - 58;
		int rows = Math.max(1, (bottom - top) / ROW);
		int perView = rows * 2;
		List<ShaderOption> options = pages.get(page).options();
		int subPages = Math.max(1, (options.size() + perView - 1) / perView);
		subPage = Math.max(0, Math.min(subPage, subPages - 1));
		int first = subPage * perView;
		int last = Math.min(options.size(), first + perView);
		int rowsUsed = (last - first + 1) / 2; // fill both columns evenly
		for (int i = first; i < last; i++) {
			int slot = i - first;
			int col = slot / rowsUsed;
			int row = slot % rowsUsed;
			int x = col == 0 ? center - WIDGET_W - 4 : center + 4;
			int y = top + row * ROW;
			this.addOption(options.get(i), 300 + i, x, y);
		}
		if (subPages > 1) {
			ButtonElement prev = this.add(new ButtonElement(400, center - WIDGET_W - 4, this.height - 52, 20, WIDGET_H, "<"));
			prev.enabled = subPage > 0;
			prev.listener = btn -> {
				subPage--;
				this.rebuild();
			};
			ButtonElement next = this.add(new ButtonElement(401, center + WIDGET_W + 4 - 20, this.height - 52, 20, WIDGET_H, ">"));
			next.enabled = subPage < subPages - 1;
			next.listener = btn -> {
				subPage++;
				this.rebuild();
			};
		}

		// Bottom row.
		int y = this.height - 26;
		ButtonElement reset = this.add(new ButtonElement(500, center - 154, y, 100, WIDGET_H, "Reset Page"));
		reset.listener = btn -> {
			for (ShaderOption option : pages.get(page).options()) {
				option.reset();
			}
			this.changed();
		};
		ButtonElement apply = this.add(new ButtonElement(501, center - 50, y, 100, WIDGET_H, "Apply"));
		apply.listener = btn -> this.apply();
		ButtonElement done = this.add(new ButtonElement(502, center + 54, y, 100, WIDGET_H, "Done"));
		done.listener = btn -> {
			if (this.dirty) {
				this.apply();
			}
			this.mc.displayScreen(this.parentScreen);
		};
	}

	private void addOption(ShaderOption option, int id, int x, int y) {
		switch (option.kind) {
			case FLOAT, INT -> {
				OptionSlider slider = this.add(new OptionSlider(id, x, y, option, this));
				this.widgets.add(slider);
			}
			default -> {
				OptionButton button = this.add(new OptionButton(id, x, y, option));
				button.listener = btn -> {
					option.cycle();
					button.refresh();
					this.changed();
				};
				this.widgets.add(button);
			}
		}
	}

	/** Something changed: refresh labels / preset highlight, remember to save. */
	void changed() {
		this.dirty = true;
		this.status = "Unsaved changes: Apply to see them.";
		this.rebuild();
	}

	private void rebuild() {
		this.opened(this.width, this.height);
	}

	private void apply() {
		ShaderConfig.save();
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		long start = System.nanoTime();
		if (renderer != null) {
			renderer.applySettings();
		} else {
			ShaderConfig.load();
		}
		this.dirty = false;
		this.status = String.format("Applied (shaders recompiled in %d ms).", (System.nanoTime() - start) / 1_000_000);
		BtaShaders.LOGGER.info("Shader settings applied.");
		this.rebuild();
	}

	@Override
	public void keyPressed(char eventCharacter, int eventKey, int mx, int my) {
		if (eventKey == Keyboard.KEY_ESCAPE) {
			if (this.dirty) {
				ShaderConfig.load(); // discard unsaved edits
			}
			this.mc.displayScreen(this.parentScreen);
			return;
		}
		super.keyPressed(eventCharacter, eventKey, mx, my);
	}

	@Override
	public void render(int mx, int my, float partialTick) {
		this.renderBackground();
		super.render(mx, my, partialTick);
		int center = this.width / 2;
		this.drawStringCenteredShadow(this.fontRenderer, "BTA Shaders", center, 6, 0xFFFFFF);
		int presetLabelX = center - (4 * 64 - 4) / 2 + 22 - 44;
		ShaderOptions.Preset current = ShaderOptions.currentPreset();
		this.drawStringShadow(this.fontRenderer, current == null ? "Custom:" : "Preset:", presetLabelX, 26, 0xA0A0A0);

		// Description of the hovered option.
		for (OptionWidget widget : this.widgets) {
			if (widget.element().isHovered(mx, my)) {
				String text = widget.option().description();
				if (!text.isEmpty()) {
					this.drawStringCenteredShadow(this.fontRenderer, text, center, this.height - 40, 0xE0E0A0);
				}
				return;
			}
		}
		if (!this.status.isEmpty()) {
			this.drawStringCenteredShadow(this.fontRenderer, this.status, center, this.height - 40, 0xA0E0A0);
		}
	}

	// ---------------------------------------------------------------------------------------------

	private static final class OptionButton extends ButtonElement implements OptionWidget {
		private final ShaderOption option;

		OptionButton(int id, int x, int y, ShaderOption option) {
			super(id, x, y, WIDGET_W, WIDGET_H, option.displayText());
			this.option = option;
		}

		@Override
		public ShaderOption option() {
			return this.option;
		}

		@Override
		public ButtonElement element() {
			return this;
		}

		@Override
		public void refresh() {
			this.displayString = this.option.displayText();
		}
	}

	private static final class OptionSlider extends SliderElement implements OptionWidget {
		private final ShaderOption option;
		private final ShaderSettingsScreen screen;
		private boolean moved = false;

		OptionSlider(int id, int x, int y, ShaderOption option, ShaderSettingsScreen screen) {
			super(id, x, y, WIDGET_W, WIDGET_H, option.displayText(), option.toSlider(option.value()));
			this.option = option;
			this.screen = screen;
		}

		private void update() {
			float value = this.option.fromSlider(this.sliderValue);
			if (Math.abs(value - this.option.value()) > 1e-6F) {
				this.option.set(value);
				this.moved = true;
			}
			this.refresh();
		}

		@Override
		public void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
			super.mouseDragged(mc, mouseX, mouseY);
			if (this.dragging) {
				this.update();
			}
		}

		@Override
		public boolean mouseClicked(Minecraft mc, int mouseX, int mouseY) {
			boolean clicked = super.mouseClicked(mc, mouseX, mouseY);
			if (clicked) {
				this.update();
			}
			return clicked;
		}

		@Override
		public void mouseReleased(int mouseX, int mouseY) {
			super.mouseReleased(mouseX, mouseY);
			if (this.moved) {
				this.moved = false;
				this.screen.dirty = true;
				this.screen.status = "Unsaved changes: Apply to see them.";
			}
		}

		@Override
		public ShaderOption option() {
			return this.option;
		}

		@Override
		public ButtonElement element() {
			return this;
		}

		@Override
		public void refresh() {
			this.displayString = this.option.displayText();
		}
	}

	@Override
	protected void buttonClicked(@NotNull ButtonElement button) {
	}
}
