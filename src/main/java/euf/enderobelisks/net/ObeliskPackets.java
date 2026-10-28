package euf.enderobelisks.net;

import euf.enderobelisks.EnderObelisks;
import euf.enderobelisks.ObeliskInfo;
import euf.enderobelisks.ObeliskService;
import euf.enderobelisks.ObeliskService.TeleportResult;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import euf.enderobelisks.platform.Network;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import euf.enderobelisks.platform.Network.Server;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Channel ids and the server side of every packet. The server re-checks everything a client asks for. */
public final class ObeliskPackets {
	public static final ResourceLocation MENU = EnderObelisks.id("menu");
	public static final ResourceLocation TELEPORT = EnderObelisks.id("teleport");
	public static final ResourceLocation RENAME = EnderObelisks.id("rename");
	public static final ResourceLocation TOGGLE_PUBLIC = EnderObelisks.id("toggle_public");
	public static final ResourceLocation UPGRADE = EnderObelisks.id("upgrade");
	public static final ResourceLocation SET_COLOR = EnderObelisks.id("set_color");
	public static final ResourceLocation FRIEND_ADD = EnderObelisks.id("friend_add");
	public static final ResourceLocation FRIEND_REMOVE = EnderObelisks.id("friend_remove");

	private ObeliskPackets() {
	}

	public static void registerServerReceivers() {
		registerSimple();
		Server.registerGlobalReceiver(TELEPORT, (server, player, handler, buf, sender) -> {
			BlockPos source = buf.readBlockPos();
			UUID target = buf.readUUID();
			server.execute(() -> {
				// Starts the warmup (or jumps at once); refusals reopen the menu with the reason in the action bar
				TeleportResult result = ObeliskService.startTeleport(player, source, target);
				if (result != TeleportResult.OK) {
					player.displayClientMessage(Component.translatable("ender_obelisks.teleport." + result.name().toLowerCase(Locale.ROOT)), true);
					ObeliskService.openMenu(player, source);
				}
			});
		});
		Server.registerGlobalReceiver(RENAME, (server, player, handler, buf, sender) -> {
			BlockPos source = buf.readBlockPos();
			String name = buf.readUtf(ObeliskService.MAX_NAME_LENGTH * 4);
			server.execute(() -> {
				ObeliskService.rename(player, source, name);
				ObeliskService.openMenu(player, source);
			});
		});
		Server.registerGlobalReceiver(TOGGLE_PUBLIC, (server, player, handler, buf, sender) -> {
			BlockPos source = buf.readBlockPos();
			server.execute(() -> {
				ObeliskService.togglePublic(player, source);
				ObeliskService.openMenu(player, source);
			});
		});
		Server.registerGlobalReceiver(UPGRADE, (server, player, handler, buf, sender) -> {
			BlockPos source = buf.readBlockPos();
			server.execute(() -> {
				if (!ObeliskService.upgrade(player, source)) {
					player.displayClientMessage(Component.translatable("ender_obelisks.message.upgrade_failed"), true);
				}
				ObeliskService.openMenu(player, source);
			});
		});
	}

	private static void registerSimple() {
		Server.registerGlobalReceiver(SET_COLOR, (server, player, handler, buf, sender) -> {
			BlockPos source = buf.readBlockPos();
			int color = buf.readVarInt();
			server.execute(() -> {
				ObeliskService.setColor(player, source, color);
				ObeliskService.openMenu(player, source);
			});
		});
		Server.registerGlobalReceiver(FRIEND_ADD, (server, player, handler, buf, sender) -> {
			BlockPos source = buf.readBlockPos();
			String name = buf.readUtf(64);
			server.execute(() -> {
				if (!ObeliskService.addFriend(player, source, name)) {
					player.displayClientMessage(Component.translatable("ender_obelisks.message.friend_unknown", name), true);
				}
				ObeliskService.openMenu(player, source);
			});
		});
		Server.registerGlobalReceiver(FRIEND_REMOVE, (server, player, handler, buf, sender) -> {
			BlockPos source = buf.readBlockPos();
			UUID friend = buf.readUUID();
			server.execute(() -> {
				ObeliskService.removeFriend(player, source, friend);
				ObeliskService.openMenu(player, source);
			});
		});
		// Every player gets their list of visible obelisks on join (map integrations need it before any menu opens)
		NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> { if (event.getEntity() instanceof ServerPlayer player) ObeliskSync.sendTo(player); });
	}

	public static void sendMenu(ServerPlayer player, ObeliskInfo source, boolean isOwner, int cooldownTicks, List<ObeliskInfo> visible) {
		FriendlyByteBuf buf = Network.create();
		MenuData.write(buf, source, isOwner, cooldownTicks, visible, player.getUUID());
		Server.send(player, MENU, buf);
	}
}
