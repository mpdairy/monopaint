# MonoPaint todo

Captured 2026-09-29. Checked items are implemented; unchecked items remain in
the backlog. Tentative ideas and suggestions below still need a design decision.

## Requested features

- [x] **Consistent side-opening tool settings — implemented; hands-on acceptance pending.**
  After the first-use Shapes choice, every tool and custom preset selects on
  the first tap; tapping the
  selected entry again opens its choices/current settings beside the icon.
  Brush and Shapes icons reflect their selected variant in both toolbar sections
  and update live after edits. Custom presets retain independent settings and
  deletion; matching shortcut icons receive consecutive dots. Removed redundant
  tool headings. Settings text uses bold 16 sp labels
  by default, with an 18 sp Large option under main Settings; helper text is one
  point smaller. Layers, Palette, and main settings share the text preference.
  Zoom now uses the direct lock toggle described below.
  This replaces the oversized 18–24 sp readability follow-up. Panels fit either
  toolbar side and every rotation. Filled shapes still use one solid shade.

- [x] **Layer opacity — implemented; hands-on acceptance pending.** Layers has a
  0–100% opacity slider for the selected layer, applied on release. New and older
  layers default to 100%. Opacity fades the whole layer without changing its
  marks, persists with the drawing, and supports undo/redo. Shape previews,
  zoom, page thumbnails, and ordinary drawing use the same faded composition.

- [x] **First-use Shapes chooser — implemented; hands-on acceptance pending.**
  The default Shapes tool shows the combined square-and-circle icon until a
  shape is chosen. Its first tap opens the chooser directly. Choosing a shape
  reveals its settings and remembers the choice; later taps follow normal
  select-then-settings behavior. Custom shapes retain their own icons/settings.

- [x] **Flood Fill chooser like Brushes and Shapes — implemented; hands-on acceptance pending.**
  The side popout offers Flat fill, Linear gradient, and Circular gradient icons.
  Flat has no settings or instructions and fills on tap or drag. Each gradient
  shows tolerance and brief direction instructions. The selected icon follows
  the type in both regular and custom tools; older gradient settings persist.

