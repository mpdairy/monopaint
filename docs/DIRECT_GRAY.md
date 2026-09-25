# Direct gray display experiment (0.12)

The current dot brush uses the measured coverage calibration documented in
[GRAY_DENSITY.md](GRAY_DENSITY.md). Its display requests are unchanged.

Current comparison: [PIXEL_BLOCK.md](PIXEL_BLOCK.md) records the binary-dot
evidence from Atelier and the new **Shade: dots** option. Driver mode 7 /
flags 0 remains the baseline; 0.11 removes the failed GL/mode-4 experiment.
The sections below retain the history of earlier display comparisons.

Physical evidence: 0.5's View-based gray/white presentation works but lags;
black is instant. User also confirms gray Ink in Notes is instant. This is
not a hardware inability to draw fast gray.

Physical Atelier comparison — 2026-09-24: the user confirms that the marker's
**Pixel Block** mode draws gray without a visible black trail during drawing.
Use this specific mode as the reference. A visible black trail is therefore
avoidable on this device; the cause of our probe's dark tip and the settings
needed to reproduce Atelier's result remain unknown. This observation does
not validate all 16 shades, white behavior, or measured latency.

Physical 0.6 result: substantially faster, but gray has a transient dark tip,
white has a following halo, and pen-up flashes. This does not match Notes or
Atelier. The 0.7 changes removed competing PW surface-writing state,
redundant pen-up redraw, and unchanged-pixel refresh requests. These are
testable sources of extra updates; physical elimination of the artifacts is
not established by those changes. Physical 0.7 feedback subsequently confirms
the dark leading tip persists, with new horizontal/vertical dashes at stroke
edges. Switching colors clears the artifacts. The user's photo is retained
locally at `artifacts/gray-0.7-physical-artifacts.jpeg`.

0.8 targets the edge regression: retain and copy the brush's clean border in
every submitted region. The earlier adapter's exact changed-pixel cropping
left shared driver scratch outside the tiny rectangles unspecified. This is
a buffer correction and a physical experiment, **not a proven explanation or cure for the dark
tip**. No new waveform numbers or flags are guessed. Exact driver alignment
requirements and the source of the dark tip remain unresolved.

Physical 0.8 feedback: the same dark tip and dashes remain; the dashes appear
a few pixels away from the painted line. This is consistent with an update
boundary problem, but does not prove one. Restoring the border did not solve
the user's problem.

## Controlled request comparison (0.9)

The installed system `libeinkutils.so` provides a second client-side reference
for the same 24-byte region request. `postEinkHostBmpRect` at 0x6474 and
`postEinkHostBmpRectFast` at 0x66fc use offset 0, mode 7 for their default
opaque bitmap branch, and **flags byte 0**. The latter's request setup is at
0x6854–0x687c. In contrast, Atelier's presenter sets the low three flag bits
to 1, preserving the other bits. Our previous adapter supplied flags byte 1.
The system's `postEinkPWRectFast` uses mode 9 and flags 1/5, but also a
different pixel conversion. We do not assume mode 9 supports our opaque
16-level canvas, and do not use it.

0.9 exposes `Update: bitmap` (flags 0, default) and `Update: previous` (flags 1,
the 0.8 request). This changes only the flags byte of direct gray/white
submissions. The mode, offset, rasterization, region bounds, timing, and
retention stay the same. Only these two observed flag values are accepted.
The exact flag semantics remain unknown: the labels identify their observed
clients, not a proven waveform interpretation. This is a physical A/B
diagnostic. Black and View fallback are not
affected by this switch.

Physical 0.9 result: the user confirms the detached edge artifacts are gone
and the drawing looks good, but the dark leading tip remains. Logs from
process 5434 confirm mode 7 / flags 0 (`Update: bitmap`) for the observed
gray strokes. This is now the clean-edge baseline. Evidence:
`artifacts/gray-0.9-physical-success-log.txt`; preserved APK:
`artifacts/tilesmile-0.9-clean-edges.apk`.

Local reference: `artifacts/firmware/libeinkutils-asm.txt`; library SHA-256
`10394f4cc06899a6c6228b33260b1634aa792ac9722da5bf27ba0a94dbf05f3b`.
The library was read from the installed system; it is not bundled or loaded
by the app. No global display state changes are introduced.

## Optional grayscale refresh comparison (0.10)

**Physically rejected:** user reports the GL option produces almost no gray
or white stroke, only scattered pixels/artifacts. Pure black continues to
work through the separate firmware pen renderer, which is unaffected by the
GL switch. Successful mapped-buffer tests did not validate this mode's panel
output. Use the mode 7 / flags 0 baseline; remove the failed comparison from
the next build. Atelier was brought to the foreground for a physical comparison;
the user subsequently confirmed gray without a black trail in Pixel Block
marker mode, as recorded above.

The rejected experiment kept flags 0 and compared baseline driver mode 7
with driver mode 4. It was based on the system bitmap presenter's mapping, not on equating Java
and driver enum numbers. The installed framework's `EINK_SHOW_MODE_GL` is 3.
In `postEinkHostBmpRectFast`, 0x67e8 subtracts 3 from the requested mode;
the jump table at 0x352e has first byte 0x0e, branching from 0x6810 to
0x6848, which selects driver mode 4. Its source pixel conversion and flags
0 remain the same. The non-fast bitmap presenter independently has the
same mapping at 0x65d0 / table 0x3520 / target 0x662c.

