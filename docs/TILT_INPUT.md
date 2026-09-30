# Supernote tilt input mapping

On the connected Manta firmware, MotionEvent contains signed **degrees**:

| Physical component | Linux input | MotionEvent field |
| --- | --- | --- |
| X lean | ABS_TILT_X (0x1a) / 100 | AXIS_ORIENTATION (8) |
| Y lean | ABS_TILT_Y (0x1b) / 100 | AXIS_TILT (25) |

Pass these to DrawingStroke.sample in X, Y order, including historical samples.
This firmware does not use stock Android's inclination/azimuth encoding.
Native PWInputPoint has separately named getTiltX/getTiltY accessors.

The former app code read TILT as X and ORIENTATION as Y. The synthetic event
helper made the same mistake, so comparisons against the correct brush geometry
passed while physical horizontal/vertical pen lean produced reversed widths.
Keep BrushDirection's perpendicular broad edge: downward lean should make a
wide vertical stroke and a thin horizontal stroke when Angle offset is zero.

## Evidence from the device, 2026-09-25

Inspected `/system/lib64/libinputreader.so` from a Supernote Manta.
Firmware build: `Chauvet.E103.2606141001.2389_release`.
SHA-256: `83db75245da1cf51b78a9744a31266bef7b389e3e1c85d3aaa567a1f764ba6bd`.
The firmware binary is not included in this repository.

- SingleTouchMotionAccumulator::process dispatches event 0x1a to 0x2aba0
  (accumulator offset 20), and 0x1b to 0x2ab88 (offset 24).
- SingleTouchInputMapper::syncTouch copies these to raw pointer tilt fields
  at 0x32508–0x32514.
- TouchInputMapper::cookPointerData loads X/Y at 0x38f68/0x38f6c and scales
  them into s11/s10 at 0x38f98/0x38f9c. The device reports both scales as 0.01.
- It passes s11 to setAxisValue(8) at 0x39440–0x39448 and s10 to
  setAxisValue(25) at 0x39450–0x39458.
- Display orientation was 0 (portrait), with identity input calibration.

The device test helper now encodes this actual wire format. The live brush
regression covers pen-down, current movement and batched historical movement,
including unequal diagonal tilt components to expose an X/Y swap. Raster checks
also verify broad downstrokes and thin sideways strokes for a constant downward
lean. Those tests establish software behavior; physical pen feel still requires
the user's assessment.

Validation: replaying the corrected wire format against the old app failed its
directional pencil assertion. With the input fix installed, the full paint suite
passed, including the live brush regression above. The first fixed-app run
stopped at a toolbar picker assertion; a repeat with the independent live input
check and expanded picker diagnostics passed the entire suite. Host checks,
Android builds and lint passed (lint: 0 errors, 66 warnings). Original recovery
book ZIP entries and all saved preference values matched after testing.

## Tilt-dependent contact (0.32)

Flat and Filbert use both lean direction and inclination when Follow pen tilt
is enabled. Pressure sets the overall diameter. Head thickness sets the narrow
dimension (0–100%, default 10%, with a visible pixel floor); old saved tools gain
the new 10% default and retain their other settings.

Contact grows from a compact equal-sided footprint near upright to the full
broad head between 5 and 60 degrees of inclination. Its center shifts forward
so a leaned blot extends from the tip toward the signed lean. Flat stays filled;
Filbert progressively hollows into a crescent. Upright Filbert contact is a
filled circle. A stationary press-and-lift therefore records the contact shape,
and dragging sweeps it into a stroke. Pressure and tilt changes are interpolated
along with position. Invalid tilt retains the preceding valid contact.

The contact's signed component is measured in the continuous head-angle frame,
so crossing the 180-degree heading boundary keeps the crescent oriented
consistently. Stamp bounds include the forward offset. The settings preview
centers the leaned footprint at actual size. Fixed-angle heads keep centered
rectangular/oval contact.

`brushOnly` device checks cover zero and maximum thickness, upright/leaned taps,
pressure-dependent blot size, opposite tilt, the crescent hollow, continuous
dragged marks, live historical input, brush settings/presets and exact undo.
`artifacts/filbert-contact.png` shows actual rendered taps and pulls at 40%
thickness to make the contact shape easy to inspect.

## Stationary pressure and rounded Filbert tips (0.33)