- [x] **Nomad-sized preview on Manta — implemented; hands-on acceptance pending.**
  Settings → **Nomad Simulation Mode** centers the live app in a 1404 × 1872 px area,
  with black margins. The setting persists and defaults
  off. Controls keep their physical size; the app lays out within the smaller
  area in every rotation and drawing hand. Existing pages fit without changing
  their dimensions or marks; switching modes preserves the drawing and page.
  Tool/file panels and settings stay within the preview. Nomad now has a square
  Pages icon with a fast-opening anchored panel for previous/next and Add page;
  Wet canvas and its strength slider stay beside the color controls. Header
  groups fit their icons, and the floating rotation prompt uses no header space;
  the color bar takes all reclaimed space. Page arrows and Add page give
  immediate fast press feedback before loading in both modes; Manta remains
  expanded.
  Manta uses Android density 300; Nomad Android UI density
  still needs measurement before claiming an exact match to native Nomad controls.
  Both screens are 300 PPI: [screen specs](https://supernote.com/pages/help-me-choose-supernote).

- [x] **Temporary corner rotation prompt — hands-on acceptance pending.**
  Appears in the corner diagonally opposite the hamburger after a stable turn,
  with its icon facing the suggested orientation. Expires after five seconds; a shake can recall the
  pending orientation. Steady sensor updates do not repeatedly reopen it.
  Works inside either full-screen Manta or the centered Nomad preview.
  Pressing the prompt gives immediate fast-path feedback before the app turns.

- [x] **Actual Nomad display support — implemented; physical pen acceptance pending.**
  Nomad's driver buffer is 1872 × 1404, stride 1872, size 2628288: the panel's
  landscape scan order (firmware `hwrota=270`). A read-only dump of the shared
  plane shows panel (x,y) stored at (y, 1404−x); `libeinkutils` rotates its
  rectangles before the same region ioctl. `DirectEink` composes that fixed
  mount onto its panel transform; the native guard accepts only the two
  queried layouts. A real Nomad also uses the compact header (Pages button).
  Tool-selection feedback was verified in the dumped plane. User confirms
  smooth painting (2026-10-05). Pen tilt arrived 90° off (thin Flat on the
  broad side): DrawingPad turns Nomad tilt from the landscape frame using the
  display mount's direction. User confirms the fixed tilt works (2026-10-05).
  After the firmware update (now Chauvet.E103.2606141001.2389, as on Manta)
  layout and tilt are unchanged. Color-bar dragging initially left a solid
  trail with mode 7; pacing could not remove it without choppiness. The binary
  color bar now uses the firmware pen plane and mode 9/flags 1 without pacing.
  User confirms the dots follow perfectly (2026-10-05). See DIRECT_GRAY.md
  for the verified pixel format. All binary control patches now use that path,
  including wetness and swatches; button edges use consistent dot rendering.
  The 60 ms workaround is removed. Gray patches, Manta feedback and canvas
  drawing retain mode 7.

- [x] **Clear button dropdown — implemented; hands-on acceptance pending.**
  Clear offers Clear current layer and Clear all layers. Both are one undoable
  edit on the current page. Clear all includes hidden layers while retaining
  layer names, order, visibility, opacity, and selection.

- [x] **Palette tool in the sidebar — complete and user-accepted.** The rounded
  painter's palette icon opens a two-column swatch editor beside its icon,
  while leaving the main color selector usable.
  Tap a swatch to select and edit its shade with the main color bar. Tap the
  dashed empty box, then choose a color to add one; after three seconds the
  selected empty box shows “Select a color.” Drag onto a swatch to insert before
  it, or onto the empty box to move to the end. Drag into the trash to delete;
  tapping the trash does nothing. Deletion closes gaps, and an empty palette is
  supported. Up to 16 swatches persist across restarts. The sidebar grows with
  the set and supports toolbar visibility/order in all rotations and both hands.
  Swatch selection retains the brush/preset and exits brush erasing. Live color
  selection leaves the swatches unchanged while the pen is down; release updates
  the selected cell and sidebar and saves without rebuilding the toolbar.
  User tested and accepted the palette on 2026-09-29, including popup placement
  and the responsive color selector with swatch updates on pen-up.

- [x] **Gradient flood fill — complete and user-tested.** Linear and Circular
  are selectable in Fill settings and remembered per tool/preset.
  Tap for solid fill; drag for a gradient, then choose the second color.
  Linear follows the drag direction. Circular uses pen-down as the center and
  the drag length as its radius, with the second color at and beyond that radius.
  Both preserve the seed region, tolerance, selected layer, and one-step undo.
  Both types show only the direction line over unchanged artwork until
  color selection starts. User confirmed it works well on 2026-09-29.
  The delayed hint, held preview, eyedropper sampling
  underneath the preview, and release-to-finish behavior apply to both types.

- [x] **Basic shapes tool — complete and user-accepted.** Shapes
  in the sidebar offers Line, Rectangle, Square, Oval, and Circle. Drag to size
  a live preview; lift to commit. Square and Circle keep equal dimensions.
  Circle now uses pen-down as its center and drag distance as its radius
  (implemented 2026-10-01; hands-on acceptance pending).
  Outline uses a 1–128 px width and leaves the interior empty; Filled uses one
  solid current shade with no separate outline color. Shapes affect only the
  selected layer and support one-step undo/redo. Interrupted previews cancel.
  Settings persist per regular tool/custom preset, with toolbar visibility/order.
  Drag performance follow-up uses a native display-only preview and coalesces
  pen moves to the latest position. The document changes once on pen-up.
  User accepted the improved shape drag speed on 2026-09-30.
  Host checks and tablet pen/control checks cover rotations, both hands, zoom,
  preview replacement, pen-up placement, cancellation, and session restoration.

- [x] **Choose visible tools in Settings.** Settings → Toolbar uses actual icons,
  visibility checkboxes, and up/down ordering for Brush, Pencil, Fill, Eraser,
  Blending stump, Airbrush, Layers, Zoom, and Palette. All remain visible by
  default, and at least one regular tool stays visible. Hiding the selected
  regular tool selects another visible tool. Hidden tools keep their settings;
  custom presets remain available and keep their selection. Shapes is included in
  these controls too.

- [x] **Eyedropper beside the color picker.** The eyedropper sits immediately
  left of the color bar, after the wet-canvas controls. Tap it, then hold and
  drag the pen over the canvas to sample visible logical grayscale continuously.
  The color marker follows the pen; lifting keeps the shade and returns to the
  previous tool or preset. Sampling adds no mark or undo action. Before contact,
  the color indicators move below the eyedropper; while sampling, the palette
  marker reappears to show the sampled shade. Hidden selected layers do not
  prevent sampling visible artwork. An interrupted gesture restores the prior shade.

- [x] **Zoom and pan lock — complete and user-accepted.**
  Navigation starts locked; tapping Zoom toggles both pinch zoom and two-finger
  panning. A small closed/open padlock updates through the fast e-ink feedback
  path beside the current percentage. Locking preserves the current view and
  leaves pen drawing enabled. The lock choice persists across restarts.
  Zoom has no settings panel or hold-to-fit action; pinch inward to fit the page.
  One finger never paints or pans, and pen contact/hover suppresses navigation
  even when unlocked. Magnification tops out at 800%; new pages and orientation
  changes start fitted. Host checks, lint, and tablet gesture/toolbar checks pass
  in all four rotations and both hands, including fast lock feedback and drawing
  while locked. User accepted the lock on 2026-09-30.

- [x] **Faster pinch and pan preview — complete and user-accepted.**
  Gestures use the fast e-ink path with one retained presenter and coalesce queued
  moves to the latest finger position. The percentage uses fast feedback too.
  Full-resolution calibrated shades remain intact; gesture completion and pen
  interruption settle the latest view. Native layer composition speeds gesture
  setup, and tiled sampling plus native panel rotation speed up sideways views.
  Host/build/lint and all-rotation tablet checks pass, including queued input,
  pen/lock interruption, exact preview pixels, and retained Android redraws.
  User accepted the faster navigation through hands-on use on 2026-09-30.

- [x] **Erase with the current brush.** An eraser color sits beside the white
  end of the color selector, including rotated layouts. Selecting it moves the
  color marker under the icon; selecting a shade (including the same shade) or
  accepting an eyedropper sample returns to painting. Tapping the eraser color
  again returns to the previously selected shade. Brush heads retain their
  shape, size, tilt and pressure response; pencil retains its grain, and airbrush
  retains its softness, flow and pressure size. Erasing removes selected-layer coverage
  to reveal layers underneath, with one-step undo. It settles wet paint and bypasses
  wet/transparent paint while retaining those settings for subsequent painting.
  The mode follows supported tools and presets and is remembered across restarts.
  Fill, Blending stump, and the standalone Eraser exit the mode and disable its
  selector. The standalone Eraser and existing eraser presets remain available.
  Host and tablet checks pass; hands-on pen evaluation remains welcome.

- [x] **Airbrush — complete and user-accepted.** Smooth, soft-edged
  spray paints the selected shade on the selected layer. Pressure controls
  strength; Diameter sets a fixed 2–128 px size and Flow controls buildup.
  Flow is now six times stronger after the first pen evaluation. Fast strokes
  use the eraser's immediate distance-spaced sampling; the icon sprays down-left.
  Holding still or moving slowly builds color over time. One stroke is one
  undo; settings, toolbar visibility/order, and custom presets are supported.
  Host and tablet checks pass. User accepted the current behavior on 2026-09-29:
  it fits the app's painting focus; further airbrush refinement is not required.
  The standalone eraser remains available.
- [x] **Evaluate airbrush before changing erasers.** Completed through actual
  pen use. Fixed size, pressure-controlled strength, increased flow, and immediate
  sampling are accepted. The user also verified the faster Flat brush strokes.

- [x] **Offer to save before New drawing — implemented; hands-on acceptance pending.**
  New and Open ask Save / Don't save / Cancel when the drawing has changes
  since its last save or load, including a never-saved drawing restored after
  a restart; Save runs the normal save (or Save As) first. Unchanged drawings
  are replaced without asking.

- [x] **Keep drawings outside the app — implemented; hands-on acceptance pending.**
  Every launch asks for file access until allowed, so drawings survive
  uninstalling or a signing-key change. Once allowed, the library (folders,
  drawings and the working drawing's autosave) moves to `Document/MonoPaint`
  before the drawing loads; clashing names keep both copies, and each copy is
  read back and compared before the private original is deleted. The prompt
  counts drawings still at risk inside the app. Other tablets use the standard
  `Documents/MonoPaint`. Drawings are `.mpaint`; older `.tsm` files are renamed
  in place, never copied or deleted.

## Design opinions to revisit

- Eraser as a paint mode would let the same brush vocabulary work for adding
  and removing marks. It now works with the smooth spray while retaining the standalone
  eraser for people who prefer it.
- Tool visibility would keep the default interface simple as more tools arrive.
- The eyedropper seems like a useful early improvement; picking the visible
  shade should be easy to understand even in layered drawings.
- Zoom seems worth offering as an optional convenience with an easy whole-page
  reset. It does not have to change the app's default painting experience.
- Airbrush evaluation is complete: keep the accepted fixed size and pressure
  strength controls. Tilt-controlled size is not needed for the completed tool.
- A gradient emanating from a freehand curve remains exploratory; decide its
  distance/falloff behavior and whether it fits a simple painting app.
