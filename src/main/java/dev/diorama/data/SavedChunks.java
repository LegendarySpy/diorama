package dev.diorama.data;

import dev.diorama.Diorama;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The chunks in the world's save, in singleplayer: what a pre-generation tool such as Chunky made
 * and the player never visited. Chunks are read through the game's own server, which owns the
 * files: it hands back chunks it hasn't written yet, upgrades old ones, and never sees its files
 * change under it. Reading is all this does; nothing here loads a chunk into the world.
 *
 * <p>Only the region files' headers are read directly, to list the chunks and when each was last
 * saved. All reads block; call them off the render thread.
 */
public final class SavedChunks {
	private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

	private final ClientLevel level;
	private final ServerLevel serverLevel;
	private final ChunkMap chunkMap;
	private final Path regionFolder;
	/** Builds containers from the client's registries, so biomes match the ones the map already knows. */
	private final PalettedContainerFactory containers;
	private final int heightBits;
	/** Set when the map stops reading: reads waiting on a server that's shutting down give up. */
	private volatile boolean closed;

	private SavedChunks(ClientLevel level, ServerLevel serverLevel, Path regionFolder) {
		this.level = level;
		this.serverLevel = serverLevel;
		this.chunkMap = serverLevel.getChunkSource().chunkMap;
		this.regionFolder = regionFolder;
		this.containers = level.palettedContainerFactory();
		this.heightBits = Mth.ceillog2(level.getHeight() + 1);
	}

