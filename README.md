# MonoPaint

A small drawing app for **Supernote Manta**. Pressure-sensitive brushes, textured
pencil, dotted grayscale shading, and a blending stump for sketching on e-ink.

[Download the latest APK](https://github.com/mpdairy/monopaint/releases/latest/download/Mattelier.apk)
· [Release notes](https://github.com/mpdairy/monopaint/releases)

## What it does

- Round, Flat, and Filbert brush heads with adjustable size and pressure response.
- Canvas wetness and opaque/transparent paint work with the normal brush.
- Wet strokes blend into existing paint; transparent strokes build up darkness.
- Tilt pencil, gradual eraser, flood fill, and directional blending stump.
- 65 grayscale dot densities, including pure black and white.
- Custom tools, drag-to-reorder toolbar, undo/redo, and multi-page drawings.
- Local saves and automatic recovery. No account or network permissions.

Tap a tool to select it. Tap a selected brush again (or hold it) to open the
centered panel of large Round / Flat / Filbert tips. Choose a tip to show its
controls directly underneath; the menu stays open while you switch and adjust
tips. Other tools and custom tools open settings on a second tap.
**Add to Toolbar** saves a custom tool, and its settings save automatically when
edited. Hold and drag custom tools to reorder them. For thinner light strokes,
move **Pressure response** toward **Firm touch**; 50% keeps the original response.

Each brush head remembers its own width and pressure response. Flat has a square
edge; Filbert has a full, rounded oval footprint. Flat and Filbert always
follow pen tilt, keeping the broad edge perpendicular to your lean. Leaning down
gives a wide downstroke, with a fine sideways stroke for Flat and a fuller
rounded mark for Filbert. Near upright, the brush keeps
its last direction. This reads lean direction, not barrel twist.

Flat keeps a thin bristle band; Filbert has a much fuller rounded contact.
A light upright touch is compact;
pressure spreads it wider, and leaning exposes the broad edge. Their compact
settings contain minimum width, maximum width, and pressure response. Older
presets use this automatic tilt behavior too, with no angle offset or adjustable
head thickness. The toolbar and saved custom tools show the selected head icon.

Brush footprints are solid: bristle streaks and their control are disabled for
now, including in existing presets. Raise **Minimum width** for broad strokes
with a light touch.

The **water droplet left of the color bar** toggles wet canvas mode. Its dot means
wet-in-wet is on. Use the normal brush with any head: fresh strokes appear
immediately, then blend with existing paint over a few seconds. Small strokes
blend live while you draw. Blending uses spare drawing time and slows down or
briefly pauses for larger strokes, busy wet areas, or delayed pen input. It catches
up after you lift the pen. Slide the narrow
black bar beside the outlined droplet upward for more blending, downward for less.
Touching or dragging the bar above zero enables wet mode; zero switches it off.
The droplet can also turn blending off while remembering the bar's strength.
Tapping it again uses that strength, or restores medium wetness if the bar is zero.

The two icons **right of the color bar** choose **Transparent** (overlapping
outlines) or **Opaque** (solid square). Opaque is the default and covers with the
selected shade, including white. Transparent adds a half-strength multiply glaze:
white adds no pigment, even black stays translucent, and separate strokes
build density. Overlapping stamps within one stroke do not repeatedly darken it.
On a wet canvas, transparent white acts as clear water and blends existing paint.
The color bar's marker indicates the selected shade; there is no extra preview square.

The left rail now contains Brush, Pencil, Fill, Eraser, and Soften plus your custom
tools. Older watercolor and wash presets keep their brush shape and settings and
use the canvas wetness and paint mode controls. Wetness and transparency are global
brush controls, independent of the selected head or preset.

Undo removes a wet stroke together with its animation. Undo/Redo, other painting
tools, clearing, changing pages, saving a named drawing, or leaving the app stop
ongoing animation. Your wetness and paint mode choices persist; the next brush
stroke can mix with existing paint again when the droplet is on. Animation work
is bounded to keep the pen responsive; this is grayscale blending, not fluid physics.

**Menu → Settings → Instant selection dots** enables direct e-ink feedback for
tool, shade, wetness, and paint mode selection. It is experimental and defaults off.
The new mode dots and wetness bar also work with ordinary display feedback.

## Sideload onto a Manta

1. Download **Mattelier.apk** from the latest release (not the source ZIP).
2. Install Google's [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools)
   on your computer and open a terminal in the folder containing `adb`.
3. On the Manta, turn on **Settings → Security & Privacy → Sideloading**.
   Connect it by USB, unlock it, and accept the debugging prompt if one appears.
4. Run the commands below, replacing the APK path with your download location:

   ```sh
   adb devices
   adb install -r /path/to/Mattelier.apk
   adb shell am start -n dev.tilesmile.supernote.paint/dev.tilesmile.supernote.PaintActivity
   ```

   On Windows PowerShell, use `./adb.exe`; on macOS/Linux, use `./adb` if it
   isn't on your PATH. If multiple devices appear, add `-s SERIAL` after `adb`.
   If a device is `unauthorized`, approve the prompt on the tablet and retry.

For updates, repeat `adb install -r` with the new APK. The app ID remains
`dev.tilesmile.supernote.paint`, so this also updates the earlier TileSmile app.
Do not uninstall first: uninstalling deletes the app's private drawings.

## Current limits

This is an early, unofficial app tested on **Manta (1920 × 2560, arm64)**.
The direct display path is Manta-specific; Nomad and other tablets are unverified.
There are no layers, zoom/pan, or in-app image export yet. Drawings live in the
app's private storage, and undo history does not survive a restart.

## Nomad testers wanted

If you try MonoPaint on a Nomad, [open an issue](https://github.com/mpdairy/monopaint/issues)
with your firmware and app versions, whether pressure/tilt works, and how live
ink, grays, and selection refreshes behave. Test on a new drawing first. The fast
display path currently requires the Manta screen layout; Nomad may use the slower
Android drawing fallback. Nomad support is not yet verified.

## Development and license

See [building, testing, and publishing](docs/BUILDING.md). Technical research notes
are in [docs](docs/). Earlier notes use the working name TileSmile.

[MIT licensed](LICENSE). The pen bridge includes MIT-licensed work from
[AnimInk](https://github.com/YoramDevGH/AnimInk); its attribution is retained in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and inside the APK.
