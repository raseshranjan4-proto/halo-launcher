package dev.halo.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Frosted glass for Android 9 (no RenderEffect): draws a pre-blurred snapshot of the
 * screen, offset by the host view's on-screen position, so the panel shows the blurred
 * content that is really behind it — even while it slides — then adds a tint on top.
 */
final class GlassDrawable extends Drawable {
    private final Bitmap blurred;
    private final View host;
    private final int screenW, screenH, tint;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();
    private final int[] loc = new int[2];

    GlassDrawable(Bitmap blurred, View host, int screenW, int screenH, int tint) {
        this.blurred = blurred;
        this.host = host;
        this.screenW = screenW;
        this.screenH = screenH;
        this.tint = tint;
    }

    @Override
    public void draw(Canvas c) {
        // Use the resting layout position (not the animated one): the GPU records this
        // drawable once, so an in-flight slide position would get baked in.
        loc[0] = 0; loc[1] = 0;
        for (View v = host; v != null; ) {
            loc[0] += v.getLeft();
            loc[1] += v.getTop();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        c.save();
        c.translate(-loc[0], -loc[1]);
        dst.set(0, 0, screenW, screenH);
        c.drawBitmap(blurred, null, dst, paint);
        c.restore();
        c.drawColor(tint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
