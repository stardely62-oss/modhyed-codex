package euf.enderobelisks.client.screen;

import euf.enderobelisks.ObeliskService;
import com.mojang.math.Axis;
import euf.enderobelisks.ObeliskColors;
import euf.enderobelisks.ObeliskTiers;
import euf.enderobelisks.client.map.MapCache;
import euf.enderobelisks.client.map.MapScanner;
import euf.enderobelisks.client.map.MapTiles;
import euf.enderobelisks.net.MenuData;
import euf.enderobelisks.net.MenuData.Entry;
import euf.enderobelisks.net.ObeliskPackets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import euf.enderobelisks.platform.Network.Client;
import euf.enderobelisks.platform.Network;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The obelisk menu: an explored map of the dimension in a carved obsidian frame, with every obelisk you may use, the
 * glowing range circle, a ley line to the chosen destination and your position; on the right an obsidian panel with
 * this obelisk (name, tier gems, access, colour, upgrade), the chosen destination and a list sorted by distance.
 * Drag to pan, scroll to zoom, click a marker or a list row to select. Icons say what the buttons do; the words are
 * in their tooltips.
 */
public class ObeliskScreen extends Screen {
	private static final int PANEL_W = 172;
	private static final int PAD = 8;
	private static final int ROW_H = 12;
	private static final double MIN_SCALE = 0.01;
	private static final double MAX_SCALE = 8.0;

	// Panel rows, top to bottom
	private static final int Y_NAME = 10;
	private static final int Y_TIER = 37;
	private static final int Y_ACCESS = 48;
	private static final int Y_UPGRADE = 76;
	private static final int Y_DIVIDER1 = 104;
	private static final int Y_CARD = 112;
	private static final int Y_TELEPORT = 166;
	private static final int Y_DIVIDER2 = 196;
	private static final int Y_LIST = 204;

	private static final int SWATCH_R = 4;
	private static final int SWATCH_STEP = 11;

	/** A marker on the map: a little obelisk with a glowing eye. */
	private static final String[] MARKER = {
			"..#..",
			".###.",
			".#+#.",
			".###.",
			".###.",
			"#####",
			"#####"};
	/** Your position: an arrow that turns with you. */
	private static final String[] ARROW = {
			"..#..",
			".###.",
			".###.",
			"#####",
			"##.##",
			"#...#"};

	private MenuData data;
	private long dataTime;
	@Nullable
	private UUID selected;
	private double centerX;
	private double centerZ;
	private double scale;
	private boolean dragging;
	private int listScroll;

	private int mapX0;
	private int mapY0;
	private int mapX1;
	private int mapY1;
	private int panelX;

	private EditBox nameField;
	private RuneButton renameButton;
	private RuneButton accessButton;
	private RuneButton friendsButton;
	private RuneButton upgradeButton;
	private RuneButton teleportButton;

	public ObeliskScreen(MenuData data) {
		super(Component.translatable("ender_obelisks.menu.title"));
		this.data = data;
		this.centerX = data.sourcePos().getX() + 0.5;
		this.centerZ = data.sourcePos().getZ() + 0.5;
	}

	public boolean isFor(BlockPos sourcePos) {
		return data.sourcePos().equals(sourcePos);
	}

	public MenuData getData() {
		return data;
	}

	public void update(MenuData newData) {
		this.data = newData;
		this.dataTime = worldTime();
		if (selected != null && newData.entries().stream().noneMatch(e -> e.id().equals(selected))) {
			selected = null;
		}
		if (nameField != null && !nameField.isFocused()) {
			nameField.setValue(newData.name());
		}
		refreshButtons();
	}

	@Override
	protected void init() {
		panelX = width - PANEL_W;
		mapX0 = PAD + 4;
		mapY0 = 26;
		mapX1 = panelX - PAD - 4;
		mapY1 = height - PAD - 4;
		if (scale == 0) {
			int size = Math.min(mapX1 - mapX0, mapY1 - mapY0);
			scale = Mth.clamp(size / (data.range() * 2.3), MIN_SCALE, MAX_SCALE);
		}
		dataTime = worldTime();

		int x = panelX + PAD;
		int w = PANEL_W - 2 * PAD;
		String typed = nameField != null ? nameField.getValue() : data.name();
		nameField = new EditBox(font, x + 6, Y_NAME + 5, w - 30, 10, Component.translatable("ender_obelisks.menu.name"));
		nameField.setMaxLength(ObeliskService.MAX_NAME_LENGTH);
		nameField.setBordered(false);
		nameField.setTextColor(ObeliskUi.TEXT);
		nameField.setTextColorUneditable(ObeliskUi.TEXT);
		nameField.setValue(typed);
		addRenderableWidget(nameField);
		renameButton = addRenderableWidget(new RuneButton(x + w - 19, Y_NAME + 1, 18, 16, Component.literal("✔"), null, b -> rename()));
		renameButton.setTooltip(Tooltip.create(Component.translatable("ender_obelisks.menu.rename")));
		accessButton = addRenderableWidget(new RuneButton(x, Y_ACCESS, 22, 20, Component.empty(), ObeliskUi.ICON_PUBLIC,
				b -> send(ObeliskPackets.TOGGLE_PUBLIC, null)));
		friendsButton = addRenderableWidget(new RuneButton(x + 25, Y_ACCESS, 36, 20, Component.empty(), ObeliskUi.ICON_FRIENDS,
				b -> minecraft.setScreen(new FriendsScreen(this))));
		upgradeButton = addRenderableWidget(new RuneButton(x + w - 58, Y_UPGRADE, 58, 20, Component.empty(), ObeliskUi.ICON_UPGRADE, b -> upgrade()));
		teleportButton = addRenderableWidget(new RuneButton(x, Y_TELEPORT, w, 24, Component.translatable("ender_obelisks.menu.teleport"),
				ObeliskUi.ICON_TELEPORT, b -> teleportSelected()).primary());
		refreshButtons();
	}

