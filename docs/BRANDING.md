# MonoPaint logo

The launcher and README use
[`ic_launcher.png`](../app/src/main/res/mipmap-nodpi/ic_launcher.png), a square RGBA
PNG with transparent outer corners and approximately 2% padding on each side.
The white brush is opaque; the area outside the rounded square is transparent.

The design comes from the [project owner's supplied logo](https://chatgpt.com/s/m_6abb32b362a881919ccd7ce241d79641).
The checked-in version was edited with image generation to remove the surrounding
white frame and shared-preview side bars. It is an edited raster asset, not a
pixel-exact crop or a vector original.

Keep the transparent padding when replacing the image. The Android manifest
references this asset directly, and the README displays the same file at 96 pixels.
