# Mob faces on the radar

The radar draws each mob as its face, cut from the mob's own texture, so texture variants and resource packs show up on the map. Diorama has faces built in for about 40 vanilla mobs. Every other mob, including every modded one, is a coloured dot.

One JSON file gives a mob a face. A mod can ship it in its jar, and a modpack or resource pack can add one or replace a built-in face. No code, and no dependency on Diorama: without Diorama installed, the game ignores the file.

## Where the file goes

The face of the mob `<namespace>:<name>` is read from

```
assets/<namespace>/diorama/mob_faces/<name>.json
```

A mod puts it in its own jar, next to its textures. The face of `examplemod:frog_knight` is `assets/examplemod/diorama/mob_faces/frog_knight.json`.

A resource pack uses the same path. A file in a resource pack replaces a mod's file for the same mob, and a pack higher in the list wins over one below it, as with any other asset. A file for a vanilla mob (`assets/minecraft/diorama/mob_faces/zombie.json`) replaces Diorama's built-in face.

Press F3+T to reload resource packs, and Diorama reads the files again. That's the quickest way to try one out.

## Worked example: the ocelot

The ocelot is a dot without a file. Its model, in `AdultFelineModel` (26.3, Mojang names), builds the head from boxes:

```java
CubeListBuilder.create()
	.addBox("main", -2.5F, -2.0F, -3.0F, 5.0F, 4.0F, 5.0F, g)      // texture offset (0, 0)
	.addBox("nose", -1.5F, -0.001F, -4.0F, 3, 2, 2, g, 0, 24)     // texture offset (0, 24)
```

and the texture is 64 x 32 pixels. A box at texture offset (u, v) with size width x height x depth has its front face at (u + depth, v + depth), width x height. So:

- The head is 5 x 4 x 5 at (0, 0): its front face is at (5, 5), 5 wide and 4 tall.
- The nose is 3 x 2 x 2 at (0, 24): its front is at (2, 26), 3 x 2. It starts 1 pixel right of the head's left edge (-1.5 against -2.5) and 2 pixels down from its top (0 against -2).

From 26.1 a baby ocelot has a model and a 32 x 32 texture of its own (`BabyFelineModel`): the head's front face is at (4, 4), 5 x 4, and the nose's at (19, 4), 3 x 2, at the same place in the face.

That gives `assets/minecraft/diorama/mob_faces/ocelot.json`:

```json
{
	"texture_size": [64, 32],
	"face": {"u": 5, "v": 5, "width": 5, "height": 4},
	"layers": [
		{"u": 2, "v": 26, "width": 3, "height": 2, "x": 1, "y": 2}
	],
	"baby": {
		"texture_size": [32, 32],
		"face": {"u": 4, "v": 4, "width": 5, "height": 4},
		"layers": [
			{"u": 19, "v": 4, "width": 3, "height": 2, "x": 1, "y": 2}
		]
	}
}
```

Put it in a resource pack, turn the pack on, and ocelots show their faces on the minimap and the fullscreen map. Before 26.1 a baby ocelot is the adult model scaled down, drawn with the adult texture, so for those versions leave `baby` out.

For a modded mob, read the numbers off its model the same way. In Blockbench, the face's rectangle is the head cube's front face in the UV editor.

## Fields

| Field | Required | What it is |
|---|---|---|
| `texture_size` | yes | `[width, height]` of the texture the rectangles are measured in, as the model declares it (`LayerDefinition.create(mesh, 64, 32)`). Measure in these units even when a high-resolution resource pack makes the image bigger: Diorama scales to fit. |
| `face` | yes | The face: `u` and `v` (its top-left corner in the texture), `width` and `height`. At most 32 x 32. |
| `layers` | no | Pieces drawn over the face in order, for a hat, a hood, a nose or a snout. Each has `u`, `v`, `width` and `height` like `face`, plus `x` and `y`: where its top-left corner goes, in pixels from the face's top-left corner (default 0). A layer must fit inside the face, and a face can have at most 8. |
| `texture` | no | A texture to cut the face from instead of the mob's own, such as `"examplemod:textures/entity/frog_knight_icon.png"`. See below. |
| `baby` | no | The babies' face. Leave it out if babies are the adult model scaled down with the adult's texture: they get the adult's face. Set it to an object with the same fields (except `baby`) if babies have a texture of their own. Set it to `false` to show babies as dots. |

Rectangles are in texture pixels with (0, 0) at the top left. Without `texture`, Diorama cuts the face from whatever texture the game draws that mob with right now, so variants (a tamed wolf, a cold cow) and resource packs that retexture the mob still show.

Diorama asks the mob's renderer for that texture. From Minecraft 1.21.2 only renderers built on vanilla's `LivingEntityRenderer` can say; others, such as GeckoLib's `GeoEntityRenderer`, can't, and their mobs need a `texture` (the log says so when one is missing). Before 1.21.2 every renderer can say.

## Icons of their own

Some faces aren't one rectangle of the texture: a head made of several boxes, a model with a separate texture region per face, or a face the radar can't read at its size. For those, draw an icon and point `texture` at it:

```json
{
	"texture": "examplemod:textures/entity/frog_knight_icon.png",
	"texture_size": [8, 8],
	"face": {"u": 0, "v": 0, "width": 8, "height": 8}
}
```

The icon then stays the same whatever variant or resource pack the mob has.

## Keep faces small

The radar draws a face as pixel art: one texture pixel is at least one screen pixel. The built-in faces are 4 to 20 pixels across, and a much bigger face comes out bigger than every other mob on the map. Faces larger than 32 x 32 are refused.

## When a file doesn't work

The game log says why. A file with a mistake is skipped, and the mob keeps its built-in face or stays a dot:

```
Mob face minecraft:diorama/mob_faces/sheep.json (from file/my-pack) ignored: face.height is missing
```

Other messages:

- `unknown field`: a field Diorama doesn't know, most likely a typo. The rest of the file still applies.
- `there's no mob examplemod:frog_knigt in this game`: the file name doesn't match a mob. Either it's misspelt, or the mod isn't installed, which is fine for a modpack that covers optional mods.
- `texture ... isn't in any resource pack`: the `texture` path is wrong. The radar would show the missing-texture pattern.
- `its renderer doesn't say which texture it draws the mob with`: the mob's renderer isn't a `LivingEntityRenderer` (see above). Give the face a `texture`.
