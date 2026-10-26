package dev.diorama.render;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.ChunkSource;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.SavedChunk;
import dev.diorama.data.SavedChunks;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Draws the chunks in the world's save that the map never saw onto the saved map, so places a
 * pre-generation tool (Chunky and the like) made show up without walking there. Singleplayer
 * only: on a server the save isn't here (see {@link SavedChunks}). Only the sky view: cave slices
 * keep showing only what the player has been near.
 *
 * <p>Runs on its own low-priority thread. A round lists the save's chunks, picks the fully
 * generated ones it hasn't drawn (or that were saved again since), and bakes every sky view tile
 * they show in, nearest the player first. Each tile is merged into the saved map like any bake.
 * Progress is kept per chunk, by when the save last wrote it, in the sky view's folder, so a
 * later visit only draws what changed. Rounds repeat every so often, so a pre-generation running
 * while the player plays shows up as it goes.
 */
public final class SavedChunkImport {
	private static final long RESCAN_MS = 30_000;
	/** Chunk snapshots kept between tiles; neighbouring tiles read many of the same chunks. */
	private static final int CACHED_SNAPSHOTS = 768;
	private static final int CACHED_SAVED = 1024;
	/** Tiles are baked in columns this many tiles wide, north to south, so the cache stays warm. */
	private static final int STRIP_TILES = 4;
	private static final long SAVE_PROGRESS_MS = 10_000;

	private final MapLevel map;
	private final SavedChunks save;
	private final Thread thread;
	private volatile boolean stopped;

	private SavedChunkImport(MapLevel map, SavedChunks save) {
		this.map = map;
		this.save = save;
		this.thread = new Thread(this::run, "diorama-saved-chunks");
		this.thread.setDaemon(true);
		this.thread.setPriority(Thread.MIN_PRIORITY);
	}

	/** Starts drawing the save behind this map, or returns null when there's no save here. */
	public static @Nullable SavedChunkImport start(MapLevel map, net.minecraft.client.multiplayer.ClientLevel level) {
		SavedChunks save = SavedChunks.open(level);
		if (save == null) {
			return null;
		}
		SavedChunkImport job = new SavedChunkImport(map, save);
		job.thread.start();
		return job;
	}

