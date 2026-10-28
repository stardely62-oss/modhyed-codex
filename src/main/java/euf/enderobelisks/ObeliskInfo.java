package euf.enderobelisks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** One obelisk as the server registry knows it, loaded or not. {@code pos} is the lower half. */
public class ObeliskInfo {
	public final UUID id;
	public final ResourceKey<Level> dimension;
	public final BlockPos pos;
	public final UUID owner;
	public String ownerName;
	public String name;
	public boolean isPublic;
	public int tier;
	/** Players the owner let in while the obelisk is private (UUID -> last known name). */
	public final Map<UUID, String> friends = new LinkedHashMap<>();
	/** Marker colour on the maps, an index into {@link ObeliskColors}. */
	public int color;

	public ObeliskInfo(UUID id, ResourceKey<Level> dimension, BlockPos pos, UUID owner, String ownerName, String name,
			boolean isPublic, int tier) {
		this.id = id;
		this.dimension = dimension;
		this.pos = pos.immutable();
		this.owner = owner;
		this.ownerName = ownerName;
		this.name = name;
		this.isPublic = isPublic;
		this.tier = tier;
	}

	public int range() {
		return ObeliskTiers.range(tier);
	}

	public double distanceTo(ObeliskInfo other) {
		return Math.sqrt(pos.distSqr(other.pos));
	}

	public CompoundTag toNbt() {
		CompoundTag nbt = new CompoundTag();
		nbt.putUUID("id", id);
		nbt.putString("dim", dimension.location().toString());
		nbt.putLong("pos", pos.asLong());
		nbt.putUUID("owner", owner);
		nbt.putString("ownerName", ownerName);
		nbt.putString("name", name);
		nbt.putBoolean("public", isPublic);
		nbt.putInt("tier", tier);
		nbt.putInt("color", color);
		ListTag list = new ListTag();
		friends.forEach((uuid, friendName) -> {
			CompoundTag friend = new CompoundTag();
			friend.putUUID("id", uuid);
			friend.putString("name", friendName);
			list.add(friend);
		});
		nbt.put("friends", list);
		return nbt;
	}

	public static ObeliskInfo fromNbt(CompoundTag nbt) {
		ObeliskInfo info = new ObeliskInfo(nbt.getUUID("id"),
				ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(nbt.getString("dim"))),
				BlockPos.of(nbt.getLong("pos")), nbt.getUUID("owner"), nbt.getString("ownerName"),
				nbt.getString("name"), nbt.getBoolean("public"), nbt.getInt("tier"));
		info.color = nbt.getInt("color");
		for (Tag element : nbt.getList("friends", Tag.TAG_COMPOUND)) {
			CompoundTag friend = (CompoundTag) element;
			info.friends.put(friend.getUUID("id"), friend.getString("name"));
		}
		return info;
	}
}
