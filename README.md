# MonoPaint

<img src="app/src/main/res/mipmap-nodpi/ic_launcher.png" alt="MonoPaint brush logo" width="96" height="96">

A small drawing app for **Supernote Manta**. Pressure-sensitive brushes, textured
pencil, dotted grayscale shading, and a blending stump for sketching on e-ink.

[Download the latest APK](https://github.com/mpdairy/monopaint/releases/latest/download/monopaint.apk)
· [All releases and release notes](https://github.com/mpdairy/monopaint/releases)

## What it does

- Round, Flat and Filbert brushes with pressure and tilt, plus oil paint that runs out and smudges.
- Wet canvas blending (with optional gravity), and a brush pen that pushes paint around with water.
- Pencil, airbrush, eraser, blending stump, flat and gradient fills, and shapes.
- 65 grayscale shades, an eyedropper, a swatch palette, and an eraser color for any tool.
- Up to 8 layers per page, multiple pages, undo/redo, and custom saved tools.
- Portrait or landscape for either drawing hand, plus lockable pinch zoom.
- Saves locally with automatic recovery. No account or network access.

**[Tool and feature guide](docs/TOOLS.md)**: what every icon does and how to use it.

## Sideload onto a Manta

1. Download **monopaint.apk** from the [latest release](https://github.com/mpdairy/monopaint/releases/latest) (not the source ZIP).
2. Install Google's [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools)
   on your computer and open a terminal in the folder containing `adb`.
3. On the Manta, turn on **Settings → Security & Privacy → Sideloading**.
   Connect it by USB, unlock it, and accept the debugging prompt if one appears.
4. Run the commands below, replacing the APK path with your download location:

   ```sh
   adb devices
   adb install -r /path/to/monopaint.apk
   adb shell am start -n dev.tilesmile.supernote.paint/io.github.mpdairy.monopaint.PaintActivity
   ```

   On Windows PowerShell, use `./adb.exe`; on macOS/Linux, use `./adb` if it
   isn't on your PATH. If multiple devices appear, add `-s SERIAL` after `adb`.
   If a device is `unauthorized`, approve the prompt on the tablet and retry.

For updates, repeat `adb install -r` with the new APK. The app ID remains
`dev.tilesmile.supernote.paint`, so releases signed with the existing key also
update the earlier TileSmile and Mattelier apps.
Do not uninstall first: uninstalling deletes the app's private drawings.

## Saving

**Menu → Save drawing** asks for a name and folder the first time, and later
saves update that same drawing. Drawings are kept in the app's private storage,
so **uninstalling deletes them**. See the [guide](docs/TOOLS.md#saving) for
details and PNG conversion.

## Current limits

This is an early, unofficial app tested on **Manta (1920 × 2560, arm64)**.
The direct display path is Manta-specific; Nomad and other tablets are unverified.
There is no in-app image export yet. Drawings live in the
app's private storage, and undo history does not survive a restart.

## Nomad testers wanted

If you try MonoPaint on a Nomad, [open an issue](https://github.com/mpdairy/monopaint/issues)
with your firmware and app versions, whether pressure/tilt works, and how live
ink, grays, and selection refreshes behave. Test on a new drawing first. The fast
display path currently requires the Manta screen layout; Nomad may use the slower
Android drawing fallback. Nomad support is not yet verified.

## Development and license

See [building, testing, and publishing](docs/BUILDING.md) and
[contributing](CONTRIBUTING.md). Technical research notes are in [docs](docs/).
Earlier notes use the working names TileSmile and Mattelier; compatibility details
are in the [development guide](docs/BUILDING.md#project-names-and-compatibility).

[MIT licensed](LICENSE). The pen bridge includes MIT-licensed work from
[AnimInk](https://github.com/YoramDevGH/AnimInk); its attribution is retained in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and inside the APK.
