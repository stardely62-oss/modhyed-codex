package euf.enderobelisks.net;

import euf.enderobelisks.ObeliskConfig;
import euf.enderobelisks.ObeliskInfo;
import euf.enderobelisks.ObeliskTiers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * What the obelisk menu shows: the obelisk you stand at, the server's rules for it (range, next upgrade, cooldown,
 * cross-dimension jumps), your cooldown, and every obelisk you may use. The client reads no config of its own.
 */
public record MenuData(BlockPos sourcePos, UUID sourceId, ResourceLocation dimension, String name, boolean isOwner,
		boolean isPublic, int tier, int color, int range, int nextRange, List<ItemStack> nextCost, int cooldownTicks,
		int cooldownTotal, boolean crossDimension, int crossDimensionMinTier, List<Friend> friends, List<Entry> entries) {

	public record Entry(UUID id, ResourceLocation dimension, BlockPos pos, String name, String ownerName, boolean isOwn,
			boolean isPublic, int tier, int color) {
	}

	public record Friend(UUID id, String name) {
	}

	public boolean maxTier() {
		return tier >= ObeliskTiers.MAX_TIER;
	}

	public static void write(FriendlyByteBuf buf, ObeliskInfo source, boolean isOwner, int cooldownTicks, List<ObeliskInfo> visible,
			UUID viewer) {
		ObeliskConfig config = ObeliskConfig.get();
		buf.writeBlockPos(source.pos);
		buf.writeUUID(source.id);
		buf.writeResourceLocation(source.dimension.location());
		buf.writeUtf(source.name);
		buf.writeBoolean(isOwner);
		buf.writeBoolean(source.isPublic);
		buf.writeVarInt(source.tier);
		buf.writeVarInt(source.color);
		buf.writeVarInt(ObeliskTiers.range(source.tier));
		buf.writeVarInt(ObeliskTiers.range(source.tier + 1));
		List<ItemStack> cost = ObeliskTiers.upgradeCost(source.tier);
		buf.writeVarInt(cost.size());
		cost.forEach(stack -> { buf.writeResourceLocation(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())); buf.writeVarInt(stack.getCount()); });
		buf.writeVarInt(cooldownTicks);
		buf.writeVarInt(config.cooldownSeconds * 20);
		buf.writeBoolean(config.crossDimension);
		buf.writeVarInt(config.crossDimensionMinTier);
		// Only the owner sees (and edits) the friends list
		List<Map.Entry<UUID, String>> friends = isOwner ? List.copyOf(source.friends.entrySet()) : List.of();
		buf.writeVarInt(friends.size());
		for (Map.Entry<UUID, String> friend : friends) {
			buf.writeUUID(friend.getKey());
			buf.writeUtf(friend.getValue());
		}
		buf.writeVarInt(visible.size());
		for (ObeliskInfo o : visible) {
			buf.writeUUID(o.id);
			buf.writeResourceLocation(o.dimension.location());
			buf.writeBlockPos(o.pos);
			buf.writeUtf(o.name);
			buf.writeUtf(o.ownerName);
			buf.writeBoolean(o.owner.equals(viewer));
			buf.writeBoolean(o.isPublic);
			buf.writeVarInt(o.tier);
			buf.writeVarInt(o.color);
		}
	}

	public static MenuData read(FriendlyByteBuf buf) {
		BlockPos pos = buf.readBlockPos();
		UUID id = buf.readUUID();
		ResourceLocation dimension = buf.readResourceLocation();
		String name = buf.readUtf();
		boolean isOwner = buf.readBoolean();
		boolean isPublic = buf.readBoolean();
		int tier = buf.readVarInt();
		int color = buf.readVarInt();
		int range = buf.readVarInt();
		int nextRange = buf.readVarInt();
		int costCount = buf.readVarInt();
		List<ItemStack> cost = new ArrayList<>(costCount);
		for (int i = 0; i < costCount; i++) {
			cost.add(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(buf.readResourceLocation()), buf.readVarInt()));
		}
		int cooldown = buf.readVarInt();
		int cooldownTotal = buf.readVarInt();
		boolean crossDimension = buf.readBoolean();
		int crossDimensionMinTier = buf.readVarInt();
		int friendCount = buf.readVarInt();
		List<Friend> friends = new ArrayList<>(friendCount);
		for (int i = 0; i < friendCount; i++) {
			friends.add(new Friend(buf.readUUID(), buf.readUtf()));
		}
		int count = buf.readVarInt();
		List<Entry> entries = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			entries.add(new Entry(buf.readUUID(), buf.readResourceLocation(), buf.readBlockPos(), buf.readUtf(), buf.readUtf(),
					buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt()));
		}
		return new MenuData(pos, id, dimension, name, isOwner, isPublic, tier, color, range, nextRange, cost, cooldown,
				cooldownTotal, crossDimension, crossDimensionMinTier, friends, entries);
	}
}