	private void refreshButtons() {
		if (teleportButton == null) {
			return;
		}
		boolean owner = data.isOwner();
		nameField.setEditable(owner);
		renameButton.visible = owner && !nameField.getValue().equals(data.name());
		accessButton.active = owner;
		accessButton.setIcon(data.isPublic() ? ObeliskUi.ICON_PUBLIC : ObeliskUi.ICON_PRIVATE);
		accessButton.setTooltip(Tooltip.create(Component.translatable(data.isPublic() ? "ender_obelisks.menu.access_public" : "ender_obelisks.menu.access_private")));
		friendsButton.visible = owner;
		friendsButton.setMessage(Component.literal(String.valueOf(data.friends().size())));
		friendsButton.setTooltip(Tooltip.create(Component.translatable("ender_obelisks.menu.friends", data.friends().size())));
		List<ItemStack> cost = data.nextCost();
		upgradeButton.visible = !data.maxTier();
		upgradeButton.active = owner && !data.maxTier() && (creative() || cost.stream().allMatch(s -> have(s) >= s.getCount()));
		upgradeButton.setMessage(Component.literal(String.valueOf(data.nextRange())));
		upgradeButton.setTooltip(Tooltip.create(Component.translatable("ender_obelisks.menu.next_tier", data.nextRange())));
		Entry target = selectedEntry();
		teleportButton.active = target != null && !target.id().equals(data.sourceId()) && inRange(target) && cooldownLeft() == 0;
		teleportButton.setProgress(cooldownLeft() > 0 && data.cooldownTotal() > 0 ? 1 - cooldownLeft() / (float) data.cooldownTotal() : -1);
		teleportButton.setTooltip(target == null || teleportButton.active ? null : Tooltip.create(statusLine(target)));
	}

