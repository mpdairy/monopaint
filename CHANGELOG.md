# Changelog

User-facing changes since the last release. Add an entry under **Unreleased**
with each change; when cutting a release, turn that section into
`dist/RELEASE_NOTES.md` and retitle it with the new version.

## Unreleased (since 0.95)

## 0.95

- MonoPaint now supports drawing on the Supernote Nomad, with a compact Pages
  menu that leaves more room for the color bar.
- Hide the bars for more drawing room. Swipe one finger from the canvas off a
  bar's edge of the screen to hide or show just that bar, starting from any
  layout. The artwork stays in place. Swipes that stop inside the canvas do
  nothing, and Supernote's own gestures still work. On the Manta, upward swipes
  now work reliably even though its touch screen loses the finger just before
  the top edge.
- A two-finger double tap hides both bars when both are showing, or brings both
  back when neither is showing. With one bar showing, it undoes the last bar
  change. The three-finger gesture is gone, and Fullscreen in the menu offers
  all four bar layouts.
- The first time you hide the bars on a page, illustrated buttons let you keep
  the canvas size or expand it with blank margins that fill the screen, without
  stretching the artwork. Each page has its own choice; revisit it in Canvas
  size. Expansion can be undone, and showing the bars again never crops a page.
  Existing drawings still open normally.
- Quickly double-tap the Zoom tool to return to 100%, with the artwork back
  where the page began; an expanded page reaches under the bars. A single tap
  still locks or unlocks zoom and pan, and a double tap keeps your lock setting.
- Export PNG has a **Change folder…** button. It opens at Supernote's EXPORT
  folder, which the tablet's file manager shows (Pictures/MonoPaint doesn't).
  The app remembers the folder you choose for later exports.
- Nomad fixes: tilt-sensitive brushes like Flat follow the pen's tilt; color
  bar markers follow the pen smoothly without leaving a trail and stay on the
  chosen shade after a screen refresh; the wetness slider, swatches, buttons
  and page controls use fast pressed feedback with softer-edged icons.
- Page-navigation buttons clear before the page changes, avoiding repeated
  flashes while the page counter and arrows update.

## 0.91

See the [0.91 release notes](https://github.com/mpdairy/monopaint/releases/tag/v0.91).

## 0.9

See the [0.9 release notes](https://github.com/mpdairy/monopaint/releases/tag/v0.9).
