package io.github.mpdairy.monopaint;

/** Debounces accelerometer angles without depending on Android or changing orientation itself. */
final class RotationSuggestion {
    static final int NONE = -1;
    static final long HOLD_MS = 650;
    static final long SHOW_MS = 5000;
    private int candidate = NONE;
    private long since;
    private boolean offered;
    private long visibleUntil;

    int update(int degrees, int currentQuarter, long now) {
        int next = NONE;
        if (degrees >= 0 && degrees < 360) {
            int quarter = ((degrees + 45) / 90) % 4;
            int distance = Math.abs(degrees - quarter * 90);
            distance = Math.min(distance, 360 - distance);
            // Leave a broad dead band around diagonals. Unknown includes a flat tablet.
            if (distance <= 25 && quarter != currentQuarter) next = quarter;
        }
        if (next != candidate) { candidate = next; since = now; offered=false; }
        if(next==NONE || now-since<HOLD_MS)return NONE;
        if(!offered){offered=true;visibleUntil=now+SHOW_MS;}
        return now<visibleUntil?next:NONE;
    }

    int shake(int degrees,int currentQuarter,long now) {
        update(degrees,currentQuarter,now);
        if(candidate==NONE || now-since<HOLD_MS)return NONE;
        offered=true;visibleUntil=now+SHOW_MS;return candidate;
    }
    long remaining(long now){return Math.max(0,visibleUntil-now);}
    void dismiss(){offered=true;visibleUntil=0;}
    void reset() { candidate = NONE;offered=false;visibleUntil=0; }
}
