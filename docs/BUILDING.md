# Building and publishing

Use JDK 21, Android SDK 34, NDK 27.0.12077973, and CMake 3.22.1.
Set `JAVA_HOME` and `ANDROID_HOME` for your machine; the Gradle wrapper is included.

## Project names and compatibility

MonoPaint is the app name; `monopaint` is the repository and release-file name.
Java sources and tests live under `io/github/mpdairy/monopaint`, with package
`io.github.mpdairy.monopaint`. The native library is `monopaint_display`.

Some old identifiers deliberately remain stable:

- `dev.tilesmile.supernote.paint` is the installed Android application ID. Changing
  it would create a separate app with a separate private drawing library.
- `dev.tilesmile.supernote.PaintActivity` remains a launcher alias so existing
  shortcuts and launch commands continue to work.
- `.tsm` drawings, archive entry names, and format signatures remain unchanged,
  keeping existing drawings and recovery data readable.
- Historical research notes and old release artifacts retain their original names.

The checked-in `dist/Mattelier.apk`, checksum, version, and release notes describe
the previous release. They are not a MonoPaint build. Before publishing the first
MonoPaint release, run the release script below, update the release notes, and
remove the superseded `dist/Mattelier.apk` and its `.gitignore` exception. The
workflow requires a freshly prepared `dist/monopaint.apk` and matching checksum.

## Development checks

```sh
bash scripts/test_document.sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

The development APK is `app/build/outputs/apk/debug/app-debug.apk`.
To run the device checks, install that APK and the test APK, then run:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e paintOnly true dev.tilesmile.supernote.paint.test/io.github.mpdairy.monopaint.WidthInstrumentation
```

These checks use a temporary drawing and restore the original drawing and tool
settings. They exercise the Manta-specific display path and require a Manta.
Use `-e layersOnly true` for layer controls in every rotation and drawing hand,
brush/eraser isolation, and eight full-resolution layers with autosave. The suite
uses a temporary book, restores the original session, and saves `layers-*.png`
screenshots in the test app cache.
Use `-e storageOnly true` for focused Android storage checks in a disposable cache
directory: nested folders, repeat saves, overwrite protection, Save As copies,
failed saves, recovery destinations, and interrupted-save backup restoration.
These checks do not open or change the user's canvas.
Use `-e zoomOnly true` for two-finger pinch/pan, the toolbar percentage, single-finger
and active-pen palm rejection, cancellation, zoomed pen placement and undo,
fractional-scale shade calibration, clipped native display updates, fit reset,
page changes, and all four orientations with both hands. It also reports full-screen
zoom raster CPU time and restores the original drawing and settings.
Use `-e gradientOnly true` for Linear/Circular settings and persistence, radial
colors and line-only guides, full-page circular fills, held pen previews and release-to-commit on the
color bar and eyedropper in every orientation and hand, retained direction guide
before color contact, delayed hint timing/placement/dismissal, sampling underneath
the preview, continuous regular eyedropper drags, layer boundaries, undo/redo,
solid taps, canceled color gestures, interrupted previews, and hidden layers. The suite
restores the original book and settings and saves `gradient-<rotation>-<hand>.png`
screenshots in the app cache.
Use `-e eraseOnly true` for the eraser color selector in all four rotations and
both drawing hands, real pen layer reveal, exact Round/Flat/Filbert footprints,
pencil and soft airbrush erasing, undo, wet/transparent painting settings, favorites,
saved selection, same-shade return to painting, eyedropper accept/cancel, and
retained standalone eraser behavior. It restores the original book and settings
and saves `erase-mode.png` in the target app cache.
Use `-e airbrushOnly true` for airbrush toolbar selection, retained standalone
Eraser, diameter/flow controls, stationary timed spray in all four orientations,
immediate movement without a timer wait, batched fast sweeps with interleaved
hold timers, the final pen-up segment,
pen-up/focus interruption, and single-stroke undo. It restores the original
book and settings afterward.
Use `-e paletteOnly true` for pen/finger input in the sidebar and grid editor in all four orientations
and both hands: icon-anchored placement without covering the color bar, direct
pen-up swatch feedback without rebuilding the toolbar, no swatch repaint/save
while the pen is down, main color bar editing without
closing the panel, the delayed
empty-cell hint, adding colors, drag insertion and append, drag-only trash
deletion, outside-drop cancellation, deleting the last shade, same-shade addition,
retained brushes/presets, persistence, visibility, and no marks or undo entries.
The suite restores the original drawing/settings and saves
`palette-editor-<rotation>-<hand>.png` and `palette-<rotation>-<hand>.png` screenshots.
Use `-e palettePerfOnly true` for a focused color-handler timing comparison,
including main-window layout counts and retention of existing toolbar views.
It restores the original session. These measurements do not establish physical
panel latency.
Use `-e toolbarOnly true` for icon rows, layer visibility, saved toolbar ordering,
rotated move-up/down touch targets, always-on instant selection, retained hidden-tool
settings and favorites, and eyedropper sampling through actual pen input in all
four orientations and both drawing hands. It checks visible-layer composition,
cancellation, marker transfer below the upright eyedropper and exact restoration,
no accidental marks, the next stroke's shade, and undo. Screenshots named
`eyedropper-<rotation>-<hand>.png` and `toolbar-settings-<rotation>-<hand>.png`
are saved in the target app's cache. These
checks use a temporary book and restore the original session.
Use `-e orientationOnly true` for app-only rotation with Android locked to portrait,
sensor-suggestion timing, rotated palette/dialog touch targets, both drawing hands,
canvas coordinate mapping, original pen tilt (including history), native display
pixels, no Android pen frames, retained undo, and full-redraw ink retention.
The orientation suite checks all four display directions and writes
`orientation-right.png`, `orientation-left.png`, `orientation-right-edge-down.png`,
`orientation-left-right-edge-down.png`, and `orientation-suggestion.png` to the
test app's cache. It checks the left-handed tool rail stays at the top in both
landscape directions, the hamburger occupies the outer corner, the color marker
faces the canvas with black at the bottom, and menu/undo/colors/pages order.
It also checks the file menu opens below the hamburger for both hands in all
four orientations, its Settings item responds at the displayed touch target,
and tapping outside dismisses it without changing the drawing. Menu screenshots
are saved as `orientation-menu-<rotation>-<toolboxRight>.png`.
Artwork stays fixed relative to the tablet in every direction.

