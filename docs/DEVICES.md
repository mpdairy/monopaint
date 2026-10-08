# Supported tablets

Every hardware difference MonoPaint knows about is recorded in one of two places.
The rest of the app asks about a named fact (`device.landscapeTilt`,
`DirectEink.fastBinaryControls()`) and never about a model.
`DeviceChecks`, run by `scripts/test_document.sh`, fails if app code outside
`Device.java` names a tablet. Comments may still say where a behavior was observed.

| Where | What it holds | How it identifies the tablet |
| --- | --- | --- |
| `Device.java` | Input, layout and storage differences | Manufacturer, then the physical panel size |
| `LAYOUTS` in `cpp/direct_eink.c` | The e-ink driver buffer: size, scan order, fast binary pen path | The driver's own `/dev/ebc` query |

Neither place trusts Android's model property. The Manta reports itself as
"Supernote Nomad".

## Profiles

| Fact | Manta | Nomad | Other |
| --- | --- | --- | --- |
| Panel (portrait) | 1920×2560 | 1404×1872 | — |
| `landscapeTilt`: digitizer tilt arrives in the landscape scan frame | no | yes (hwrota=270; confirmed by hand 2026-10-06) | no |
| `compactControls`: Pages button replaces the page row | no | yes | no |
| `edgeSwipeSlopDp`: edge-swipe reach, tuned by hand | 24 | 6 | 24 |
| `simulates`: smaller tablet it can preview at physical size | Nomad | — | — |
| `testedFirmware`: builds the device checks passed on | `Chauvet.E103.2606141001.2389_release` | `Chauvet.E103.2606141001.2389_release` | none |
| Driver buffer | 1920×2560 portrait | 1872×1404 landscape | none (Android drawing only) |
| Fast binary controls (plane 1, mode 9) | no | yes | no |

## Firmware

The firmware drives each model's panel through its own kernel driver, so the same
build can work on the Manta and break on the Nomad. `Device.firmware()` is the
build (`Build.DISPLAY`), and each profile lists the builds the device checks
passed on with that tablet. A build tested on one model says nothing about another.

MonoPaint always tries fast e-ink, on untested firmware too. On an untested
(model, build) pair it says so once at startup, and Settings shows the build and
whether it is tested. Settings' **Screen drawing → Standard Android**
(`DirectEink.useAndroidDrawing`) makes `DirectEink.layout()` report no driver and
skips the firmware pen setup, so every display path takes its Android fallback
and MonoPaint makes no firmware e-ink calls. It is never chosen automatically.

When you run the device checks on a tablet with a new firmware build, add the
build to that profile's `testedFirmware`.

## Real hardware and simulation

`PaintActivity.device` is the hardware in use. `layoutDevice()` is the tablet the
controls are laid out for, which is the simulated one while Settings' simulation
mode is on. Layout questions such as `compactLayout()` use `layoutDevice()`.
Input and display quirks always use `device`: a Manta simulating a Nomad still has
the Manta's digitizer and driver.

## Adding a tablet

1. Add a profile constant to `Device` and list it in `SUPERNOTES` (or match it
   another way in `Device.current`). Decide every field from the device itself and
   say in a comment how you observed it.
2. If it has a new driver buffer, add a `LAYOUTS` row in `direct_eink.c` with the
   stride and size the driver reports. Unknown buffers fall back to Android
   drawing rather than guessing an ABI.
3. A difference no field covers gets a new field with a name that says what it
   does, not which tablet it is for. Give every existing profile a value for it.
4. Add a column to the table above and run the device checks on every tablet.
   List each firmware build they passed on in the new profile's `testedFirmware`.
   Device checks state their expectations in terms of these facts
   (`TestAccess.panelTilt`, `DirectEink.bufferFromPanel()`, `compactLayout()`),
   never a model name, so a new profile runs the same suites.
