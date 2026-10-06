# Changelog

User-facing changes since the last release. Add an entry under **Unreleased**
with each change; when cutting a release, turn that section into
`dist/RELEASE_NOTES.md` and retitle it with the new version.

## Unreleased (since 0.95)

- The color selector is faster on the Nomad: only the small marker below the
  shade strip moves, leaving the strip's dots untouched.
- Rapid color taps leave fewer stray marker copies on the Nomad. The marker
  follows each tap immediately, with a cleanup a quarter-second after you pause
  instead of extra refreshes after every tap.
- Paintings are now kept in `Document/MonoPaint`, where the Files app shows them
  and they survive uninstalling or reinstalling MonoPaint. Each launch asks for
  file access until you allow it, saying how many paintings are still stored
  inside the app; then they and the painting you're working on move there
  automatically. After a reinstall, paintings already in that folder open again.
  On tablets other than Supernotes, paintings go to the standard
  `Documents/MonoPaint` instead.
- Export PNG now saves to `EXPORT/MonoPaint` on a Supernote, where the Files
  app shows it, once MonoPaint has file access. Other tablets still use
  `Pictures/MonoPaint`, and a folder chosen with Change folder… still wins.
- The menu no longer has a Canvas size item. Each page still offers Keep
  canvas size or Expand canvas the first time its bars are hidden.
- Paintings are now `.mpaint` files. Existing `.tsm` paintings are renamed
  automatically the first time this version starts.
- New painting and Open painting now ask whether to save unsaved changes first:
  Save, Don't save, or Cancel. If nothing changed since your last save, they go
  straight ahead.
- On the Nomad, shape previews and the gradient direction line now follow the
  pen without leaving a trail of copies or catching up after you stop.
- The app now calls your artwork paintings: New painting, Open painting, Save
  painting as…, and so on.

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
