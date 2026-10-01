package io.github.mpdairy.monopaint;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Which way is down on the page, for wet paint that runs downhill. The artwork never
 * turns with the app (Android and the page stay in portrait), so page axes are the
 * device's own: x right, y down. A tablet lying flat reports no pull.
 */
final class CanvasGravity {
    private static final float FLAT = .08f, SMOOTHING = .2f;
    private final SensorManager sensors;
    private final PaintPreferences prefs;
    private boolean listening, seeded;
    private float x, y;
    private final SensorEventListener listener = new SensorEventListener() {
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
        @Override public void onSensorChanged(SensorEvent event) {
            // The accelerometer reads the push against gravity on axes with y up.
            float gx = -event.values[0] / SensorManager.GRAVITY_EARTH;
            float gy = event.values[1] / SensorManager.GRAVITY_EARTH;
            // The first reading is used as is, so newly wet paint never starts out level.
            if (!seeded) { x = gx; y = gy; seeded = true; }
            else { x += (gx - x) * SMOOTHING; y += (gy - y) * SMOOTHING; }
        }
    };

    CanvasGravity(PaintActivity app) {
        sensors = (SensorManager)app.getSystemService(android.content.Context.SENSOR_SERVICE);
        prefs = app.prefs;
    }

    /** The pad listens only while wet paint is still spreading, and only with the setting on. */
    void listen(boolean spreading) {
        boolean wanted = spreading && prefs.wetGravity() && sensors != null;
        if (wanted == listening) return;
        Sensor sensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (wanted && sensor != null) listening = sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI);
        else if (!wanted) { sensors.unregisterListener(listener); listening = seeded = false; x = y = 0; }
    }

    /** Applies the current downhill direction to wet paint; strength grows with tilt, at most 1. */
    void apply(WetWatercolor wet) {
        float length = (float)Math.hypot(x, y);
        if (!seeded || length <= FLAT) { wet.setGravity(0, 0); return; }
        float strength = Math.min(1, (length - FLAT) / (1 - FLAT)) / length;
        wet.setGravity(x * strength, y * strength);
    }
}
