package euf.enderobelisks.client.map;

import euf.enderobelisks.EnderObelisks;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.world.level.ChunkPos;

/**
 * Explored-map colours of one world + dimension: one ARGB int per block column, 256 per chunk (index z * 16 + x).
 * Stored on the client in 32x32-chunk region files under .minecraft/ender_obelisks_map/<world>/<dimension>/.
 */
public class MapCache {
	private final Path dir;
	private final Map<Long, int[]> chunks = new HashMap<>();
	private final Set<Long> loadedRegions = new HashSet<>();
	private final Set<Long> dirtyRegions = new HashSet<>();

	public MapCache(Path dir) {
		this.dir = dir;
	}

	public int[] get(int chunkX, int chunkZ) {
		ensureLoaded(chunkX >> 5, chunkZ >> 5);
		return chunks.get(ChunkPos.asLong(chunkX, chunkZ));
	}

	public void put(int chunkX, int chunkZ, int[] colors) {
		ensureLoaded(chunkX >> 5, chunkZ >> 5);
		chunks.put(ChunkPos.asLong(chunkX, chunkZ), colors);
		dirtyRegions.add(ChunkPos.asLong(chunkX >> 5, chunkZ >> 5));
	}

	private void ensureLoaded(int regionX, int regionZ) {
		long key = ChunkPos.asLong(regionX, regionZ);
		if (!loadedRegions.add(key)) {
			return;
		}
		Path file = regionFile(regionX, regionZ);
		if (!Files.exists(file)) {
			return;
		}
		try (InputStream raw = Files.newInputStream(file); DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
			int count = in.readInt();
			for (int i = 0; i < count; i++) {
				int local = in.readUnsignedShort();
				int[] colors = new int[256];
				for (int c = 0; c < 256; c++) {
					colors[c] = in.readInt();
				}
				chunks.put(ChunkPos.asLong((regionX << 5) + (local & 31), (regionZ << 5) + (local >> 5)), colors);
			}
		} catch (IOException e) {
			EnderObelisks.LOGGER.warn("Could not read map region {}", file, e);
		}
	}

	public void save() {
		for (long region : dirtyRegions) {
			int regionX = ChunkPos.getX(region);
			int regionZ = ChunkPos.getZ(region);
			Path file = regionFile(regionX, regionZ);
			try {
				Files.createDirectories(dir);
				Map<Integer, int[]> inRegion = new HashMap<>();
				for (int local = 0; local < 1024; local++) {
					int[] colors = chunks.get(ChunkPos.asLong((regionX << 5) + (local & 31), (regionZ << 5) + (local >> 5)));
					if (colors != null) {
						inRegion.put(local, colors);
					}
				}
				Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
				try (OutputStream raw = Files.newOutputStream(tmp); DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
					out.writeInt(inRegion.size());
					for (Map.Entry<Integer, int[]> entry : inRegion.entrySet()) {
						out.writeShort(entry.getKey());
						for (int color : entry.getValue()) {
							out.writeInt(color);
						}
					}
				}
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				EnderObelisks.LOGGER.warn("Could not save map region {}", file, e);
			}
		}
		dirtyRegions.clear();
	}

	private Path regionFile(int regionX, int regionZ) {
		return dir.resolve("r." + regionX + "." + regionZ + ".bin");
	}
}
