package dev.halo.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes the remote's Home button open Halo: when Amazon's home screen comes to the front,
 * Halo is asked for straight away.
 *
 * Fire OS holds back any app that asks to open within ~5 seconds of a Home press, and a
 * further Home press throws that held request away. So while Amazon's home stays in front
 * and Halo isn't showing, Halo keeps asking once a second until it gets through.
 *
 * Fire's full Settings menu lives inside Amazon's home app, so Halo can pause the
 * redirect briefly while it opens it.
 */
public class HomeRedirectService extends AccessibilityService {
    static final String AMAZON_HOME = "com.amazon.tv.launcher";
    private static final long RETRY_MS = 1000;

    /** The running service, so Halo can ask it to put the TV to sleep. */
    static volatile HomeRedirectService instance;
    /** Redirects are skipped until this uptime (set when Halo opens Amazon's Settings). */
    static volatile long pauseUntil;

    private final Handler main = new Handler(Looper.getMainLooper());
    /** True while Amazon's home screen is the front window. */
    private boolean amazonHomeInFront;

    private final Runnable retry = new Runnable() {
        @Override public void run() {
            if (!amazonHomeInFront || MainActivity.shown || paused()) return;
            openHalo();
            main.postDelayed(this, RETRY_MS);
        }
    };

    static void pauseFor(long ms) {
        pauseUntil = SystemClock.uptimeMillis() + ms;
    }

    private static boolean paused() {
        return SystemClock.uptimeMillis() < pauseUntil;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        main.removeCallbacks(retry);
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        CharSequence cls = e.getClassName();
        if (cls != null && cls.toString().startsWith("android.")) return;   // toasts, dialogs, layouts
        if (getPackageName().contentEquals(pkg)) { amazonHomeInFront = false; return; }

        boolean amazon = AMAZON_HOME.contentEquals(pkg);
        amazonHomeInFront = amazon && isHomeScreen(cls);
        main.removeCallbacks(retry);
        if (!amazon) return;   // another app is in front: leave it alone
        Log.i("HaloRedirect", cls + (paused() ? " (paused)" : ""));
        if (amazonHomeInFront && !paused()) retry.run();
    }

    private void openHalo() {
        Log.i("HaloRedirect", "open");
        Intent i = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    /** Turns the screen off the way Android's lock-screen action does. */
    boolean sleep() {
        if (Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) return true;
        return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }

    /** Amazon's launcher also shows search, settings, profiles etc.; only redirect its home screen. */
    static boolean isHomeScreen(CharSequence cls) {
        if (cls == null) return true;
        String l = cls.toString().toLowerCase();
        String[] skip = {"search", "setting", "profile", "dialog", "notification", "voice", "player", "oobe", "parental"};
        for (String s : skip) if (l.contains(s)) return false;
        return true;
    }

    @Override
    public void onInterrupt() { }
}
