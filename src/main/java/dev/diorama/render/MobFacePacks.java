package dev.diorama.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.diorama.Diorama;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

/**
 * Mob faces declared in resource packs, so a mod can give its mobs a radar face from its own
 * assets and a modpack or resource pack can add or change one, without code. The face of the mob
 * {@code <namespace>:<name>} is {@code assets/<namespace>/diorama/mob_faces/<name>.json}; the
 * format is documented in {@code docs/mob-faces.md}. A file replaces Diorama's built-in face for
 * its mob. The files are read again on every resource reload (F3+T): each loader's entrypoint
 * registers {@link #LISTENER} with its own event, since NeoForge refuses listeners added to the
 * game's resource manager directly once the game has started.
 */
public final class MobFacePacks {
	public static final Identifier ID = Diorama.id("mob_faces");
	public static final ResourceManagerReloadListener LISTENER = MobFacePacks::reload;
	static final String DIRECTORY = "diorama/mob_faces";
	/** Faces are drawn one texture pixel to at least one screen pixel, so a big one is a big icon. */
	private static final int MAX_FACE = 32;
	private static final Set<String> FACE_FIELDS = Set.of("texture", "texture_size", "face", "layers");
	private static final Set<String> RECT_FIELDS = Set.of("u", "v", "width", "height");
	private static final Set<String> LAYER_FIELDS = Set.of("u", "v", "width", "height", "x", "y");

	private static @Nullable Map<EntityType<?>, MobFaces.Faces> faces;

	private MobFacePacks() {
	}

	/** The face a resource pack declares for this kind of mob, or null to use the built-in one. */
	static MobFaces.@Nullable Faces get(EntityType<?> type) {
		Map<EntityType<?>, MobFaces.Faces> loaded = faces;
		if (loaded == null) {
			// The listener runs in the game's first resource load, so this is only a fallback.
			loaded = load(Minecraft.getInstance().getResourceManager());
			faces = loaded;
		}
		return loaded.get(type);
	}

	/** Reads the files again; runs on the game thread at the end of each resource reload. */
	public static void reload(ResourceManager manager) {
		faces = load(manager);
	}

	private static Map<EntityType<?>, MobFaces.Faces> load(ResourceManager manager) {
		Map<EntityType<?>, MobFaces.Faces> loaded = new HashMap<>();
		// One file per path: a resource pack's file replaces a mod's, as with any other asset.
		for (Map.Entry<Identifier, Resource> entry : manager.listResources(DIRECTORY, path -> path.getPath().endsWith(".json")).entrySet()) {
			Identifier file = entry.getKey();
			String path = file.getPath();
			String name = path.substring(DIRECTORY.length() + 1, path.length() - ".json".length());
			Identifier mob = Identifier.tryBuild(file.getNamespace(), name);
			Optional<EntityType<?>> type = mob == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(mob);
			if (type.isEmpty()) {
				// Often a modpack's file for a mod that isn't installed; also a misspelt file name.
				Diorama.LOGGER.info("Mob face {} (from {}): there's no mob {}:{} in this game", file, entry.getValue().sourcePackId(), file.getNamespace(), name);
				continue;
			}
			try (Reader reader = entry.getValue().openAsReader()) {
				loaded.put(type.get(), parse(JsonParser.parseReader(reader), file, manager));
			} catch (IOException | RuntimeException e) {
				Diorama.LOGGER.warn("Mob face {} (from {}) ignored: {}", file, entry.getValue().sourcePackId(), e.getMessage());
			}
		}
		if (!loaded.isEmpty()) {
			Diorama.LOGGER.info("Loaded {} mob faces from resource packs", loaded.size());
		}
		return loaded;
	}

	private static MobFaces.Faces parse(JsonElement json, Identifier file, ResourceManager manager) {
		JsonObject root = object(json, "the file");
		warnUnknown(root, file, "", FACE_FIELDS, "baby");
		MobFaces.Face adult = face(root, file, "", manager);
		JsonElement baby = root.get("baby");
		if (baby == null) {
			// Babies that are the adult model scaled down, drawn with the adult texture.
			return new MobFaces.Faces(adult, adult);
		}
		if (baby instanceof JsonPrimitive primitive && primitive.isBoolean() && !primitive.getAsBoolean()) {
			return new MobFaces.Faces(adult, null);
		}
		JsonObject babyObject = object(baby, "baby (an object, or false)");
		warnUnknown(babyObject, file, "baby.", FACE_FIELDS, null);
		return new MobFaces.Faces(adult, face(babyObject, file, "baby.", manager));
	}

