# MonoPaint tools and features

How to use each tool: tap a tool to select it, then tap it again to open its
settings beside the icon. Tap × or anywhere outside the panel to close it.

## Drawing tools

| Tool | What it does |
|:-:|---|
| <img src="icons/brush.svg" width="48"><br>**Brush** | Pressure-sensitive brush. Settings: head, min/max width, pressure response, **Oil paint**. Works with wet canvas and the opaque/transparent modes. |
| <img src="icons/brush_pen.svg" width="48"><br>**Brush pen** | Lays down clear water that lifts and drags the paint underneath. Tilt sets its size and pressure sets how much paint it pulls. Edges stay sharp on a dry canvas, and on a wet canvas it blends into the wet paint. Settings: Strength, Pressure response, Carry original paint. |
| <img src="icons/pencil.svg" width="48"><br>**Pencil** | Textured pencil. Leaning the pen broadens the stroke (**Broaden with tilt**). Settings: Hardness (soft = darker), upright tip size. |
| <img src="icons/airbrush.svg" width="48"><br>**Airbrush** | Soft spray at a fixed size. Press harder for stronger spray, and hold still or move slowly to build color. Settings: Diameter, Flow. |
| <img src="icons/fill.svg" width="48"><br>**Fill** | Flood fill. Tap it again to pick the fill type (below). |
| <img src="icons/shapes.svg" width="48"><br>**Shapes** | Line, rectangle, square, oval or circle (below). |
| <img src="icons/eraser.svg" width="48"><br>**Eraser** | Gradual eraser that reveals the layers underneath. Settings: Softness. |
| <img src="icons/soften.svg" width="48"><br>**Blending stump** | Pulls shading in the direction you rub. Lower strength blends more gently, and you can repeat passes to build it up. |

### Brush heads

The toolbar icon changes to match the selected head. Each head keeps its own
width and pressure settings.

| Head | |
|:-:|---|
| <img src="icons/brush.svg" width="48"><br>**Round** | Even in every direction. |
| <img src="icons/brush_flat.svg" width="48"><br>**Flat** | Square edge that turns to follow pen tilt. Extra **Height** setting (1 px, or 1–20% of the width). |
| <img src="icons/brush_filbert.svg" width="48"><br>**Filbert** | Rounded oval that follows pen tilt. With oil paint it becomes a crescent shape. |

**Oil paint:** the brush starts each stroke loaded with your shade and runs out
along the stroke. As it empties it lays down more of the paint it picks up from
the canvas, and once empty it only smudges. To reload, rub the pen around in the
color bar or on a palette swatch. **Minimum load** sets how much a single tap
loads, and **Loading speed** sets how fast rubbing adds paint.

### Fill types

| Type | |
|:-:|---|
| <img src="icons/fill.svg" width="48"><br>**Flat fill** | Tap to fill a region with the current shade (exact tone match). |
| <img src="icons/fill_linear.svg" width="48"><br>**Linear gradient** | Drag along the gradient, then slide on the color bar to choose the second color. Lift to finish. |
| <img src="icons/fill_circular.svg" width="48"><br>**Circular gradient** | Drag from the center to the edge, then choose the second color. |

The gradient fills have a **Tolerance** setting. Choose the eraser color as
either end of a gradient to fade the fill to transparent.

### Shapes

| | | | | |
|:-:|:-:|:-:|:-:|:-:|
| <img src="icons/shape_line.svg" width="48"> | <img src="icons/shape_rectangle.svg" width="48"> | <img src="icons/shape_square.svg" width="48"> | <img src="icons/shape_oval.svg" width="48"> | <img src="icons/shape_circle.svg" width="48"> |
| Line | Rectangle | Square | Oval | Circle |

Drag to size the shape and lift to finish. A circle starts at its center.
Choose **Outline** (1–128 px width) or **Filled**. Shapes always paint opaque.

## Color controls

| Control | What it does |
|:-:|---|
| **Color bar** | 65 grayscale dot densities, from white to black. The marker shows the selected shade. |
| <img src="icons/eraser.svg" width="48"><br>**Eraser color** | The eraser at the white end of the color bar. Your current brush, pencil, airbrush, shape or fill erases instead of painting. Tap any shade to paint again. |
| <img src="icons/eyedropper.svg" width="48"><br>**Eyedropper** | Hold and drag the pen over the artwork to sample a shade, and lift to keep it. |
| <img src="icons/water_drop.svg" width="48"><br>**Wet canvas** | Turns wet-in-wet blending on or off (a dot means on). Slide the bar next to it to set how wet the canvas is. New brush strokes blend with existing paint over a few seconds. |
| <img src="icons/opaque.svg" width="48"><br>**Opaque** | Paint covers what's underneath, white included. This is the default. |
| <img src="icons/transparent.svg" width="48"><br>**Transparent** | A half-strength glaze that darkens with each separate stroke. On a wet canvas, transparent white works as clear water. |

**Wet canvas uses gravity** (Settings) makes wet paint run downhill as you tilt
the tablet.

## Toolbar extras

| Item | What it does |
|:-:|---|
| <img src="icons/layers.svg" width="48"><br>**Layers** | Up to 8 layers per page. You can add, rename, reorder and delete layers, and set opacity. <img src="icons/layer_visible.svg" width="20"> / <img src="icons/layer_hidden.svg" width="20"> toggles visibility. Every tool works on the selected layer only. |
| <img src="icons/zoom.svg" width="48"><br>**Zoom** | Tap to unlock, then pinch with two fingers to zoom (up to 800%) or drag with two fingers to pan. Pinching inward fits the whole page. Tap again to lock. |
| <img src="icons/palette.svg" width="48"><br>**Palette** | Up to 16 saved swatches. Tap a swatch to edit it, drag to reorder, or drag to the trash to delete. |
| **Custom tools** | **Add to Toolbar** saves the current tool and its settings as a shortcut. Hold and drag shortcuts to reorder them. |

## Header

| Control | What it does |
|:-:|---|
| <img src="icons/menu.svg" width="48"><br>**Menu** | New, Open, Save, Save as, and Settings. Drawings can go in folders. |
| <img src="icons/undo.svg" width="48"> <img src="icons/redo.svg" width="48"><br>**Undo / Redo** | Covers strokes, fills, layer changes and clears. |
| <img src="icons/clear.svg" width="48"><br>**Clear** | Clears the current layer or all layers. Undo restores them. |
| <img src="icons/pages.svg" width="48"> <img src="icons/new.svg" width="48"><br>**Pages** | Previous/next page and Add page. Tap the page number to see a thumbnail grid. |
| <img src="icons/rotate.svg" width="48"><br>**Rotate** | Shows up briefly after you turn the tablet. Tap it to rotate the controls. The artwork stays put like a sheet of paper. |

## Settings

Open these from **Menu → Settings**:

- **Drawing hand**: puts the controls on the side opposite your hand.
- **Turn to landscape / portrait**: rotates without using the tilt sensor.
- **Side toolbar icon size** and **Settings text size**: Medium or Large.
- **Wet canvas uses gravity**: makes wet paint flow downhill.
- **Nomad Simulation Mode** (Manta only): previews the smaller Nomad screen size.
- **Toolbar**: show, hide and reorder the built-in tools.

## Saving

Drawings save to the app's private storage as `.tsm` files, with all pages in
one file. Work in progress is recovered automatically after a restart. The undo
history does not survive a restart. To convert a drawing to PNG on a computer,
see [BUILDING.md](BUILDING.md#png-conversion).
