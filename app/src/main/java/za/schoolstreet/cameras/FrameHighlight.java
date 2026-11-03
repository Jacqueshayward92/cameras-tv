package za.schoolstreet.cameras;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** Draws a red box around each camera that has just seen a person, so several can show at once. */
final class FrameHighlight extends View {

    private final long[] showUntil = new long[FrameGrid.SIDE * FrameGrid.SIDE];
    private final Paint paint = new Paint();
    private final Runnable refresh = this::invalidate;

    FrameHighlight(Context context, int color, float strokeWidth) {
        super(context);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
    }

    void show(int cell, long forMs) {
        if (cell < 0) {
            return;
        }
        showUntil[cell] = SystemClock.uptimeMillis() + forMs;
        invalidate();
        postDelayed(refresh, forMs + 50);
    }

    void clear() {
        for (int cell = 0; cell < showUntil.length; cell++) {
            showUntil[cell] = 0;
        }
        removeCallbacks(refresh);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float cellWidth = getWidth() / (float) FrameGrid.SIDE;
        float cellHeight = getHeight() / (float) FrameGrid.SIDE;
        float inset = paint.getStrokeWidth() / 2;
        for (int cell = 0; cell < showUntil.length; cell++) {
            if (showUntil[cell] > now) {
                float left = cell % FrameGrid.SIDE * cellWidth;
                float top = cell / FrameGrid.SIDE * cellHeight;
                canvas.drawRect(left + inset, top + inset, left + cellWidth - inset, top + cellHeight - inset, paint);
            }
        }
    }
}
