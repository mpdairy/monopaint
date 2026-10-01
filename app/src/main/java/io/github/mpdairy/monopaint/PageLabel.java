package io.github.mpdairy.monopaint;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.widget.TextView;

/** The "3 / 7" page counter. In a turned header it stacks the numbers upright. */
@SuppressLint("ViewConstructor")
final class PageLabel extends TextView {
    private final ControlHost host;
    PageLabel(Context context, ControlHost host) { super(context); this.host = host; }
    @Override protected void onDraw(Canvas canvas) {
        int turn = host.toolbarTurn();
        if (turn == 0) { super.onDraw(canvas); return; }
        canvas.save(); canvas.rotate(-turn, getWidth()/2f, getHeight()/2f);
        Paint paint = getPaint(); paint.setColor(Color.BLACK); paint.setTextAlign(Paint.Align.CENTER);
        String[] lines = getText().toString().split(" / ");
        float middle = getHeight()/2f - (paint.ascent()+paint.descent())/2f;
        float lineHeight = paint.getFontSpacing();
        canvas.drawText(lines[0], getWidth()/2f, middle-lineHeight, paint);
        canvas.drawText("/", getWidth()/2f, middle, paint);
        canvas.drawText(lines.length > 1 ? lines[1] : "", getWidth()/2f, middle+lineHeight, paint);
        paint.setTextAlign(Paint.Align.LEFT); canvas.restore();
    }
}
