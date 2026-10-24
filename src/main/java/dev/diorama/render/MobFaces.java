package dev.diorama.render;

import dev.diorama.render.gpu.MapCanvas;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
//? if >=1.21.2 {
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
//?}
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * Mob faces for the radar, cut from each mob's own texture, so variants and resource packs show.
 * Only mobs whose face is one clean rectangle of their texture are listed; the rest stay dots,
 * unless a resource pack gives them a face ({@link MobFacePacks}, which can replace these too).
 * Rectangles are read from the 26.3 models: a head box at texOffs(u, v) with size w x h x d has
 * its front face at (u + d, v + d), w x h.
 */
public final class MobFaces {
	/**
	 * Where a face is in a texture of the given size: the mob's own texture, or {@code texture} if
	 * set. {@code layers} are drawn over the face in order, each at {@code (dx, dy)} in face pixels.
	 */
	record Face(@Nullable Identifier texture, int texW, int texH, int u, int v, int w, int h, List<Piece> layers) {
		Face(int texW, int texH, int u, int v, int w, int h) {
			this(null, texW, texH, u, v, w, h, List.of());
		}

		/**
		 * A face with an outer {@code hat} layer (zombie and skeleton hats, villager hoods) and a
		 * {@code feature}, a nose or snout that sticks out of the face, drawn under the hat.
		 */
		Face(int texW, int texH, int u, int v, int w, int h, @Nullable Piece hat, @Nullable Piece feature) {
			this(null, texW, texH, u, v, w, h, feature == null ? hat == null ? List.of() : List.of(hat)
				: hat == null ? List.of(feature) : List.of(feature, hat));
		}
	}

	record Piece(int u, int v, int w, int h, int dx, int dy) {
	}

	/** An adult face, and a baby's when babies have their own texture layout (or null for a dot). */
	record Faces(Face adult, @Nullable Face baby) {
	}

	private static final Map<EntityType<?>, Faces> FACES = new HashMap<>();
	/**
	 * The texture each entity was last drawn with, its age then, and whether it was a baby.
	 * Working it out builds a render state, so it's redone every two seconds of the entity's own
	 * life, often enough for a wolf turning angry, and straight away when a baby grows up, since
	 * the face layout changes with it. Keyed by the entity object itself (entities compare equal
	 * by id, which another world can reuse), and dropped once the entity is gone.
	 */
	private static final Map<Entity, Looked> TEXTURES = new com.google.common.collect.MapMaker().weakKeys().makeMap();
	private static final int REFRESH_TICKS = 40;

	private record Looked(@Nullable Identifier texture, int age, boolean baby) {
	}

	private static final Piece HUMANOID_HAT = new Piece(40, 8, 8, 8, 0, 0);
	private static final Piece BABY_ZOMBIE_HAT = new Piece(41, 9, 6, 6, 0, 0);
	private static final Piece VILLAGER_HAT = new Piece(40, 8, 8, 10, 0, 0);
	/** Villager-style noses stick out from the lower middle of a 10-pixel-tall face. */
	private static final Piece VILLAGER_NOSE = new Piece(26, 2, 2, 3, 3, 7);
	//? if >=1.21.5 {
	private static final Face COW = new Face(64, 64, 6, 6, 8, 8, null, new Piece(2, 34, 6, 3, 1, 5));
	//?} else {
	/*// The cow model before 1.21.5: head box at texOffs(0, 0), 8 x 8 x 6, no muzzle, in a 64 x 32 texture.
	private static final Face COW = new Face(64, 32, 6, 6, 8, 8);
	*///?}
	/** Cold cows keep their muzzle elsewhere in the texture: their model moves its texture origin. */
	private static final Face COLD_COW = new Face(COW.texW(), COW.texH(), COW.u(), COW.v(), COW.w(), COW.h(), null, new Piece(10, 34, 6, 3, 1, 5));

