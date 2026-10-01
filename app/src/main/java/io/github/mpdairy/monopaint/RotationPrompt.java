package io.github.mpdairy.monopaint;

import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.view.OrientationEventListener;
import android.view.View;

/**
 * Offers to turn the app when the tablet is held in a new orientation. Android stays in
 * portrait, so this watches the sensor itself and shows a transient Rotate button;
 * shaking the tablet recalls a dismissed suggestion.
 */
final class RotationPrompt {
    private final PaintActivity app;
    private final PageActionButton button;
    final OrientationEventListener orientationSensor;
    final RotationSuggestion rotationSuggestion = new RotationSuggestion();
    private final SensorManager shakeSensors;
    private int suggestedQuarter = RotationSuggestion.NONE;
    private int lastSensorDegrees = OrientationEventListener.ORIENTATION_UNKNOWN;
    private long lastShake;
    private final Runnable rotationCheck = () -> suggest(lastSensorDegrees);
    private final Runnable rotationExpiry = () -> suggest(lastSensorDegrees);
    private final SensorEventListener shakeListener = new SensorEventListener() {
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
        @Override public void onSensorChanged(SensorEvent event) {
            float x = event.values[0], y = event.values[1], z = event.values[2];
            long now = SystemClock.uptimeMillis();
            if (x*x+y*y+z*z > 465 && now-lastShake > 1500) { lastShake = now; shake(); }
        }
    };

    RotationPrompt(PaintActivity app, PageActionButton button) {
        this.app = app; this.button = button;
        orientationSensor = new OrientationEventListener(app, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override public void onOrientationChanged(int degrees) {
                lastSensorDegrees = degrees;
                suggest(degrees);
                // The listener need not emit another event once a device stops moving.
                button.removeCallbacks(rotationCheck);
                if (degrees >= 0 && suggestedQuarter == RotationSuggestion.NONE)
                    button.postDelayed(rotationCheck, RotationSuggestion.HOLD_MS);
            }
        };
        shakeSensors = (SensorManager)app.getSystemService(android.content.Context.SENSOR_SERVICE);
    }

    void enable() {
        if (orientationSensor.canDetectOrientation()) orientationSensor.enable();
        if (shakeSensors == null) return;
        Sensor sensor = shakeSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (sensor != null) shakeSensors.registerListener(shakeListener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
    }
    void disable() {
        orientationSensor.disable();
        if (shakeSensors != null) shakeSensors.unregisterListener(shakeListener);
    }

    private boolean allowed() {
        return app.resumed && app.hasWindowFocus() && app.pagePanel == null && !app.busy();
    }
    private int currentQuarter() { return (4 - app.appRotation) % 4; }

    private void suggest(int degrees) {
        if (!allowed() || app.pad.penActive() || app.pad.navigating) { dismiss(); return; }
        long now = SystemClock.uptimeMillis();
        int next = rotationSuggestion.update(degrees, currentQuarter(), now);
        button.removeCallbacks(rotationExpiry);
        if (next != suggestedQuarter) {
            suggestedQuarter = next;
            button.setContentDescription(next % 2 == 1 ? "Rotate to landscape" : "Rotate to portrait");
            setVisible(next != RotationSuggestion.NONE);
        }
        if (next != RotationSuggestion.NONE) button.postDelayed(rotationExpiry, Math.max(1, rotationSuggestion.remaining(now)));
    }
    private void setVisible(boolean visible) {
        if (app.previewFrame == null || (button.getVisibility() == View.VISIBLE) == visible) return;
        if (visible) {
            // Turn the glyph toward the suggested orientation; its corner follows the hamburger.
            int rotation = (4-suggestedQuarter)%4;
            app.previewFrame.turnRotationHint(rotation == 3 ? -90 : rotation*90);
            app.pad.disconnectDisplay();
        }
        Rect area = new Rect(button.getLeft(), button.getTop(), button.getRight(), button.getBottom());
        app.selectionFeedback.update(app.previewFrame, area, () -> {
            button.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            if (!visible) button.press.set(false);
        });
        if (!visible && app.pad != null) app.pad.post(app.pad::connectDisplay);
    }
    /** Recalls a suggestion for the current orientation. */
    void shake() {
        if (!allowed()) return;
        rotationSuggestion.shake(lastSensorDegrees, currentQuarter(), SystemClock.uptimeMillis());
        suggest(lastSensorDegrees);
    }
    /** Hides the prompt until the tablet moves to another orientation. */
    void dismiss() {
        rotationSuggestion.dismiss(); suggestedQuarter = RotationSuggestion.NONE;
        button.removeCallbacks(rotationExpiry); setVisible(false);
    }
    /** Hides the prompt and forgets the sensor history. */
    void hide() {
        rotationSuggestion.reset(); suggestedQuarter = RotationSuggestion.NONE;
        button.removeCallbacks(rotationCheck); button.removeCallbacks(rotationExpiry);
        setVisible(false);
    }
    /** The Rotate button was pressed. */
    void accept() {
        if (suggestedQuarter == RotationSuggestion.NONE || app.busy()) return;
        app.requestQuarter(suggestedQuarter);
    }
}
