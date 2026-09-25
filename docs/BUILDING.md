# Building and publishing

Use JDK 21, Android SDK 34, NDK 27.0.12077973, and CMake 3.22.1.
Set `JAVA_HOME` and `ANDROID_HOME` for your machine; the Gradle wrapper is included.

```sh
bash scripts/test_document.sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

The development APK is `app/build/outputs/apk/debug/app-debug.apk`.
To run the device checks, install that APK and the test APK, then run:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e paintOnly true dev.tilesmile.supernote.paint.test/dev.tilesmile.supernote.WidthInstrumentation
```

These checks use a temporary drawing and restore the original drawing and tool
settings. They exercise the Manta-specific display path and require a Manta.
A locally built debug APK has your machine's signing key, so it may not update
an installed official release. Never uninstall a release merely to resolve a
signature mismatch without preserving your drawings first.

## Signed releases

Official APKs are non-debuggable release builds. The existing private signing
identity is retained for upgrade compatibility with the early TileSmile builds.
The signing key is kept outside this repository; it is never sent to CI.

Set `MATTELIER_KEYSTORE`, `MATTELIER_KEY_ALIAS`, `MATTELIER_STORE_PASSWORD`, and
`MATTELIER_KEY_PASSWORD` locally, then run:

```sh
bash scripts/prepare_release.sh
```

The script builds, aligns, signs, and verifies `dist/Mattelier.apk`, and updates
its checksum and version. Update `dist/RELEASE_NOTES.md`, test the signed APK on
a Manta, then commit the source and the small signed distribution files together.
Push `main` and a matching tag, e.g. `v0.26`. The release workflow verifies the
checksum and publishes the already-signed APK and checksum as GitHub Release
assets. It can also be rerun manually from that version's tag.

```sh
git push origin main
git tag -a v0.26 -m 'Mattelier 0.26'
git push origin v0.26
```

The checked-in APK allows publishing without exposing the private signing key
or requiring a new signing identity on each build. Keep the signing key backed
up securely: future Android updates need the same identity.

## PNG conversion

For a `.tsm` book already available on your computer:

```sh
bash scripts/export_png.sh drawing.tsm 2 output.png
```

This exports page two. Add `raw` or `dots` to choose logical tones or the tablet's
dot pattern. This utility does not retrieve private files from the tablet.
