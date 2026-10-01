# Code structure

All app code is in `app/src/main/java/io/github/mpdairy/monopaint`.

## The painting screen

`PaintActivity` owns the open book, the current selection and the layout, and
coordinates the panels. Android and the e-ink driver always stay in portrait; the
app turns its own view tree (`QuarterTurnLayout`), so popups are placed in the
app's upright coordinates and mapped back to the portrait window (`Popups`).

| Class | Role |
| --- | --- |
| `DrawingPad` | The canvas: rendering, direct e-ink presentation, pen gestures, pinch/pan, wet paint |
| `Toolbar` | Side toolbar: tools, Layers, Zoom, palette strip and favorites (drag to reorder) |
| `ToolSettingsPanel` | Settings popup for the selected tool, opened beside its toolbar button |
| `PaletteEditor`, `LayersPanel`, `AppSettingsDialog` | The other panels |
| `RotationPrompt` | Sensor-driven "Rotate" suggestion |
| `ShadePicker`, `WetnessBar` | Color bar controls |
| `ToolButton`, `PageActionButton`, `PressOutline` | Icon controls and their immediate pressed outline |
| `SelectionFeedback` | Sends small control changes straight to the panel |
| `PaintState` | Current color, erase mode and wet/transparent paint |
| `PaintPreferences` | Typed access to saved settings |

## Tools

Tool settings are immutable `ToolSettings` objects. `ToolLibrary` remembers the
settings of each built-in tool and the user's favorites, and stores them in a
versioned binary format.

To add a tool:

1. Append a constant to `ToolSettings.Tool` with its label and kind. Never reorder
   the constants. Add a format version in `ToolLibrary` (`FORMAT` and `toolCount`).
2. Give it an icon in `ToolIcons`.
3. Describe its settings rows in `ToolControls`, using `SettingsForm`'s bound
   sliders, checkboxes and hints. Each control reads and edits the current
   `ToolSettings`; the panel saves and refreshes the toolbar automatically.
4. Return its `DrawingStroke` from `ToolStrokes.create`. A stroke receives pen samples
   in page coordinates and edits the `ToneDocument`; `finish()` commits one undo step.
5. Add it to `Toolbar.TOOLS` to show it in the side toolbar.

Fill and Shapes are drag-and-release gestures rather than strokes. `DrawingPad` runs
them itself.

## Documents

`ToneDocument` holds one page's layers and undo history. `DrawingBook` holds the
pages, and `DocumentStore` saves books and the autosave/recovery file in the
background (`BookCodec`, `DocumentCodec`, `RecoveryCodec`).
