package io.github.mpdairy.monopaint;

interface DrawingStroke {
    void sample(float x, float y, float pressure, float tiltX, float tiltY);
    boolean finish();
}