	/** Stops, waiting a moment for a tile being baked, so the map can close after. */
	public void stop() {
		this.stopped = true;
		this.save.close();
		this.thread.interrupt();
		try {
			this.thread.join(5000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void run() {
		while (!this.stopped) {
			try {
				if (!this.round()) {
					Thread.sleep(RESCAN_MS);
				}
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				if (this.stopped) {
					return;
				}
				Diorama.LOGGER.error("Drawing saved chunks failed", t);
				try {
					Thread.sleep(RESCAN_MS);
				} catch (InterruptedException e) {
					return;
				}
			}
		}
	}

	/** One pass over the save. Returns whether it drew anything, so another pass may follow at once. */
	private boolean round() throws InterruptedException {
		Path folder = this.map.importFolder();
		BlockLooks looks = BlockLooks.get();
		if (folder == null || looks == null || !this.save.isOpen()) {
			return false;
		}
		long start = System.nanoTime();
		Progress progress = Progress.load(folder.resolve("saved-chunks.bin"));
		Map<Long, int[]> regions = this.save.scan();

		// Chunks saved since they were last drawn. Chunks the client has loaded are drawn by the
		// map as usual, from fresher data than the save.
		List<long[]> candidates = new ArrayList<>();
		for (Map.Entry<Long, int[]> region : regions.entrySet()) {
			int regionX = (int) (region.getKey() >> 32);
			int regionZ = (int) (long) region.getKey();
			int[] times = region.getValue();
			for (int i = 0; i < 1024; i++) {
				int time = times[i];
				if (time == 0 || progress.time(regionX, regionZ, i) == time) {
					continue;
				}
				int cx = (regionX << 5) | (i & 31);
				int cz = (regionZ << 5) | (i >> 5);
				if (this.map.chunk(cx, cz) != null) {
					progress.mark(regionX, regionZ, i, time);
				} else {
					candidates.add(new long[] {ChunkSnapshot.key(cx, cz), time});
				}
			}
		}
		if (candidates.isEmpty()) {
			progress.saveIfChanged();
			return false;
		}

		// Which are fully generated, and how high they reach, from their height maps alone.
		Map<TileKey, List<long[]>> tiles = new HashMap<>();
		int top = Integer.MIN_VALUE;
		int full = 0;
		for (long[] candidate : candidates) {
			if (this.stopped) {
				return false;
			}
			int cx = (int) (candidate[0] >> 32);
			int cz = (int) candidate[0];
			SavedChunks.Summary summary = this.save.summary(cx, cz);
			if (summary == null || !summary.full()) {
				// Nothing to draw yet. Once it's finished, the save writes it again.
				progress.mark(cx >> 5, cz >> 5, ((cz & 31) << 5) | (cx & 31), (int) candidate[1]);
				continue;
			}
			full++;
			top = Math.max(top, summary.maxY() + 1);
			for (TileKey key : this.tilesShowing(cx, cz, summary)) {
				tiles.computeIfAbsent(key, k -> new ArrayList<>()).add(candidate);
			}
		}
		long scanned = System.nanoTime();
		Diorama.LOGGER.info("Saved chunks: {} new or changed in {} region files, {} fully generated, in {} tiles; listed in {} ms",
			candidates.size(), regions.size(), full, tiles.size(), (scanned - start) / 1_000_000);
		if (tiles.isEmpty()) {
			progress.saveIfChanged();
			return false;
		}

		// How many of its tiles each chunk still waits for; it's done when none are left.
		Map<Long, Integer> waiting = new HashMap<>();
		for (List<long[]> chunks : tiles.values()) {
			for (long[] chunk : chunks) {
				waiting.merge(chunk[0], 1, Integer::sum);
			}
		}
		List<TileKey> order = this.order(tiles.keySet());
		Source source = new Source(Math.max(top, this.map.topY()));
		long lastSave = System.currentTimeMillis();
		long bakeNanos = 0;
		int baked = 0;
		for (TileKey key : order) {
			if (this.stopped || !folder.equals(this.map.importFolder())) {
				// Left the world, or the sky view moved to another folder: its own progress applies there.
				progress.saveIfChanged();
				return false;
			}
			long t0 = System.nanoTime();
			int generation = this.map.bakeImported(key, source, looks, () -> this.stopped);
			bakeNanos += System.nanoTime() - t0;
			if (generation != this.map.importGeneration(key)) {
				// New block colours meanwhile: the map drops this bake. Start over with the new look.
				progress.saveIfChanged();
				return true;
			}
			baked++;
			for (long[] chunk : tiles.get(key)) {
				if (waiting.merge(chunk[0], -1, Integer::sum) == 0) {
					int cx = (int) (chunk[0] >> 32);
					int cz = (int) chunk[0];
					progress.mark(cx >> 5, cz >> 5, ((cz & 31) << 5) | (cx & 31), (int) chunk[1]);
				}
			}
			if (baked % 25 == 0) {
				Diorama.LOGGER.info("Saved chunks: drew {} of {} tiles, {} ms a tile; read {} chunks in {} ms",
					baked, order.size(), bakeNanos / baked / 1_000_000, source.reads, source.readNanos / 1_000_000);
			}
			if (System.currentTimeMillis() - lastSave > SAVE_PROGRESS_MS) {
				progress.saveIfChanged();
				lastSave = System.currentTimeMillis();
			}
		}
		progress.saveIfChanged();
		Diorama.LOGGER.info("Saved chunks: drew {} tiles in {} s ({} ms a tile); read {} chunks in {} ms; listing took {} ms",
			baked, (System.nanoTime() - start) / 1_000_000_000.0, baked == 0 ? 0 : bakeNanos / baked / 1_000_000,
			source.reads, source.readNanos / 1_000_000, (scanned - start) / 1_000_000);
		return true;
	}

	/**
	 * The sky view tiles a chunk can show in, as {@link MapLevel} works it out for a newly loaded
	 * chunk: its own blocks, plus the shadows and sky occlusion they cast on blocks nearby.
	 */
	private List<TileKey> tilesShowing(int cx, int cz, SavedChunks.Summary summary) {
		int x0 = cx << 4;
		int z0 = cz << 4;
		int x1 = x0 + 15;
		int z1 = z0 + 15;
		double top = summary.maxY() + 2;
		double bottom = summary.floorY() - 1 - TileBaker.SHADOW_REACH_Y - TileBaker.SKY_REACH;
		if (this.map.roofless()) {
			// Under a roof the view starts at the roof and can reach any floor below it.
			top = Math.min(top, this.map.roof());
			bottom = this.map.minY() - 1;
		}
		double u0 = x0 - 1 - TileBaker.SKY_REACH;
		double u1 = x1 + 2 + Math.max(TileBaker.SHADOW_REACH_X, TileBaker.SKY_REACH);
		double v0 = Projection.v(top, z0 - 1 - Math.max(TileBaker.SHADOW_REACH_Z, TileBaker.SKY_REACH));
		double v1 = Projection.v(bottom, z1 + 2 + TileBaker.SKY_REACH);
		double units = Projection.TILE_UNITS;
		List<TileKey> keys = new ArrayList<>();
		for (int tu = (int) Math.floor(u0 / units); tu <= (int) Math.floor(u1 / units); tu++) {
			for (int tv = (int) Math.floor(v0 / units); tv <= (int) Math.floor(v1 / units); tv++) {
				keys.add(new TileKey(TileKey.SURFACE, tu, tv));
			}
		}
		return keys;
	}

	/** Strips of tiles nearest the player first, each north to south, so chunks read for one tile serve the next. */
	private List<TileKey> order(java.util.Set<TileKey> keys) {
		LocalPlayer player = Minecraft.getInstance().player;
		double pu = player == null ? 0 : player.getX();
		double pv = player == null ? 0 : Projection.v(player.getY(), player.getZ());
		Map<Integer, List<TileKey>> strips = new HashMap<>();
		for (TileKey key : keys) {
			strips.computeIfAbsent(Math.floorDiv(key.tileU(), STRIP_TILES), s -> new ArrayList<>()).add(key);
		}
		List<List<TileKey>> sorted = new ArrayList<>(strips.values());
		for (List<TileKey> strip : sorted) {
			strip.sort(Comparator.comparingInt(TileKey::tileV).thenComparingInt(TileKey::tileU));
		}
		sorted.sort(Comparator.comparingDouble(strip -> {
			double best = Double.MAX_VALUE;
			for (TileKey key : strip) {
				double du = key.minU() + Projection.TILE_UNITS / 2.0 - pu;
				double dv = key.minV() + Projection.TILE_UNITS / 2.0 - pv;
				best = Math.min(best, du * du + dv * dv);
			}
			return best;
		}));
		List<TileKey> order = new ArrayList<>(keys.size());
		sorted.forEach(order::addAll);
		return order;
	}

	/**
	 * What import bakes read: the client's own snapshot where it has one, else the chunk as saved.
	 * Import thread only.
	 */
	private final class Source implements ChunkSource {
		private final int topY;
		private final Map<Long, @Nullable ChunkSnapshot> snapshots = lru(CACHED_SNAPSHOTS);
		private final Map<Long, @Nullable SavedChunk> saved = lru(CACHED_SAVED);
		int reads;
		long readNanos;

		Source(int topY) {
			this.topY = topY;
		}

		@Override
		public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
			ChunkSnapshot live = SavedChunkImport.this.map.chunk(chunkX, chunkZ);
			if (live != null) {
				return live;
			}
			long key = ChunkSnapshot.key(chunkX, chunkZ);
			if (this.snapshots.containsKey(key)) {
				return this.snapshots.get(key);
			}
			SavedChunk chunk = this.saved(chunkX, chunkZ);
			ChunkSnapshot snapshot = null;
			if (chunk != null && chunk.full()) {
				SavedChunk[] around = new SavedChunk[9];
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						around[(dx + 1) * 3 + dz + 1] = dx == 0 && dz == 0 ? chunk : this.saved(chunkX + dx, chunkZ + dz);
					}
				}
				snapshot = ChunkSnapshot.fromSave(chunk, around, SavedChunkImport.this.save.level());
			}
			this.snapshots.put(key, snapshot);
			return snapshot;
		}

		private @Nullable SavedChunk saved(int chunkX, int chunkZ) {
			long key = ChunkSnapshot.key(chunkX, chunkZ);
			if (this.saved.containsKey(key)) {
				return this.saved.get(key);
			}
			long t0 = System.nanoTime();
			SavedChunk chunk = SavedChunkImport.this.save.read(chunkX, chunkZ);
			this.readNanos += System.nanoTime() - t0;
			this.reads++;
			this.saved.put(key, chunk);
			return chunk;
		}

		@Override
		public int minY() {
			return SavedChunkImport.this.map.minY();
		}

		@Override
		public int maxY() {
			return SavedChunkImport.this.map.maxY();
		}

		@Override
		public int topY() {
			return this.topY;
		}

		@Override
		public int roof() {
			return SavedChunkImport.this.map.roof();
		}

		@Override
		public boolean isSeen(int x, int y, int z) {
			return SavedChunkImport.this.map.isSeen(x, y, z);
		}
	}

