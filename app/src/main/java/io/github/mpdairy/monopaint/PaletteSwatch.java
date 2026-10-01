package io.github.mpdairy.monopaint;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/** One saved palette shade, drawn with the tablet's real dot pattern. A negative tone is an empty cell. */
@SuppressLint("ViewConstructor")
final class PaletteSwatch extends View {
    private final Paint ink = new Paint();
    private final Paint border = new Paint();
    private final DashPathEffect dashed;
    private Bitmap tile;
    /** Larger cells in the palette editor; small ones in the toolbar. */
    final boolean editorCell;
    boolean chosen, dropHere, empty;
    int tone = -2;

    PaletteSwatch(Context context, int tone, boolean editorCell) {
        super(context); this.editorCell = editorCell; setFocusable(true);
        dashed = new DashPathEffect(new float[]{dp(4), dp(3)}, 0);
        replaceTone(tone); invalidate();
    }
    private int dp(float value) { return Ui.dp(getContext(), value); }
    /** Changes the shade through the fast display path. */
    void presentTone(int value, SelectionFeedback feedback, boolean windowFocused) {
        if (tone == value) return;
        feedback.update(this, new android.graphics.Rect(0, 0, getWidth(), getHeight()), () -> replaceTone(value), windowFocused);
    }
    private void replaceTone(int tone) {
        this.tone = tone;
        Bitmap previous = tile; empty = tone < 0; tile = empty ? null : DotGray.tile(tone);
        ink.setShader(empty ? null : new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        if (previous != null) previous.recycle();
    }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.WHITE);
        int size = dp(editorCell ? 40 : 22), left = (getWidth()-size)/2, top = (getHeight()-size)/2;
        ink.setStyle(Paint.Style.FILL); if (!empty) canvas.drawRect(left, top, left+size, top+size, ink);
        boolean emphasized = chosen || dropHere;
        border.setColor(Color.BLACK); border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(dp(emphasized ? 3 : 1));
        border.setPathEffect(empty && !emphasized ? dashed : null);
        canvas.drawRect(left, top, left+size, top+size, border);
        if (emphasized) { border.setPathEffect(null); canvas.drawRect(left-dp(4), top-dp(4), left+size+dp(4), top+size+dp(4), border); }
    }
}
