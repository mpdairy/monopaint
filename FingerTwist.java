package io.github.mpdairy.monopaint;

/**
 * Turns the twist of two fingers into canvas rotation only once it is deliberate, so a plain
 * pinch or pan never tilts the page. Once a twist passes {@link #START} it follows the fingers freely.
 */
final class FingerTwist {
    /** Degrees the fingers must twist, in total, before the canvas starts to follow. */
    static final float START = 8;
    private float last, travelled;
    private boolean turning;
    /** Starts a gesture with the line between the fingers at {@code degrees}. */
    void begin(float degrees) { last=degrees; travelled=0; turning=false; }
    /** Takes {@code degrees} as the fingers' line without counting the change, while they are too close to tell. */
    void skip(float degrees) { last=degrees; }
    /**
     * @param degrees the line between the fingers now, clockwise from the screen's right
     * @return how far to turn the canvas clockwise for this move, in degrees
     */
    float move(float degrees) {
        float change=degrees-last; last=degrees;
        // The line's angle wraps at 180 degrees; take the short way round.
        change-=360*Math.round(change/360);
        if(turning) return change;
        travelled+=change;
        if(Math.abs(travelled)<START) return 0;
        turning=true;
        // Start from the edge of the dead zone, so the canvas does not jump.
        return travelled-Math.signum(travelled)*START;
    }
}
