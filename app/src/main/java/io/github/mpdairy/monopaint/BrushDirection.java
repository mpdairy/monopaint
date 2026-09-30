package io.github.mpdairy.monopaint;

/** Supernote's measured signed X/Y tilt degrees, projected onto the page. */
final class BrushDirection {
    private static final double MIN_LEAN = Math.tan(Math.toRadians(5));
    static float resolve(float tiltX, float tiltY, float offset, float fallback) {
        if (!Float.isFinite(tiltX) || !Float.isFinite(tiltY)
                || Math.abs(tiltX) > 90 || Math.abs(tiltY) > 90) return fallback;
        double x=Math.tan(Math.toRadians(Math.max(-89.9,Math.min(89.9,tiltX))));
        double y=Math.tan(Math.toRadians(Math.max(-89.9,Math.min(89.9,tiltY))));
        // A nearly upright pen has no dependable azimuth; keep its last heading.
        if (Math.hypot(x,y) < MIN_LEAN) return fallback;
        // A flat brush's broad edge lies across its shaft's lean direction.
        return normalize((float)Math.toDegrees(Math.atan2(y,x))+90+offset);
    }
    static float againstLean(float tiltX, float tiltY, float dx, float dy) {
        if (!Float.isFinite(tiltX) || !Float.isFinite(tiltY) || !Float.isFinite(dx) || !Float.isFinite(dy)
                || Math.abs(tiltX)>90 || Math.abs(tiltY)>90) return 0;
        double x=Math.tan(Math.toRadians(Math.max(-89.9,Math.min(89.9,tiltX))));
        double y=Math.tan(Math.toRadians(Math.max(-89.9,Math.min(89.9,tiltY))));
        double lean=Math.hypot(x,y), distance=Math.hypot(dx,dy);
        if (lean<MIN_LEAN || distance<.001) return 0;
        // Keep signed lean here: a head has 180-degree symmetry, a push does not.
        return (float)Math.max(0,Math.min(1,-(x*dx+y*dy)/(lean*distance)));
    }
    static float normalize(float angle) { return (angle % 180 + 180) % 180; }
    static float delta(float from, float to) {
        // Both heads are symmetric: 179 -> 1 is a two-degree turn, not 178.
        return normalize(to-from+90)-90;
    }
    private BrushDirection() {}
}
