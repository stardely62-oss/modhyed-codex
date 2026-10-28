package euf.enderobelisks.client.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

/**
 * Fills the explored map as the player moves: a few loaded chunks per tick are turned into top-down colours the way
 * vanilla maps do it (block map colour, lighter or darker by height, water by depth). Chunks are rescanned every 30 s
 * while loaded, so new buildings show up.
 */
public final class MapScanner {
	private static final int CHUNKS_PER_TICK = 4;
	private static final long RESCAN_TICKS = 600;
	private static final int SAVE_EVERY_TICKS = 6000;

	@Nullable
	private static MapCache cache;
	@Nullable
	private static String cacheKey;
	private static final Map<Long, Long> lastScan = new HashMap<>();
	private static final LinkedHashSet<Long> queue = new LinkedHashSet<>();
	private static long ticks;

	private MapScanner() {
	}

	public static void register() {
		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick(Minecraft.getInstance()));
		NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> Minecraft.getInstance().execute(MapScanner::close));
	}

	@Nullable
	public static MapCache cache() {
		return cache;
	}

	private static void close() {
		if (cache != null) {
			cache.save();
		}
		cache = null;
		cacheKey = null;
		lastScan.clear();
		queue.clear();
		MapTiles.clear();
	}

	private static void tick(Minecraft client) {
		ClientLevel world = client.level;
		if (world == null || client.player == null) {
			return;
		}
		String key = worldKey(client) + "/" + world.dimension().location().toString().replace(':', '_');
		if (!key.equals(cacheKey)) {
			close();
			cache = new MapCache(client.gameDirectory.toPath().resolve("ender_obelisks_map").resolve(key));
			cacheKey = key;
		}
		ticks++;
		if (ticks % 20 == 0) {
			refillQueue(client, world);
		}
		var it = queue.iterator();
		for (int i = 0; i < CHUNKS_PER_TICK && it.hasNext(); i++) {
			long packed = it.next();
			it.remove();
			scan(world, ChunkPos.getX(packed), ChunkPos.getZ(packed), client.player.getBlockY());
		}
		if (ticks % SAVE_EVERY_TICKS == 0) {
			cache.save();
		}
	}

	private static void refillQueue(Minecraft client, ClientLevel world) {
		ChunkPos center = client.player.chunkPosition();
		int radius = client.options.getEffectiveRenderDistance();
		List<Long> candidates = new ArrayList<>();
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				int x = center.x + dx;
				int z = center.z + dz;
				long packed = ChunkPos.asLong(x, z);
				Long last = lastScan.get(packed);
				if ((last == null || ticks - last > RESCAN_TICKS) && world.getChunkSource().hasChunk(x, z)) {
					candidates.add(packed);
				}
			}
		}
		candidates.sort(Comparator.comparingInt(p -> {
			int dx = ChunkPos.getX(p) - center.x;
			int dz = ChunkPos.getZ(p) - center.z;
			return dx * dx + dz * dz;
		}));
		queue.addAll(candidates);
	}

	private static void scan(ClientLevel world, int chunkX, int chunkZ, int playerY) {
		LevelChunk chunk = world.getChunk(chunkX, chunkZ);
		if (chunk instanceof EmptyLevelChunk || chunk.isEmpty()) {
			return;
		}
		lastScan.put(ChunkPos.asLong(chunkX, chunkZ), ticks);
		boolean ceiling = world.dimensionType().hasCeiling();
		int bottom = world.getMinBuildHeight();
		int[] heights = new int[256];
		int[] colors = new int[256];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int x = (chunkX << 4) + lx;
				int z = (chunkZ << 4) + lz;
				int y = ceiling ? floorBelow(chunk, pos, x, z, Math.min(playerY + 8, world.getMaxBuildHeight() - 1), bottom)
						: chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);

				// Step down through blocks maps don't draw (glass, air, ...)
				BlockState state = chunk.getBlockState(pos.set(x, y, z));
				MapColor color = state.getMapColor(world, pos);
				for (int steps = 0; color == MapColor.NONE && y > bottom && steps < 16; steps++) {
					y--;
					state = chunk.getBlockState(pos.set(x, y, z));
					color = state.getMapColor(world, pos);
				}
				int i = lz * 16 + lx;
				heights[i] = y;
				if (color == MapColor.NONE) {
					colors[i] = 0xFF000000;
					continue;
				}

				int brightness;
				if (state.getFluidState().is(FluidTags.WATER)) {
					int depth = 0;
					while (depth < 10 && chunk.getBlockState(pos.set(x, y - depth - 1, z)).getFluidState().is(FluidTags.WATER)) {
						depth++;
					}
					brightness = depth >= 7 ? 180 : depth >= 3 ? 220 : 255;
				} else {
					int north = lz > 0 ? heights[i - 16] : y;
					brightness = y > north ? 255 : y < north ? 180 : 220;
				}
				colors[i] = shade(color.col, brightness);
			}
		}
		cache.put(chunkX, chunkZ, colors);
		MapTiles.markDirty(Math.floorDiv(chunkX, MapTiles.TILE_CHUNKS), Math.floorDiv(chunkZ, MapTiles.TILE_CHUNKS));
	}

	/** In dimensions with a roof (the Nether), the first floor below the player's height instead of the roof. */
	private static int floorBelow(LevelChunk chunk, BlockPos.MutableBlockPos pos, int x, int z, int start, int bottom) {
		boolean seenAir = false;
		for (int y = start; y > bottom && y > start - 96; y--) {
			if (chunk.getBlockState(pos.set(x, y, z)).isAir()) {
				seenAir = true;
			} else if (seenAir) {
				return y;
			}
		}
		return start;
	}

	private static int shade(int rgb, int brightness) {
		int r = ((rgb >> 16) & 0xFF) * brightness / 255;
		int g = ((rgb >> 8) & 0xFF) * brightness / 255;
		int b = (rgb & 0xFF) * brightness / 255;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static String worldKey(Minecraft client) {
		String raw;
		if (client.hasSingleplayerServer() && client.getSingleplayerServer() != null) {
			raw = "sp_" + client.getSingleplayerServer().getWorldData().getLevelName();
		} else {
			ServerData server = client.getCurrentServer();
			raw = "mp_" + (server != null ? server.ip : "unknown");
		}
		return raw.replaceAll("[^A-Za-z0-9._-]", "_");
	}
}
