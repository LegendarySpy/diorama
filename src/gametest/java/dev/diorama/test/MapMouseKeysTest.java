package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.DioramaClient;
import dev.diorama.MapScreen;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingStore;
import dev.diorama.render.MapViewport;
import dev.diorama.waypoint.WaypointListScreen;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * The map's key bindings on mouse buttons (#200): a side button does what the key does, over a
 * control and with the menu open too, and the buttons the map clicks and drags with stay the map's.
 */
final class MapMouseKeysTest {
	private static final int LEFT = InputConstants.MOUSE_BUTTON_LEFT;
	private static final int RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;
	private static final int MIDDLE = InputConstants.MOUSE_BUTTON_MIDDLE;
	private static final int MOUSE_4 = LEFT + 3;
	private static final int MOUSE_5 = LEFT + 4;

	private MapMouseKeysTest() {
	}

	static void run(ClientGameTestContext context) {
		InputConstants.Key openMap = context.computeOnClient(mc -> InputConstants.getKey(DioramaClient.OPEN_MAP.saveString()));
		InputConstants.Key waypointList = context.computeOnClient(mc -> InputConstants.getKey(DioramaClient.WAYPOINT_LIST.saveString()));
		List<String> wrong = new ArrayList<>();

		// The report: the button that opens the map closes it, and the press doesn't open it again.
		bind(context, MOUSE_5, MOUSE_4);
		context.getInput().pressMouse(MOUSE_5);
		context.waitTicks(5);
		check(wrong, onMap(context), "Mouse 5 didn't open the map");
		context.takeScreenshot("map-mouse-keys-01-opened");
		context.getInput().pressMouse(MOUSE_5);
		context.waitTicks(5);
		context.takeScreenshot("map-mouse-keys-02-pressed-again");
		check(wrong, closed(context), "Mouse 5 didn't close the map");
		context.setScreen(() -> null);

		// Over a button: widgets only click with the left button.
		openMap(context);
		moveTo(context, widget(context, MapScreen::drawButton));
		context.getInput().pressMouse(MOUSE_5);
		context.waitTicks(5);
		check(wrong, closed(context), "Mouse 5 over the pencil didn't close the map");
		context.setScreen(() -> null);

		// With the right-click menu open, as M does.
		openMap(context);
		moveTo(context, spot(context));
		context.getInput().pressMouse(RIGHT);
		context.waitTicks(3);
		check(wrong, context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen map && map.menu() != null), "Right-click didn't open the menu");
		context.getInput().pressMouse(MOUSE_5);
		context.waitTicks(5);
		check(wrong, closed(context), "Mouse 5 with the menu open didn't close the map");
		context.setScreen(() -> null);

