# Pixel Block comparison — 0.11

Follow-up: [GRAY_DENSITY.md](GRAY_DENSITY.md) records the user's gradient
comparison and 0.12's nonlinear density calibration. The initial linear
coverage mapping described below is retained as the 0.11 experiment history.

The user physically confirms that Atelier's Pixel Block marker draws gray
without a visible black trail. Inspection on 2026-09-24 found a concrete
difference from our solid-gray probe: the sampled gray-looking strokes in
Atelier's retained screenshot consist exclusively of black and white pixels.
The dot density produces the gray appearance.

## Evidence and limits

`artifacts/atelier-pixel-block-reference.png` shows the Pixel Block marker
selected. The corresponding process-5738 log records `Marker:3:Pixel Block`
and brush brightness grade 10. In a 720 × 2560 strip of the canvas at x=1200,
all 1,843,200 captured pixels are either RGB 0 or RGB 255. Two smaller samples
on the right likewise contain only those values. The full screenshot contains
476 RGB colors, including intermediate shades in the UI, so the screenshot
as a whole was not simply reduced to two colors. Counts and the screenshot
hash are in `artifacts/atelier-pixel-block-analysis.txt`.

The installed library provides a compatible live-rendering explanation:
`MainWnd::Impl::tryStrokeTo` calls the layer renderer with `GrayscaleDither`
and its parameters at 0x4cdf48, then its direct branch calls
`screenUpdate<false>` at 0x4cdf88. That calls `repaintC::updateScreen1` at
0x4d0b30. The A5X2 branch still requests driver mode 7 at 0x9209cc–0x9209d0;
its presenter converts source bytes with a four-bit right shift and sets the
low three flag bits to 1. These are static call-path findings, not a runtime
trace of individual drawing requests.

This supports testing binary dithering while keeping our physically cleaner
mode 7 / flags 0 request. It does not establish Atelier's exact dither method,
prove that every brush or setting uses binary output, or show the physical
panel's transient states. The screenshot contains existing marks from several
brushes; it does not attribute each mark to Pixel Block. The user's live
observation remains the evidence for the absence of the black trail.

## Independent comparison

Version 0.11 replaces the failed GL refresh control with **Shade: solid** /
**Shade: dots**. Solid remains the startup baseline. Dots affects the direct
pressure brush for nonblack swatches; black keeps its working firmware path.
The View fallback continues to draw solid gray.

Dots uses an independent 8 × 8 ordered threshold pattern. White-pixel coverage
approximates each requested gray within two RGB units in area average; all 16
palette selections have distinct coverage. This is a textured approximation
of gray, not a claim of 16 uniform physical pixel shades or calibrated
perceptual brightness. No Atelier implementation or brush assets are bundled.

The pattern is opaque and fixed to canvas coordinates, so lighter strokes
replace darker dots, white paints solid white, and repeated identical strokes
do not accumulate darkness. The retained bitmap stores the same dots sent
live; pen-up and later UI redraws do not replace them with solid gray.
Switching shade mode affects subsequent strokes and preserves existing marks.
Display mode, request flags, pressure curve, region handling and submit timing
are unchanged. Driver mode 4 is now rejected by the adapter.

## Physical check

Use **Native only**, **Paint brush**, **Gray: direct test**, **Update: bitmap**,
and **Shade: dots**, then select a middle gray such as 136. Compare fresh
strokes against **Shade: solid**, checking for the moving dark tip, lag,
dot texture, retained shade, and white overpainting. Automated buffer and
screenshot checks cannot establish the physical result.