	static {
		Face zombie = new Face(64, 64, 8, 8, 8, 8, HUMANOID_HAT, null);
		Face babyZombie = new Face(64, 64, 9, 9, 6, 6, BABY_ZOMBIE_HAT, null);
		for (EntityType<?> type : new EntityType<?>[] {EntityTypes.ZOMBIE, EntityTypes.HUSK, EntityTypes.DROWNED}) {
			FACES.put(type, new Faces(zombie, babyZombie));
		}
		FACES.put(EntityTypes.ZOMBIE_VILLAGER, new Faces(
			new Face(64, 64, 8, 8, 8, 10, VILLAGER_HAT, VILLAGER_NOSE),
			new Face(64, 64, 7, 7, 8, 8, new Piece(7, 38, 8, 8, 0, 0), null)));
		Face skeleton = new Face(64, 32, 8, 8, 8, 8, HUMANOID_HAT, null);
		for (EntityType<?> type : new EntityType<?>[] {EntityTypes.SKELETON, EntityTypes.STRAY, EntityTypes.WITHER_SKELETON, EntityTypes.BOGGED}) {
			FACES.put(type, new Faces(skeleton, null));
		}
		FACES.put(EntityTypes.CREEPER, new Faces(new Face(64, 32, 8, 8, 8, 8), null));
		Face spider = new Face(64, 32, 40, 12, 8, 8);
		FACES.put(EntityTypes.SPIDER, new Faces(spider, null));
		FACES.put(EntityTypes.CAVE_SPIDER, new Faces(spider, null));
		FACES.put(EntityTypes.ENDERMAN, new Faces(new Face(64, 32, 8, 8, 8, 8), null));
		FACES.put(EntityTypes.WITCH, new Faces(new Face(64, 128, 8, 8, 8, 10, null, VILLAGER_NOSE), null));
		FACES.put(EntityTypes.BLAZE, new Faces(new Face(64, 32, 8, 8, 8, 8), null));
		// Piglin snouts: a 4x4 box from the face's middle, rows 4 to 8 of 8.
		Face piglin = new Face(64, 64, 8, 8, 10, 8, null, new Piece(32, 2, 4, 4, 3, 4));
		Face babyPiglin = new Face(64, 64, 7, 7, 9, 6, null, new Piece(22, 31, 3, 3, 3, 3));
		FACES.put(EntityTypes.PIGLIN, new Faces(piglin, babyPiglin));
		FACES.put(EntityTypes.PIGLIN_BRUTE, new Faces(piglin, null));
		FACES.put(EntityTypes.ZOMBIFIED_PIGLIN, new Faces(piglin, babyPiglin));
		Face illager = new Face(64, 64, 8, 8, 8, 10, null, VILLAGER_NOSE);
		for (EntityType<?> type : new EntityType<?>[] {EntityTypes.PILLAGER, EntityTypes.VINDICATOR, EntityTypes.EVOKER}) {
			FACES.put(type, new Faces(illager, null));
		}
		FACES.put(EntityTypes.RAVAGER, new Faces(new Face(128, 128, 16, 16, 16, 20), null));
		FACES.put(EntityTypes.VILLAGER, new Faces(
			new Face(64, 64, 8, 8, 8, 10, VILLAGER_HAT, VILLAGER_NOSE),
			new Face(64, 64, 7, 7, 8, 8, new Piece(7, 37, 8, 8, 0, 0), null)));
		FACES.put(EntityTypes.WANDERING_TRADER, new Faces(new Face(64, 64, 8, 8, 8, 10, VILLAGER_HAT, VILLAGER_NOSE), null));
		FACES.put(EntityTypes.IRON_GOLEM, new Faces(new Face(128, 128, 8, 8, 8, 10, null, VILLAGER_NOSE), null));
		// Cow muzzles sit under the eyes, rows 5 to 8 of 8; a calf's at rows 3 to 6 of 6.
		Faces cow = new Faces(COW, new Face(64, 64, 5, 23, 6, 6, null, new Piece(13, 30, 4, 3, 1, 3)));
		FACES.put(EntityTypes.COW, cow);
		FACES.put(EntityTypes.MOOSHROOM, cow);
		// The pig's snout covers the middle of its face, rows 4 to 7 of 8.
		//? if >=1.21.5 {
		FACES.put(EntityTypes.PIG, new Faces(new Face(64, 64, 8, 8, 8, 8, null, new Piece(17, 17, 4, 3, 2, 4)), new Face(32, 32, 6, 21, 7, 6)));
		//?} else {
		/*// The pig model before 1.21.5: the same head and snout boxes in a 64 x 32 texture.
		FACES.put(EntityTypes.PIG, new Faces(new Face(64, 32, 8, 8, 8, 8, null, new Piece(17, 17, 4, 3, 2, 4)), null));
		*///?}
		FACES.put(EntityTypes.SHEEP, new Faces(new Face(64, 32, 8, 8, 6, 6), new Face(64, 32, 5, 5, 5, 5)));
		// The chicken's beak is across rows 2 to 4 of its 6-pixel-tall face.
		FACES.put(EntityTypes.CHICKEN, new Faces(new Face(64, 32, 3, 3, 4, 6, null, new Piece(16, 2, 4, 2, 0, 2)), new Face(16, 16, 4, 4, 4, 4)));
		FACES.put(EntityTypes.WOLF, new Faces(new Face(64, 32, 4, 4, 6, 6), new Face(32, 32, 5, 17, 6, 5)));
		FACES.put(EntityTypes.CAT, new Faces(new Face(64, 32, 5, 5, 5, 4), new Face(32, 32, 4, 4, 5, 4)));
		FACES.put(EntityTypes.FOX, new Faces(new Face(48, 32, 7, 11, 8, 6, null, new Piece(9, 21, 4, 2, 2, 4)), new Face(32, 32, 5, 5, 6, 5)));
		//? if >=26.1 {
		FACES.put(EntityTypes.RABBIT, new Faces(new Face(64, 64, 5, 21, 5, 5), new Face(32, 32, 4, 4, 5, 4)));
		//?} else {
		/*// The rabbit model before 26.1: head box at texOffs(32, 0), 5 x 4 x 5, in a 64 x 32 texture.
		FACES.put(EntityType.RABBIT, new Faces(new Face(64, 32, 37, 5, 5, 4), null));
		*///?}
		FACES.put(EntityTypes.POLAR_BEAR, new Faces(new Face(128, 64, 7, 7, 7, 7), new Face(64, 64, 4, 4, 6, 5)));
		FACES.put(EntityTypes.PANDA, new Faces(new Face(64, 64, 9, 15, 13, 10, null, new Piece(47, 18, 7, 5, 3, 5)), new Face(64, 64, 5, 5, 7, 6)));
		FACES.put(EntityTypes.ALLAY, new Faces(new Face(32, 32, 5, 5, 5, 5), null));
		FACES.put(EntityTypes.BREEZE, new Faces(new Face(32, 32, 8, 8, 8, 8), null));
		//? if >=1.21.2 {
		FACES.put(EntityTypes.CREAKING, new Faces(new Face(64, 64, 6, 6, 6, 10), null));
		//?}
		FACES.put(EntityTypes.DOLPHIN, new Faces(new Face(64, 64, 6, 6, 8, 7), new Face(64, 64, 4, 4, 6, 5)));
		FACES.put(EntityTypes.BEE, new Faces(new Face(64, 64, 10, 10, 7, 7), new Face(32, 32, 5, 5, 4, 4)));
		FACES.put(EntityTypes.AXOLOTL, new Faces(new Face(64, 64, 5, 6, 8, 5), new Face(32, 32, 4, 12, 6, 3)));
		FACES.put(EntityTypes.STRIDER, new Faces(new Face(64, 128, 16, 16, 16, 14), new Face(32, 32, 8, 8, 7, 7)));
	}