		// The waypoint list's key, which the map honours too.
		openMap(context);
		context.getInput().pressMouse(MOUSE_4);
		context.waitTicks(5);
		check(wrong, context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointListScreen), "Mouse 4 didn't open the waypoint list from the map");
		context.setScreen(() -> null);

		// A half-typed note survives the map closing under it.
		String dimension = context.computeOnClient(mc -> mc.level.dimension().identifier().toString());
		openMap(context);
		click(context, widget(context, MapScreen::drawButton));
		click(context, widget(context, map -> map.drawMode().toolButtons().get(3)));
		click(context, spot(context));
		context.getInput().typeChars("Kept");
		context.getInput().pressMouse(MOUSE_5);
		context.waitTicks(5);
		check(wrong, closed(context), "Mouse 5 while typing a note didn't close the map");
		context.setScreen(() -> null);
		Drawing note = context.computeOnClient(mc -> {
			List<Drawing> drawings = DrawingStore.get().in(dimension);
			return drawings.isEmpty() ? null : drawings.get(drawings.size() - 1);
		});
		check(wrong, note != null && note.kind == Drawing.Kind.NOTE && note.text.equals("Kept"), "A half-typed note was lost when Mouse 5 closed the map");
		if (note != null) {
			context.runOnClient(mc -> DrawingStore.get().remove(note));
		}

		// While drawing, the middle button pans, so it can't also close the map. It does otherwise.
		bind(context, MIDDLE, MOUSE_4);
		openMap(context);
		context.getInput().pressMouse(MIDDLE);
		context.waitTicks(5);
		check(wrong, closed(context), "A middle button bound to Open map didn't close the map");
		context.setScreen(() -> null);
		openMap(context);
		click(context, widget(context, MapScreen::drawButton));
		click(context, widget(context, map -> map.drawMode().toolButtons().get(0)));
		double[] at = spot(context);
		check(wrong, panned(context, MIDDLE, at), "The middle button didn't pan while drawing");
		check(wrong, onMap(context), "Panning with the middle button while drawing closed the map");
		context.setScreen(() -> null);

		// Left and right are how the map is used at all: bound to these, the map keeps them.
		bind(context, LEFT, MOUSE_4);
		openMap(context);
		click(context, spot(context));
		check(wrong, onMap(context), "A left click closed the map with Open map on the left button");
		check(wrong, panned(context, LEFT, spot(context)), "The left button didn't pan with Open map on it");
		click(context, widget(context, MapScreen::drawButton));
		check(wrong, context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen map && map.drawMode().active()), "The pencil didn't take a left click with Open map on the left button");
		context.setScreen(() -> null);
		bind(context, RIGHT, MOUSE_4);
		openMap(context);
		moveTo(context, spot(context));
		context.getInput().pressMouse(RIGHT);
		context.waitTicks(3);
		check(wrong, context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen map && map.menu() != null), "A right click didn't open the menu with Open map on the right button");
		context.setScreen(() -> null);

		context.runOnClient(mc -> {
			DioramaClient.OPEN_MAP.setKey(openMap);
			DioramaClient.WAYPOINT_LIST.setKey(waypointList);
			KeyMapping.resetMapping();
		});
		if (!wrong.isEmpty()) {
			throw new AssertionError(String.join("; ", wrong));
		}
	}

	private static void bind(ClientGameTestContext context, int openMap, int waypointList) {
		context.runOnClient(mc -> {
			DioramaClient.OPEN_MAP.setKey(InputConstants.Type.MOUSE.getOrCreate(openMap));
			DioramaClient.WAYPOINT_LIST.setKey(InputConstants.Type.MOUSE.getOrCreate(waypointList));
			KeyMapping.resetMapping();
		});
	}

	private static void check(List<String> wrong, boolean right, String message) {
		if (!right) {
			wrong.add(message);
		}
	}

	private static void openMap(ClientGameTestContext context) {
		context.setScreen(MapScreen::new);
		context.waitTicks(5);
	}

	private static boolean onMap(ClientGameTestContext context) {
		return context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen);
	}

	private static boolean closed(ClientGameTestContext context) {
		return context.computeOnClient(mc -> mc.gui.screen() == null);
	}

	/** Dragging with a button moves the map's centre. */
	private static boolean panned(ClientGameTestContext context, int button, double[] from) {
		double before = centre(context);
		moveTo(context, from);
		context.getInput().holdMouse(button);
		context.waitTick();
		moveTo(context, new double[] {from[0] + 30, from[1]});
		context.getInput().releaseMouse(button);
		context.waitTicks(2);
		return centre(context) != before;
	}

	private static double centre(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			MapViewport.View view = mc.gui.screen() instanceof MapScreen map ? map.view() : null;
			return view == null ? Double.NaN : view.centerU();
		});
	}

	/** Open map beside the middle of the screen, in GUI pixels: away from the controls and the player. */
	private static double[] spot(ClientGameTestContext context) {
		return context.computeOnClient(mc -> new double[] {mc.gui.screen().width / 2.0 + 40, mc.gui.screen().height / 2.0});
	}

	private static double[] widget(ClientGameTestContext context, WidgetOf of) {
		return context.computeOnClient(mc -> {
			AbstractWidget widget = of.get((MapScreen) mc.gui.screen());
			return new double[] {widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0};
		});
	}

	private static void click(ClientGameTestContext context, double[] at) {
		moveTo(context, at);
		context.getInput().pressMouse(LEFT);
		context.waitTicks(2);
	}

	private static void moveTo(ClientGameTestContext context, double[] at) {
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		context.getInput().setCursorPos(at[0] * scale, at[1] * scale);
		context.waitTick();
	}

	private interface WidgetOf {
		AbstractWidget get(MapScreen map);
	}
}
