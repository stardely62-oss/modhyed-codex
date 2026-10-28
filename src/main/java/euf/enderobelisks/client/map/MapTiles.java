package euf.enderobelisks.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/** GPU textures for the map, one per 8x8 chunks (128x128 blocks, one pixel per block), rebuilt when a chunk changes. */
public final class MapTiles {
	public static final int TILE_CHUNKS = 8;
	public static final int TILE_BLOCKS = TILE_CHUNKS * 16;

	private static final class Tile {
		DynamicTexture texture;
		ResourceLocation id;
		boolean dirty = true;
		boolean empty = true;
	}

	private static final Map<Long, Tile> tiles = new HashMap<>();

	private MapTiles() {
	}

	public static void markDirty(int tileX, int tileZ) {
		Tile tile = tiles.get(ChunkPos.asLong(tileX, tileZ));
		if (tile != null) {
			tile.dirty = true;
		}
	}

	/** The tile's texture, rebuilt from the cache if needed; null where nothing has been explored. Render thread only. */
	@Nullable
	public static ResourceLocation get(MapCache cache, int tileX, int tileZ) {
		Tile tile = tiles.computeIfAbsent(ChunkPos.asLong(tileX, tileZ), key -> new Tile());
		if (tile.dirty) {
			tile.dirty = false;
			rebuild(cache, tileX, tileZ, tile);
		}
		return tile.empty ? null : tile.id;
	}

	private static void rebuild(MapCache cache, int tileX, int tileZ, Tile tile) {
		int[][] chunkColors = new int[TILE_CHUNKS * TILE_CHUNKS][];
		boolean any = false;
		for (int i = 0; i < chunkColors.length; i++) {
			chunkColors[i] = cache.get(tileX * TILE_CHUNKS + i % TILE_CHUNKS, tileZ * TILE_CHUNKS + i / TILE_CHUNKS);
			any |= chunkColors[i] != null;
		}
		tile.empty = !any;
		if (!any) {
			return;
		}
		if (tile.texture == null) {
			tile.texture = new DynamicTexture(TILE_BLOCKS, TILE_BLOCKS, true);
			tile.id = Minecraft.getInstance().getTextureManager().register("ender_obelisks_map", tile.texture);
		}
		NativeImage image = tile.texture.getPixels();
		for (int i = 0; i < chunkColors.length; i++) {
			int[] colors = chunkColors[i];
			int baseX = (i % TILE_CHUNKS) * 16;
			int baseZ = (i / TILE_CHUNKS) * 16;
			for (int c = 0; c < 256; c++) {
				image.setPixelRGBA(baseX + (c & 15), baseZ + (c >> 4), colors == null ? 0 : argbToAbgr(colors[c]));
			}
		}
		tile.texture.upload();
	}

	/** NativeImage stores pixels as ABGR. */
	private static int argbToAbgr(int argb) {
		return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
	}

	public static void clear() {
		tiles.values().forEach(tile -> {
			if (tile.id != null) {
				Minecraft.getInstance().getTextureManager().release(tile.id);
			}
		});
		tiles.clear();
	}
}
