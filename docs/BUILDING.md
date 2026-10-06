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
- Drawings are `.mpaint` files. Before 0.96 they were `.tsm`; the app renames
  those in place on launch. Archive entry names and format signatures remain
  unchanged, keeping existing drawings and recovery data readable.
- Historical research notes and old release artifacts retain their original names.

The checked-in `dist/monopaint.apk`, checksum, version, and release notes describe
the current release. Before publishing an update, run the release script below
and update the release notes. The workflow requires a freshly prepared
`dist/monopaint.apk` and matching checksum. Increase Android's `versionCode` for
every release, independently of the user-facing `versionName`.

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
settings. They run on both the Manta and the Nomad; checks of the Manta's Nomad simulation
skip on a real Nomad. The runner grants the app all-files access first, so the
"Keep your paintings safe" prompt never takes focus from a check. Keep the tablet
awake and other apps out of the foreground while checks run.
Use `-e settingsOnly true` for the fixed Shapes icon, simplified hand controls,
Nomad Simulation Mode placement/toggle, and the persistent settings scroll rail.
It verifies pen/finger dragging to both ends and arrow scrolling in all four
rotations, both text sizes, and full-screen/simulated Nomad layouts. The suite
restores the original session; `settings-scroll.png` captures the smaller layout.
Use `-e layersOnly true` for layer controls, opacity slider input and one-step
undo, and both Clear dropdown scopes in every rotation and drawing hand,
eye-icon visibility toggles, brush/eraser isolation, and eight full-resolution layers with autosave. The suite
uses a temporary book, restores the original session, and saves `layers-*.png`
screenshots in the test app cache.
Use `-e storageOnly true` for focused Android storage checks in a disposable cache
directory: nested folders, repeat saves, overwrite protection, Save As copies,
failed saves, recovery destinations, and interrupted-save backup restoration.
These checks do not open or change the user's canvas.
Use `-e nomadOnly true` for the Nomad mode Settings toggle, its saved preference,
exact centered 1404 × 1872 viewport, unchanged control sizes, Manta fast display,
screen-coordinate stylus placement and undo, inert margins, file/tool/settings
panel bounds, and returning to the full screen. Checks cover all four rotations
and both drawing hands, use a temporary drawing, then verify restoration of
every original page and layer. It also checks the compact Pages button and
reclaimed color-bar width, wet controls retained beside the color bar, no hidden
rotation-button gap, synchronous popup presentation,
real pen presses acknowledged through the fast path before page work, retained
page artwork, previous/next boundaries, one blank page per Add press, outside
dismissal, Back, and fast canvas reconnection. Page checks also verify native
full-page raster agreement with the calibrated Java renderer, direct artwork
submission with the popup open, page counters updated through normal Android
redraw without direct e-ink submissions, and report page-turn CPU timings at Nomad and
Manta canvas sizes. These timings do not measure physical panel latency.
Page navigation covers both
Nomad and expanded Manta controls in all eight rotation/hand combinations.
The page-number thumbnail grid checks cover pen selection, current-page highlighting,
background previews, dismissal, restored canvas drawing, and scrolling a 100-page book.
Use `-e pagesOnly true` to run just the page navigation and thumbnail grid checks,
with the same original-session restoration.
Screenshots `nomad-0.png` through `nomad-3.png`, `nomad-settings.png`, and
`nomad-pages.png` are saved in the target app cache. The suite also verifies
the transient rotation prompt at the new physical orientation’s bottom-left
corner in both modes, every target direction and both hands: five-second
expiry, no repeat while stationary, shake recall, fast pressed feedback before
touch-to-rotate, cleared press state on dismissal, unchanged
color width, and canvas reconnection. `rotation-new-landscape.png` captures the
portrait-bottom-right / new-landscape-bottom-left example.
Use `-e fullscreenOnly true` for two-finger double taps, all four
control layouts, illustrated per-page Keep/Expand choices, unchanged artwork
position through expansion, UI undo/redo, independent new pages and Back.
It covers all four rotations and both drawing hands, restores the original
book/page/settings and verifies every original page and layer. It saves
`fullscreen-choice.png` in the app cache. Gesture recognition and variable-size
page persistence also have desktop checks in `scripts/test_document.sh`.
Use `-e edgeBarsOnly true` for bar swipes through Android's input dispatcher,
including Supernote's system gesture listener. It checks all four bar layouts,
first-swipe margin choice, fast exits with missing edge samples, short-swipe
rejection, finger contact footprints at the bezel, stationary artwork, fast drawing
after toggles, mixed-layout undo, two-finger double taps (with three-finger rejection),
and Zoom-tool double taps in every rotation and both drawing hands.
It restores the original drawing/page/settings and saves
`edge-choice.png` in the app cache.
Use `-e zoomOnly true` for the default/saved navigation lock, fast lock feedback,
finger/pen double taps returning to actual 100% from above and below 100% while
preserving the lock and artwork,
locked pinch/pan rejection with pen drawing retained, pinch-to-fit, no Zoom panel
or hold reset, the toolbar percentage, single-finger
and active-pen palm rejection, cancellation, zoomed pen placement and undo,
fractional-scale shade calibration, clipped native display updates, fit reset,
page changes, and all four orientations with both hands. It also reports full-screen
zoom raster CPU time and restores the original drawing and settings. The checks
include queued-move coalescing, a retained native presenter, no Android canvas
redraws during live navigation, exact preview/Android redraw agreement,
cancellation and pen/lock interruption, fractional sampling in every rotation,
native composition of eight partially transparent/hidden layers, and exact
clipped native panel rotation. Gesture
and preview-frame timings measure app processing, not physical panel latency.
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
retained standalone eraser behavior. It also checks shape and solid-fill erasing,
both gradient types with either transparent endpoint, cancellation, and native
shape previews against the committed composite. It restores the original book and settings
and saves `erase-mode.png` in the target app cache.
Use `-e shapesOnly true` for Shapes toolbar selection, actual settings taps in all
four orientations and both hands, live preview/shrink, final pen-up placement,
undo/redo, interrupted preview cancellation, zoomed circle geometry, and a large
solid fill. The suite restores the original book/settings and drains its saves;
`shapes-settings.png` in app cache shows only a temporary test drawing. The suite
also compares the native preview against the committed raster for every shape,
shade, fill mode, clipping direction, and partially transparent upper layers.
Queued-motion checks and a continuous 125 Hz stylus replay verify that previews
use the latest position and pen-up commits the exact endpoint. Use
`-e shapesPerfOnly true` for large rectangle/circle preview benchmarks, including
input handling, the coalesced preview frame, and direct display submission.
Both modes restore and verify every saved page afterward.
Use `-e airbrushOnly true` for airbrush toolbar selection, retained standalone
Eraser, size/softness/flow controls, no buildup while held in all four
orientations, batched fast sweeps, and single-stroke undo. It restores the original
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
Use `-e colorBarOnly true` for focused Nomad/Manta color-bar checks: pen and
finger drags with reversals in all rotations and both hands, immediate final
position, requests bounded to the old and new marker positions, no-op
suppression, and rejection of nonbinary pixels on the Nomad fast path.
It also checks wetness drags and wet/dry toggles in every rotation/hand,
button outlines, layer-eye icons, zoom labels, nonfocusable popup swatches,
and automatic mode-7 fallback for control patches with genuine gray.
The suite restores the original drawing, page, and settings. These assertions
verify software behavior; physical trailing still needs visual confirmation.
Use `-e pageFeedbackOnly true` for actual pen presses on Previous/Next/Add,
checking fast press and release requests, page boundaries, unchanged artwork,
compact-panel dismissal and canvas reconnection in all rotations and both
hands. Runs on actual Nomad and Manta (including its compact simulation),
with the original drawing, active page and settings restored afterward.
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

