MonoPaint 0.91 adds PNG export, eraser-end pens, and optional smooth brush edges.

- Pens with an eraser end, like the Staedtler Noris digital jumbo, now erase
  when flipped over, and holding a pen's side button while drawing erases too,
  using the Eraser tool's settings. Your selected tool doesn't change, so the
  regular tip keeps drawing with whatever tool you were using.
- Export PNG: a new menu item saves the current page, or every page, as PNG
  pictures in `Pictures/MonoPaint`. All pages are numbered from your chosen
  name, like `sketch001.png`, `sketch002.png`, and so on. Grays match the
  tablet's shading, and pictures are turned the way you're holding the tablet.
  It asks before replacing earlier exports.
- Smooth brush edges: a new option in Settings gives dry brush strokes and the
  hard eraser soft, anti-aliased edges instead of stair-stepped pixels, so
  exported PNGs look clean. Wet paint was already smooth. It's off by default,
  since it makes drawing slightly laggier.
- The side toolbar always uses large icons; the Medium choice is gone, since
  the buttons were the same size either way.
- Open drawing shows your drawings as a grid of first-page previews, like the
  Pages overview, so you can find a drawing by how it looks.

Download **monopaint.apk** and follow the [sideload instructions](https://github.com/mpdairy/monopaint#sideload-onto-a-manta).
Update with `adb install -r monopaint.apk` to preserve drawings and settings.
The application ID and signing identity are unchanged from MonoPaint 0.9.
Android version code 43 allows this release to update 0.9 and earlier builds.

`SHA256SUMS` contains the APK checksum. This is a signed, non-debuggable release build.
