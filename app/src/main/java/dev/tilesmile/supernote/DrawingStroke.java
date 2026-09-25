package dev.tilesmile.supernote;

interface DrawingStroke {
    void sample(float x, float y, float pressure, float tiltX, float tiltY);
    boolean finish();
}
