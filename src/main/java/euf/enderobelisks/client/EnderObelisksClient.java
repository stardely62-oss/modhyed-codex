package euf.enderobelisks.client;

import euf.enderobelisks.EnderObelisks;
import euf.enderobelisks.client.map.MapScanner;
import euf.enderobelisks.client.screen.FriendsScreen;
import euf.enderobelisks.client.screen.ObeliskScreen;
import euf.enderobelisks.net.MenuData;
import euf.enderobelisks.net.ObeliskPackets;
import euf.enderobelisks.net.ObeliskSync;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import euf.enderobelisks.platform.Network.Client;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

@net.neoforged.fml.common.EventBusSubscriber(modid = EnderObelisks.MOD_ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public class EnderObelisksClient {
    private static final Map<Item, String> LORE = new java.util.HashMap<>();
    @net.neoforged.bus.api.SubscribeEvent
    public static void setup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(() -> new EnderObelisksClient().onInitializeClient());
    }
    @net.neoforged.bus.api.SubscribeEvent
    public static void renderers(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(EnderObelisks.OBELISK_BLOCK_ENTITY.get(), ObeliskRenderer::new);
    }
    @net.neoforged.bus.api.SubscribeEvent
    public static void models(net.neoforged.neoforge.client.event.ModelEvent.RegisterAdditional event) {
        event.register(ObeliskRenderer.EYE_MODEL); event.register(ObeliskRenderer.SHARD_MODEL); event.register(ObeliskRenderer.SHARD_GLOW_MODEL);
    }

	/**
	 * Lore under the item name, in italics, from ender_obelisks.lore.<item>.1, .2, ... (as many lines as the language
	 * file has); the practical hints the items add themselves follow after an empty line.
	 */
	static void addLore(ItemStack stack, TooltipFlag context, List<Component> lines) {
		String item = LORE.get(stack.getItem());
		if (item == null || lines.isEmpty()) {
			return;
		}
		List<Component> lore = new ArrayList<>();
		for (int i = 1; Language.getInstance().has("ender_obelisks.lore." + item + "." + i); i++) {
			lore.add(Component.translatable("ender_obelisks.lore." + item + "." + i).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		}
		if (lore.isEmpty()) {
			return;
		}
		hideHints(lines);
		if (lines.size() > 1) {
			lore.add(Component.empty());
		}
		lines.addAll(1, lore);
	}

	/** How-to hints the items add: shown only while Shift is held, so the tooltip is the name and the story. */
	private static final Set<String> HINTS = Set.of("ender_obelisks.tooltip.use", "ender_obelisks.tooltip.upgrade", "ender_obelisks.tooltip.name",
			"ender_obelisks.return.tooltip_use", "ender_obelisks.return.tooltip_unbound");

	private static void hideHints(List<Component> lines) {
		if (Screen.hasShiftDown()) {
			return;
		}
		boolean removed = lines.removeIf(line -> line.getContents() instanceof TranslatableContents t && HINTS.contains(t.getKey()));
		if (removed) {
			lines.add(Component.translatable("ender_obelisks.tooltip.more").withStyle(ChatFormatting.DARK_GRAY));
		}
	}

	public void onInitializeClient() {
        LORE.put(EnderObelisks.OBELISK_ITEM.get(), "obelisk");
        LORE.put(EnderObelisks.RUINED_OBELISK_ITEM.get(), "ruined_obelisk");
        LORE.put(EnderObelisks.TIME_SHARD.get(), "time_shard");
        LORE.put(EnderObelisks.RETURN_SHARD.get(), "return_shard");

		MapScanner.register();
		net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event) -> addLore(event.getItemStack(), event.getFlags(), event.getToolTip()));
		Client.registerGlobalReceiver(ObeliskPackets.MENU, (client, handler, buf, sender) -> {
			MenuData data = MenuData.read(buf);
			client.execute(() -> {
				if (client.screen instanceof ObeliskScreen screen && screen.isFor(data.sourcePos())) {
					screen.update(data);
				} else if (client.screen instanceof FriendsScreen friends && friends.parent().isFor(data.sourcePos())) {
					friends.parent().update(data);
					friends.refresh();
				} else {
					client.setScreen(new ObeliskScreen(data));
				}
			});
		});
		Client.registerGlobalReceiver(ObeliskSync.SYNC, (client, handler, buf, sender) -> {
			var entries = ObeliskSync.read(buf);
			client.execute(() -> ObeliskClientCache.set(entries));
		});
		net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> net.minecraft.client.Minecraft.getInstance().execute(() -> ObeliskClientCache.set(List.of())));
	}
}
