package euf.enderobelisks;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/** Every obelisk on the server, saved with the overworld, so the map can list obelisks in unloaded chunks. */
public class ObeliskRegistry extends SavedData {
	private static final String KEY = EnderObelisks.MOD_ID;
	private final Map<UUID, ObeliskInfo> byId = new LinkedHashMap<>();

	public static ObeliskRegistry get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(ObeliskRegistry::new, (tag, registries) -> fromNbt(tag), null), KEY);
	}

	public Collection<ObeliskInfo> all() {
		return byId.values();
	}

	public Optional<ObeliskInfo> byId(UUID id) {
		return Optional.ofNullable(byId.get(id));
	}

	public Optional<ObeliskInfo> at(ResourceKey<Level> dimension, BlockPos pos) {
		return byId.values().stream().filter(o -> o.dimension.equals(dimension) && o.pos.equals(pos)).findFirst();
	}

	public void put(ObeliskInfo info) {
		byId.put(info.id, info);
		setDirty();
	}

	public void remove(UUID id) {
		if (byId.remove(id) != null) {
			setDirty();
		}
	}

	@Override
	public CompoundTag save(CompoundTag nbt, net.minecraft.core.HolderLookup.Provider registries) {
		ListTag list = new ListTag();
		byId.values().forEach(o -> list.add(o.toNbt()));
		nbt.put("obelisks", list);
		return nbt;
	}

	private static ObeliskRegistry fromNbt(CompoundTag nbt) {
		ObeliskRegistry registry = new ObeliskRegistry();
		for (Tag element : nbt.getList("obelisks", Tag.TAG_COMPOUND)) {
			ObeliskInfo info = ObeliskInfo.fromNbt((CompoundTag) element);
			registry.byId.put(info.id, info);
		}
		return registry;
	}
}
