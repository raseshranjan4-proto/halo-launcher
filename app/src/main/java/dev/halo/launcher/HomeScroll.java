package dev.halo.launcher;

import android.content.Context;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.widget.ScrollView;

/**
 * A ScrollView that never scrolls by itself: MainActivity drives all scrolling with
 * its own eased animation, so movement stays smooth and predictable on D-pad.
 */
public class HomeScroll extends ScrollView {
    interface Listener { void onScrolled(int y); }

    private Listener listener;

    public HomeScroll(Context c) { super(c); }

    void setListener(Listener l) { listener = l; }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) { return 0; }

    @Override
    public boolean executeKeyEvent(KeyEvent event) { return false; }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (listener != null) listener.onScrolled(t);
    }
}
