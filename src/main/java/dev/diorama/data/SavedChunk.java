package dev.diorama.data;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;

/**
 * One chunk as the world's save has it, read for {@link ChunkSnapshot#fromSave}. Only a fully
 * generated chunk has blocks worth drawing; the chunks round the edge of a generated area are
 * kept half-done (biomes, maybe terrain) and only lend their biomes to tints.
 *
 * @param sections block states and biomes per section, null where the save has none
 * @param blockLight per section, null where the save stores none (dark)
 * @param skyLight per section, null where the save stores none (the same as the section above)
 * @param surface per column, indexed {@code (z << 4) | x}: the top block's y; empty unless full
 * @param solid the same for the top block that stops movement or holds fluid
 */
public record SavedChunk(
	int chunkX,
	int chunkZ,
	boolean full,
	@Nullable LevelChunkSection[] sections,
	@Nullable DataLayer[] blockLight,
	@Nullable DataLayer[] skyLight,
	int[] surface,
	int[] solid
) {
}
