package io.github.mpdairy.monopaint;

final class FingerTwistChecks {
    public static void main(String[] args) {
        FingerTwist twist=new FingerTwist();
        // A pinch or pan whose fingers wobble a few degrees never turns the canvas.
        twist.begin(10);
        near(twist.move(13),0); near(twist.move(8),0); near(twist.move(15),0); near(twist.move(11),0);
        // A deliberate twist starts from the edge of the dead zone, then follows the fingers exactly.
        twist.begin(0);
        near(twist.move(5),0);
        near(twist.move(12),4);
        near(twist.move(20),8);
        near(twist.move(15),-5);
        // Once turning, it keeps following even when the fingers return near where they began.
        near(twist.move(-1),-16);
        // The other direction mirrors it.
        twist.begin(0);
        near(twist.move(-5),0); near(twist.move(-12),-4); near(twist.move(-20),-8);
        // The line between the fingers wraps at 180 degrees: crossing it is a small turn, not a spin.
        twist.begin(170);
        near(twist.move(178),0);   // exactly the dead zone: turning starts, nothing to apply yet
        near(twist.move(-174),8);  // 178 to -174 is 8 degrees clockwise, not 352 back
        near(twist.move(-170),4);
        twist.begin(-175);
        near(twist.move(175),-2);  // 10 degrees anticlockwise across the wrap, less the dead zone
        near(twist.move(165),-10);
        // Fingers too close to tell apart can swing the line wildly; none of that counts as a twist.
        twist.begin(0);
        twist.skip(120); twist.skip(-60);
        near(twist.move(-58),0); near(twist.move(-55),0);
        // A new gesture starts over with a fresh dead zone.
        twist.begin(0); near(twist.move(20),12);
        twist.begin(20); near(twist.move(22),0);
        System.out.println("PASS: finger twist dead zone, smooth start, both directions and 180-degree wrap");
    }
    private static void near(float actual,float wanted) {
        if(Math.abs(actual-wanted)>.001f) throw new AssertionError(actual+" != "+wanted);
    }
}
