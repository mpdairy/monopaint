# Feasibility notes — 2026-09-24

## User's reference behavior

The user reports that Atelier on their Manta displays its grays without a
visible preview/final mismatch and provides pencil texture with no perceptible
delay. Treat that as the target and as evidence of what the device can do in
Atelier, not proof that an external application has access to the same engine.

In the follow-up physical comparison on 2026-09-24, the user specifically
confirms that Atelier's **Pixel Block** marker draws gray without a visible
black trail while drawing. Use that mode as the reference for resolving the
probe's remaining dark tip; a visible black trail is avoidable on this device.

Reviewed ../tilesmile: its BOOX SDK and preview-color findings are
device-specific. Reuse the product concept, not BOOX firmware calls or the
assumption that Supernote must have BOOX's preview-color limitations.

## Deployment choice

[Official plugin architecture](https://docs.supernote.com/en/principle)
currently lists NOTE and DOC as plugin-enabled apps. It does not list Atelier.
A Notes/DOC plugin can host its own native view; a native APK is a smaller
first experiment in this empty workspace. Packaging as a plugin remains an
option if the native view proves useful.

[Official Sideloading switch documentation](https://support.supernote.com/changelog-for-manta-and-nomad)
places it under Settings → Security & Privacy. The host detected USB
2207:0007 (the USB descriptor says Supernote Nomad; this alone does not identify
the actual model), but initially ADB listed no devices. Firmware version and
hardware rendering results must be collected after ADB is enabled.

## Most relevant reference: AnimInk

- [Source](https://github.com/YoramDevGH/AnimInk)
- [Pen bridge](https://github.com/YoramDevGH/AnimInk/blob/main/app/src/main/java/com/illou/animink/SupernotePenEngine.java)
- [Canvas](https://github.com/YoramDevGH/AnimInk/blob/main/app/src/main/java/com/illou/animink/InkCanvasView.java)
- [Author's device research](https://github.com/YoramDevGH/AnimInk/blob/main/docs/PERFORMANCE_RESEARCH.md)

The author reports native rendering and a valid stroke bitmap from an ordinary
APK on a Nomad. This is useful first-hand evidence, not our Manta verification.
AnimInk has both APK and NOTE/DOC plugin editions.

Its bridge calls `View.getPWInterFace()` and uses firmware `PWCoreCtrl` methods
for brush/color settings, writable bounds, visibility, input forwarding and a
stroke-end bitmap/dirty-rectangle callback. Our probe adapts that approach and
copies the dirty pixels before returning from the callback, avoiding retention
of the firmware-owned bitmap. Native mode commits those pixels, not a guessed
reconstruction of the brush from MotionEvents.

AnimInk's canvas explicitly requests black live ink; its sketch layer converts
strokes to gray afterward. Its code comments report a slower path for real gray.
Consequently AnimInk does **not** settle our live 16-gray question. Our probe
requests each actual grayscale color without silently substituting black.

The author's research reports a separate native MyPaint-based tiled engine in
Atelier. That suggests PW and Atelier need not expose identical rendering
features. Subsequent local inspection established the palette and direct
presenter; the Pixel Block follow-up also found binary dithering in sampled
gray-looking strokes. See [PIXEL_BLOCK.md](PIXEL_BLOCK.md) for evidence and
the limits of the comparison.

## Alternative investigated

[sn-canvas](https://github.com/j-raghavan/sn-canvas) and
[layuv's experiment](https://github.com/afluffywaffle/layuv/blob/native-port-drawpath-ink/leamh_tracker.md)
describe the firmware `drawPath` service and direct low-latency preview.
The latter reports a successful Manta experiment. This is a useful alternative,
but PW's stroke bitmap callback makes it the better first painting probe.
The final probe does not call drawPath or change global E Ink refresh modes.

## Acceptance evidence still needed

Update: the user clarified that preset size changes worked, but the original
native Ink did not noticeably vary width with pressure. Bitmap live was very
sluggish. The user also confirmed thin-to-thick pressure behavior in the Notes
app's Ink pen; PW type 2 must not be assumed to be that same brush.

Controlled replay reproduced a flat response from pressure .10 to .40 in PW
types 2 and 4. Inspection of this device's publicly readable Java pen libraries
identified `registerPointWidthCallBack(int, OnUpdatePointWidthCallBack)`.
Its `getPointWidth(int,int,PWInputPoint,float)` callback can supply a per-point
width while PW keeps rendering and returning native bitmap pixels. The new
Paint brush uses this API for a strong pressure curve. This does not patch
firmware, bypass permissions, or distribute vendor code. Original firmware
artifacts stay in the gitignored local artifacts directory.

`setPenStdWidth(float)` supplies direct base width, whereas the earlier size
setting is an index. This distinction is useful for large brushes, but was
not the user's reported problem. Tests and physical results are tracked in
VERIFICATION.md.

- PW controller accessible to our ordinary app on this Manta/firmware.
- Real stylus strokes visible in native-only mode, without Android brush drawing.
- Native bitmap callbacks and retained strokes after transient pixels clear.
- Each requested gray's appearance during motion and after commit.
- Pressure range, native ink width response, and maximum useful brush size.
- Opaque replacement rather than unwanted pencil-like accumulation.
- No stray ink on controls; lifecycle release/resume works.

Configuration success, a non-null bitmap, and MotionEvent samples are separate
observations. None alone establishes low-latency visible drawing. A screenshot
does not establish physical display timing.


## Latest physical findings and extension probe

The user confirms 0.2 pressure painting is fast and broad, but only black
works live. Do not treat the palette buttons or retained gray pixels as proof
of live grayscale support. Firmware's default live path uses a fixed binary
threshold even when the pen bitmap contains gray.

Version 0.3 adds native-event tilt-driven round-brush broadening and an optional
bounded native gray-refresh experiment. See VERIFICATION.md for the input
precision limitation, native refresh calls, and physical acceptance still
needed. Gray speed and preview/final agreement remain open questions.


0.3 physical follow-up: both experiments failed. 0.4 corrects the native tilt
unit/axis assumption and tries a replacement firmware-canvas renderer for gray
strokes. Live gray and physical tilt remain unverified until retested. The exact
Atelier 1.1.82 brush brightness palette is now established; see
[ATELIER_PALETTE.md](ATELIER_PALETTE.md). The previous evenly spaced 0–255
palette did not match Atelier's grade mapping.

0.4 physical follow-up: tilt works; user requests it off by default. Gray only
appears after changing swatches, and white fails. Version 0.5 removes the
single-shot firmware-writing-state gate on final presentation, hides the
binary overlay for gray/white, and presents bounded native-canvas patches via
the Android View using local A16/partial-gray requests. Actual Activity screen
capture tests pass for live gray, final gray without palette changes, repeated
strokes, and white over black. This is a presentation fallback; physical speed
and shade appearance remain unverified. Native-speed gray is still open.

0.5 physical follow-up: gray/white work during motion but lag. Notes gray Ink
is physically confirmed instant. 0.6 now has a direct display-driver region
presenter alongside the View fallback. Tests confirm accepted direct gray
requests and driver-buffer pixels before pen-up with no Android redraws.
Physical speed is pending; [DIRECT_GRAY.md](DIRECT_GRAY.md) records the route,
layout/lifecycle checks, and unresolved mapping to 16 distinct physical shades.

0.6 physical follow-up: faster, but transient dark gray tips, white halos and
pen-up flash remain. 0.7 isolates direct input from PW's surface-writing state,
commits without an Android pen-up redraw, and skips unchanged four-bit pixels.
Device tests verify those properties and retained strokes, including pressure
history. Physical artifact removal remains pending.

## Palette feedback idea — deferred until gray painting works

User proposes an immediate large dot under the pen when tapping a swatch,
using the fast rendering path and the selected shade. Consider Atelier-style
solid gray circles with an outer selection ring. The motivation is the delay
before ordinary UI selection feedback appears. Revisit after live gray/white
painting is established; gray support in this feedback path is not yet proven.
