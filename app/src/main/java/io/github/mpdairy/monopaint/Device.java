package io.github.mpdairy.monopaint;

import android.os.Build;

/** Facts about the tablet MonoPaint is running on. */
final class Device {
    /** A Supernote (Manta or Nomad). MonoPaint also installs and runs on other Android tablets. */
    static boolean supernote() { return "Supernote".equalsIgnoreCase(Build.MANUFACTURER); }

    private Device() {}
}