Pressure now deforms tilted Flat/Filbert contact independently of diameter.
At fixed width, additional pressure extends contact toward the lean and lays
more of the Filbert band into its hollow. Deformation is interpolated even
without position changes. Previously deposited pixels remain painted as pressure
rises or falls. Upright contact has no directional deformation.

Filbert curvature has a minimum depth relative to broad width, independent of
bristle band thickness. A thin setting therefore keeps a visible rounded arc
instead of flattening it into an almost straight line. This also increases the
spatial depth swept by a sideways Filbert stroke. Fixed-angle geometry retains
its existing centered oval/rectangle. Stamp bounds and the settings preview
include the fully compressed contact.

The stationary-pressure regression holds minimum and maximum width equal and
checks extra coverage in six lean directions, preservation of initial tip paint,
release, upright contact, roundness at 10% thickness, and exact undo/redo.
`artifacts/brush-pressure.png` compares light contact, additional pressure in the
same spot, upright contact, and a light pull for both heads.

## Full pressure contact and idle presentation (0.34)

The 0.33 upright footprint was constrained by Head thickness, and its explicit
compression multiplied by lean, making upright compression zero at fixed size.
The new contact model spreads upright bristles across the broad width and adds
depth as pressure rises. Leaned contact also gains width and lays down farther
toward the lean. Filbert hollowing reduces as more of its belly makes contact.
Light contact retains the rounded thin tip. Compression uses the full .05–1
input range independently from the existing .05–.45 diameter response, so
increasing pressure above the old diameter cutoff still changes contact.

A separate display issue could leave newly rendered pressure pixels pending:
the 8 ms presentation throttle returned without scheduling a flush. If no
further pen event arrived, those pixels waited until movement or pen-up. The
throttled path now posts a presentation at the end of its coalescing window.

Regression coverage uses stationary samples at .05, .2, .45 and .9 pressure,
with equal minimum/maximum widths, upright/moderate/strong tilt, and six lean
directions. The live test deliberately defers the last pressure update, sends
no further input, and checks the actual direct-display buffer before pen-up.
The earlier test only checked document pixels and immediately sent pen-up,
which missed the pending-presentation failure.

The connected tablet reports one Android motion sensor: an accelerometer.
No gyroscope, magnetometer or rotation-vector sensor is exposed. Pen tilt
already uses the tablet's own surface as its reference; no gravity correction
is needed for brush contact. Gravity-driven wet paint remains a future idea.

## Flat pressure assistance from lean (0.35)

Tilt-enabled Flat heads now gain pressure sensitivity with inclination. The
normalized response is `gain*p / (1 + (gain-1)*p)`, with `gain = 1 + 8*lean²`.
This preserves zero/full pressure and the upright response, while a strong lean
needs substantially less force for a broad filled rectangle. Both diameter and
contact compression use this assistance; changing only compression would leave
a small pressure-sized stamp. Bristle texture retains its existing response.
Filbert, Round and fixed-angle heads retain their prior behavior.

The regression finds the pressure needed to reach 80% of each inclination's
fully pressed footprint. That threshold must decrease from upright to 30° to
60°, with both a fixed width and the normal pressure-dependent size range. A
light .2 pressure at strong lean must produce a substantial filled rectangle.
Flat contact can reach its full raster footprint early, so subsequent pressure
checks allow a plateau while continuing to require preservation of prior paint.

## Deep Flat contact with localized tip splay (0.36)

Strong lean now exposes the selected Flat width independently of pressure.
Pressure mostly changes the laid-down length: at 60° inclination, light .1
pressure produces about a square contact and .15 produces a slightly taller
rectangle, up to roughly 1.3 times the body width at full compression.
Inclination still blends smoothly into the existing upright behavior.

Only the leading bristle tips flare sideways, by up to 2.5% of the radius on
each side. A short trapezoidal cap tapers back to the unchanging body width; the
new contact laid down behind it does not flare. Flat stamp bounds and preview
centering include the deeper footprint. Filbert geometry is unchanged.

Regressions measure square/tall light-pressure contacts with fixed and variable
minimum width. At strong lean, the body stays exactly 64 pixels wide across
pressure levels, total width changes by at most three pixels, and only the tip
is wider at firm pressure. The light-pressure rectangle must have filled
corners. Existing firm sideways Flat stroke checks now expect a deep belly;
barely touching contact still retains the thin edge controlled by thickness.