	private MobFaces() {
	}

	/** Whether this entity has a face icon; if not the radar draws a dot. */
	public static boolean has(Entity entity) {
		return face(entity) != null;
	}

	/** Whether this entity's face can be drawn now: it has one, and there's a texture for it. For tests. */
	public static boolean canDraw(Entity entity) {
		return describe(entity) != null;
	}

	/**
	 * The face this entity is drawn with, as {@code "<texture> u,v wxh"} and then
	 * {@code " + u,v wxh at x,y"} for each layer, or null if it has none to draw. For tests.
	 */
	public static @Nullable String describe(Entity entity) {
		Face face = face(entity);
		Identifier texture = face == null ? null : texture(entity, face);
		if (face == null || texture == null) {
			return null;
		}
		StringBuilder out = new StringBuilder().append(texture).append(' ')
			.append(face.u()).append(',').append(face.v()).append(' ').append(face.w()).append('x').append(face.h());
		for (Piece p : face.layers()) {
			out.append(" + ").append(p.u()).append(',').append(p.v()).append(' ').append(p.w()).append('x').append(p.h())
				.append(" at ").append(p.dx()).append(',').append(p.dy());
		}
		return out.toString();
	}

	private static @Nullable Face face(Entity entity) {
		boolean baby = entity instanceof LivingEntity living && living.isBaby();
		// A resource pack's face describes the textures it's used with, so it applies on every version.
		Faces packed = MobFacePacks.get(entity.getType());
		if (packed != null) {
			return baby ? packed.baby() : packed.adult();
		}
		Faces faces = FACES.get(entity.getType());
		if (faces == null) {
			return null;
		}
		//? if >=26.1 {
		Face face = baby ? faces.baby() : faces.adult();
		//?} else {
		/*// Before 26.1 a baby is its adult model scaled down, drawn with the adult texture, so it
		// has the adult's face.
		Face face = faces.adult();
		*///?}
		return face == COW && coldCow(entity) ? COLD_COW : face;
	}

