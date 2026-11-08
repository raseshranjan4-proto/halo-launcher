package dev.halo.launcher;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

/** One tile on the home screen. All heavy work (icons, blurs) is done once, off the UI thread. */
final class AppEntry {
    String key;          // package name, or a special key for built-in tiles
    String pkg;
    String label;
    Intent intent;
    Drawable banner;     // 16:9 TV banner, if the app has one
    Drawable icon;       // fallback square icon
    Bitmap ambient;      // soft glow used for the full-screen background
    int[] tileColors;    // gradient behind icon-only / odd-shaped tiles (from the art's main color)
    float bannerAspect;  // width / height of the banner, 0 if none
    Bitmap heroBg;       // softly blurred full-screen backdrop, made on demand
    boolean system;
    boolean special;
    boolean hidden;
}
