# Atelier 1.1.82 brush grayscale grades

Read from the user's installed `com.ratta.supernote.paint`, versionCode 1182,
versionName 1.1.82, on 2026-09-24. These are brush brightness values, not inferred
from a photograph or generic Android UI colors.

| Grade | R = G = B | RGB hex |
| ---: | ---: | --- |
| 0 | 0 | #000000 |
| 1 | 80 | #505050 |
| 2 | 96 | #606060 |
| 3 | 104 | #686868 |
| 4 | 112 | #707070 |
| 5 | 128 | #808080 |
| 6 | 136 | #888888 |
| 7 | 144 | #909090 |
| 8 | 160 | #A0A0A0 |
| 9 | 170 | #AAAAAA |
| 10 | 182 | #B6B6B6 |
| 11 | 192 | #C0C0C0 |
| 12 | 200 | #C8C8C8 |
| 13 | 208 | #D0D0D0 |
| 14 | 225 | #E1E1E1 |
| 15 | 255 | #FFFFFF |

The probe now requests these values with full opacity. Matching brightness
values does not by itself reproduce Atelier's brush texture, opacity behavior,
compositing, dithering, or physical panel refresh.

In particular, these RGB values are **not** dot-density percentages. Version
0.12 calibrates the dot brush against the user's actual Atelier gradient;
see [GRAY_DENSITY.md](GRAY_DENSITY.md) for the measured coverage table and
the native gamma-stage evidence.

## Local evidence and method

The APK's native `libspaint_arm64-v8a.so` contains a 16-entry `(grade,value)`
integer table at file offset / virtual address `0x187eec` (duplicate at
`0x1b2938`). The constructor of PaletteForm registers buttons with grades 0–15.

Native initializers at `0x404bb8`, `0x404c98`, and `0x404d78` each load the same
128-byte table and initialize, respectively:

- `BrushColorManager::smoothColorMap`
- `BrushColorManager::mediumTextureColorMap`
- `BrushColorManager::texturedColorMap`

Relocations at `0xa045f8`, `0xa04608`, and `0xa04618` identify those map symbols.
`BrushManager::Impl::setColorGrade(int)` at `0x41266c` clamps grade to 0–15,
looks up its map value, divides it by 255, and calls `setBrightnessFp(float)`.
This connects the numbers to brush brightness, rather than just palette icons.
All three named maps initialize from the same values in this installed version.

APK SHA-256: `0d187a728271be5b11df7b456dd6abca27d61a9c6ebea71242321aba507d84c7`

Native library SHA-256: `4c8f9c495a7a00c70ebc19b50b11261f65c83d0fbbb4d16df663c714b41ffbab`

Read-only extracted APK/library and disassembly are local under the ignored
`artifacts/firmware/` directory. No Atelier code, library, icons, or brushes
are included in the probe. `GrayPalette.java` records only the palette values.
