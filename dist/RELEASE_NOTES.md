MonoPaint 0.8 is a maintenance release that reorganizes the app's internals.

- The painting screen is split into focused components, and tools are now
  described declaratively, making new tools and settings easier to add.
- Tool settings rows share consistent padding.
- Drawing behavior, tools, and file formats are otherwise unchanged from 0.7.

Download **monopaint.apk** and follow the [sideload instructions](https://github.com/mpdairy/monopaint#sideload-onto-a-manta).
Update with `adb install -r monopaint.apk` to preserve drawings and settings.
The application ID and signing identity are unchanged from MonoPaint 0.7.
Android version code 40 allows this release to update 0.7 and earlier builds.

`SHA256SUMS` contains the APK checksum. This is a signed, non-debuggable release build.