	/** The save behind this level, or null when there's none to read: on a server, the save isn't here. */
	public static @Nullable SavedChunks open(ClientLevel level) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return null;
		}
		ServerLevel serverLevel = server.getLevel(level.dimension());
		if (serverLevel == null) {
			return null;
		}
		Path folder = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("region");
		return new SavedChunks(level, serverLevel, folder);
	}

	/** Stops reading; reads in progress return nothing. */
	public void close() {
		this.closed = true;
	}

	/**
	 * Waits for the server's storage thread. A server shutting down drops the reads still queued
	 * without answering them, so this keeps checking whether to give up.
	 */
	private <T> @Nullable T await(CompletableFuture<T> future) {
		while (!this.closed) {
			try {
				return future.get(100, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				// Keep waiting.
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			} catch (ExecutionException e) {
				throw new CompletionException(e.getCause());
			}
		}
		return null;
	}

	public ClientLevel level() {
		return this.level;
	}

	/** Whether the server behind this save is still the one running. */
	public boolean isOpen() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		return server != null && server.isRunning() && server.getLevel(this.level.dimension()) == this.serverLevel;
	}

	public static long regionKey(int regionX, int regionZ) {
		return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
	}

	/**
	 * Every region file's chunks, by {@link #regionKey}: for each of its 32 × 32 chunks, indexed
	 * {@code (z << 5) | x}, when the save last wrote it in seconds, or 0 if it has none. Chunks the
	 * server hasn't written to its files yet are left out until it does.
	 */
	public Map<Long, int[]> scan() {
		Map<Long, int[]> regions = new HashMap<>();
		if (!Files.isDirectory(this.regionFolder)) {
			return regions;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(this.regionFolder, "r.*.mca")) {
			for (Path file : files) {
				Matcher m = REGION_FILE.matcher(file.getFileName().toString());
				if (!m.matches()) {
					continue;
				}
				int[] times = readHeader(file);
				if (times != null) {
					regions.put(regionKey(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), times);
				}
			}
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't list the saved chunks in {}", this.regionFolder, e);
		}
		return regions;
	}

	/** A region file's chunk save times, from its header: 4 KiB of locations, then 4 KiB of times. */
	private static int @Nullable [] readHeader(Path file) {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer header = ByteBuffer.allocate(8192);
			while (header.hasRemaining() && channel.read(header) >= 0) {
			}
			if (header.hasRemaining()) {
				// New and still empty, or cut short.
				return null;
			}
			header.flip();
			int[] times = new int[1024];
			for (int i = 0; i < 1024; i++) {
				int location = header.getInt(i * 4);
				int time = header.getInt(4096 + i * 4);
				times[i] = location == 0 ? 0 : Math.max(1, time);
			}
			return times;
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't read the region file header of {}", file, e);
			return null;
		}
	}

	/** What a chunk's save says about it without reading its blocks; see {@link #summary}. */
	public record Summary(boolean full, int maxY, int floorY) {
	}

	/**
	 * Whether the chunk is fully generated and how high it reaches, from its status and height
	 * maps alone, or null if the save has none. Much cheaper than {@link #read}. Blocks.
	 */
	public @Nullable Summary summary(int chunkX, int chunkZ) {
		ChunkPos pos = new ChunkPos(chunkX, chunkZ);
		CollectFields fields = new CollectFields(
			new FieldSelector(IntTag.TYPE, "DataVersion"),
			new FieldSelector(StringTag.TYPE, "Status"),
			new FieldSelector(CompoundTag.TYPE, "Heightmaps")
		);
		try {
			this.await(this.chunkMap.chunkScanner().scanChunk(pos, fields));
		} catch (RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't scan saved chunk {}", pos, e);
			return null;
		}
		if (!(fields.getResult() instanceof CompoundTag tag) || tag.isEmpty()) {
			return null;
		}
		if (NbtUtils.getDataVersion(tag, -1) < SharedConstants.getCurrentVersion().dataVersion().version()) {
			// Saved by an older version: the server upgrades it on load, and so must we.
			SavedChunk chunk = this.read(chunkX, chunkZ);
			return chunk == null ? null : summaryOf(chunk);
		}
		boolean full = tag.read("Status", ChunkStatus.CODEC).orElse(ChunkStatus.EMPTY).isOrAfter(ChunkStatus.FULL);
		if (!full) {
			return new Summary(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
		}
		CompoundTag heightmaps = tag.getCompoundOrEmpty("Heightmaps");
		int[] surface = this.heights(heightmaps.getLongArray(Heightmap.Types.WORLD_SURFACE.getSerializationKey()));
		int[] solid = this.heights(heightmaps.getLongArray(Heightmap.Types.MOTION_BLOCKING.getSerializationKey()));
		return surface == null || solid == null ? new Summary(false, Integer.MIN_VALUE, Integer.MIN_VALUE) : summaryOf(surface, solid);
	}

	private static Summary summaryOf(SavedChunk chunk) {
		return chunk.full() ? summaryOf(chunk.surface(), chunk.solid()) : new Summary(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
	}

	private static Summary summaryOf(int[] surface, int[] solid) {
		int top = Integer.MIN_VALUE;
		int floor = Integer.MAX_VALUE;
		for (int i = 0; i < 256; i++) {
			top = Math.max(top, surface[i]);
			floor = Math.min(floor, solid[i]);
		}
		return new Summary(true, top, floor - 24);
	}

	/**
	 * The chunk as saved, or null if the save has none or it can't be read. Blocks while the
	 * server's storage thread reads it.
	 */
	public @Nullable SavedChunk read(int chunkX, int chunkZ) {
		ChunkPos pos = new ChunkPos(chunkX, chunkZ);
		try {
			Optional<CompoundTag> read = this.await(this.chunkMap.read(pos));
			CompoundTag tag = read == null ? null : read.orElse(null);
			if (tag == null) {
				return null;
			}
			int current = SharedConstants.getCurrentVersion().dataVersion().version();
			if (NbtUtils.getDataVersion(tag, -1) < current) {
				Optional<net.minecraft.resources.Identifier> generator = this.serverLevel.getChunkSource().getGenerator().getTypeNameForDataFixer();
				tag = this.chunkMap.upgradeChunkTag(tag, -1, ChunkMap.getChunkDataFixContextTag(this.level.dimension(), generator), current);
			}
			SerializableChunkData data = SerializableChunkData.parse(this.level, this.containers, tag);
			if (data == null) {
				return null;
			}
			int count = this.level.getSectionsCount();
			int minSection = this.level.getMinSectionY();
			LevelChunkSection[] sections = new LevelChunkSection[count];
			DataLayer[] blockLight = new DataLayer[count];
			DataLayer[] skyLight = new DataLayer[count];
			for (SerializableChunkData.SectionData section : data.sectionData()) {
				int i = section.y() - minSection;
				if (i >= 0 && i < count) {
					sections[i] = section.chunkSection();
					blockLight[i] = section.blockLight();
					skyLight[i] = section.skyLight();
				}
			}
			boolean full = data.chunkStatus().isOrAfter(ChunkStatus.FULL);
			int[] surface = full ? this.heights(data.heightmaps().get(Heightmap.Types.WORLD_SURFACE)) : null;
			int[] solid = full ? this.heights(data.heightmaps().get(Heightmap.Types.MOTION_BLOCKING)) : null;
			full &= surface != null && solid != null;
			return new SavedChunk(chunkX, chunkZ, full, sections, blockLight, skyLight, full ? surface : new int[0], full ? solid : new int[0]);
		} catch (RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read saved chunk {}", pos, e);
			return null;
		}
	}

	/** A saved height map as each column's top block y, or null if it's missing or the wrong size. */
	private int @Nullable [] heights(long @Nullable [] packed) {
		if (packed == null) {
			return null;
		}
		try {
			SimpleBitStorage storage = new SimpleBitStorage(this.heightBits, 256, packed);
			int[] heights = new int[256];
			int minY = this.level.getMinY();
			for (int i = 0; i < 256; i++) {
				// Saved as the first free y above the column, counted from the bottom of the world.
				heights[i] = storage.get(i) + minY - 1;
			}
			return heights;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private int @Nullable [] heights(Optional<long[]> packed) {
		return this.heights(packed.orElse(null));
	}
}