`Refresh: current` (mode 7) remains the default. `Refresh: GL test` selects
mode 4 only for direct gray/white. The mode is stored per presenter session;
the switch reconfigures the session and redraws the painting. It changes no
global display settings. The bridge accepts only the two observed modes.
Physical latency, dark-tip shortening and gray quality are not established
by successful submissions or mapped-pixel tests.

The user suggests the dark tip may be an intentional panel transition that
makes the final gray consistent, and would accept it if much shorter. That
is a plausible hypothesis, not a confirmed driver mechanism. Our gray
rasterizer sends the requested gray directly; it has no explicit black
pre-stroke pass. If a dark transition phase is responsible, its visible
length should roughly track pen speed times phase duration. The subsequent
Pixel Block check shows that a visible black trail is avoidable in Atelier;
it does not establish whether a waveform phase causes our probe's dark tip.

## Earlier background diagnostic

A trial initialized the entire mapped canvas once. The added diagnostic found
that a later ordinary UI redraw overwrote it: pixel (279,496) was level 6
instead of the app's RGB 136 >> 4 = 8. One-time initialization therefore does
not establish stable background pixels in this shared buffer and was removed.
The retained fix copies the full brush region's border on each submission.
Normal UI gray conversion also differs from our direct conversion; this is
additional evidence that live/final shade calibration remains unfinished.

## Interface provenance

Installed Atelier 1.1.82's native `repaintC` renderer opens `/dev/ebc`, queries
its buffer layout, maps it, and submits bounded image regions directly. Its
Manta/A5X2 path reduces gray bytes to four-bit levels and requests mode 7.
Its query and region-update ABI are the basis for our independent JNI adapter;
no Atelier code or vendor library is bundled. Local evidence is
`artifacts/firmware/atelier-display-asm.txt` (constructor at 0x920024,
region presenter at 0x9205f0) and the palette document's library hash.

Read-only capability check succeeded under our ordinary application identity.
The node is mode 0666 with the `rga_device` SELinux label. No root, permission
changes, hidden-API policy changes, service impersonation, or firmware changes
are used. Successful open/query is distinct from successful drawing.

The [published Supernote kernel](https://github.com/Supernote-Ratta/kernel_Nomad_Manta)
was also consulted. Its generic Rockchip `ebc_dev.h` describes a different
ioctl family; we do **not** assume that interface matches the installed HT
driver. The adapter validates the actual queried 1920×2560 layout, 1920-byte
stride and 4,915,200-byte plane, and refuses other layouts and nonzero rotation.

## Rendering and lifecycle

- Direct gray disables PW rendering and does not call its event forwarding or
  custom stroke handler. It reads Android stylus events and every historical
  sample using the established pressure curve. PW's custom handler in 0.6
  still invoked doPenDown/doPenUp and setSurfacePWFlag around our callback;
  hiding its bitmap did not remove that surface-writing state.
- For gray/white, an opaque app bitmap is seeded from the existing painting
  once on configuration. The custom brush rasterizes directly into it.
- JNI initializes a private four-bit comparison copy from the painting at
  configuration. It skips wholly unchanged regions, but copies/submits the complete brush region including
  its clean border. 0.7's exact changed-pixel cropping is removed. The private
  copy advances only after success, so a busy response cannot falsely mark
  pending pixels as presented. Both app-local and display bounds are checked
  before any memory write. Screen origin comes from the drawing View.
- There is no View redraw or preview-copy callback during a
  direct stroke. Submissions are limited to one per 8 ms. EAGAIN/EINTR retain
  the accumulated region for a subsequent sample, without sleeping on input.
  Nonnegative ioctl returns are success; positive success codes must not be
  mistaken for errors using stale errno.
- Pen-up flushes the pending region and updates the backing and retained View
  bitmaps **without invalidation**. A later normal UI redraw uses the committed
  bitmap. This removes 0.6's redundant Android refresh at every pen-up.
- Disable, focus loss, pause, or reconfiguration closes/unmaps the presenter.
  Synchronized release waits for an active input callback before recycling its
  bitmap. No display writes are issued while the adapter is disabled.
- Black keeps the working firmware renderer. `Gray: View fallback` retains
  0.5's slower, physically working presentation path.

## Verification limits

On-device tests verify driver acceptance and buffer contents **before pen-up**,
while the Activity's `onDraw` counter remains unchanged, including pen-up.
They check that the firmware's writing state stays off, repeated identical
overpainting issues zero requests, and batched stylus input retains pressure
variation. 0.8 adds pixel-by-pixel comparison
around crossing gray/white pressure curves, including unpainted borders.
They also check retained pixels and later UI screen captures,
white over black and preserved neighboring black, and
reject a region outside its bitmap before any driver write. These checks are
stronger than the earlier capture-only tests, but cannot prove physical panel
speed, ghosting, or live/final shade matching. Submission time is not panel
latency.

The current adapter converts opaque RGB gray with `value >> 4`, matching the
observed framebuffer conversion. Atelier's *brush brightness* table does not
prove its final tile pixel values. Some requested palette RGB values therefore
share a four-bit level; calibration of 16 distinct painted shades and exact
Atelier preview/final matching remains separate work. Do not claim that all
16 physical grays are established by the bitmap tests.
