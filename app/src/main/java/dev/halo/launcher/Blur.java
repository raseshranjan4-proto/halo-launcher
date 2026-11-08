package dev.halo.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

/**
 * Image helpers that run once per app, off the UI thread:
 *  - ambient(): a genuinely blurred (3-pass box blur) low-res glow for the background.
 *  - dominantColor(): the icon's main color, for clean gradient tiles.
 * Pure Java, so it works on every Fire OS version (RenderEffect needs Android 12).
 */
final class Blur {
    private Blur() {}

    static Drawable copy(Drawable d) {
        if (d == null) return null;
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable() : d;
    }

    /** Full-screen background glow: 96x54, heavily blurred. The ImageView scales it up smoothly. */
    static Bitmap ambient(Drawable d) {
        int w = 96, h = 54;
        Bitmap b = render(d, w, h, 0xFF15151A, true);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) boxBlur(px, w, h, 7);
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    /** Full-screen hero backdrop: the app's art, enlarged and softly blurred. */
    static Bitmap heroBackdrop(Drawable d) {
        Bitmap b = render(d, 256, 144, 0xFF101014, true);
        blurInPlace(b, 3, 2);
        return b;
    }

    /** Blur an opaque bitmap in place (used for the frosted-glass snapshot). */
    static void blurInPlace(Bitmap b, int radius, int passes) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < passes; i++) boxBlur(px, w, h, radius);
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** Main color of an icon, ignoring transparent, white and grey pixels where possible. */
    static int dominantColor(Drawable d) {
        int s = 32;
        Bitmap b = render(d, s, s, 0x00000000, false);
        int[] px = new int[s * s];
        b.getPixels(px, 0, s, 0, 0, s, s);
        b.recycle();
        // Bucket vivid pixels by hue and take the strongest bucket, so multi-colored
        // icons (e.g. red/yellow/green/blue) pick one clean color instead of mud.
        final int bins = 12;
        double[] wt = new double[bins], br = new double[bins], bg = new double[bins], bb = new double[bins];
        double nr = 0, ng = 0, nb = 0, nSum = 0;   // fallback: all opaque pixels
        float[] hsv = new float[3];
        for (int p : px) {
            if (Color.alpha(p) < 160) continue;
            int cr = Color.red(p), cg = Color.green(p), cb = Color.blue(p);
            nr += cr; ng += cg; nb += cb; nSum++;
            Color.colorToHSV(p, hsv);
            double wgt = hsv[1] * hsv[1] * hsv[2];   // favor vivid colors
            if (wgt < 0.08) continue;
            int bin = Math.min(bins - 1, (int) (hsv[0] / 360f * bins));
            wt[bin] += wgt; br[bin] += cr * wgt; bg[bin] += cg * wgt; bb[bin] += cb * wgt;
        }
        int best = 0;
        for (int i = 1; i < bins; i++) if (wt[i] > wt[best]) best = i;
        if (wt[best] > 0.5) {
            return Color.rgb((int) (br[best] / wt[best]), (int) (bg[best] / wt[best]), (int) (bb[best] / wt[best]));
        }
        if (nSum > 0) return Color.rgb((int) (nr / nSum), (int) (ng / nSum), (int) (nb / nSum));
        return 0xFF3A3A40;
    }

    /** Two-stop vertical gradient for an icon tile, tuned to look rich but not garish. */
    static int[] tileGradient(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] < 0.15f) {                       // greys / whites -> graphite
            return new int[]{0xFF4A4A52, 0xFF26262B};
        }
        float sat = Math.min(hsv[1], 0.78f);
        float[] top = {hsv[0], sat, Math.min(0.80f, Math.max(0.55f, hsv[2]))};
        float[] bot = {hsv[0], Math.min(1f, sat * 1.05f), top[2] * 0.55f};
        return new int[]{Color.HSVToColor(top), Color.HSVToColor(bot)};
    }

    private static Bitmap render(Drawable src, int w, int h, int base, boolean cover) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (base != 0) c.drawColor(base);
        Drawable d = copy(src);
        if (d != null) {
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) { iw = w; ih = h; }
            float s = cover ? Math.max(w / (float) iw, h / (float) ih) : Math.min(w / (float) iw, h / (float) ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            int l = (w - dw) / 2, t = (h - dh) / 2;
            d.setBounds(l, t, l + dw, t + dh);
            try { d.draw(c); } catch (Throwable ignored) { }
        }
        return b;
    }

    /** One horizontal + vertical box blur pass (opaque images only). */
    private static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        blurLine(px, tmp, w, h, r, true);
        blurLine(tmp, px, w, h, r, false);
    }

    private static void blurLine(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int win = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[idx(line, clamp(i, len), w, horizontal)];
                sr += (p >> 16) & 0xFF; sg += (p >> 8) & 0xFF; sb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[idx(line, i, w, horizontal)] = 0xFF000000 | ((sr / win) << 16) | ((sg / win) << 8) | (sb / win);
                int add = in[idx(line, clamp(i + r + 1, len), w, horizontal)];
                int rem = in[idx(line, clamp(i - r, len), w, horizontal)];
                sr += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
                sb += (add & 0xFF) - (rem & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) { return i < 0 ? 0 : (i >= len ? len - 1 : i); }

    private static int idx(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
