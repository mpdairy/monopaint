# MonoPaint tools and features

Tap a tool to select it. Tap it again to open its settings.

## Drawing tools

| Tool | What it does |
|:-:|---|
| <img src="icons/brush.svg" width="24"><br>**Brush** | Pressure-sensitive paintbrush. |
| <img src="icons/brush_pen.svg" width="24"><br>**Brush pen** | Clear water that drags paint around. |
| <img src="icons/pencil.svg" width="24"><br>**Pencil** | Textured pencil. Lean it for broader strokes. |
| <img src="icons/airbrush.svg" width="24"><br>**Airbrush** | Soft spray. Go over an area again to build up color. |
| <img src="icons/fill.svg" width="24"><br>**Fill** | Flat or gradient flood fill. |
| <img src="icons/shapes.svg" width="24"><br>**Shapes** | Lines, rectangles, squares, ovals and circles. |
| <img src="icons/eraser.svg" width="24"><br>**Eraser** | Erases to reveal the layers below. A pen's eraser end or side button, if it has one, always uses this tool. |
| <img src="icons/soften.svg" width="24"><br>**Blending stump** | Smudges shading in the direction you rub. |

### Brush heads

| Head | |
|:-:|---|
| <img src="icons/brush.svg" width="24"><br>**Round** | Even in every direction. |
| <img src="icons/brush_flat.svg" width="24"><br>**Flat** | Square edge that follows pen tilt. |
| <img src="icons/brush_filbert.svg" width="24"><br>**Filbert** | Rounded oval that follows pen tilt. |

**Oil paint** (a brush option): the brush runs out of paint along the stroke and
then just smudges. Rub the pen on the color bar or a swatch to reload it.

### Fill types

| Type | |
|:-:|---|
| <img src="icons/fill.svg" width="24"><br>**Flat fill** | Tap to fill a region. |
| <img src="icons/fill_linear.svg" width="24"><br>**Linear gradient** | Drag a line, then pick the second color. |
| <img src="icons/fill_circular.svg" width="24"><br>**Circular gradient** | Drag from the center out, then pick the second color. |

### Shapes

| | | | | |
|:-:|:-:|:-:|:-:|:-:|
| <img src="icons/shape_line.svg" width="24"> | <img src="icons/shape_rectangle.svg" width="24"> | <img src="icons/shape_square.svg" width="24"> | <img src="icons/shape_oval.svg" width="24"> | <img src="icons/shape_circle.svg" width="24"> |
| Line | Rectangle | Square | Oval | Circle |

## Color controls

| Control | What it does |
|:-:|---|
| **Color bar** | 65 shades of gray. |
| <img src="icons/eraser.svg" width="24"><br>**Eraser color** | Makes your current tool erase instead of paint. |
| <img src="icons/eyedropper.svg" width="24"><br>**Eyedropper** | Picks a shade from the drawing. |
| <img src="icons/water_drop.svg" width="24"><br>**Wet canvas** | New strokes blend into the paint already there. |
| <img src="icons/opaque.svg" width="24"><br>**Opaque** | Paint covers what's below. |
| <img src="icons/transparent.svg" width="24"><br>**Transparent** | Paint layers like a glaze. |

## Toolbar extras

| Item | What it does |
|:-:|---|
| <img src="icons/layers.svg" width="24"><br>**Layers** | Up to 8 layers per page. <img src="icons/layer_visible.svg" width="24"> shows or hides a layer. |
| <img src="icons/zoom.svg" width="24"><br>**Zoom** | Tap to lock/unlock pinch and pan. Quickly double-tap for 100% with the artwork back where the page began (an expanded page reaches under the bars), keeping the lock setting. |
| <img src="icons/palette.svg" width="24"><br>**Palette** | Your saved swatches. |
| **Custom tools** | **Add to Toolbar** saves a tool with its settings. |

## Header

| Control | What it does |
|:-:|---|
| <img src="icons/menu.svg" width="24"><br>**Menu** | New, open, save and settings. |
| <img src="icons/undo.svg" width="24"> <img src="icons/redo.svg" width="24"><br>**Undo / Redo** | Undo or redo the last change. |
| <img src="icons/clear.svg" width="24"><br>**Clear** | Clears one layer or all layers. |
| <img src="icons/pages.svg" width="24"> <img src="icons/new.svg" width="24"><br>**Pages** | Change pages or add a page. |
| <img src="icons/rotate.svg" width="24"><br>**Rotate** | Appears when you turn the tablet. Tap it to rotate. |

## Fullscreen and canvas size

Swipe one finger from the canvas all the way toward a bar's screen edge to hide
or show that bar. This works immediately in the regular layout. Both bars toggle
independently and stay where you put them. The artwork stays in place.
Swipe **up toward the top edge** for the top bar; Supernote's downward swipe
from the bezel remains available. The touch screen often loses a finger just
before the bezel, so a finger lifted while still heading off the edge counts;
pausing first, or lifting farther inside the canvas, does nothing.

Double-tap with **two fingers** to hide both bars when both are showing, or show
both when neither is showing. With one bar showing, it undoes the last bar change.
**Back** brings all controls back. These gestures work while Zoom is locked.
**Menu → Fullscreen** offers all four layouts without gestures.

The first time a swipe or double tap hides controls on **each page**, large
illustrated buttons offer **Keep canvas size** or **Expand canvas**. Expansion adds blank margins, keeps the
artwork at the same size, and can be undone. Each page remembers its own choice;
other pages are unaffected. Showing the bars again never crops an expanded page.
Existing saved drawings also support these options.

## Settings

**Menu → Settings** has the drawing hand, rotation, settings text size,
**Wet canvas uses gravity** (wet paint runs downhill), **Smooth brush edges**
(anti-aliased dry brush and hard eraser strokes; better for PNG exports; adds a tiny bit of lag), Nomad Simulation Mode,
and which tools appear on the toolbar.

## Saving

Each painting saves as one `.mpaint` file holding every page, and your work is
recovered after a restart. Once MonoPaint has file access, which it asks for at
every launch until allowed, paintings live in `Document/MonoPaint` on a Supernote
(where Files shows them) or `Documents/MonoPaint` on other tablets, and survive
uninstalling the app. **New painting** and **Open painting** offer to save
unsaved changes first. **Export PNG** saves to `EXPORT/MonoPaint` on a Supernote
with file access, otherwise `Pictures/MonoPaint`. To convert a painting to PNG
on a computer, see [BUILDING.md](BUILDING.md#png-conversion).
