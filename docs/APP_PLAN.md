# TileSmile app handoff plan

Requested scope, 2026-09-24, updated during implementation. The 0.23 document
app implements the foundation, all five tools, saved custom presets, and an
optional selection-marker experiment (off by default). The pencil is an initial
independent implementation awaiting physical reference comparison. Real-pen
acceptance of the new tools and physical evaluation of selection feedback remain.
The accepted 0.12 probe is preserved separately.

0.16 follow-up: the user reports the tools working and requests stronger
progressive softening, pencil hardness, gradual rubbing-based erasing with
adjustable softness, and icons without names plus individual settings arrows.
These changes are implemented, including Add to Custom within each tool's
settings. Existing presets migrate with their identities, names, order and sizes.

0.17 follow-up: tiny arrows now live within each tool box. First tap recalls
the tool/preset; tapping the current selection again opens settings. Brush,
Pencil, Eraser and Soften have minimum/maximum size sliders and simultaneous
footprint previews. The eraser icon is a white rectangular block and Soften is
a blur-dot icon. New/Open/Save/Clear live in a top-left file dropdown; Clear is
one undoable edit covering the entire document.

0.18–0.19 follow-up: square tool buttons have larger integrated arrows. Clear
page sits above Undo/Redo, with Custom below. An icon-equipped hamburger menu
provides New/Open/Save/Settings. Page-plus appends blank paper; previous/next
arrows navigate the drawing. One ZIP-based `.tsm` saves all ordered tone pages
and the current position, while importing older single-page files. Settings can
move controls to either side. Tool dialogs use compact, bold slider controls
without size shortcuts. Zero-softness erasing is now fully opaque and crisp;
positive softness preserves gradual rubbing.

0.20–0.21 follow-up: page controls sit after the gradient at the upper right.
The tool rail is narrowed to 64 dp with a 2 dp outer inset. New drawings gain
canvas width; existing pages keep their dimensions. Fill tolerance is a saved
per-tool/preset setting; it expands the fixed seed-tone range without allowing
that range to drift. Soften now uses a rolled-paper blending-stump icon and
blends mostly from behind the moving tip, making rubbing direction matter.

Build a simple painting app for the Supernote Manta with a pressure paint
brush, tilt pencil, flood fill, hard/soft erasers, soften brush, and saved
tool presets. Put tools on the left and a continuous-looking dotted shade
picker across the top. Add the fast selection-blob experiment later.

## Keep the proven drawing behavior

Version **0.12 is the physically accepted gradient baseline**. Preserve its
calibrated dot densities, responsive pressure brush, opaque overpainting,
and retained appearance after lifting the pen. Reference:

- [Gray density and calibration](GRAY_DENSITY.md)
- [Direct display implementation and history](DIRECT_GRAY.md)
- [Verification record](../VERIFICATION.md)
- Baseline APK: `artifacts/tilesmile-0.12-calibrated-density.apk`

The current direct path uses driver mode 7 / flags 0. Failed GL/mode-4
experiments should not return. The 0.12 probe uses a separate firmware
renderer for black; the document app integrates black into its logical tone
model and direct display path. That integration needs its own physical check.
Correct tilt input was physically verified for the round pressure brush;
that does not establish a finished textured, tilting pencil.

## Layout and interaction

- **Top:** a black-to-white dotted gradient that supports tapping or dragging
  anywhere to pick a shade, with a selection marker and selected-shade preview.
  Keep it shorter than the full screen width. Include generous pure-black and
  pure-white areas at its ends so those shades are easy to tap.
  This supersedes the original 16 fixed swatches at the user's request: the
  lightest gray-to-white gap was too large. Space the picker by dot density,
  exposing all 65 densities supported by the accepted 8 × 8 pattern. Render
  the gradient and preview with the same calibrated black-and-white pixels
  as canvas painting, not solid-gray UI fills. Keep white visibly bounded.
- **Left or right:** built-in tools, Clear, Undo/Redo, then **Custom** presets. Allow this
  rail to scroll when needed. Keep the active tool/preset clearly marked.
  Give tools and supporting actions clear monochrome icons without visible names;
  keep accessible labels and tooltips. Custom names appear in their settings.
