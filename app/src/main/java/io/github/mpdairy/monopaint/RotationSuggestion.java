package io.github.mpdairy.monopaint;

/** Debounces accelerometer angles without depending on Android or changing orientation itself. */
final class RotationSuggestion {
    static final int NONE = -1;
    static final long HOLD_MS = 650;
    private int candidate = NONE;
    private long since;

    int update(int degrees, int currentQuarter, long now) {
        int next = NONE;
        if (degrees >= 0 && degrees < 360) {
            int quarter = ((degrees + 45) / 90) % 4;
            int distance = Math.abs(degrees - quarter * 90);
            distance = Math.min(distance, 360 - distance);
            // Leave a broad dead band around diagonals. Unknown includes a flat tablet.
            if (distance <= 25 && quarter != currentQuarter) next = quarter;
        }
        if (next != candidate) { candidate = next; since = now; }
        return next != NONE && now - since >= HOLD_MS ? next : NONE;
    }

    void reset() { candidate = NONE; }
}
