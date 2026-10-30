package dev.diorama.test;

import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/**
 * The minimap's frame comes from a resource pack's picture (JCS on CurseForge): the example pack's
 * oak and gold frame replaces the Classic frame, square and round, and turning it off brings
 * Diorama's own back. The pictures keep the frames as they looked when drawn in code.
 */
final class MinimapFrameTest {
	/** The pack's gold line, a GUI pixel wide and 2 pixels in from the map's edge, so on top of the map. */
	private static final int GOLD = 0xE8C14A;
	private static final int MARGIN = 6;

	private MinimapFrameTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		context.setScreen(() -> null);
		// The same view on every build, so pictures of two builds can be compared pixel by pixel.
		BlockPos from = context.computeOnClient(mc -> mc.player.blockPosition());
		context.runOnClient(mc -> mc.player.getAbilities().flying = true);
		world.getServer().runCommand("tp @a 0 120 0 0 90");
		context.waitTicks(100);
		int[] saved = context.computeOnClient(mc -> {
			Settings s = Settings.get();
			return new int[] {s.minimapFrame.ordinal(), s.minimapShape.ordinal(), s.minimapSize, s.minimapOpacity};
		});
		photos(context, "default");

		String id = "file/diorama-test-minimap-frame";
		Path pack = context.computeOnClient(mc -> mc.getResourcePackDirectory().resolve("diorama-test-minimap-frame"));
		context.runOnClient(mc -> {
			String folder = "assets/diorama/textures/gui/minimap_frame/";
			for (String file : new String[] {"pack.mcmeta", folder + "classic_square.png", folder + "classic_round.png"}) {
				DioramaClientTest.packFixture("/minimap-frame-pack/" + file, pack.resolve(file));
			}
		});
		DioramaClientTest.reloadPacks(context, mc -> {
			mc.getResourcePackRepository().reload();
			if (!mc.getResourcePackRepository().addPack(id)) {
				throw new AssertionError("The minimap frame test pack didn't load");
			}
			// The pack.mcmeta docs/minimap-frames.md gives, which it says suits every version.
			if (!mc.getResourcePackRepository().getPack(id).getCompatibility().isCompatible()) {
				throw new AssertionError("Minecraft calls the guide's pack.mcmeta incompatible");
			}
		});
		photos(context, "pack");
		set(context, Settings.Frame.CLASSIC, Settings.Shape.SQUARE, 96, 100);
		check(context, true, "the pack's frame around a square minimap");
		set(context, Settings.Frame.CLASSIC, Settings.Shape.ROUND, 96, 100);
		check(context, true, "the pack's frame around a round minimap");
		// The pack only redraws Classic: the other frame stays Diorama's.
		set(context, Settings.Frame.MINECRAFT, Settings.Shape.SQUARE, 96, 100);
		check(context, false, "the Minecraft frame, which the pack doesn't replace");
		// The settings screen's preview draws the same frame.
		set(context, Settings.Frame.CLASSIC, Settings.Shape.ROUND, 96, 100);
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(10);
		context.takeScreenshot("minimap-frames-pack-settings");
		context.setScreen(() -> null);

		DioramaClientTest.reloadPacks(context, mc -> mc.getResourcePackRepository().removePack(id));
		set(context, Settings.Frame.CLASSIC, Settings.Shape.SQUARE, 96, 100);
		check(context, false, "the square frame with the pack off again");
		set(context, Settings.Frame.CLASSIC, Settings.Shape.ROUND, 96, 100);
		check(context, false, "the round frame with the pack off again");

		context.runOnClient(mc -> {
			try (Stream<Path> files = Files.walk(pack)) {
				files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			Settings s = Settings.get();
			s.minimapFrame = Settings.Frame.values()[saved[0]];
			s.minimapShape = Settings.Shape.values()[saved[1]];
			s.minimapSize = saved[2];
			s.minimapOpacity = saved[3];
			mc.player.getAbilities().flying = false;
		});
		world.getServer().runCommand("tp @a " + from.getX() + " " + from.getY() + " " + from.getZ());
		context.waitTicks(20);
	}

	/** Every frame on both shapes, at the sizes' ends, and see-through. */
	private static void photos(ClientGameTestContext context, String name) {
		for (Settings.Frame frame : Settings.Frame.values()) {
			for (Settings.Shape shape : Settings.Shape.values()) {
				for (int[] look : new int[][] {{96, 100}, {96, 50}, {64, 100}, {224, 100}}) {
					set(context, frame, shape, look[0], look[1]);
					context.takeScreenshot("minimap-frames-" + name + "-" + (frame.name() + "-" + shape.name()).toLowerCase(Locale.ROOT)
						+ "-" + look[0] + "-" + look[1]);
				}
			}
		}
	}

	private static void set(ClientGameTestContext context, Settings.Frame frame, Settings.Shape shape, int size, int opacity) {
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapFrame = frame;
			s.minimapShape = shape;
			s.minimapSize = size;
			s.minimapOpacity = opacity;
		});
		context.waitTicks(5);
	}

	/**
	 * Whether the pack's gold line shows, in the screen's own pixels: part way down the left side
	 * (square), clear of the compass letter there, or on the diagonal (round, clear of the pack's gems).
	 */
	private static void check(ClientGameTestContext context, boolean gold, String what) {
		boolean round = context.computeOnClient(mc -> Settings.get().minimapShape == Settings.Shape.ROUND);
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		int guiWidth = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
		int size = 96;
		double x = guiWidth - size - MARGIN;
		double y = MARGIN;
		double r = size / 2.0;
		double in = 2.5;
		double gx = round ? x + r - (r - in) * Math.sqrt(0.5) : x + in;
		double gy = round ? y + r - (r - in) * Math.sqrt(0.5) : y + size / 4.0;
		BufferedImage shot = read(context.takeScreenshot("minimap-frames-check-" + what.replace(' ', '-').replace(",", "").replace("'", "")));
		int rgb = shot.getRGB((int) (gx * scale), (int) (gy * scale)) & 0xFFFFFF;
		boolean close = Math.abs((rgb >> 16) - (GOLD >> 16)) <= 12 && Math.abs((rgb >> 8 & 0xFF) - (GOLD >> 8 & 0xFF)) <= 12
			&& Math.abs((rgb & 0xFF) - (GOLD & 0xFF)) <= 12;
		if (close != gold) {
			throw new AssertionError("Expected " + what + (gold ? " to show the gold line" : " not to show the pack's gold line")
				+ ", got " + String.format("%06X", rgb));
		}
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
