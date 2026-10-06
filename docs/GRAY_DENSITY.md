# Calibrated dot density — 0.12

The user supplied `/tmp/gradient_us.jpeg` and `/tmp/gradient_atelier.jpeg`
and confirmed that both gradients step through every swatch from black to
white. The photos show a different progression and dot texture. Atelier still
held that gradient, so an ADB PNG capture allowed counting black/white pixels
without camera exposure, perspective, lighting or JPEG artifacts influencing
the density estimate.

Our 0.11 mapping used `round(gray * 64 / 255)` white pixels in each 8 × 8
tile. Atelier's effective coverage is strongly nonlinear: at swatch 136,
its sampled stroke is 75.7% black; ours was only 46.9% black. The palette RGB
values established in [ATELIER_PALETTE.md](ATELIER_PALETTE.md) are brush
brightness inputs, not white-dot coverage percentages.

## Measurement

Source: `artifacts/gradient-atelier-reference.png`, 1920 × 2560, SHA-256
`afad96f02df59fc86822ece39e1d1517232d11392cdbaaa6b49de28caca6e8cc`.
For each visible grade, count a 30 × 10 pixel rectangle inside the stroke:
x=[500,530), y=[center−5,center+5), with centers
110, 160, 190, 232, 270, 303, 347, 380, 416, 452, 490, 535, 572, 604, 639.
All samples contain only RGB 0 and 255. The white endpoint is defined as
zero black coverage; white ink cannot be measured against the white background.

These are small interior samples of this particular retained gradient, not
exact universal coverage constants. Spatial variation, brush settings and
stroke history can affect another sample. The annotated chart shows the
sampling locations so the measurement is reviewable.

| Grade | Brightness input | Atelier sampled black | 0.11 black | 0.12 black | White pixels per tile |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 0 | 100.0% | 100.0% | 100.0% | 0/64 |
| 1 | 80 | 94.3% | 68.8% | 93.8% | 4/64 |
| 2 | 96 | 90.3% | 62.5% | 90.6% | 6/64 |
| 3 | 104 | 89.0% | 59.4% | 89.1% | 7/64 |
| 4 | 112 | 84.7% | 56.2% | 84.4% | 10/64 |
| 5 | 128 | 79.7% | 50.0% | 79.7% | 13/64 |
| 6 | 136 | 75.7% | 46.9% | 75.0% | 16/64 |
| 7 | 144 | 72.7% | 43.8% | 73.4% | 17/64 |
| 8 | 160 | 65.3% | 37.5% | 65.6% | 22/64 |
| 9 | 170 | 58.3% | 32.8% | 57.8% | 27/64 |
| 10 | 182 | 49.3% | 28.1% | 50.0% | 32/64 |
| 11 | 192 | 45.0% | 25.0% | 45.3% | 35/64 |
| 12 | 200 | 40.3% | 21.9% | 40.6% | 38/64 |
| 13 | 208 | 34.7% | 18.8% | 34.4% | 42/64 |
| 14 | 225 | 21.3% | 12.5% | 21.9% | 50/64 |
| 15 | 255 | 0.0% (defined) | 0.0% | 0.0% | 64/64 |

Run `python3 scripts/analyze_gray_density.py` to reproduce the counts, CSV,
JSON and annotated chart (requires Pillow and Matplotlib). The script checks
the capture hash because its sample coordinates are specific to this image.

## Native evidence for the nonlinear transfer

The installed library's `FloydSteinbergDitherer6431::GAMMA_VALUE` has initial
value **2.2**, at virtual address 0xa14b50 / file offset 0xa11b50. Relocation
0xa05bc0 identifies the symbol. The global dither core normalizes an input
byte by 255, loads this gamma and calls `pow` at 0x850b54, then scales and
rounds before thresholding/error diffusion. `ditherSingleRow` likewise calls
`pow` at 0x853ae4. Earlier Atelier logs identify the 6431 ditherer in use.

This is evidence of a gamma stage and its stored default, not a read of the
current runtime gamma or proof that gamma alone determines the final stroke
density. Actual measured coverage also reflects brush rendering, error
diffusion and sampling. 0.12 therefore uses the observed coverage table;
it does not claim to reproduce Atelier by applying a guessed exponent.

## Change and limits

`DotGray` now uses the calibrated white-pixel counts. The greatest deviation
from these samples is **0.771 percentage points**. All 16 swatches remain
distinct and ordered; other input values interpolate monotonically between
the palette entries. Black and white remain exact opaque endpoints.

The existing 8 × 8 ordered pattern, opaque overpainting, coordinate anchoring,
pressure brush and display requests are unchanged. Atelier uses an error
diffusion pattern; ours retains its more regular texture. Equal black coverage
does not guarantee equal perceived tone on the panel. The user subsequently
drew a fresh gradient in 0.12 and confirmed its physical appearance as perfect.
The saved capture `artifacts/gray-density-physical-success.png` also contains
complete matching tiles for all 15 nonwhite calibrated shades. Version 0.12
is now the accepted gradient baseline. Existing bitmap
strokes are not retrospectively recolored; this change affects new strokes.

Artifacts: `gray-density-measurements.{json,csv}`, `gray-density-comparison.png`,
`gradient-{us,atelier}-photo.jpeg`, and the preserved pre-install probe capture
`gradient-us-before.png`, all under `artifacts/`.

## Smooth PNG export

The first raw grayscale export exposed the logical brush inputs directly as RGB
values. The user reported it much lighter than the tablet, whose rendering applies
the coverage curve above. The host exporter now defaults to `calibrated`: it
interpolates that same curve continuously and maps its white coverage to 0–255
RGB channel values. It does not round to one of the 65 tile densities or invert
the curve. For example, logical 128 becomes 52, and 182 becomes 128. Black and
white remain exact, and no spatial blur or resizing is applied.

This is a coverage-based contrast rendering, not a measured color profile for
the physical panel or monitor. It should be judged visually; equal coverage and
an RGB channel value do not guarantee identical perceived brightness on e-ink.
The tablet's accepted calibration and the saved logical tones are unchanged.

Run `bash scripts/export_png.sh drawing.mpaint 2 output.png` to export page two.
The optional final argument is `calibrated` (default), `raw` (logical tones) or
`dots` (the exact tablet pixel pattern). The exporter checks decoded PNG pixels
against its output buffer. The in-app **Export PNG…** menu action writes the same `calibrated` pixels
to `EXPORT/MonoPaint` on a Supernote with file access, otherwise `Pictures/MonoPaint` (or a folder chosen with **Change folder…**), turned to the current app orientation: one page as
`name.png`, or every page as `name001.png`, `name002.png`, …