To run device checks in a separate install with its own empty drawing library:

```sh
./gradlew -PisolatedChecks :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e orientationOnly true dev.tilesmile.supernote.paint.checks.test/io.github.mpdairy.monopaint.WidthInstrumentation
```

The isolated app ID is `dev.tilesmile.supernote.paint.checks`; the normal app and
its drawings stay separate. Omit `-PisolatedChecks` for a normal development build.

For brush raster, tilt input, head settings/presets and wet-brush checks, replace
`-e paintOnly true` with `-e brushOnly true`. This focused run also restores the
original drawing and settings and writes `filbert-rounding-comparison.png` to the app cache.
It also checks live adaptive wet blending and replays small and large wet strokes,
reporting pen-event CPU time and seep calculation, raster, and display submission
times. These timings do not measure physical panel latency.
A locally built debug APK has your machine's signing key, so it may not update
an installed official release. Never uninstall a release merely to resolve a
signature mismatch without preserving your drawings first.

## Signed releases

Official APKs are non-debuggable release builds. The existing private signing
identity is retained for upgrade compatibility with the early TileSmile builds.
The signing key is kept outside this repository; it is never sent to CI.

Set `MONOPAINT_KEYSTORE`, `MONOPAINT_KEY_ALIAS`, `MONOPAINT_STORE_PASSWORD`, and
`MONOPAINT_KEY_PASSWORD` locally, then run:

```sh
bash scripts/prepare_release.sh
```

The script builds, aligns, signs, and verifies `dist/monopaint.apk`, and updates
its checksum and version. Update `dist/RELEASE_NOTES.md`, test the signed APK on
a Manta, then commit the source and the small signed distribution files together.
Push `main` and a matching tag, e.g. `v0.38`. The release workflow verifies the
checksum and publishes the already-signed APK and checksum as GitHub Release
assets. It can also be rerun manually from that version's tag.

```sh
git push origin main
git tag -a v0.38 -m 'MonoPaint 0.38'
git push origin v0.38
```

The checked-in APK allows publishing without exposing the private signing key
or requiring a new signing identity on each build. Keep the signing key backed
up securely: future Android updates need the same identity.

## PNG conversion

For a `.tsm` book already available on your computer:

```sh
bash scripts/export_png.sh drawing.tsm 2 output.png
```

This exports page two. Add `raw` or `dots` to choose logical tones or the tablet's
dot pattern. This utility does not retrieve private files from the tablet.

For an isolated brush CPU benchmark (no changes to the user's document), run:

```sh
adb -s SERIAL shell am instrument -w -e brushPerfOnly true dev.tilesmile.supernote.paint.test/io.github.mpdairy.monopaint.WidthInstrumentation
```

Replace `SERIAL` with the device ID from `adb devices`, or omit `-s SERIAL` if
only one device is connected. It reports warmup-adjusted median replay time and
raster fingerprints, plus a matched 128px round-brush, eraser, and airbrush
CPU replay. The
`texture` number in benchmark case metadata is the saved setting; bristle
texture is currently disabled, so that case must match the solid footprint.
Timings measure rendering work, not panel latency.