	private static <V> Map<Long, V> lru(int size) {
		return new LinkedHashMap<>(size, 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
				return this.size() > size;
			}
		};
	}

	/**
	 * Which saved chunks are drawn: for each, when the save had last written it at the time. Kept
	 * in {@code saved-chunks.bin}: {@code DSC1}, then deflated, the region count and, per region,
	 * its x, z and 1024 save times.
	 */
	private static final class Progress {
		private static final int MAGIC = 0x44534331;
		private final Path file;
		private final Map<Long, int[]> times = new HashMap<>();
		private boolean changed;

		private Progress(Path file) {
			this.file = file;
		}

		static Progress load(Path file) {
			Progress progress = new Progress(file);
			if (!Files.exists(file)) {
				return progress;
			}
			try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
				if (in.readInt() != MAGIC) {
					return progress;
				}
				DataInputStream data = new DataInputStream(new InflaterInputStream(in));
				int count = data.readInt();
				for (int r = 0; r < count; r++) {
					int x = data.readInt();
					int z = data.readInt();
					int[] times = new int[1024];
					for (int i = 0; i < 1024; i++) {
						times[i] = data.readInt();
					}
					progress.times.put(SavedChunks.regionKey(x, z), times);
				}
			} catch (IOException e) {
				Diorama.LOGGER.warn("Couldn't read which saved chunks are drawn from {}; drawing them again", file, e);
				progress.times.clear();
			}
			return progress;
		}

		int time(int regionX, int regionZ, int index) {
			int[] times = this.times.get(SavedChunks.regionKey(regionX, regionZ));
			return times == null ? 0 : times[index];
		}

		void mark(int regionX, int regionZ, int index, int time) {
			int[] times = this.times.computeIfAbsent(SavedChunks.regionKey(regionX, regionZ), k -> new int[1024]);
			if (times[index] != time) {
				times[index] = time;
				this.changed = true;
			}
		}

		void saveIfChanged() {
			if (!this.changed) {
				return;
			}
			try {
				Files.createDirectories(this.file.getParent());
				Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
				try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temp))) {
					out.writeInt(MAGIC);
					DeflaterOutputStream deflated = new DeflaterOutputStream(out);
					DataOutputStream data = new DataOutputStream(deflated);
					data.writeInt(this.times.size());
					for (Map.Entry<Long, int[]> e : this.times.entrySet()) {
						data.writeInt((int) (e.getKey() >> 32));
						data.writeInt((int) (long) e.getKey());
						for (int time : e.getValue()) {
							data.writeInt(time);
						}
					}
					data.flush();
					deflated.finish();
				}
				Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				this.changed = false;
			} catch (IOException e) {
				Diorama.LOGGER.warn("Couldn't save which saved chunks are drawn to {}", this.file, e);
			}
		}
	}
}
