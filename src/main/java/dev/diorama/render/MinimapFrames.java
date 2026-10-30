package dev.diorama.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.diorama.Diorama;
import dev.diorama.Settings;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

/**
 * The minimap's frame, drawn from a picture a resource pack can replace:
 * {@code assets/diorama/textures/gui/minimap_frame/<frame>_<shape>.png}, like {@code classic_round.png}.
 * The picture shows the frame around a map {@code PICTURE - 2 * OUTSIDE} wide, and only its outer
 * {@link #BAND} is used, so it draws at any minimap size at the same thickness: a square's corners
 * stay as they are and its edges repeat, and a ring bends to fit. Hand-drawn, a file twice as wide
 * is the same picture in finer detail. Vanilla's GUI sprites don't exist on every version.
 */
public final class MinimapFrames {
	/** A picture's width in GUI pixels, whatever the file's own size. */
	private static final float PICTURE = 128;
	/** How far a picture reaches past the map, into the room the corner's margin and the info lines leave. */
	private static final int OUTSIDE = 4;
	/** The width of the part of a picture that's used. What's inside {@link #OUTSIDE} lies over the map's edge. */
	private static final int BAND = 16;
	private static final String FOLDER = "textures/gui/minimap_frame/";
	private static final Map<String, Optional<Picture>> PICTURES = new HashMap<>();

	private MinimapFrames() {
	}

	/** A loaded picture, at the top left of its square texture: {@code u} and {@code v} are how much of it the picture fills. */
	private record Picture(MapTexture texture, float u, float v) {
	}

	/** Draws the frame around a map {@code size} wide at (x, y). Only its rim, so a see-through map stays see-through. */
	public static void draw(GuiGraphicsExtractor graphics, Settings.Frame frame, boolean round, int x, int y, int size) {
		if (frame == Settings.Frame.NONE) {
			return;
		}
		Picture picture = picture(frame.name().toLowerCase(Locale.ROOT) + (round ? "_round" : "_square"));
		if (picture == null) {
			return;
		}
		Circle.Quads quads = round
			? new Circle(x + size / 2.0F, y + size / 2.0F, size / 2.0F + OUTSIDE).ring(BAND, BAND / PICTURE)
			: square(x - OUTSIDE, y - OUTSIDE, size + 2 * OUTSIDE);
		float[] uvs = quads.uvs();
		for (int i = 0; i < uvs.length; i += 2) {
			uvs[i] *= picture.u;
			uvs[i + 1] *= picture.v;
		}
		// A square's pixels land on the GUI's; a ring's never do, and its curve only stays clean smoothed.
		MapCanvas.of(graphics).quad(picture.texture, round ? Sampling.CLAMP_LINEAR : Sampling.CLAMP_CRISP, quads.corners(), uvs, 0xFFFFFFFF);
	}

	/** Forgets the pictures: a resource reload may have changed them. */
	public static void reload() {
		PICTURES.values().forEach(known -> known.ifPresent(picture -> picture.texture.close()));
		PICTURES.clear();
	}

	/**
	 * The square frame around a box {@code width} wide at (x, y): the picture's four corners, and
	 * the picture's edges between them, repeated out from the middle of each side. A picture with
	 * something in the middle of a side keeps it there, and both ends are cut the same.
	 */
	private static Circle.Quads square(int x, int y, int width) {
		float length = width - 2 * BAND;
		float tile = PICTURE - 2 * BAND;
		int tiles = (int) Math.ceil(length / tile);
		tiles += 1 - tiles % 2;
		float start = (length - tiles * tile) / 2;
		float[] corners = new float[(4 + 4 * tiles) * 8];
		float[] uvs = new float[corners.length];
		int quad = 0;
		float far = PICTURE - BAND;
		int x1 = x + width - BAND;
		int y1 = y + width - BAND;
		quad = rect(corners, uvs, quad, x, y, BAND, BAND, 0, 0);
		quad = rect(corners, uvs, quad, x1, y, BAND, BAND, far, 0);
		quad = rect(corners, uvs, quad, x, y1, BAND, BAND, 0, far);
		quad = rect(corners, uvs, quad, x1, y1, BAND, BAND, far, far);
		for (int i = 0; i < tiles; i++) {
			float tileStart = start + i * tile;
			float from = Math.max(0, tileStart);
			float to = Math.min(length, tileStart + tile);
			float along = BAND + from - tileStart;
			quad = rect(corners, uvs, quad, x + BAND + from, y, to - from, BAND, along, 0);
			quad = rect(corners, uvs, quad, x + BAND + from, y1, to - from, BAND, along, far);
			quad = rect(corners, uvs, quad, x, y + BAND + from, BAND, to - from, 0, along);
			quad = rect(corners, uvs, quad, x1, y + BAND + from, BAND, to - from, far, along);
		}
		return new Circle.Quads(corners, uvs);
	}

	/** Adds a {@code w} x {@code h} piece at (x, y), cut from the picture at (u, v) the same size. Returns the next quad's index. */
	private static int rect(float[] corners, float[] uvs, int quad, float x, float y, float w, float h, float u, float v) {
		int at = quad * 8;
		float[] xy = {x, y, x, y + h, x + w, y + h, x + w, y};
		float[] uv = {u, v, u, v + h, u + w, v + h, u + w, v};
		System.arraycopy(xy, 0, corners, at, 8);
		for (int i = 0; i < 8; i++) {
			uvs[at + i] = uv[i] / PICTURE;
		}
		return quad + 1;
	}

	private static @Nullable Picture picture(String name) {
		Optional<Picture> known = PICTURES.get(name);
		if (known == null) {
			known = Optional.ofNullable(load(name));
			PICTURES.put(name, known);
		}
		return known.orElse(null);
	}

	private static @Nullable Picture load(String name) {
		Identifier file = Identifier.fromNamespaceAndPath(Diorama.MOD_ID, FOLDER + name + ".png");
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(file);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			int width = image.getWidth();
			int height = image.getHeight();
			int[] argb = new int[width * height];
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					argb[y * width + x] = WaypointIcons.argb(image, x, y);
				}
			}
			int edge = Math.max(width, height);
			MapTexture texture = MapTexture.create("diorama minimap frame", edge, 1);
			texture.write(0, 0, 0, edge, PinArt.fromArgb(width, height, argb).abgr(edge));
			return new Picture(texture, (float) width / edge, (float) height / edge);
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Can't read the minimap frame {}", file, e);
			return null;
		}
	}
}
