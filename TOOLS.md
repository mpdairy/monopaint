# MonoPaint tools and features

Tap a tool to select it. Tap it again to open its settings.

## Drawing tools

| Tool | What it does |
|:-:|---|
| <img src="icons/brush.svg" width="24"><br>**Brush** | Pressure-sensitive paintbrush. |
| <img src="icons/brush_pen.svg" width="24"><br>**Brush pen** | Clear water that drags paint around. Choose Dry or Wet in its settings: the wet one's ink keeps running through its water. Tap a color, or pick one with the Eyedropper, to dip the wet one in that ink; it gives it out as you paint and picks up what it passes over, and each time you lift it the color bar's marker slides to the ink it has left. Tap white to clean it; the Eraser color erases with it, as with other tools. |
| <img src="icons/pencil.svg" width="24"><br>**Pencil** | Textured pencil. Lean it for broader strokes. |
| <img src="icons/airbrush.svg" width="24"><br>**Airbrush** | Soft spray. Go over an area again to build up color. |
| <img src="icons/fill.svg" width="24"><br>**Fill** | Flat or gradient flood fill. |
| <img src="icons/shapes.svg" width="24"><br>**Shapes** | Lines, rectangles, squares, ovals and circles. |
| <img src="icons/eraser.svg" width="24"><br>**Eraser** | Erases to reveal the layers below. A pen's eraser end or side button, if it has one, always uses this tool. |
| <img src="icons/soften.svg" width="24"><br>**Blending stump** | Smudges shading in the direction you rub. |
| <img src="icons/lasso.svg" width="24"><br>**Lasso** | Draw a loop around part of the current layer to pick it up. Drag inside it to move it, a corner dot to resize it (it keeps its proportions), or the round knob to turn it; turns settle on quarter turns. The knob turns with the piece, and sits just inside it when there's no room on screen. Tap outside it, or choose anything else, to put it down. Undo puts it back where it was. |

### Brush heads

| Head | |
|:-:|---|
| <img src="icons/brush.svg" width="24"><br>**Round** | Even in every direction. |
| <img src="icons/brush_flat.svg" width="24"><br>**Flat** | Square edge that follows pen tilt. |
| <img src="icons/brush_filbert.svg" width="24"><br>**Filbert** | Rounded oval that follows pen tilt. |

**Oil paint mode when opaque** (a brush option): with opaque paint, the brush
runs out of paint along the stroke and then just smudges. Rub the pen on the
color bar or a swatch to reload it.

**Watercolor mode** (the droplet in the color bar): with transparent paint,
brushes paint like the wet brush pen: they lift paint, soak up ink and keep it between
strokes, and their ink runs and pools downhill when **Watercolor uses gravity** is
on. While the pen is down the stroke goes on as a flat glaze and nothing moves;
the paint lifts, blends and runs once you lift the pen. The selected color is the lightest shade it lays; darker colors soak up less.
Tap a color to reset the ink the brush has picked up. On a dry canvas, dried
paint stays stuck under the wash, however much you rub it; use the wet brush pen
to lift it. On a wet canvas some of that paint
comes loose and bleeds and runs with the wash, more the wetter the canvas, and
the rest stays put and shows through. Shapes and flat fills lay their whole area as one watercolor wash, which
then blends and runs the same way; they start clean each time. Pencils, the
airbrush and gradient fills paint as usual.

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
| <img src="icons/masking_fluid.svg" width="24"><br>**Masking fluid** | Choose it like a color, then paint it on with any tool; it shows as diagonal hatching. Paint, erasing and seeping wet paint can't reach under it, on any layer. With the Eraser color on as well, your tool rubs it off. Hold it and <img src="icons/peel.svg" width="24"> **Peel** pops out below: slide onto it and lift, or tap it, to peel it all off. Clear page peels it too. |
| <img src="icons/water_drop.svg" width="24"><br>**Watercolor mode** | Transparent brushes, shapes and flat fills paint watercolor (see Brushes). The droplet is solid while it's on and dashed while it's off. While watercolor or wet canvas paint is still moving, the droplet's outline ripples, slowing as the paint settles. Tap it while it ripples and it turns into a <img src="icons/hair_dryer.svg" width="24"> **blow dryer** for a moment: all wet paint dries at once and stays as it is, and both modes stay as they were. Tap the resting droplet to turn Watercolor mode on or off. |
| **Wetness slider** | Beside the droplet: slide it up to wet the canvas, so new strokes blend into the paint already there, the higher the longer it stays wet. Slide it to the bottom to dry the canvas. |
| <img src="icons/opaque.svg" width="24"><br>**Opaque** | Paint covers what's below. |
| <img src="icons/transparent.svg" width="24"><br>**Transparent** | Paint layers like a glaze. |

## Toolbar extras