## Thin contacts and reduced stamp processing (0.37)

At the user's request, both heads return to thin contact under pressure. Flat
retains its broad width at strong tilt and slight tip-only splay. Filbert keeps
a rounded crescent with a thin painted band instead of filling its belly.
Pressure broadens the band only modestly (up to 20%); upright contact remains
filled. Existing thickness settings are preserved. The former square/tall
contact has been removed to reduce the painting workload.

Stamp allocation bounds now match the thinner maximum footprint. Non-wet brush
stamps also copy and process only a conservative rotated bounding rectangle
around the actual contact, avoiding transparent margins. Canvas drawing retains
the same origin and geometry for pixel-exact results. Wet watercolor keeps its
existing neighborhood wake-up bounds. No input samples or interpolation stamps
are dropped.

`-e brushPerfOnly true` runs an isolated 120-sample CPU replay (warmup plus three
measured runs per case) and emits median timing and SHA-256 raster fingerprints.
It covers 64/128 px Flat, textured Flat, zero-thickness Flat, and Filbert. These
measure rendering work, not physical panel latency.

Bristle streaks are disabled across all brush heads and brush tools, including
legacy presets with nonzero bristle values. The settings control is removed;
stored values are retained for compatibility but do not affect rendering.
Pressure/tilt geometry, chosen shades, watercolor stippling and wet mixing
retain their tool-specific behavior. Solid-mask tests cover all heads and brush
tools with light/firm pressure, push/pull and fixed/tilted contact.

## Solid rounded Filbert (0.38)

Filbert is now a filled rounded rectangle rather than a crescent. The center
and both long edges are solid; semicircular ends replace Flat's square corners.
Head thickness still controls the thin dimension. Both fixed-angle and tilted
Filbert stamps use the rounded rectangle. It now shares Flat's broad-width
response to tilt and light-pressure assistance; only Flat keeps tip splay.
The old crescent subtraction and minimum arc-depth floor are removed.

The fast bounded stamp-processing path is retained. Regression coverage checks
a filled interior, rounded empty corners, no crescent cutout, rotated/clipped
capsules, thin contact, stationary pressure display, settings, wash and undo.

## Direction-only brush tilt

Flat and Filbert now use tilt only to rotate the head. Pressure selects width
through the configured minimum, maximum and response curve. Inclination no
longer inflates width, compresses the head, shifts contact forward or splays the
tips. The stamp stays centered on the pen: a thin rectangle for Flat, a fuller
oval for the automatic Filbert. Settings previews
use the same geometry. This replaces the contact behavior described above.

Raster regressions cover identical footprints at different inclinations,
rotation without resizing, exact 1px light-pressure dabs and continuous
hairlines, pressure growth at a stationary point, fixed-width strokes, rounded
Filbert ends and exact undo/redo.

## Fixed 1px Flat experiment

Flat's width limit is now 256px and its short dimension stays 1px at every
pressure and width, including legacy presets. Filbert and other tools retain
their existing geometry and 128px size limit. Flat stamp spacing is capped at
0.5px so sparse input and rotation do not leave holes in the thin stroke.

The initial Manta CPU comparison used the same 120-sample replay, one warmup
and three measured runs per case. Light-pressure 128px replay was essentially
unchanged (65.19ms before, 64.41ms after). Full-pressure replay increased from
51.68ms to 80.44ms at 64px and from 75.59ms to 148.84ms at 128px: maintaining
continuous 1px coverage requires more stamps. This is a brush-shape experiment,
not a speed improvement. These measurements exclude physical panel latency.

## Adjustable Flat height

The Flat editor now exposes Height: 0 selects a fixed 1px edge, while 1–20
select the height as a percentage of the pressure-sized width, with a 1px
floor. The UI labels zero as "1 px". Ten percent restores the old proportion;
20% doubles it. Tilt still controls only heading. Preview geometry, stroke
bounds and interpolation spacing all use the selected height.

Preset format TSP13 reuses the existing thickness field. TSP12 and older Flat
settings migrate to zero so the last build's 1px appearance remains unchanged.
Each regular head and favorite retains its own setting. Filbert keeps its
automatic 55% aspect ratio. Checks cover migration, independent memories,
slider persistence, raster dimensions through 256px width, rotated/clipped
continuous strokes and exact undo/redo.
