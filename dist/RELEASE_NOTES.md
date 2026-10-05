**Experimental Nomad build.** This is MonoPaint 0.91 with the fast e-ink
display path turned on for the Supernote Nomad. It is untested on a real Nomad.
For everyday use, stay on the [regular release](https://github.com/mpdairy/monopaint/releases/latest).

MonoPaint draws strokes straight to the e-ink panel for low-lag ink. Until now
that only worked on the Manta, so on a Nomad it fell back to slower Android
drawing. This build tries the fast path on any screen whose driver reports a
sensible layout. Nothing else is changed from 0.91.

**Please test on a new drawing** and [open an issue](https://github.com/mpdairy/monopaint/issues)
with your firmware version and what you saw:

- Does ink appear right under the pen with little lag?
- Do grays, white paint and the eraser look right?
- Any garbled, shifted or striped ink, or screen glitches?
- Did you see "Fast display unavailable; using standard drawing"?

If something looks wrong, go back to the regular release by installing
0.91 over this build (`adb install -r monopaint.apk`). Drawings and settings are
kept either way: both builds use Android version code 43 and the same signing
identity, so each installs over the other.

`SHA256SUMS` contains the APK checksum. This is a signed, non-debuggable release build.