	/**
	 * Draws an entity's face centred on (x, y), fitted in a square {@code size} across, keeping
	 * the face's proportions. Returns false if it has no face icon.
	 */
	public static boolean draw(GuiGraphicsExtractor graphics, Entity entity, float x, float y, float size, int color) {
		Face face = face(entity);
		Identifier texture = face == null ? null : texture(entity, face);
		if (face == null || texture == null) {
			return false;
		}
		float scale = size / Math.max(face.w(), face.h());
		float w = face.w() * scale;
		float h = face.h() * scale;
		graphics.pose().pushMatrix();
		graphics.pose().translate(x - w / 2, y - h / 2);
		graphics.pose().scale(scale);
		blitFace(graphics, texture, face, color);
		graphics.pose().popMatrix();
		return true;
	}

	/**
	 * Draws an entity's face as pixel art centred on (x, y), about {@code size} GUI pixels across:
	 * each texel a whole number of screen pixels, with a one-texel outline and a drop shadow.
	 * Returns false if it has no face icon.
	 */
	public static boolean drawPixel(GuiGraphicsExtractor graphics, Entity entity, float x, float y, float size, int color, int outline, int alpha) {
		Face face = face(entity);
		Identifier texture = face == null ? null : texture(entity, face);
		if (face == null || texture == null) {
			return false;
		}
		float texel = Radar.texel(size, Math.max(face.w(), face.h()));
		Radar.pixelFrame(graphics, x, y, face.w(), face.h(), texel, outline, alpha);
		blitFace(graphics, texture, face, color);
		graphics.pose().popMatrix();
		return true;
	}

	/** The face at (0, 0), one texture pixel to one unit, with its layers over it. */
	private static void blitFace(GuiGraphicsExtractor graphics, Identifier texture, Face face, int color) {
		MapCanvas canvas = MapCanvas.of(graphics);
		canvas.blit(texture, 0, 0, face.u(), face.v(), face.w(), face.h(), face.texW(), face.texH(), color);
		for (Piece p : face.layers()) {
			canvas.blit(texture, p.dx(), p.dy(), p.u(), p.v(), p.w(), p.h(), face.texW(), face.texH(), color);
		}
	}

	/** Cold cows use a different model, with the muzzle elsewhere in the texture. */
	private static boolean coldCow(Entity entity) {
		//? if >=1.21.5 {
		return entity instanceof net.minecraft.world.entity.animal.cow.Cow cow
			&& cow.getVariant().value().modelAndTexture().model() == net.minecraft.world.entity.animal.cow.CowVariant.ModelType.COLD;
		//?} else {
		/*// Cow variants came in 1.21.5.
		return false;
		*///?}
	}

	/** The face's own texture if it has one, otherwise the one the game draws this entity with. */
	private static @Nullable Identifier texture(Entity entity, Face face) {
		if (face.texture() != null) {
			return face.texture();
		}
		Identifier texture = texture(entity);
		if (texture == null) {
			MobFacePacks.noTexture(entity.getType());
		}
		return texture;
	}

	/** The texture the game draws this entity with, from its renderer. */
	private static @Nullable Identifier texture(Entity entity) {
		Looked looked = TEXTURES.get(entity);
		int age = entity.tickCount;
		boolean baby = entity instanceof LivingEntity living && living.isBaby();
		if (looked == null || age - looked.age() >= REFRESH_TICKS || age < looked.age() || baby != looked.baby()) {
			looked = new Looked(lookUp(entity), age, baby);
			TEXTURES.put(entity, looked);
		}
		return looked.texture();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static @Nullable Identifier lookUp(Entity entity) {
		//? if >=1.21.2 {
		// Only living-entity renderers say which texture they use. Others, such as GeckoLib's, don't:
		// a resource pack's face for such a mob needs a texture of its own.
		EntityRenderer<?, ?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
		if (!(renderer instanceof LivingEntityRenderer living)) {
			return null;
		}
		try {
			LivingEntityRenderState state = (LivingEntityRenderState) ((LivingEntityRenderer) living).createRenderState((LivingEntity) entity, 1.0F);
			return ((LivingEntityRenderer) living).getTextureLocation(state);
		} catch (RuntimeException e) {
			return null;
		}
		//?} else {
		/*// Renderers had no render state yet, and every one, GeckoLib's too, said which texture it
		// draws an entity with.
		EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
		try {
			return ((EntityRenderer) renderer).getTextureLocation(entity);
		} catch (RuntimeException e) {
			return null;
		}
		*///?}
	}
}
