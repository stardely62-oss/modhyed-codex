package euf.enderobelisks.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import euf.enderobelisks.EnderObelisks;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * The look shared by the obelisk screens: obsidian panels with violet edges, ender-teal accents, rune buttons, the
 * Codex-drawn icons and the drifting motes of the End. Everything is drawn with fills, so it scales with the GUI.
 */
public final class ObeliskUi {
	public static final int VOID = 0xFF07050C;
	public static final int OBSIDIAN = 0xFF120B1C;
	public static final int OBSIDIAN_LIGHT = 0xFF2A1D3D;
	public static final int EDGE = 0xFF4A3566;
	public static final int TEAL = 0xFF5FD3A0;
	public static final int TEAL_DIM = 0xFF2F6B55;
	public static final int VIOLET = 0xFFA77CE8;
	public static final int TEXT = 0xFFE8E0F4;
	public static final int MUTED = 0xFF8C80A0;
	public static final int GOLD = 0xFFFFD24A;
	public static final int RED = 0xFFF07070;
	public static final int GREEN = 0xFF8CF08C;

	public static final ResourceLocation ICON_TELEPORT = icon("teleport");
	public static final ResourceLocation ICON_PUBLIC = icon("public");
	public static final ResourceLocation ICON_PRIVATE = icon("private");
	public static final ResourceLocation ICON_FRIENDS = icon("friends");
	public static final ResourceLocation ICON_UPGRADE = icon("upgrade");
	public static final ResourceLocation ICON_COOLDOWN = icon("cooldown");
	public static final ResourceLocation ICON_RANGE = icon("range");

	private ObeliskUi() {
	}

	private static ResourceLocation icon(String name) {
		return ResourceLocation.fromNamespaceAndPath(EnderObelisks.MOD_ID, "textures/gui/icon/" + name + ".png");
	}

	/** Seconds since start, for animations. */
	public static float time() {
		return (Util.getMillis() % 3_600_000L) / 1000f;
	}

	/** A cheap stable pseudo-random number in [0, 1) for star and mote positions. */
	public static float hash(int i, int salt) {
		int h = i * 0x9E3779B1 + salt * 0x85EBCA77;
		h ^= h >>> 15;
		h *= 0x2C1B3C6D;
		h ^= h >>> 12;
		return (h & 0xFFFFFF) / (float) 0x1000000;
	}

	public static int alpha(int color, float alpha) {
		int a = (int) (((color >>> 24) & 0xFF) * Math.max(0, Math.min(1, alpha)));
		return (a << 24) | (color & 0xFFFFFF);
	}

	/** The screen behind the menu: a dark violet haze over the world with motes rising like End particles. */
	public static void background(GuiGraphics context, int width, int height) {
		context.fillGradient(0, 0, width, height, 0xE00B0614, 0xF0050309);
		float t = time();
		for (int i = 0; i < 46; i++) {
			float speed = 6 + hash(i, 1) * 14;
			int x = (int) (hash(i, 2) * width + Math.sin(t * 0.7 + i) * 5);
			int y = (int) (height + 10 - (t * speed + hash(i, 3) * (height + 20)) % (height + 20));
			float glow = 0.35f + 0.35f * (float) Math.sin(t * 2 + i * 1.7);
			int color = alpha(i % 3 == 0 ? TEAL : VIOLET, glow);
			int s = hash(i, 4) > 0.75f ? 2 : 1;
			context.fill(x, y, x + s, y + s, color);
		}
	}

