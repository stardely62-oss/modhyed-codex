package euf.enderobelisks.net;

import euf.enderobelisks.EnderObelisks;
import euf.enderobelisks.ObeliskInfo;
import euf.enderobelisks.ObeliskRegistry;
import euf.enderobelisks.ObeliskService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import euf.enderobelisks.platform.Network;
import euf.enderobelisks.platform.Network.Server;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps every client's list of the obelisks it may see (all dimensions) up to date: sent on join and after any
 * change. Map mods (JourneyMap) draw their markers from it.
 */
public final class ObeliskSync {
	public static final ResourceLocation SYNC = EnderObelisks.id("sync");

	public record Entry(UUID id, ResourceLocation dimension, BlockPos pos, String name, String ownerName, boolean isOwn,
			boolean isPublic, int color) {
	}

	private ObeliskSync() {
	}

	public static void sendTo(ServerPlayer player) {
		List<ObeliskInfo> visible = ObeliskRegistry.get(player.getServer()).all().stream()
				.filter(o -> ObeliskService.canUse(o, player)).toList();
		FriendlyByteBuf buf = Network.create();
		buf.writeVarInt(visible.size());
		for (ObeliskInfo o : visible) {
			buf.writeUUID(o.id);
			buf.writeResourceLocation(o.dimension.location());
			buf.writeBlockPos(o.pos);
			buf.writeUtf(o.name);
			buf.writeUtf(o.ownerName);
			buf.writeBoolean(o.owner.equals(player.getUUID()));
			buf.writeBoolean(o.isPublic);
			buf.writeVarInt(o.color);
		}
		Server.send(player, SYNC, buf);
	}

	public static void broadcast(MinecraftServer server) {
		server.getPlayerList().getPlayers().forEach(ObeliskSync::sendTo);
	}

	public static List<Entry> read(FriendlyByteBuf buf) {
		int count = buf.readVarInt();
		List<Entry> entries = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			entries.add(new Entry(buf.readUUID(), buf.readResourceLocation(), buf.readBlockPos(), buf.readUtf(), buf.readUtf(),
					buf.readBoolean(), buf.readBoolean(), buf.readVarInt()));
		}
		return entries;
	}
}
