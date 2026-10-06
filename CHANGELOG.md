# Changelog

User-facing changes since the last release. Add an entry under **Unreleased**
with each change; when cutting a release, turn that section into
`dist/RELEASE_NOTES.md` and retitle it with the new version.

## Unreleased (since 0.91)

- MonoPaint now supports drawing on the Supernote Nomad, with a compact Pages
  menu that leaves more room for the color bar.
- Flat and other tilt-sensitive brushes now follow the pen's tilt correctly
  on Nomad.
- On Nomad, the color bar's selection markers now follow the pen smoothly
  without leaving a solid trail behind them.
- Nomad's wetness slider, swatches, and black-and-white button feedback now
  use the same faster refresh. Button icons and labels use black-and-white
  dots for softer edges, and the extra delay between control updates is gone.
- Nomad's page-menu arrows and Add page button now use the same fast pressed
  feedback as the sidebar tools. The Pages, menu, and rotation buttons do too.
- Page-navigation button boxes now clear before the page changes, avoiding
  repeated flashes while the page counter and disabled arrows update.

- Export PNG has a **Change folder…** button. It opens at Supernote's EXPORT
  folder, which the tablet's file manager shows (Pictures/MonoPaint doesn't).
  The app remembers the folder you choose for later exports.

## 0.91

See the [0.91 release notes](https://github.com/mpdairy/monopaint/releases/tag/v0.91).

## 0.9

See the [0.9 release notes](https://github.com/mpdairy/monopaint/releases/tag/v0.9).