- **Tool settings:** each tool/preset has a tiny sideways arrow within its box.
  Tap to recall; tap the selected box again to open a panel for
  size and that tool's options. Changing settings should not require drawing
  a test stroke: show both footprint previews and numeric minimum/maximum diameters.
  Include **Add to Custom** in this panel instead of a separate rail action.
- **Canvas:** use the remaining space. Toolbar touches must never paint.
  Tool/color changes take effect on the next stroke; finish an active gesture
  before switching settings.
- **Small supporting controls:** Clear above Undo/Redo in the rail; a hamburger
  menu for new/open/save/settings. Page-plus, count and navigation live in the
  header. Clear affects only the current page and supports one-step undo. Keep debug
  modes and probe statistics out of the main drawing UI.

## Tools

| Tool | Intended behavior | User settings |
| --- | --- | --- |
| Paint brush | Opaque round brush: pressure grows it from the selected minimum to maximum. Tilt stays off by default. | Minimum diameter and maximum up to 128 px, sliders and paired previews. |
| Pencil | Textured pencil inspired by the reference app. Upright grows from the minimum to its full-pressure tip; leaning broadens contact up to the maximum. Soft deposits more graphite; hard leaves a lighter mark. | Minimum/maximum diameters, upright tip within those bounds, tilt toggle and 0–100% hardness. |
| Flood fill | Tap to replace a connected region with the selected gray. | Selected palette shade and 0–100% tolerance, measured against the original tapped tone; 0 keeps exact fill. |
| Eraser | Pressure controls diameter within the selected range. Zero softness erases fully with a crisp edge. Positive softness builds with rubbing; holding still adds no effect. | Minimum/maximum diameter and 0–100% softness, from crisp to feathered edges. |
| Soften | Use the paper blending stump to pull existing tones in the rubbing direction. Repeated passes increase the blending. It uses existing colors and adds no effect while stationary. | Minimum/maximum diameter and 0–100% strength, default 35%; 100% retains the initial directional stump strength. |

“The other app's pencil” is assumed to mean **Atelier's pencil**. At the pencil
milestone, confirm the preferred pencil variant and compare its upright and
tilted strokes. Implement the desired behavior independently; no vendor brush
assets or code are part of this plan. Tilt should influence the contact shape
and orientation where practical, rather than only enlarging a round brush.

For this first single-layer app, erasing restores the white paper. Transparent
layer erasing can be designed when layers become an actual requirement.
Soft erasing and softening can share footprint/falloff code, but their pixel
operations differ: one blends toward white; the other blends nearby tones.

## Custom presets

Every built-in tool can be added to **Custom** with its current settings.
One tap recalls the tool, maximum size, and applicable hardness, softness, tip
and tilt settings. A preset can be renamed, reordered, updated or removed.
Remember the last settings used for each built-in tool as well.

Default choice: **color stays global**, so switching between a big brush and
a small pencil keeps the currently selected shade. Presets initially save
tool settings, not color. Store presets across restarts; later edits to an
active tool should not silently overwrite a saved preset.

## Foundation needed for these tools

The probe currently retains binary display dots. The app needs a separate
**logical grayscale document** and **derived dotted display bitmap**:

1. Tools modify logical tone values and mark a dirty region.
2. The renderer converts that region into the calibrated opaque dot pattern,
   anchored to document coordinates so overlapping updates do not shift it.
3. Present those pixels through the accepted display path and retain the
   same pixels for ordinary UI redraws. Keep the existing no-extra-pen-up
   refresh behavior.

The picker exposes all 65 existing dot densities; an 8-bit internal tone buffer
also holds intermediate values needed by soft edges and blending. Use the calibrated
transfer curve for presentation. Never infer a painted shade back from its
black/white display dots.

Flood fill must inspect logical colors, otherwise it can escape through white
holes in dithering. Use an iterative, bounded fill (initially four-connected),
with a responsive UI and one undoable result. Soften should sample a stable
source neighborhood for each dab, avoiding in-place scan-order bias. Its
boundary handling must not pull nonexistent pixels in as black or white.

Pencil texture belongs to the logical mark; display dithering is a separate
step. If using a firmware renderer for any tool, establish how its mark enters
the logical document. Do not substitute thresholded display output for tones
needed by fill or blending.

