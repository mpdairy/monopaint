# MonoPaint todo

Captured 2026-09-29. Checked items are implemented; unchecked items remain in
the backlog. Tentative ideas and suggestions below still need a design decision.

## Requested features

- [ ] **Nomad-sized preview on Manta.** Add a temporary preview that lays out
  the whole app inside a 1404 × 1872 px area of the Manta, leaving the rest blank,
  to try the Nomad's smaller painting space, sidebar, settings, and rotated
  layouts. Preserve physical control and brush sizes rather than shrinking a
  screenshot; verify the Nomad's Android UI density when matching controls.
  Both screens are 300 PPI, and the A6 X2 Nomad and A5 X2 Manta share the
  Core X2 motherboard (RK3566, 4 GB RAM), making this useful for layout testing.
  Separately investigate adapting the Manta-specific direct e-ink screen checks;
  actual Nomad pen latency and refresh behavior still need testing on a Nomad.
  References: [screen specs](https://supernote.com/pages/help-me-choose-supernote)
  and [shared motherboard](https://supernote.com/products/supernote-motherboards).
  Backlog idea only; do not implement yet.

- [ ] **Clear button dropdown.** Tapping Clear opens a dropdown with two
  options: **Clear current layer** and **Clear all layers**. Backlog idea only;
  do not implement yet.

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

- [ ] **Basic shapes tool.** Draw straight lines, squares, rectangles, circles,
  and ovals. Decide how to select a shape and constrain squares/circles, and
  whether the first version supports outlines, filled shapes, or both.

- [x] **Choose visible tools in Settings.** Settings → Toolbar uses actual icons,
  visibility checkboxes, and up/down ordering for Brush, Pencil, Fill, Eraser,
  Blending stump, Airbrush, Layers, Zoom, and Palette. All remain visible by
  default, and at least one regular tool stays visible. Hiding the selected
  regular tool selects another visible tool. Hidden tools keep their settings;
  custom presets remain available and keep their selection. Shapes can join
  these controls when implemented.

- [x] **Eyedropper beside the color picker.** The eyedropper sits immediately
  left of the color bar, after the wet-canvas controls. Tap it, then hold and
  drag the pen over the canvas to sample visible logical grayscale continuously.
  The color marker follows the pen; lifting keeps the shade and returns to the
  previous tool or preset. Sampling adds no mark or undo action. Before contact,
  the color indicators move below the eyedropper; while sampling, the palette
  marker reappears to show the sampled shade. Hidden selected layers do not
  prevent sampling visible artwork. An interrupted gesture restores the prior shade.

- [x] **Zoom and pan — complete and user-tested.** Two-finger pinch and drag navigate the page; one finger
  never paints or pans, and pen contact/hover suppresses navigation. The Zoom
  toolbar control shows the current percentage and offers Zoom in, Zoom out,
  and Fit page; holding it fits the page directly. Magnification tops out at
  800%. New pages and orientation changes start fitted. Zoom renders logical
  shades at screen resolution and keeps pen input on the direct e-ink path.
  Host and tablet checks cover coordinates, undo, palm rejection, cancellation,
  shade calibration, all rotations, and both hands. User verified zoom and pan
  through hands-on use on 2026-09-29.

- [x] **Erase with the current brush.** An eraser color sits beside the white
  end of the color selector, including rotated layouts. Selecting it moves the
  color marker under the icon; selecting a shade (including the same shade) or
  accepting an eyedropper sample returns to painting. Brush heads retain their
  shape, size, tilt and pressure response; pencil retains its grain, and airbrush
  retains its soft flow and timed buildup. Erasing removes selected-layer coverage
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
