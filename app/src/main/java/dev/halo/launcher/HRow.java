package dev.halo.launcher;

import android.content.Context;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.widget.HorizontalScrollView;

/** A sideways row that never scrolls itself; MainActivity animates it with a spring. */
public class HRow extends HorizontalScrollView {
    public HRow(Context c) {
        super(c);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) { return 0; }

    @Override
    public boolean executeKeyEvent(KeyEvent event) { return false; }
}
