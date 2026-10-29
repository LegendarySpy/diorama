package dev.diorama.light;

import dev.diorama.Diorama;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.SurfaceArea;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.VoxelReader;
import dev.diorama.render.Circle;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapViewport;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Light carried around a cave slice: a torch or lantern in a player's hand lights the cave around
 * them on the map as it does in the game. The surface map is a daytime view, where it wouldn't
 * show, so only cave slices get it.
 *
 * <p>Each light is a level dimmer for each block of the shortest open path to it, diagonals
 * included, so it comes out round in the open and still goes round corners and stops at walls.
 * The pixels it then lights brighter than their tile shows are lit again from what the tile
 * recorded about them ({@link SurfaceArea#relit}), with the same lighting the tile was baked
 * with but the brighter block light, and laid over the tile. Render thread, but the overlays are
 * made on a thread of their own.
 */
public final class Lights {
	private final ClientLevel level;
	private final MapLevel map;
	/** The last overlay made for each player's light. */
	private final Map<UUID, Glow> glows = new HashMap<>();

	/** One light's overlay: {@code width} by {@code height} map pixels from (pu0, pv0) in an {@code edge}-square texture. */
	private static final class Glow {
		@Nullable Object madeFor;
		@Nullable MapTexture texture;
		int edge;
		int pu0;
		int pv0;
		int width;
		int height;
		boolean lit;
		@Nullable CompletableFuture<@Nullable Made> pending;
	}

	/** Makes the overlays, off the render thread. */
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "diorama-held-light");
		thread.setDaemon(true);
		return thread;
	});

	public Lights(ClientLevel level, MapLevel map) {
		this.level = level;
		this.map = map;
	}

	/** The light level a player carries, 0..15: the brightest block light in either hand. */
	static int carried(Player player) {
		return Math.max(emission(player.getMainHandItem()), emission(player.getOffhandItem()));
	}

	private static int emission(ItemStack stack) {
		if (stack.is(Items.LAVA_BUCKET)) {
			return 15;
		}
		return stack.getItem() instanceof BlockItem item ? item.getBlock().defaultBlockState().getLightEmission() : 0;
	}

	/** Draws the carried lights over a view of a cave slice. */
	public void draw(GuiGraphicsExtractor graphics, MapViewport.View view, float partialTick, float opacity) {
		if (view.layer() == TileKey.SURFACE) {
			return;
		}
		MapStyle style = MapStyle.get();
		List<Glow> drawn = new ArrayList<>();
		for (Player player : this.level.players()) {
			int carried = carried(player);
			if (carried <= 0) {
				drop(this.glows.remove(player.getUUID()));
				continue;
			}
			Vec3 eye = player.getEyePosition(partialTick);
			// Held at about shoulder height.
			int sx = (int) Math.floor(eye.x);
			int sy = (int) Math.floor(eye.y - 0.5);
			int sz = (int) Math.floor(eye.z);
			if (sy > view.layer()) {
				continue;
			}
			Glow glow = this.glows.computeIfAbsent(player.getUUID(), id -> new Glow());
			this.make(glow, sx, sy, sz, carried, view.layer(), style);
			if (glow.lit && glow.texture != null) {
				drawn.add(glow);
			}
		}
		List<UUID> here = this.level.players().stream().map(Player::getUUID).toList();
		this.glows.entrySet().removeIf(entry -> {
			if (here.contains(entry.getKey())) {
				return false;
			}
			drop(entry.getValue());
			return true;
		});
		if (drawn.isEmpty()) {
			return;
		}
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		MapCanvas canvas = MapCanvas.of(graphics);
		Circle circle = view.round() ? Circle.in(view) : null;
		int tint = Math.round(Math.max(0, Math.min(1, opacity)) * 255) << 24 | 0xFFFFFF;
		double ppu = Projection.PIXELS_PER_UNIT;
		for (Glow glow : drawn) {
			float x0 = view.screenX(glow.pu0 / ppu);
			float y0 = view.screenY(glow.pv0 / ppu);
			float x1 = view.screenX((glow.pu0 + glow.width) / ppu);
			float y1 = view.screenY((glow.pv0 + glow.height) / ppu);
			float tu = (float) glow.width / glow.edge;
			float tv = (float) glow.height / glow.edge;
			if (circle != null) {
				Circle.Quads inside = circle.clip(x0, y0, x1, y1, 0, 0, tu, tv);
				if (inside != null) {
					canvas.quad(glow.texture, MapViewport.sampling(view), inside.corners(), inside.uvs(), tint);
				}
			} else {
				canvas.rect(glow.texture, MapViewport.sampling(view), x0, y0, x1, y1, 0, 0, tu, tv, tint);
			}
		}
		graphics.disableScissor();
	}

	/**
	 * Makes a light's overlay again if it moved to another block, changed strength, or the
	 * ground under it is better known: the pixels it lights brighter than their tile shows. Made
	 * on the light thread; the last one made stays up until the next is ready.
	 */
	private void make(Glow glow, int sx, int sy, int sz, int carried, int layer, MapStyle style) {
		if (glow.pending != null) {
			if (!glow.pending.isDone()) {
				return;
			}
			Made made = glow.pending.join();
			glow.pending = null;
			if (made != null) {
				show(glow, made);
			}
		}
		double sin = Projection.sin();
		double cos = Projection.cos();
		int ppu = Projection.PIXELS_PER_UNIT;
		int reach = carried - 1;
		int pu0 = (sx - reach) * ppu;
		int pu1 = (sx + reach + 1) * ppu;
		int pv0 = (int) Math.floor(((sz - reach) * sin - (sy + reach + 1) * cos) * ppu);
		int pv1 = (int) Math.ceil(((sz + reach + 1) * sin - (sy - reach) * cos) * ppu);
		SurfaceArea area = this.map.surfaces(layer, pu0, pv0, pu1, pv1);
		List<Object> key = List.of(sx, sy, sz, carried, layer, style, sin, java.util.Arrays.asList(area.tiles()));
		if (key.equals(glow.madeFor)) {
			return;
		}
		glow.madeFor = key;
		VoxelReader reader = new VoxelReader(this.map, BlockLooks.get());
		glow.pending = CompletableFuture.supplyAsync(() -> build(reader, area, sx, sy, sz, carried, pu0, pv0, pu1 - pu0, pv1 - pv0, style), WORKER)
			.exceptionally(e -> {
				Diorama.LOGGER.warn("Couldn't light the map around a held light", e);
				return null;
			});
	}

	/** An overlay made: its mips, or null where it lights nothing brighter than the tiles. */
	private record Made(int pu0, int pv0, int width, int height, int edge, int @Nullable [][] mips) {
	}

	private static Made build(VoxelReader reader, SurfaceArea area, int sx, int sy, int sz, int carried, int pu0, int pv0, int width, int height, MapStyle style) {
		double sin = Projection.sin();
		double cos = Projection.cos();
		int ppu = Projection.PIXELS_PER_UNIT;
		int reach = carried - 1;
		int side = 2 * reach + 1;
		float[] levels = spread(reader, sx, sy, sz, carried, reach, side);
		int step = 1 << (Mipmaps.LEVELS - 1);
		int edge = (Math.max(width, height) + step - 1) / step * step;
		int[] pixels = new int[edge * edge];
		float[] scratch = new float[4];
		boolean lit = false;
		for (int py = 0; py < height; py++) {
			for (int px = 0; px < width; px++) {
				int pu = pu0 + px;
				int pv = pv0 + py;
				float y = area.y(pu, pv);
				if (Float.isNaN(y)) {
					continue;
				}
				// Half a block out from the surface the camera sees here, into the open space in
				// front of it, relative to the corner of the cube the light spread over.
				boolean top = area.top(pu, pv);
				double qx = (pu + 0.5) / ppu - sx + reach;
				double qy = y + (top ? 0.5 : 0) - sy + reach;
				double qz = ((pv + 0.5) / ppu + y * cos) / sin + (top ? 0 : 0.5) - sz + reach;
				float light = smooth(levels, side, reader, sx - reach, sy - reach, sz - reach, qx, qy, qz);
				int own = area.blockLight(pu, pv);
				if (light > own) {
					// Faded in over its first level, so the lit patch has no edge against the tile.
					int alpha = Math.round(Math.min(1, light - own) * 255);
					pixels[py * edge + px] = area.relit(pu, pv, light, alpha, style, scratch);
					lit = true;
				}
			}
		}
		return new Made(pu0, pv0, width, height, edge, lit ? Mipmaps.build(pixels, edge) : null);
	}

	/** Puts a made overlay up in place of the last. Render thread. */
	private static void show(Glow glow, Made made) {
		glow.lit = made.mips() != null;
		if (!glow.lit) {
			return;
		}
		if (glow.texture != null && glow.edge != made.edge()) {
			glow.texture.close();
			glow.texture = null;
		}
		if (glow.texture == null) {
			glow.texture = MapTexture.create("diorama held light", made.edge(), Mipmaps.LEVELS);
		}
		for (int level = 0; level < Mipmaps.LEVELS; level++) {
			glow.texture.write(level, 0, 0, made.edge() >> level, made.mips()[level]);
		}
		glow.edge = made.edge();
		glow.pu0 = made.pu0();
		glow.pv0 = made.pv0();
		glow.width = made.width();
		glow.height = made.height();
	}

	/**
	 * Block light from a light of {@code carried} at (sx, sy, sz) over the cube {@code side} blocks
	 * across around it: {@code carried} less the length of the shortest path to each cell through
	 * blocks that aren't solid. Levels by cell, x fastest, then z, then y.
	 */
	private static float[] spread(VoxelReader reader, int sx, int sy, int sz, int carried, int reach, int side) {
		float[] levels = new float[side * side * side];
		// Per cell: 0 not looked at yet, 1 open, 2 closed.
		byte[] open = new byte[levels.length];
		PriorityQueue<Long> queue = new PriorityQueue<>();
		int start = (reach * side + reach) * side + reach;
		levels[start] = carried;
		queue.add((long) start);
		while (!queue.isEmpty()) {
			long entry = queue.poll();
			int cell = (int) entry;
			float distance = Float.intBitsToFloat((int) (entry >>> 32));
			if (carried - distance < levels[cell]) {
				continue;
			}
			int x = cell % side;
			int z = cell / side % side;
			int y = cell / (side * side);
			for (int[] step : STEPS) {
				int nx = x + step[0];
				int ny = y + step[1];
				int nz = z + step[2];
				if (nx < 0 || ny < 0 || nz < 0 || nx >= side || ny >= side || nz >= side) {
					continue;
				}
				int next = (ny * side + nz) * side + nx;
				float further = distance + STEP_LENGTHS[Math.abs(step[0]) + Math.abs(step[1]) + Math.abs(step[2])];
				if (carried - further <= levels[next] || !passes(reader, open, side, sx - reach, sy - reach, sz - reach, x, y, z, step)) {
					continue;
				}
				levels[next] = carried - further;
				queue.add((long) Float.floatToIntBits(further) << 32 | next);
			}
		}
		return levels;
	}

	/** Whether light can step from cell (x, y, z) by {@code step}: every cell the step crosses is open, so it can't squeeze between two blocks that touch at an edge. */
	private static boolean passes(VoxelReader reader, byte[] open, int side, int x0, int y0, int z0, int x, int y, int z, int[] step) {
		// Each non-empty subset of the step's axes is a cell it crosses.
		for (int c = 1; c < 8; c++) {
			int dx = (c & 1) == 0 ? 0 : step[0];
			int dy = (c & 2) == 0 ? 0 : step[1];
			int dz = (c & 4) == 0 ? 0 : step[2];
			if (c != ((dx != 0 ? 1 : 0) | (dy != 0 ? 2 : 0) | (dz != 0 ? 4 : 0))) {
				continue;
			}
			int cell = ((y + dy) * side + z + dz) * side + x + dx;
			if (open[cell] == 0) {
				// Full solid blocks stop it, and so do blocks the map doesn't know yet.
				int solid = reader.solid(x0 + x + dx, y0 + y + dy, z0 + z + dz);
				open[cell] = (byte) (solid == 1 || solid == VoxelReader.UNKNOWN ? 2 : 1);
			}
			if (open[cell] == 2) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The light at point (qx, qy, qz) of the cube, blended from the eight cells around it as the
	 * game's smooth lighting blends them: by how near each cell's middle is, leaving out solid
	 * blocks, so light fades smoothly across a block instead of stepping a level at a time.
	 */
	private static float smooth(float[] levels, int side, VoxelReader reader, int x0, int y0, int z0, double qx, double qy, double qz) {
		int bx = (int) Math.floor(qx - 0.5);
		int by = (int) Math.floor(qy - 0.5);
		int bz = (int) Math.floor(qz - 0.5);
		double fx = qx - 0.5 - bx;
		double fy = qy - 0.5 - by;
		double fz = qz - 0.5 - bz;
		double sum = 0;
		double weights = 0;
		for (int c = 0; c < 8; c++) {
			int x = bx + (c & 1);
			int y = by + (c >> 1 & 1);
			int z = bz + (c >> 2 & 1);
			if (x < 0 || y < 0 || z < 0 || x >= side || y >= side || z >= side || reader.solid(x0 + x, y0 + y, z0 + z) == 1) {
				continue;
			}
			double w = ((c & 1) == 0 ? 1 - fx : fx) * ((c >> 1 & 1) == 0 ? 1 - fy : fy) * ((c >> 2 & 1) == 0 ? 1 - fz : fz);
			sum += w * levels[(y * side + z) * side + x];
			weights += w;
		}
		return weights > 0 ? (float) (sum / weights) : 0;
	}

	/** Every step to a neighbouring cell, through a face, an edge or a corner. */
	private static final int[][] STEPS = steps();
	/** How long a step across one, two or three axes is. */
	private static final float[] STEP_LENGTHS = {0, 1, (float) Math.sqrt(2), (float) Math.sqrt(3)};

	private static int[][] steps() {
		List<int[]> steps = new ArrayList<>();
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					if (dx != 0 || dy != 0 || dz != 0) {
						steps.add(new int[] {dx, dy, dz});
					}
				}
			}
		}
		return steps.toArray(int[][]::new);
	}

	private static void drop(@Nullable Glow glow) {
		if (glow != null && glow.texture != null) {
			glow.texture.close();
		}
	}

	/** Frees the overlays. */
	public void close() {
		this.glows.values().forEach(Lights::drop);
		this.glows.clear();
	}
}
