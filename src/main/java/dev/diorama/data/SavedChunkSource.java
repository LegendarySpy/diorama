package dev.diorama.data;

import dev.diorama.bake.ChunkSource;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a bake from the world's save reads: the map's own snapshot where it has one, since that's
 * the live world and the save can be behind it, else the chunk as saved. One thread only.
 */
public final class SavedChunkSource implements ChunkSource {
	/** Snapshots kept between tiles: neighbouring tiles read many of the same chunks. */
	private static final int CACHED_SNAPSHOTS = 768;

	private final ChunkSource live;
	private final SavedChunks save;
	private final Map<Long, @Nullable ChunkSnapshot> snapshots = new LinkedHashMap<>(CACHED_SNAPSHOTS, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, @Nullable ChunkSnapshot> eldest) {
			return this.size() > CACHED_SNAPSHOTS;
		}
	};

	public SavedChunkSource(ChunkSource live, SavedChunks save) {
		this.live = live;
		this.save = save;
	}

	@Override
	public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
		ChunkSnapshot live = this.live.chunk(chunkX, chunkZ);
		if (live != null) {
			return live;
		}
		long key = ChunkSnapshot.key(chunkX, chunkZ);
		if (this.snapshots.containsKey(key)) {
			return this.snapshots.get(key);
		}
		SavedChunk chunk = this.save.read(chunkX, chunkZ);
		ChunkSnapshot snapshot = null;
		if (chunk != null && chunk.finished()) {
			SavedChunk[] around = new SavedChunk[9];
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					around[(dx + 1) * 3 + dz + 1] = dx == 0 && dz == 0 ? chunk : this.save.read(chunkX + dx, chunkZ + dz);
				}
			}
			snapshot = ChunkSnapshot.fromSave(chunk, around, this.save.level());
		}
		this.snapshots.put(key, snapshot);
		return snapshot;
	}

	/** Lets go of everything read: the save may have moved on, and between jobs it's only memory. */
	public void clear() {
		this.snapshots.clear();
		this.save.clear();
	}

	@Override
	public int minY() {
		return this.live.minY();
	}

	@Override
	public int maxY() {
		return this.live.maxY();
	}

	/**
	 * The world's ceiling: how high this bake's chunks reach isn't known until it reads them, and
	 * each bake works that out for its own tile.
	 */
	@Override
	public int topY() {
		return this.live.maxY();
	}

	@Override
	public int roof() {
		return this.live.roof();
	}

	@Override
	public boolean isSeen(int x, int y, int z) {
		return this.live.isSeen(x, y, z);
	}
}
