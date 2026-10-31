package dev.diorama.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Upgrades a saved chunk the way the game does before loading it, read for the map. */
@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {
	@Invoker("upgradeChunkTag")
	CompoundTag diorama$upgrade(CompoundTag tag);
}
