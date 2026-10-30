package dev.diorama.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.MinecraftServer;

/**
 * Pauses the singleplayer world while a test waits for the map to finish baking, as {@code /tick
 * freeze} does. A new world never goes quiet by itself: water and lava from world generation keep
 * flowing and vines keep growing, so some tile near spawn re-bakes every second or two, and a wait
 * for 30 ticks with nothing baking ran to its 1200-tick limit, a minute here and two on boat.dev.
 * Paused, chunks still load and send and the player still moves; blocks, fluids, mobs and time
 * stand still. Nothing is paused on a dedicated server, nor on 1.20.1, which has no tick freeze.
 */
final class WorldPause implements AutoCloseable {
	private final ClientGameTestContext context;
	private final boolean paused;

	private WorldPause(ClientGameTestContext context, boolean paused) {
		this.context = context;
		this.paused = paused;
	}

	static WorldPause pause(ClientGameTestContext context) {
		//? if >=1.21 {
		boolean paused = context.computeOnClient(mc -> {
			MinecraftServer server = mc.getSingleplayerServer();
			// A world the test froze itself stays as the test left it.
			if (server == null || server.tickRateManager().isFrozen()) {
				return false;
			}
			server.execute(() -> server.tickRateManager().setFrozen(true));
			return true;
		});
		//?} else {
		/*boolean paused = false;
		*///?}
		return new WorldPause(context, paused);
	}

	@Override
	public void close() {
		if (!this.paused) {
			return;
		}
		//? if >=1.21 {
		this.context.runOnClient(mc -> {
			MinecraftServer server = mc.getSingleplayerServer();
			if (server != null) {
				server.execute(() -> server.tickRateManager().setFrozen(false));
			}
		});
		//?}
		// One tick for the server to run it, so what the test does next finds the world running.
		this.context.waitTick();
	}
}
