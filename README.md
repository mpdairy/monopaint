# Mattelier

A small drawing app for **Supernote Manta**. Pressure-sensitive brushes, textured
pencil, dotted grayscale shading, and a blending stump for sketching on e-ink.

[Download the latest APK](https://github.com/mpdairy/supernote_mattalier/releases/latest/download/Mattelier.apk)
· [Release notes](https://github.com/mpdairy/supernote_mattalier/releases)

## What it does

- Brush with adjustable minimum/maximum width and pressure response.
- Tilt pencil, gradual eraser, flood fill, and directional blending stump.
- 65 grayscale dot densities, including pure black and white.
- Custom tools, drag-to-reorder toolbar, undo/redo, and multi-page drawings.
- Local saves and automatic recovery. No account or network permissions.

Tap a tool to select it; tap it again for settings. **Add to Toolbar** saves a
custom tool, and its settings save automatically when edited. Hold and drag
custom tools to reorder them. For thinner light strokes, move the brush's
**Pressure response** toward **Firm touch**; 50% keeps the original response.

**Menu → Settings → Instant selection dots** enables direct e-ink feedback for
tool and shade selection. It is experimental and defaults off.

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

If you try Mattelier on a Nomad, [open an issue](https://github.com/mpdairy/supernote_mattalier/issues)
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
