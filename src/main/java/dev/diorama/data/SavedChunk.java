package dev.diorama.data;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.jspecify.annotations.Nullable;

/**
 * One chunk as the world's save has it, read for {@link ChunkSnapshot#fromSave}. Only a finished
 * chunk has blocks worth drawing; the chunks round the edge of a generated area are kept half-done
 * (biomes, maybe bare terrain) and only lend their biomes to tints.
 *
 * @param states block states per section, null where the save has none or the chunk isn't finished
 * @param biomes per section, null where the save has none
 * @param blockLight per section, null where the save stores none or {@link SavedChunks} wasn't asked for light
 * @param skyLight the same
 * @param surface per column, indexed {@code (z << 4) | x}: the top block's y; empty unless finished
 * @param solid the same for the top block that stops movement or holds fluid
 */
public record SavedChunk(
	int chunkX,
	int chunkZ,
	boolean finished,
	@Nullable PalettedContainer<BlockState>[] states,
	@Nullable PalettedContainerRO<Holder<Biome>>[] biomes,
	@Nullable DataLayer[] blockLight,
	@Nullable DataLayer[] skyLight,
	int[] surface,
	int[] solid
) {
}
