package euf.enderobelisks.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** A button in the obelisk style ({@link ObeliskUi#button}): an optional icon, an optional label, both centred. */
public class RuneButton extends Button {
	@Nullable
	private ResourceLocation icon;
	private boolean primary;
	private float progress = -1;

	public RuneButton(int x, int y, int width, int height, Component message, @Nullable ResourceLocation icon, OnPress onPress) {
		super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
		this.icon = icon;
	}

	public RuneButton primary() {
		this.primary = true;
		return this;
	}

	public void setIcon(@Nullable ResourceLocation icon) {
		this.icon = icon;
	}

	/** A charge bar along the bottom, 0..1; negative hides it. */
	public void setProgress(float progress) {
		this.progress = progress;
	}

	@Override
	protected void renderWidget(GuiGraphics context, int mouseX, int mouseY, float delta) {
		boolean hot = active && isHoveredOrFocused();
		ObeliskUi.button(context, getX(), getY(), width, height, active, hot, primary, progress);
		Font textRenderer = Minecraft.getInstance().font;
		Component message = getMessage();
		int textW = message.getString().isEmpty() ? 0 : textRenderer.width(message);
		int iconW = icon == null ? 0 : 16;
		int gap = iconW > 0 && textW > 0 ? 3 : 0;
		int x = getX() + (width - iconW - gap - textW) / 2;
		int lift = progress >= 0 ? 1 : 0;
		if (icon != null) {
			ObeliskUi.icon(context, icon, x, getY() + (height - 16) / 2 - lift, active);
			x += iconW + gap;
		}
		if (textW > 0) {
			int color = !active ? 0xFF6A6078 : hot ? 0xFFFFFFFF : ObeliskUi.TEXT;
			context.drawString(textRenderer, message, x, getY() + (height - 8) / 2 - lift, color);
		}
	}
}