To run device checks in a separate install with its own drawing library:

```sh
./gradlew -PisolatedChecks :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e orientationOnly true dev.tilesmile.supernote.paint.checks.test/io.github.mpdairy.monopaint.WidthInstrumentation
```

The isolated app ID is `dev.tilesmile.supernote.paint.checks`; the normal app and
its drawings stay separate. Its shared library and PNG exports use `MonoPaint checks`
folders (`Document/MonoPaint checks`, `EXPORT/MonoPaint checks`), never the user's. Omit `-PisolatedChecks` for a normal development build.

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

For repeat builds, put those settings and `export ANDROID_HOME=...` in
`.env.release` at the repository root. The script loads this optional shell file
automatically, even when invoked from another directory. It is ignored by Git;
keep it private (`chmod 600 .env.release`) and keep the keystore outside the
checkout. Use a home-relative keystore path so moving the checkout does not
break signing. Moving or cloning the repository alone does not supply signing
settings to a new shell.

The script builds, aligns, signs, and verifies `dist/monopaint.apk`, and updates
its checksum and version. Update `dist/RELEASE_NOTES.md` from the Unreleased
section of `CHANGELOG.md` (then retitle that section with the version), test the signed APK on
a Manta, then commit the source and the small signed distribution files together.
Push `main` and a matching tag, e.g. `v0.9`. The release workflow verifies the
checksum and publishes the already-signed APK and checksum as GitHub Release
assets. It can also be rerun manually from that version's tag.

```sh
git push origin main
git tag -a v0.9 -m 'MonoPaint 0.9'
git push origin v0.9
```

The checked-in APK allows publishing without exposing the private signing key
or requiring a new signing identity on each build. Keep the signing key backed
up securely: future Android updates need the same identity.

## PNG conversion

For a `.mpaint` book already available on your computer:

```sh
bash scripts/export_png.sh drawing.mpaint 2 output.png
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
CPU replay. Wet brush pen replays at 48px and 128px also report stroke processing,
12 animation frames, and the median of each run's slowest budgeted calculation
slice. Wet raster hashes are diagnostic: time-budgeted tile grouping can vary
between runs. The headless wet checks separately verify exact agreement between
sliced and uninterrupted spread planning, including pen input between slices.
The brush UI suite checks wet pen input and animation through the direct display,
without Android redraws, and one-step undo.
The
`texture` number in benchmark case metadata is the saved setting; bristle
texture is currently disabled, so that case must match the solid footprint.
Timings measure rendering work, not panel latency.

Tool-picker touch/layout checks (temporary book with verified session restoration):

```sh
adb shell am instrument -w -e pickerOnly true dev.tilesmile.supernote.paint.test/io.github.mpdairy.monopaint.WidthInstrumentation
adb shell am start -n dev.tilesmile.supernote.paint/io.github.mpdairy.monopaint.PaintActivity
```

Covers every drawing tool, including first-use Shapes with its combined icon and
persistent first choice; subsequent first-tap selection without a popup, second-tap
settings, matching options and live variant icons in both toolbar sections,
settings persistence, brush-head memory, outside dismissal without drawing, and
panel bounds in all four orientations with both toolbar positions. Also checks
the Medium/Large settings text preference against tool panels and Layers,
Zoom lock toggles without a panel or changing the drawing tool, and
custom-tool creation, independent editing, and deletion.

Layered page format TSM3 adds a checked 0–100 opacity byte per layer. TSM1 and
TSM2 remain readable with opacity 100%; new opacity pages need this build or newer.