Each stroke/fill is one undo operation. Save the logical document and presets
locally, with safe writes and recovery after activity recreation or app
restart. Add basic new/open/save so the finished app no longer loses a drawing
when the probe activity closes. Each drawing now contains ordered white-paper
pages in a ZIP-based `.tsm`, retaining editable tones rather than display PNGs.
Limit drawings to 100 pages and 16 MiB of compressed page data; keep only two
recent pages decoded with bounded undo histories. Older pages retain their marks
but lose undo history when evicted. Layers, zoom/pan, cloud sync and extra export
formats can wait for separate requirements.

## Suggested implementation order

1. **Document and renderer foundation.** Separate logical tones from display
   dots, add undo/redo and basic persistence, and reproduce the accepted 0.12
   gradient and pressure strokes through the new model.
2. **App shell and paint brush.** Build the left rail, dotted gradient picker,
   maximum-size control and footprint preview. Verify small/light through
   large/heavy strokes on the actual tablet.
3. **Presets.** Implement the common tool/settings model and one-tap custom
   list early, so each subsequent tool can use it without a separate system.
4. **Pencil and erasers.** Develop the pencil's texture/tilt behavior against
   the reference, then hard and soft pressure-sized erasers. Share sampling
   and footprint code where their behavior agrees.
5. **Fill and soften.** Work against logical tones; verify boundaries,
   repeated use, undo and acceptable performance on large affected regions.
6. **Finish and physically verify.** Check every tool and preset, persistence,
   overpainting, palm/touch handling, and shade consistency. Preserve a known
   good APK at each accepted milestone.
7. **Selection blob experiment.** Only after the ordinary app controls work.

## Later: immediate selection blobs

On a tool, preset or swatch tap, immediately draw a small marker using the
fast pen/display path. It may remain as the selection indicator. On the next
selection, restore the old marker's background and draw the new marker. Keep
one active marker per selection group, so tool and color can both be shown.
Use a contrasting outline where the selected shade would disappear into the
control background, especially white.

Implement these as **UI overlays**, excluded from the painting, undo history
and saved document. Preserve the pixels under the old marker rather than
erasing it with an assumed white patch. Normal Android redraws must reproduce
the current marker and must not resurrect the previous one. Clip all drawing
to owned control bounds and handle rapid taps, scrolling, menus and rotation/
lifecycle changes.

The canvas direct presenter is intentionally limited to the canvas. UI blobs
need a separately bounded presentation surface and coordination with Android
UI painting. This is a new physical experiment; the canvas result alone does
not prove that immediate toolbar feedback will work. Keep the normal selection
indicator as the baseline while evaluating it.

Implementation in 0.15: **Settings → Instant selection dots (experimental)**
enables separate, short-lived presentation sessions restricted to each visible
control's bounds. Tool/preset dots and the shade marker also render normally in
their owning Views. Each change captures the actual control before and after
the marker mutation, including its background; it submits only the changed
rectangle plus a small clean border. There are no deferred control writes or
retained control surfaces. Clipped/offscreen controls and busy display queues
use normal Android redraws. Canvas presentation remains separately bounded.

## Completion checks for the builders

- The accepted gray gradient and live stroke responsiveness survive the app
  conversion, pen-up and later UI redraws.
- Pressure brush/eraser diameters and the pencil's tilted footprint never
  exceed their selected maximum; pencil tilt feels right with the real pen.
- Repeated erasing restores white cleanly. Soft erasing feathers without stray
  dark pixels. Soften carries tones in the stroke direction without scan-order
  artifacts or changing pixels outside its contact footprint.
- Fill stays inside logical region boundaries, handles a large blank canvas,
  and undoes as a single action. It does not treat display dots as gaps.
- Tool presets recall the correct settings in one tap, keep global color,
  and survive restart. Undo/redo and saved documents reproduce logical tones
  and the corresponding displayed result.
- Automated bitmap/buffer checks pass, followed by physical pen checks on
  the Manta. Screenshot success alone is not a latency or panel-quality test.

Useful starting points: `ProbeActivity.java` (UI/input), `NativePen.java`
(pressure/tilt and stroke handling), `DotGray.java` / `GrayPalette.java`
(accepted shade mapping), `DirectEink.java` / `direct_eink.c` (presentation),
and `WidthInstrumentation.java` (existing regression checks).