	private static MobFaces.Face face(JsonObject json, Identifier file, String where, ResourceManager manager) {
		Identifier texture = null;
		JsonElement textureJson = json.get("texture");
		if (textureJson != null) {
			String name = string(textureJson, where + "texture");
			texture = Identifier.tryParse(name);
			if (texture == null) {
				throw new IllegalArgumentException(where + "texture \"" + name + "\" isn't a valid resource location");
			}
			if (manager.getResource(texture).isEmpty()) {
				Diorama.LOGGER.warn("Mob face {}: {}texture {} isn't in any resource pack", file, where, texture);
			}
		}
		JsonArray size = array(json.get("texture_size"), where + "texture_size");
		if (size.size() != 2) {
			throw new IllegalArgumentException(where + "texture_size must be [width, height]");
		}
		int texW = positive(size.get(0), where + "texture_size[0]");
		int texH = positive(size.get(1), where + "texture_size[1]");

		JsonObject faceJson = object(json.get("face"), where + "face");
		warnUnknown(faceJson, file, where + "face.", RECT_FIELDS, null);
		int u = natural(faceJson.get("u"), where + "face.u");
		int v = natural(faceJson.get("v"), where + "face.v");
		int w = positive(faceJson.get("width"), where + "face.width");
		int h = positive(faceJson.get("height"), where + "face.height");
		inTexture(u, v, w, h, texW, texH, where + "face");
		if (w > MAX_FACE || h > MAX_FACE) {
			throw new IllegalArgumentException(where + "face is " + w + " x " + h + "; faces can be at most " + MAX_FACE + " x " + MAX_FACE);
		}

		List<MobFaces.Piece> layers = new ArrayList<>();
		JsonElement layersJson = json.get("layers");
		if (layersJson != null) {
			JsonArray array = array(layersJson, where + "layers");
			for (int i = 0; i < array.size(); i++) {
				String at = where + "layers[" + i + "]";
				JsonObject layer = object(array.get(i), at);
				warnUnknown(layer, file, at + ".", LAYER_FIELDS, null);
				int lu = natural(layer.get("u"), at + ".u");
				int lv = natural(layer.get("v"), at + ".v");
				int lw = positive(layer.get("width"), at + ".width");
				int lh = positive(layer.get("height"), at + ".height");
				int x = layer.has("x") ? natural(layer.get("x"), at + ".x") : 0;
				int y = layer.has("y") ? natural(layer.get("y"), at + ".y") : 0;
				inTexture(lu, lv, lw, lh, texW, texH, at);
				if (x + lw > w || y + lh > h) {
					throw new IllegalArgumentException(at + " reaches outside the " + w + " x " + h + " face");
				}
				layers.add(new MobFaces.Piece(lu, lv, lw, lh, x, y));
			}
		}
		return new MobFaces.Face(texture, texW, texH, u, v, w, h, List.copyOf(layers));
	}

	private static void inTexture(int u, int v, int w, int h, int texW, int texH, String what) {
		if (u + w > texW || v + h > texH) {
			throw new IllegalArgumentException(what + " reaches outside the " + texW + " x " + texH + " texture");
		}
	}

	/** Unknown fields are most likely typos, but could be from a newer Diorama: say so and go on. */
	private static void warnUnknown(JsonObject json, Identifier file, String where, Set<String> known, @Nullable String alsoKnown) {
		for (String key : json.keySet()) {
			if (!known.contains(key) && !key.equals(alsoKnown)) {
				Diorama.LOGGER.warn("Mob face {}: unknown field {}{}", file, where, key);
			}
		}
	}

	private static JsonObject object(@Nullable JsonElement json, String what) {
		if (json == null) {
			throw new IllegalArgumentException(what + " is missing");
		}
		if (!json.isJsonObject()) {
			throw new IllegalArgumentException(what + " must be an object");
		}
		return json.getAsJsonObject();
	}

	private static JsonArray array(@Nullable JsonElement json, String what) {
		if (json == null) {
			throw new IllegalArgumentException(what + " is missing");
		}
		if (!json.isJsonArray()) {
			throw new IllegalArgumentException(what + " must be a list");
		}
		return json.getAsJsonArray();
	}

	private static String string(JsonElement json, String what) {
		if (!(json instanceof JsonPrimitive primitive) || !primitive.isString()) {
			throw new IllegalArgumentException(what + " must be a string");
		}
		return primitive.getAsString();
	}

	private static int natural(@Nullable JsonElement json, String what) {
		if (json == null) {
			throw new IllegalArgumentException(what + " is missing");
		}
		if (!(json instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
			throw new IllegalArgumentException(what + " must be a whole number");
		}
		double value = primitive.getAsDouble();
		if (value != Math.rint(value) || value < 0 || value > 4096) {
			throw new IllegalArgumentException(what + " must be a whole number from 0 to 4096");
		}
		return (int) value;
	}

	private static int positive(@Nullable JsonElement json, String what) {
		int value = natural(json, what);
		if (value == 0) {
			throw new IllegalArgumentException(what + " must be at least 1");
		}
		return value;
	}
}