| Item | What it does |
|:-:|---|
| <img src="icons/layers.svg" width="24"><br>**Layers** | Up to 10 layers per page. <img src="icons/layer_visible.svg" width="24"> shows or hides a layer. **Merge down** joins the selected layer into the one below it: the picture looks the same, each layer's opacity is applied, and the merged layer keeps the lower layer's name at 100% opacity. Both layers must be showing. One undo splits them again. |
| <img src="icons/zoom.svg" width="24"><br>**Zoom** | Tap to lock/unlock pinch, twist and pan. Twist two fingers to turn the canvas to any angle; it follows once the twist passes a few degrees, and settles upright or on a quarter turn if you finish within a few degrees of one. Quickly double-tap for 100%, upright, with the artwork back where the page began (an expanded page reaches under the bars), keeping the lock setting. |
| <img src="icons/palette.svg" width="24"><br>**Palette** | Your saved swatches. |
| **Custom tools** | **Add to Toolbar** saves a tool with its settings. |

## Header

| Control | What it does |
|:-:|---|
| <img src="icons/menu.svg" width="24"><br>**Menu** | New, open, save and settings. |
| <img src="icons/undo.svg" width="24"> <img src="icons/redo.svg" width="24"><br>**Undo / Redo** | Undo or redo the last change. Tap the canvas with **two fingers** to undo, or **three fingers** to redo. |
| <img src="icons/page.svg" width="24"><br>**Page** (Manta) | Drops down a column of page actions: <img src="icons/new.svg" width="16"> add a page, <img src="icons/pages.svg" width="16"> duplicate the page, <img src="icons/page_delete.svg" width="16"> delete the page, <img src="icons/page_clear.svg" width="16"> clear the whole page, and <img src="icons/layer_clear.svg" width="16"> clear the current layer. Deleting a page asks first; clearing can be undone. |
| <img src="icons/pages.svg" width="24"> <img src="icons/new.svg" width="24"><br>**Pages** | Change pages or add a page. On the Nomad, the Pages box also holds the other page actions in a second row. |
| <img src="icons/rotate.svg" width="24"><br>**Rotate** | Appears when you turn the tablet. Tap it to rotate. |

## Fullscreen and canvas size

Swipe one finger from the canvas all the way toward a bar's screen edge to hide
or show that bar. This works immediately in the regular layout. Both bars toggle
independently and stay where you put them. The artwork stays in place.
Swipe **up toward the top edge** for the top bar; Supernote's downward swipe
from the bezel remains available. The touch screen often loses a finger just
before the bezel, so a finger lifted while still heading off the edge counts;
pausing first, or lifting farther inside the canvas, does nothing.

**Back** brings all controls back. Swipes work while Zoom is locked.
**Menu → Fullscreen** offers all four layouts without gestures.

The first time you hide controls on **each page**, large
illustrated buttons offer **Keep canvas size** or **Expand canvas**. Expansion adds blank margins, keeps the
artwork at the same size, and can be undone. Each page remembers its own choice;
other pages are unaffected. Showing the bars again never crops an expanded page.
Existing saved drawings also support these options.

## Settings

**Menu → Settings** has the drawing hand, rotation, settings text size,
**Watercolor uses gravity** (wet paint runs downhill), **Smooth brush edges**
(anti-aliased dry brush and hard eraser strokes; better for PNG exports; adds a tiny bit of lag), Nomad Simulation Mode,
and which tools appear on the toolbar.

## Saving

Each painting saves as one `.mpaint` file holding every page, and your work is
recovered after a restart. Once MonoPaint has file access, which it asks for at
every launch until allowed, paintings live in `Document/MonoPaint` on a Supernote
(where Files shows them) or `Documents/MonoPaint` on other tablets, and survive
uninstalling the app. There is no Save button. A **New painting** is kept inside
the app until you name it with **Save painting as…**; starting or opening
another painting first asks whether to name it. Once a painting has a name, it
saves itself to that file as you work: within a minute of drawing, whenever you
leave MonoPaint, and before you open or start another painting. Save painting
as… on a named painting copies it to a new name and continues in the copy, so
the original keeps how it looked at that moment. **Import image…** brings a
PNG or JPEG from anywhere on the tablet (it opens in the folder you last imported
from, Download at first) onto the page, on the current layer or a new one (whichever you picked last), fitted to the page
or at its own size, and turned a quarter at a time with a preview of it on the page; it arrives picked up, like a Lasso piece, to move, resize and
turn before you tap outside it. Grays come in as the tones that export them, so an
exported painting imports as it was. **Export PNG** saves to `EXPORT/MonoPaint` on a Supernote
with file access, otherwise `Pictures/MonoPaint`. To convert a painting to PNG
on a computer, see [BUILDING.md](BUILDING.md#png-conversion).