	@Override
	public void tick() {
		
		refreshButtons();
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (nameField.isFocused() && (keyCode == 257 || keyCode == 335)) { // Enter
			rename();
			nameField.setFocused(false);
			setFocused(null);
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	// ---- actions (public so the CI harness can drive them) ----

	public void select(@Nullable UUID id) {
		selected = id;
		refreshButtons();
	}

	public void teleportSelected() {
		Entry target = selectedEntry();
		if (target != null) {
			FriendlyByteBuf buf = Network.create();
			buf.writeBlockPos(data.sourcePos());
			buf.writeUUID(target.id());
			Client.send(ObeliskPackets.TELEPORT, buf);
			onClose();
		}
	}

	public void upgrade() {
		send(ObeliskPackets.UPGRADE, null);
	}

	private void rename() {
		if (data.isOwner()) {
			send(ObeliskPackets.RENAME, nameField.getValue());
		}
	}

	private void send(ResourceLocation channel, @Nullable String text) {
		FriendlyByteBuf buf = Network.create();
		buf.writeBlockPos(data.sourcePos());
		if (text != null) {
			buf.writeUtf(text);
		}
		Client.send(channel, buf);
	}

	// ---- helpers ----

	private long worldTime() {
		return minecraft != null && minecraft.level != null ? minecraft.level.getGameTime() : 0;
	}

	private int cooldownLeft() {
		return (int) Math.max(0, data.cooldownTicks() - (worldTime() - dataTime));
	}

	private boolean creative() {
		return minecraft != null && minecraft.player != null && minecraft.player.getAbilities().instabuild;
	}

	private int have(ItemStack stack) {
		return minecraft == null || minecraft.player == null ? 0 : minecraft.player.getInventory().countItem(stack.getItem());
	}

	private double distance(Entry entry) {
		return Math.sqrt(entry.pos().distSqr(data.sourcePos()));
	}

	private boolean sameDimension(Entry entry) {
		return entry.dimension().equals(data.dimension());
	}

	/** Same dimension: within range. Another dimension: only if the server allows it from this tier. */
	private boolean inRange(Entry entry) {
		if (!sameDimension(entry)) {
			return data.crossDimension() && data.tier() >= data.crossDimensionMinTier();
		}
		return distance(entry) <= data.range();
	}

	private static Component dimensionName(ResourceLocation id) {
		String key = "ender_obelisks.dim." + id.getPath();
		return Component.translatableWithFallback(key, id.getPath());
	}

	@Nullable
	private Entry selectedEntry() {
		return selected == null ? null : data.entries().stream().filter(e -> e.id().equals(selected)).findFirst().orElse(null);
	}

	private List<Entry> sortedEntries() {
		List<Entry> list = new ArrayList<>(data.entries());
		// This dimension first, nearest first; then the other dimensions
		list.sort(Comparator.comparing((Entry e) -> !sameDimension(e)).thenComparingDouble(this::distance));
		return list;
	}

	private int colorOf(Entry entry) {
		if (entry.id().equals(data.sourceId())) {
			return ObeliskUi.GOLD;
		}
		if (!inRange(entry)) {
			return 0xFF6C6478;
		}
		return 0xFF000000 | ObeliskColors.rgb(entry.color());
	}

	private double mapCenterX() {
		return (mapX0 + mapX1) / 2.0;
	}

	private double mapCenterY() {
		return (mapY0 + mapY1) / 2.0;
	}

	private int toScreenX(double worldX) {
		return (int) Math.round(mapCenterX() + (worldX - centerX) * scale);
	}

	private int toScreenY(double worldZ) {
		return (int) Math.round(mapCenterY() + (worldZ - centerZ) * scale);
	}

	private boolean inMap(double x, double y) {
		return x >= mapX0 && x < mapX1 && y >= mapY0 && y < mapY1;
	}

	@Nullable
	private Entry entryAt(double mouseX, double mouseY) {
		Entry best = null;
		double bestDist = 7 * 7;
		for (Entry entry : data.entries()) {
			if (!sameDimension(entry)) {
				continue;
			}
			double dx = toScreenX(entry.pos().getX() + 0.5) - mouseX;
			double dy = toScreenY(entry.pos().getZ() + 0.5) - mouseY - 2;
			if (dx * dx + dy * dy < bestDist) {
				bestDist = dx * dx + dy * dy;
				best = entry;
			}
		}
		return best;
	}

	private int listRows() {
		return Math.max(0, (height - PAD - Y_LIST) / ROW_H);
	}

	private int swatchX(int i) {
		return panelX + PAD + 70 + i * SWATCH_STEP;
	}

	private static final int SWATCH_Y = Y_ACCESS + 10;

	// ---- drawing ----

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
		ObeliskUi.background(context, width, height);
		drawTitle(context);

		ObeliskUi.frame(context, mapX0, mapY0, mapX1, mapY1);
		context.fill(mapX0, mapY0, mapX1, mapY1, ObeliskUi.VOID);
		context.enableScissor(mapX0, mapY0, mapX1, mapY1);
		// Thousands of one-pixel fills: batch them into one draw call each
		context.drawManaged(() -> drawStars(context));
		drawTiles(context);
		context.drawManaged(() -> {
			drawRangeCircle(context);
			drawLeyLine(context);
		});
		drawMarkers(context, mouseX, mouseY);
		drawPlayer(context);
		context.drawManaged(() -> drawVignette(context));
		context.disableScissor();
		drawScaleBar(context);
		if (inMap(mouseX, mouseY)) {
			double wx = centerX + (mouseX - mapCenterX()) / scale;
			double wz = centerZ + (mouseY - mapCenterY()) / scale;
			String coords = String.format("X %d  Z %d", (int) Math.floor(wx), (int) Math.floor(wz));
			context.fill(mapX0 + 3, mapY1 - 14, mapX0 + 9 + font.width(coords), mapY1 - 3, 0xA0000000);
			context.drawString(font, coords, mapX0 + 6, mapY1 - 12, ObeliskUi.MUTED);
		}

		drawPanel(context, mouseX, mouseY);
		super.render(context, mouseX, mouseY, delta);
		drawPanelTooltips(context, mouseX, mouseY);

		Entry hovered = inMap(mouseX, mouseY) ? entryAt(mouseX, mouseY) : null;
		if (hovered != null) {
			context.renderComponentTooltip(font, describe(hovered), mouseX, mouseY);
		}
	}

	/** The menu's title above the map, between two fading lines with gems at their ends. */
	private void drawTitle(GuiGraphics context) {
		Component title = Component.translatable("ender_obelisks.menu.title");
		int cx = (mapX0 + mapX1) / 2;
		int tw = font.width(title);
		int y = 9;
		context.drawString(font, title, cx - tw / 2, y, ObeliskUi.VIOLET);
		int len = Math.min(120, (mapX1 - mapX0 - tw) / 2 - 16);
		if (len > 10) {
			ObeliskUi.divider(context, cx - tw / 2 - 8 - len, cx - tw / 2 - 8, y + 3);
			ObeliskUi.divider(context, cx + tw / 2 + 8, cx + tw / 2 + 8 + len, y + 3);
		}
	}

	/** The void outside the explored land: faint stars that drift slowly as the map pans and twinkle. */
	private void drawStars(GuiGraphics context) {
		int w = mapX1 - mapX0;
		int h = mapY1 - mapY0;
		float t = ObeliskUi.time();
		double px = centerX * scale * 0.15;
		double pz = centerZ * scale * 0.15;
		int count = w * h / 400;
		for (int i = 0; i < count; i++) {
			int x = mapX0 + (int) Math.floorMod((long) (ObeliskUi.hash(i, 11) * w - px), (long) w);
			int y = mapY0 + (int) Math.floorMod((long) (ObeliskUi.hash(i, 12) * h - pz), (long) h);
			float twinkle = 0.25f + 0.55f * (0.5f + 0.5f * (float) Math.sin(t * (0.8 + ObeliskUi.hash(i, 13) * 2) + i));
			int color = i % 7 == 0 ? ObeliskUi.TEAL : i % 3 == 0 ? ObeliskUi.VIOLET : 0xFFFFFFFF;
			context.fill(x, y, x + 1, y + 1, ObeliskUi.alpha(color, twinkle));
		}
	}

	private void drawTiles(GuiGraphics context) {
		MapCache cache = MapScanner.cache();
		if (cache == null) {
			return;
		}
		double halfW = (mapX1 - mapX0) / 2.0 / scale;
		double halfH = (mapY1 - mapY0) / 2.0 / scale;
		int tx0 = Math.floorDiv((int) Math.floor(centerX - halfW), MapTiles.TILE_BLOCKS);
		int tx1 = Math.floorDiv((int) Math.floor(centerX + halfW), MapTiles.TILE_BLOCKS);
		int tz0 = Math.floorDiv((int) Math.floor(centerZ - halfH), MapTiles.TILE_BLOCKS);
		int tz1 = Math.floorDiv((int) Math.floor(centerZ + halfH), MapTiles.TILE_BLOCKS);
		for (int tz = tz0; tz <= tz1; tz++) {
			for (int tx = tx0; tx <= tx1; tx++) {
				ResourceLocation texture = MapTiles.get(cache, tx, tz);
				if (texture == null) {
					continue;
				}
				context.pose().pushPose();
				context.pose().translate(mapCenterX() + (tx * MapTiles.TILE_BLOCKS - centerX) * scale,
						mapCenterY() + (tz * MapTiles.TILE_BLOCKS - centerZ) * scale, 0);
				context.pose().scale((float) scale, (float) scale, 1.0f);
				context.blit(texture, 0, 0, 0, 0, MapTiles.TILE_BLOCKS, MapTiles.TILE_BLOCKS, MapTiles.TILE_BLOCKS, MapTiles.TILE_BLOCKS);
				context.pose().popPose();
			}
		}
	}

	/** The range: a faint teal aura inside and a ring with pulses of light running around it. */
	private void drawRangeCircle(GuiGraphics context) {
		double cx = mapCenterX() + (data.sourcePos().getX() + 0.5 - centerX) * scale;
		double cy = mapCenterY() + (data.sourcePos().getZ() + 0.5 - centerZ) * scale;
		double r = data.range() * scale;
		int yFrom = (int) Math.max(mapY0, Math.ceil(cy - r));
		int yTo = (int) Math.min(mapY1, Math.floor(cy + r));
		for (int y = yFrom; y < yTo; y++) {
			double half = Math.sqrt(Math.max(0, r * r - (y + 0.5 - cy) * (y + 0.5 - cy)));
			int x0 = (int) Math.max(mapX0, Math.round(cx - half));
			int x1 = (int) Math.min(mapX1, Math.round(cx + half));
			if (x1 > x0) {
				context.fill(x0, y, x1, y + 1, 0x125FD3A0);
			}
		}
		float t = ObeliskUi.time();
		int steps = (int) Mth.clamp(r * Math.PI, 64, 1400);
		for (int i = 0; i < steps; i++) {
			double a = i * Math.PI * 2 / steps;
			int x = (int) Math.round(cx + Math.cos(a) * r);
			int y = (int) Math.round(cy + Math.sin(a) * r);
			if (!inMap(x, y)) {
				continue;
			}
			float wave = 0.5f + 0.5f * (float) Math.sin(a * 10 - t * 1.6);
			context.fill(x - 1, y - 1, x + 1, y + 1, ObeliskUi.alpha(ObeliskUi.TEAL, 0.35f + 0.6f * wave * wave));
		}
	}

	/** A dotted line of light flowing from this obelisk to the chosen one: teal if it can be reached, dim red if not. */
	private void drawLeyLine(GuiGraphics context) {
		Entry target = selectedEntry();
		if (target == null || !sameDimension(target) || target.id().equals(data.sourceId())) {
			return;
		}
		double x0 = toScreenX(data.sourcePos().getX() + 0.5);
		double y0 = toScreenY(data.sourcePos().getZ() + 0.5);
		double x1 = toScreenX(target.pos().getX() + 0.5);
		double y1 = toScreenY(target.pos().getZ() + 0.5);
		double length = Math.hypot(x1 - x0, y1 - y0);
		if (length < 8) {
			return;
		}
		int color = inRange(target) ? ObeliskUi.TEAL : ObeliskUi.RED;
		double offset = (ObeliskUi.time() * 18) % 6;
		for (double d = 6 + offset; d < length - 6; d += 6) {
			int x = (int) Math.round(x0 + (x1 - x0) * d / length);
			int y = (int) Math.round(y0 + (y1 - y0) * d / length);
			float fade = (float) Math.min(1, Math.min(d, length - d) / 20);
			context.fill(x - 1, y - 1, x + 1, y + 1, ObeliskUi.alpha(color, 0.85f * fade));
		}
	}

	private void drawMarkers(GuiGraphics context, int mouseX, int mouseY) {
		Entry hovered = entryAt(mouseX, mouseY);
		float t = ObeliskUi.time();
		// Labels go in order of importance (here, chosen, under the mouse, the rest); one that would overlap a label
		// already placed is left out
		List<Entry> order = new ArrayList<>(data.entries());
		order.sort(Comparator.comparingInt((Entry e) -> e.id().equals(data.sourceId()) ? 0 : e.id().equals(selected) ? 1 : e == hovered ? 2 : 3));
		List<int[]> placed = new ArrayList<>();
		for (Entry entry : order) {
			if (!sameDimension(entry)) {
				continue; // other dimensions are in the list, not on this map
			}
			int x = toScreenX(entry.pos().getX() + 0.5);
			int y = toScreenY(entry.pos().getZ() + 0.5);
			if (!inMap(x, y) && !inMap(x, y + 10)) {
				continue;
			}
			int color = colorOf(entry);
			boolean here = entry.id().equals(data.sourceId());
			boolean isSelected = entry.id().equals(selected);
			if (here) {
				// Rings of light spreading from the obelisk you stand at
				for (int k = 0; k < 2; k++) {
					float phase = (t * 0.6f + k * 0.5f) % 1f;
					ring(context, x, y, 4 + phase * 12, ObeliskUi.alpha(ObeliskUi.GOLD, 0.7f * (1 - phase)));
				}
			}
			if (isSelected) {
				// Four sparks circling the chosen obelisk
				for (int k = 0; k < 4; k++) {
					double a = t * 2 + k * Math.PI / 2;
					ObeliskUi.diamond(context, (int) Math.round(x + Math.cos(a) * 9), (int) Math.round(y + Math.sin(a) * 9), 1, 0xFFFFFFFF);
				}
			}
			int highlight = entry == hovered || isSelected ? 0xFFFFFFFF : 0xFFCFFFF0;
			ObeliskUi.pixels(context, MARKER, x - 2, y - 4, color, highlight, 0xFF000000);
			boolean label = isSelected || entry == hovered || here || scale >= 0.12;
			if (label) {
				int tw = font.width(entry.name());
				int[] box = {x - tw / 2 - 3, y + 6, x + tw / 2 + 3, y + 17};
				if (placed.stream().noneMatch(b -> b[0] < box[2] && box[0] < b[2] && b[1] < box[3] && box[1] < b[3])) {
					placed.add(box);
					context.fill(box[0], box[1], box[2], box[3], 0x90000000);
					context.drawCenteredString(font, entry.name(), x, y + 8, color);
				}
			}
		}
	}

	private static void ring(GuiGraphics context, int cx, int cy, float r, int color) {
		int steps = Math.max(16, (int) (r * 6));
		for (int i = 0; i < steps; i++) {
			double a = i * Math.PI * 2 / steps;
			int x = (int) Math.round(cx + Math.cos(a) * r);
			int y = (int) Math.round(cy + Math.sin(a) * r);
			context.fill(x, y, x + 1, y + 1, color);
		}
	}

	private void drawPlayer(GuiGraphics context) {
		if (minecraft == null || minecraft.player == null) {
			return;
		}
		int x = toScreenX(minecraft.player.getX());
		int y = toScreenY(minecraft.player.getZ());
		context.pose().pushPose();
		context.pose().translate(x + 0.5f, y + 0.5f, 0);
		// Yaw 0 looks south (+Z, down on the map); the arrow is drawn pointing up
		context.pose().mulPose(Axis.ZP.rotationDegrees(minecraft.player.getYRot() + 180));
		ObeliskUi.pixels(context, ARROW, -2, -3, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000);
		context.pose().popPose();
	}

	/** Darkens the map towards its frame, so the land fades into the void. */
	private void drawVignette(GuiGraphics context) {
		int depth = 16;
		context.fillGradient(mapX0, mapY0, mapX1, mapY0 + depth, 0xB0000000, 0x00000000);
		context.fillGradient(mapX0, mapY1 - depth, mapX1, mapY1, 0x00000000, 0xB0000000);
		for (int i = 0; i < depth; i++) {
			int color = ObeliskUi.alpha(0xB0000000, 1 - i / (float) depth);
			context.fill(mapX0 + i, mapY0, mapX0 + i + 1, mapY1, color);
			context.fill(mapX1 - i - 1, mapY0, mapX1 - i, mapY1, color);
		}
	}

	/** A short bar in the bottom right corner showing how long a round number of blocks is at this zoom. */
	private void drawScaleBar(GuiGraphics context) {
		int[] lengths = {5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000};
		int blocks = lengths[lengths.length - 1];
		for (int b : lengths) {
			if (b * scale >= 36) {
				blocks = b;
				break;
			}
		}
		int px = (int) Math.round(blocks * scale);
		int x1 = mapX1 - 8;
		int x0 = x1 - px;
		int y = mapY1 - 8;
		context.fill(x0, y, x1, y + 1, ObeliskUi.MUTED);
		context.fill(x0, y - 3, x0 + 1, y + 1, ObeliskUi.MUTED);
		context.fill(x1 - 1, y - 3, x1, y + 1, ObeliskUi.MUTED);
		String label = Component.translatable("ender_obelisks.menu.metres", blocks).getString();
		context.drawString(font, label, (x0 + x1 - font.width(label)) / 2, y - 11, ObeliskUi.MUTED);
	}

	private void drawPanel(GuiGraphics context, int mouseX, int mouseY) {
		int x = panelX + PAD;
		int w = PANEL_W - 2 * PAD;
		int right = x + w;
		ObeliskUi.panel(context, panelX + 2, 2, width - 2, height - 2);
		context.fill(panelX + 2, 2, panelX + 3, height - 2, ObeliskUi.EDGE);

		// Name plate (the text field draws itself on top)
		context.fill(x, Y_NAME, right, Y_NAME + 18, 0xFF030205);
		ObeliskUi.outline(context, x, Y_NAME, right, Y_NAME + 18, nameField.isFocused() ? ObeliskUi.TEAL : ObeliskUi.OBSIDIAN_LIGHT);

		// Tier: five gems, lit up to the current tier; the range on the right
		for (int i = 0; i <= ObeliskTiers.MAX_TIER; i++) {
			int gx = x + 4 + i * 10;
			ObeliskUi.diamond(context, gx, Y_TIER, 4, 0xFF030205);
			ObeliskUi.diamond(context, gx, Y_TIER, 3, i <= data.tier() ? ObeliskUi.TEAL : 0xFF241A30);
			if (i <= data.tier()) {
				context.fill(gx - 1, Y_TIER - 2, gx, Y_TIER - 1, 0xC0FFFFFF);
			}
		}
		String range = Component.translatable("ender_obelisks.menu.range", data.range()).getString();
		int rw = font.width(range);
		context.drawString(font, range, right - rw, Y_TIER - 4, ObeliskUi.TEXT);
		ObeliskUi.smallIcon(context, ObeliskUi.ICON_RANGE, right - rw - 11, Y_TIER - 4);

		// Marker colour: eight gems; the chosen one is larger and framed in white
		for (int i = 0; i < ObeliskColors.RGB.length; i++) {
			int sx = swatchX(i);
			boolean chosen = i == data.color();
			ObeliskUi.diamond(context, sx, SWATCH_Y, SWATCH_R + (chosen ? 2 : 1), chosen ? 0xFFFFFFFF : 0xFF030205);
			ObeliskUi.diamond(context, sx, SWATCH_Y, SWATCH_R, 0xFF000000 | ObeliskColors.rgb(i));
			context.fill(sx - 1, SWATCH_Y - 2, sx, SWATCH_Y - 1, 0x90FFFFFF);
		}

		// Upgrade: what it costs and what you have; the button on the right shows the next range
		if (data.maxTier()) {
			Component max = Component.translatable("ender_obelisks.menu.max_tier");
			int tw = font.width(max);
			int cx = x + w / 2;
			context.drawString(font, max, cx - tw / 2, Y_UPGRADE + 6, ObeliskUi.GOLD);
			ObeliskUi.diamond(context, cx - tw / 2 - 8, Y_UPGRADE + 9, 2, ObeliskUi.GOLD);
			ObeliskUi.diamond(context, cx + tw / 2 + 7, Y_UPGRADE + 9, 2, ObeliskUi.GOLD);
		} else {
			int ix = x;
			int limit = right - 62;
			for (ItemStack stack : data.nextCost()) {
				boolean enough = creative() || have(stack) >= stack.getCount();
				String count = Math.min(have(stack), 999) + "/" + stack.getCount();
				if (ix + 18 + font.width(count) > limit) {
					count = "×" + stack.getCount();
				}
				context.renderItem(stack, ix, Y_UPGRADE + 2);
				context.drawString(font, count, ix + 17, Y_UPGRADE + 6, enough ? ObeliskUi.GREEN : ObeliskUi.RED);
				ix += 21 + font.width(count);
			}
		}

		ObeliskUi.divider(context, x, right, Y_DIVIDER1);
		drawCard(context, x, right);
		ObeliskUi.divider(context, x, right, Y_DIVIDER2);
		drawList(context, x, right, mouseX, mouseY);
	}

	/** The chosen destination: its marker and name, owner, the way there with a compass arrow, and a status gem. */
	private void drawCard(GuiGraphics context, int x, int right) {
		Entry target = selectedEntry();
		if (target == null) {
			ObeliskUi.pixels(context, MARKER, x + 2, Y_CARD + 14, 0xFF3A2E4C, 0xFF5A4C70, 0xFF030205);
			context.drawWordWrap(font, Component.translatable("ender_obelisks.menu.pick").withStyle(ChatFormatting.ITALIC), x + 14, Y_CARD + 10,
					right - x - 14, ObeliskUi.MUTED);
			return;
		}
		int color = colorOf(target);
		ObeliskUi.pixels(context, MARKER, x + 1, Y_CARD + 1, color, 0xFFFFFFFF, 0xFF000000);
		context.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(target.name()).withStyle(ChatFormatting.BOLD), right - x - 12)),
				x + 12, Y_CARD + 1, color);

		ObeliskUi.smallIcon(context, target.isPublic() ? ObeliskUi.ICON_PUBLIC : ObeliskUi.ICON_PRIVATE, x + 1, Y_CARD + 13);
		context.drawString(font, font.plainSubstrByWidth(target.ownerName(), right - x - 12), x + 12, Y_CARD + 13, ObeliskUi.MUTED);

		if (sameDimension(target)) {
			if (!target.id().equals(data.sourceId())) {
				compass(context, x + 4, Y_CARD + 28, target);
			}
			context.drawString(font, Component.translatable("ender_obelisks.menu.metres", (int) distance(target)), x + 12, Y_CARD + 25,
					ObeliskUi.TEXT);
		} else {
			ObeliskUi.diamond(context, x + 4, Y_CARD + 28, 2, ObeliskUi.VIOLET);
			context.drawString(font, dimensionName(target.dimension()), x + 12, Y_CARD + 25, ObeliskUi.VIOLET);
		}

		// Status: a coloured gem and a short word
		Component status = statusShort(target);
		int statusColor = statusColor(target);
		int sy = Y_CARD + 38;
		int sw = font.width(status) + 18;
		context.fill(x, sy, x + sw, sy + 12, ObeliskUi.alpha(statusColor, 0.14f));
		ObeliskUi.outline(context, x, sy, x + sw, sy + 12, ObeliskUi.alpha(statusColor, 0.45f));
		if (cooldownLeft() > 0 && inRange(target) && !target.id().equals(data.sourceId())) {
			ObeliskUi.smallIcon(context, ObeliskUi.ICON_COOLDOWN, x + 3, sy + 2);
		} else {
			ObeliskUi.diamond(context, x + 7, sy + 6, 2, statusColor);
		}
		context.drawString(font, status, x + 14, sy + 2, statusColor);
	}

	/** A small arrow pointing from this obelisk towards the target, north up like the map. */
	private void compass(GuiGraphics context, int cx, int cy, Entry target) {
		double dx = target.pos().getX() - data.sourcePos().getX();
		double dz = target.pos().getZ() - data.sourcePos().getZ();
		float angle = (float) Math.toDegrees(Math.atan2(dz, dx)) + 90;
		context.pose().pushPose();
		context.pose().translate(cx + 0.5f, cy + 0.5f, 0);
		context.pose().mulPose(Axis.ZP.rotationDegrees(angle));
		ObeliskUi.pixels(context, ARROW, -2, -3, ObeliskUi.TEAL, ObeliskUi.TEAL, 0xFF000000);
		context.pose().popPose();
	}

	private void drawList(GuiGraphics context, int x, int right, int mouseX, int mouseY) {
		List<Entry> entries = sortedEntries();
		int rows = listRows();
		listScroll = Mth.clamp(listScroll, 0, Math.max(0, entries.size() - rows));
		boolean scrollbar = entries.size() > rows && rows > 0;
		int textRight = scrollbar ? right - 5 : right;
		for (int i = 0; i < rows && i + listScroll < entries.size(); i++) {
			Entry entry = entries.get(i + listScroll);
			int y = Y_LIST + i * ROW_H;
			boolean hot = mouseX >= x - 2 && mouseX < right + 2 && mouseY >= y - 2 && mouseY < y - 2 + ROW_H;
			if (entry.id().equals(selected)) {
				context.fill(x - 2, y - 2, right + 2, y - 2 + ROW_H, ObeliskUi.alpha(ObeliskUi.TEAL, 0.18f));
				context.fill(x - 2, y - 2, x - 1, y - 2 + ROW_H, ObeliskUi.TEAL);
			} else if (hot) {
				context.fill(x - 2, y - 2, right + 2, y - 2 + ROW_H, 0x18FFFFFF);
			}
			int color = colorOf(entry);
			ObeliskUi.diamond(context, x + 3, y + 3, 2, color);
			String dist = sameDimension(entry) ? Component.translatable("ender_obelisks.menu.metres", (int) distance(entry)).getString()
					: dimensionName(entry.dimension()).getString();
			context.drawString(font, font.plainSubstrByWidth(entry.name(), textRight - x - 10 - font.width(dist) - 6),
					x + 10, y, color);
			context.drawString(font, dist, textRight - font.width(dist), y, ObeliskUi.MUTED);
		}
		if (scrollbar) {
			int trackTop = Y_LIST - 2;
			int trackH = rows * ROW_H;
			int thumbH = Math.max(8, trackH * rows / entries.size());
			int thumbY = trackTop + (trackH - thumbH) * listScroll / Math.max(1, entries.size() - rows);
			context.fill(right - 2, trackTop, right, trackTop + trackH, 0xFF030205);
			context.fill(right - 2, thumbY, right, thumbY + thumbH, ObeliskUi.EDGE);
		}
	}

	/** Words for the things drawn without them: the tier gems and the colour gems. */
	private void drawPanelTooltips(GuiGraphics context, int mouseX, int mouseY) {
		int x = panelX + PAD;
		if (mouseY >= Y_TIER - 5 && mouseY < Y_TIER + 5 && mouseX >= x && mouseX < x + 50) {
			context.renderTooltip(font, Component.translatable("ender_obelisks.menu.tier", data.tier(), data.range()), mouseX, mouseY);
		} else if (mouseY >= SWATCH_Y - SWATCH_R - 1 && mouseY <= SWATCH_Y + SWATCH_R + 1 && mouseX >= swatchX(0) - SWATCH_R - 1
				&& mouseX <= swatchX(ObeliskColors.RGB.length - 1) + SWATCH_R + 1) {
			context.renderTooltip(font, Component.translatable("ender_obelisks.menu.color"), mouseX, mouseY);
		} else if (!data.maxTier() && mouseY >= Y_UPGRADE && mouseY < Y_UPGRADE + 20 && mouseX >= x && mouseX < panelX + PANEL_W - PAD - 62) {
			int ix = x;
			for (ItemStack stack : data.nextCost()) {
				String count = Math.min(have(stack), 999) + "/" + stack.getCount();
				int cell = 21 + font.width(count);
				if (mouseX >= ix && mouseX < ix + cell) {
					context.renderTooltip(font, stack, mouseX, mouseY);
					break;
				}
				ix += cell;
			}
		}
	}

	private Component statusShort(Entry entry) {
		if (entry.id().equals(data.sourceId())) {
			return Component.translatable("ender_obelisks.menu.status_here");
		} else if (!inRange(entry) && !sameDimension(entry)) {
			return Component.translatable("ender_obelisks.menu.pill_dimension");
		} else if (!inRange(entry)) {
			return Component.translatable("ender_obelisks.menu.pill_far");
		} else if (cooldownLeft() > 0) {
			return Component.translatable("ender_obelisks.menu.pill_cooldown", (cooldownLeft() + 19) / 20);
		}
		return Component.translatable("ender_obelisks.menu.status_ready");
	}

	private int statusColor(Entry entry) {
		if (entry.id().equals(data.sourceId())) {
			return ObeliskUi.GOLD;
		} else if (!inRange(entry)) {
			return ObeliskUi.RED;
		} else if (cooldownLeft() > 0) {
			return 0xFFF0D070;
		}
		return ObeliskUi.GREEN;
	}

	/** The full status sentence, for tooltips. */
	private Component statusLine(Entry entry) {
		if (entry.id().equals(data.sourceId())) {
			return Component.translatable("ender_obelisks.menu.status_here").withStyle(ChatFormatting.GOLD);
		} else if (!inRange(entry) && !sameDimension(entry)) {
			return (data.crossDimension() ? Component.translatable("ender_obelisks.menu.status_dimension_tier", data.crossDimensionMinTier())
					: Component.translatable("ender_obelisks.menu.status_dimension_off")).withStyle(ChatFormatting.RED);
		} else if (!inRange(entry)) {
			return Component.translatable("ender_obelisks.menu.status_far", data.range()).withStyle(ChatFormatting.RED);
		} else if (cooldownLeft() > 0) {
			return Component.translatable("ender_obelisks.menu.status_cooldown", (cooldownLeft() + 19) / 20).withStyle(ChatFormatting.YELLOW);
		}
		return Component.translatable("ender_obelisks.menu.status_ready").withStyle(ChatFormatting.GREEN);
	}

	private List<Component> describe(Entry entry) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(entry.name()).withStyle(ChatFormatting.BOLD));
		lines.add(Component.translatable(entry.isPublic() ? "ender_obelisks.menu.owner_public" : "ender_obelisks.menu.owner_private",
				entry.ownerName()).withStyle(ChatFormatting.GRAY));
		if (sameDimension(entry)) {
			lines.add(Component.translatable("ender_obelisks.menu.where", entry.pos().getX(), entry.pos().getY(), entry.pos().getZ(),
					(int) distance(entry)).withStyle(ChatFormatting.GRAY));
		} else {
			lines.add(Component.translatable("ender_obelisks.menu.where_dimension", entry.pos().getX(), entry.pos().getY(), entry.pos().getZ(),
					dimensionName(entry.dimension())).withStyle(ChatFormatting.GRAY));
		}
		lines.add(statusLine(entry));
		return lines;
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		if (nameField.isFocused() && !nameField.isMouseOver(mouseX, mouseY)) {
			nameField.setFocused(false);
			setFocused(null);
		}
		if (data.isOwner() && Math.abs(mouseY - SWATCH_Y) <= SWATCH_R + 1) {
			for (int i = 0; i < ObeliskColors.RGB.length; i++) {
				if (Math.abs(mouseX - swatchX(i)) <= SWATCH_R + 1) {
					FriendlyByteBuf buf = Network.create();
					buf.writeBlockPos(data.sourcePos());
					buf.writeVarInt(i);
					Client.send(ObeliskPackets.SET_COLOR, buf);
					return true;
				}
			}
		}
		if (inMap(mouseX, mouseY)) {
			Entry entry = entryAt(mouseX, mouseY);
			if (entry != null) {
				select(entry.id());
			} else {
				dragging = true;
			}
			return true;
		}
		if (mouseX >= panelX && mouseY >= Y_LIST - 2) {
			int row = (int) ((mouseY - Y_LIST + 2) / ROW_H);
			int index = row + listScroll;
			List<Entry> entries = sortedEntries();
			if (row < listRows() && index >= 0 && index < entries.size()) {
				Entry entry = entries.get(index);
				select(entry.id());
				if (sameDimension(entry)) {
					centerX = entry.pos().getX() + 0.5;
					centerZ = entry.pos().getZ() + 0.5;
				}
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (dragging) {
			centerX -= deltaX / scale;
			centerZ -= deltaY / scale;
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		dragging = false;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double amount) {
		if (inMap(mouseX, mouseY)) {
			// Zoom around the cursor: the block under it stays under it
			double wx = centerX + (mouseX - mapCenterX()) / scale;
			double wz = centerZ + (mouseY - mapCenterY()) / scale;
			scale = Mth.clamp(scale * (amount > 0 ? 1.25 : 0.8), MIN_SCALE, MAX_SCALE);
			centerX = wx - (mouseX - mapCenterX()) / scale;
			centerZ = wz - (mouseY - mapCenterY()) / scale;
			return true;
		}
		if (mouseX >= panelX && mouseY >= Y_LIST) {
			listScroll -= (int) Math.signum(amount);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
	}

	/** Zoom so the whole range circle fits; used by the harness for its screenshots. */
	public void zoomToRange() {
		int size = Math.min(mapX1 - mapX0, mapY1 - mapY0);
		scale = Mth.clamp(size / (data.range() * 2.3), MIN_SCALE, MAX_SCALE);
		centerX = data.sourcePos().getX() + 0.5;
		centerZ = data.sourcePos().getZ() + 0.5;
	}

	public void zoomTo(double newScale) {
		scale = Mth.clamp(newScale, MIN_SCALE, MAX_SCALE);
	}

	@Override
	public boolean isPauseScreen() {
		// The integrated server must keep running: it answers this menu's packets
		return false;
	}
}