	/** An obsidian slab: dark body, a lighter rim and a black outline. */
	public static void panel(GuiGraphics context, int x0, int y0, int x1, int y1) {
		context.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF030205);
		context.fillGradient(x0, y0, x1, y1, 0xF4160E22, 0xF40B0712);
		outline(context, x0, y0, x1, y1, OBSIDIAN_LIGHT);
	}

	/** The map's frame: a carved obsidian border with a glowing ender gem in every corner. */
	public static void frame(GuiGraphics context, int x0, int y0, int x1, int y1) {
		context.fill(x0 - 4, y0 - 4, x1 + 4, y1 + 4, 0xFF030205);
		context.fill(x0 - 3, y0 - 3, x1 + 3, y1 + 3, OBSIDIAN_LIGHT);
		context.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, OBSIDIAN);
		outline(context, x0 - 1, y0 - 1, x1 + 1, y1 + 1, EDGE);
		float pulse = 0.65f + 0.35f * (float) Math.sin(time() * 2.2);
		for (int[] c : new int[][] {{x0 - 2, y0 - 2}, {x1 + 1, y0 - 2}, {x0 - 2, y1 + 1}, {x1 + 1, y1 + 1}}) {
			diamond(context, c[0], c[1], 5, 0xFF030205);
			diamond(context, c[0], c[1], 4, EDGE);
			diamond(context, c[0], c[1], 2, alpha(TEAL, pulse));
			context.fill(c[0], c[1] - 1, c[0] + 1, c[1], 0xC0FFFFFF);
		}
	}

	public static void outline(GuiGraphics context, int x0, int y0, int x1, int y1, int color) {
		context.fill(x0, y0, x1, y0 + 1, color);
		context.fill(x0, y1 - 1, x1, y1, color);
		context.fill(x0, y0, x0 + 1, y1, color);
		context.fill(x1 - 1, y0, x1, y1, color);
	}

	public static void diamond(GuiGraphics context, int x, int y, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = r - Math.abs(dy);
			context.fill(x - half, y + dy, x + half + 1, y + dy + 1, color);
		}
	}

	/** A thin line that fades out at both ends, with a small gem in the middle. */
	public static void divider(GuiGraphics context, int x0, int x1, int y) {
		int mid = (x0 + x1) / 2;
		int half = (x1 - x0) / 2;
		int steps = 12;
		for (int i = 0; i < steps; i++) {
			int a = half * i / steps;
			int b = half * (i + 1) / steps;
			int color = alpha(EDGE, (i + 1) / (float) steps);
			context.fill(x0 + a, y, x0 + b, y + 1, color);
			context.fill(x1 - b, y, x1 - a, y + 1, color);
		}
		diamond(context, mid, y, 3, 0xFF030205);
		diamond(context, mid, y, 2, VIOLET);
	}

	/**
	 * A rune button: obsidian with a violet rim that lights up ender teal under the mouse. The primary one glows and
	 * pulses while it can be pressed; progress (0..1, or negative for none) fills it like a charging rune.
	 */
	public static void button(GuiGraphics context, int x, int y, int w, int h, boolean active, boolean hovered, boolean primary,
			float progress) {
		int x1 = x + w;
		int y1 = y + h;
		context.fill(x, y, x1, y1, 0xFF030205);
		if (!active) {
			context.fill(x + 1, y + 1, x1 - 1, y1 - 1, 0xFF0E0A14);
			outline(context, x + 1, y + 1, x1 - 1, y1 - 1, 0xFF241A30);
		} else if (primary) {
			float pulse = 0.5f + 0.5f * (float) Math.sin(time() * 3);
			context.fillGradient(x + 1, y + 1, x1 - 1, y1 - 1, hovered ? 0xFF5A3E86 : 0xFF3E2A60, hovered ? 0xFF2C1E46 : 0xFF1E1432);
			outline(context, x + 1, y + 1, x1 - 1, y1 - 1, alpha(TEAL, 0.55f + 0.45f * (hovered ? 1 : pulse)));
			context.fill(x + 2, y + 2, x1 - 2, y + 3, 0x30FFFFFF);
		} else {
			context.fillGradient(x + 1, y + 1, x1 - 1, y1 - 1, hovered ? 0xFF33244A : 0xFF231832, hovered ? 0xFF1E1530 : 0xFF150F20);
			outline(context, x + 1, y + 1, x1 - 1, y1 - 1, hovered ? TEAL : EDGE);
			context.fill(x + 2, y + 2, x1 - 2, y + 3, 0x18FFFFFF);
		}
		if (progress >= 0) {
			int fill = (int) ((w - 4) * Math.min(1, progress));
			context.fill(x + 2, y1 - 4, x + 2 + fill, y1 - 2, alpha(TEAL, 0.8f));
			context.fill(x + 2 + fill, y1 - 4, x1 - 2, y1 - 2, 0x40000000);
		}
	}

	/** One of the Codex icons (32x32 textures drawn in a 16x16 cell); dimmed when the thing it stands for is unavailable. */
	public static void icon(GuiGraphics context, ResourceLocation icon, int x, int y, boolean bright) {
		RenderSystem.enableBlend();
		if (!bright) {
			RenderSystem.setShaderColor(0.45f, 0.42f, 0.5f, 1f);
		}
		context.blit(icon, x, y, 16, 16, 0, 0, 32, 32, 32, 32);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
	}

	/** An icon at half size, for inline use next to text. */
	public static void smallIcon(GuiGraphics context, ResourceLocation icon, int x, int y) {
		context.pose().pushPose();
		context.pose().translate(x, y, 0);
		context.pose().scale(0.5f, 0.5f, 1f);
		icon(context, icon, 0, 0, true);
		context.pose().popPose();
	}

	/**
	 * Small pixel shapes drawn from rows of characters: '#' the main colour, '+' a highlight, anything else empty. With
	 * an outline colour the shape first gets a one-pixel border.
	 */
	public static void pixels(GuiGraphics context, String[] rows, int x, int y, int main, int highlight, int outline) {
		if (outline != 0) {
			for (int[] d : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
				pixelPass(context, rows, x + d[0], y + d[1], outline, outline);
			}
		}
		pixelPass(context, rows, x, y, main, highlight);
	}

	private static void pixelPass(GuiGraphics context, String[] rows, int x, int y, int main, int highlight) {
		for (int r = 0; r < rows.length; r++) {
			String row = rows[r];
			for (int c = 0; c < row.length(); c++) {
				char ch = row.charAt(c);
				if (ch == '#' || ch == '+') {
					context.fill(x + c, y + r, x + c + 1, y + r + 1, ch == '+' ? highlight : main);
				}
			}
		}
	}
}
