package dev.halo.launcher;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int DEFAULT_FAVORITES = 6;
    private static final float FOCUS_SCALE = 1.1f;
    private static final TimeInterpolator SPRING = new Spring(7f, 11f);      // focus: lively settle
    private static final TimeInterpolator SPRING_SOFT = new Spring(8f, 6f);  // scroll & panels: gentle
    private static final TimeInterpolator EASE = new DecelerateInterpolator(2f);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AppRepo repo;

    /** One titled, sideways-scrolling row. */
    private static final class Section {
        final String title;
        final List<AppEntry> items;
        View block;
        HRow row;
        ValueAnimator anim;
        Section(String title, List<AppEntry> items) { this.title = title; this.items = items; }
    }

    private List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> favorites = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private final Map<String, View> tileByKey = new HashMap<>();
    private final Map<String, TextView> subtitleByKey = new HashMap<>();

    private float dp;
    private int screenW, screenH, side, gap, tileW, tileH, labelSpace, radius, rowTop;
    private Typeface fRegular, fMedium, fSemi;

    private FrameLayout root, heroLayer;
    private ImageView backA, backB;
    private boolean backBShowing;
    private LinearLayout heroInfo;
    private FrameLayout heroLogo;
    private TextView heroTitle, heroSub;
    private LinearLayout dots;
    private HomeScroll scroll;
    private LinearLayout content;
    private ValueAnimator vAnim;
    private Runnable pendingShelf;
    private AppEntry shownHero;
    private Bitmap shownBg;
    private String focusedKey;
    private int focusedSection;
    private int lastDir;
    private boolean reloading, reloadAgain;

    private FrameLayout overlay;
    private View overlayPanel, overlayDim;
    private boolean overlayIsSidebar;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        repo = new AppRepo(this);
        fRegular = font("inter_regular", "sans-serif");
        fMedium = font("inter_medium", "sans-serif-medium");
        fSemi = font("inter_semibold", "sans-serif-medium");
        measure();
        setContentView(buildRoot());
        immersive();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        shown = true;
        immersive();
        refreshSubtitles();
        if (overlay == null && !tileByKey.isEmpty() && getCurrentFocus() == null) focusKey(focusedKey);
    }

    /** True while Halo is on screen, so the Home helper doesn't ask for it twice. */
    static volatile boolean shown;

    @Override
    protected void onPause() {
        shown = false;
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            closeOverlayNow();
            if (focusedSection > 0) focusFirst();
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            lastDir = code;
        }
        if (overlay != null) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down) closeOverlay();
                return true;
            }
            if (overlayIsSidebar && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) closeOverlay();
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        // A launcher never closes: Back returns to the top row, then opens the sidebar.
        if (focusedSection > 0) focusFirst();
        else openSidebar();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private Typeface font(String name, String fallback) {
        try { return Typeface.createFromAsset(getAssets(), "fonts/" + name + ".ttf"); }
        catch (Exception e) { return Typeface.create(fallback, Typeface.NORMAL); }
    }

    // ================================================================ layout

    private void measure() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        dp = dm.density;
        screenW = Math.max(dm.widthPixels, dm.heightPixels);
        screenH = Math.min(dm.widthPixels, dm.heightPixels);
        side = Math.round(screenW * 0.045f);
        gap = Math.round(screenW * 0.0135f);
        tileW = Math.round((screenW - side - 5 * gap) / 5.5f);   // 5 tiles + the 6th peeking in
        tileH = tileW * 9 / 16;
        labelSpace = Math.round(screenH * 0.08f);
        radius = Math.round(tileH * 0.065f);
        rowTop = Math.round(screenH * 0.655f);                   // first row's heading
    }

    private View buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // --- full-screen hero (fixed; fades and drifts up as you scroll down)
        heroLayer = new FrameLayout(this);
        backA = backdropView();
        backB = backdropView();
        backB.setAlpha(0f);
        heroLayer.addView(backA, match());
        heroLayer.addView(backB, match());
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE6000000, 0x99000000, 0x26000000, 0x00000000}));
        heroLayer.addView(left, new FrameLayout.LayoutParams(Math.round(screenW * 0.7f), ViewGroup.LayoutParams.MATCH_PARENT));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000, 0x33000000, 0xBF000000, 0xF2000000}));
        heroLayer.addView(bottom, match());

        heroInfo = new LinearLayout(this);
        heroInfo.setOrientation(LinearLayout.VERTICAL);
        heroLogo = new FrameLayout(this);
        rounded(heroLogo, Math.round(screenH * 0.014f));
        heroLogo.setElevation(16 * dp);
        int lw = Math.round(screenW * 0.2f);
        heroInfo.addView(heroLogo, new LinearLayout.LayoutParams(lw, lw * 9 / 16));
        heroTitle = new TextView(this);
        heroTitle.setTextColor(Color.WHITE);
        heroTitle.setTypeface(fSemi);
        heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.052f);
        heroTitle.setLetterSpacing(-0.015f);
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setShadowLayer(12 * dp, 0, 2 * dp, 0x99000000);
        heroTitle.setPadding(0, Math.round(screenH * 0.028f), 0, 0);
        heroInfo.addView(heroTitle);
        heroSub = new TextView(this);
        heroSub.setTextColor(0xD9FFFFFF);
        heroSub.setTypeface(fMedium);
        heroSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.025f);
        heroSub.setPadding(0, Math.round(screenH * 0.008f), 0, 0);
        heroInfo.addView(heroSub);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Math.round(screenW * 0.4f),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        ilp.leftMargin = side;
        ilp.topMargin = Math.round(screenH * 0.2f);
        heroLayer.addView(heroInfo, ilp);

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        int p = Math.round(4 * dp);
        dots.setPadding(p * 2, p, p * 2, p);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x2EFFFFFF);
        pill.setCornerRadius(20 * dp);
        dots.setBackground(pill);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        dlp.topMargin = rowTop - Math.round(screenH * 0.045f);
        heroLayer.addView(dots, dlp);
        root.addView(heroLayer, match());

        // --- rows (scroll vertically between sections; each row scrolls sideways)
        scroll = new HomeScroll(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setFocusable(false);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(0, rowTop, 0, Math.round(screenH * 0.7f));
        scroll.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setListener(y -> {
            float pr = Math.min(1f, y / (screenH * 0.55f));
            heroLayer.setAlpha(1f - 0.82f * pr);
            heroLayer.setTranslationY(-y * 0.25f);
            float info = Math.max(0f, 1f - pr * 1.8f);
            heroInfo.setAlpha(info);
            dots.setAlpha(info);
        });
        root.addView(scroll, match());
        return root;
    }

    private ImageView backdropView() {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return iv;
    }

    // ================================================================ data

    private void reload() {
        if (reloading) { reloadAgain = true; return; }
        reloading = true;
        io.execute(() -> {
            List<AppEntry> list = repo.load();
            ui.post(() -> {
                reloading = false;
                allApps = list;
                rebuild();
                if (reloadAgain) { reloadAgain = false; reload(); }
                prewarmBackdrops();
            });
        });
    }

    /** Make hero backdrops for Favorites in the background so the top row feels instant. */
    private void prewarmBackdrops() {
        final List<AppEntry> list = new ArrayList<>(favorites);
        io.execute(() -> {
            for (AppEntry a : list) {
                if (a.heroBg == null) {
                    Bitmap b = Blur.heroBackdrop(a.banner != null ? a.banner : a.icon);
                    ui.post(() -> { if (a.heroBg == null) a.heroBg = b; });
                }
            }
        });
    }

    private void rebuild() {
        List<AppEntry> visible = new ArrayList<>();
        for (AppEntry a : allApps) if (!a.hidden) visible.add(a);

        // Favorites: the user's list, or a default that puts your own apps ahead of Amazon's.
        favorites.clear();
        List<String> favKeys = repo.getFavorites();
        if (favKeys == null) {
            favKeys = new ArrayList<>();
            for (AppEntry a : visible) if (!a.special && !a.system && favKeys.size() < DEFAULT_FAVORITES) favKeys.add(a.key);
            for (AppEntry a : visible) if (!a.special && a.system && !isAmazonExtra(a) && favKeys.size() < DEFAULT_FAVORITES) favKeys.add(a.key);
        }
        for (String k : favKeys) for (AppEntry a : visible) if (a.key.equals(k)) { favorites.add(a); break; }

        List<AppEntry> mine = new ArrayList<>(), builtIn = new ArrayList<>();
        for (AppEntry a : visible) {
            if (favorites.contains(a)) continue;
            if (a.system) builtIn.add(a); else mine.add(a);
        }

        sections.clear();
        if (!favorites.isEmpty()) sections.add(new Section("Favorites", favorites));
        if (!mine.isEmpty()) sections.add(new Section("Your Apps", mine));
        if (!builtIn.isEmpty()) sections.add(new Section("Amazon & Built-in", builtIn));

        content.removeAllViews();
        tileByKey.clear();
        subtitleByKey.clear();
        for (int s = 0; s < sections.size(); s++) content.addView(buildSection(sections.get(s), s));
        if (overlay == null) focusKey(focusedKey);
    }

    /** Amazon's promotional extras that shouldn't be picked as default Favorites. */
    private static boolean isAmazonExtra(AppEntry a) {
        String p = a.pkg;
        return p.startsWith("com.amazon.") && !p.startsWith("com.amazon.avod")      // keep Prime Video
                || p.startsWith("com.amazon.venezia");                              // Appstore
    }

    private View buildSection(Section sec, int index) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setClipChildren(false);
        block.setClipToPadding(false);

        TextView h = new TextView(this);
        h.setText(sec.title);
        h.setTextColor(Color.WHITE);
        h.setTypeface(fSemi);
        h.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.03f);
        h.setPadding(side, 0, 0, Math.round(screenH * 0.017f));
        block.addView(h);

        HRow row = new HRow(this);
        row.setPadding(side, 0, side, 0);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        for (int i = 0; i < sec.items.size(); i++) {
            AppEntry a = sec.items.get(i);
            View t = makeTile(a, index, i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH + labelSpace);
            if (i > 0) lp.leftMargin = gap;
            strip.addView(t, lp);
            tileByKey.put(a.key, t);
        }
        row.addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, tileH + labelSpace));
        block.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileH + labelSpace));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = Math.round(screenH * 0.045f);
        block.setLayoutParams(blp);
        sec.block = block;
        sec.row = row;
        return block;
    }

    private void focusKey(String key) {
        View t = key == null ? null : tileByKey.get(key);
        if (t == null) { focusFirst(); return; }
        lastDir = 0;
        if (!t.requestFocus()) t.requestFocusFromTouch();
    }

    private void focusFirst() {
        if (sections.isEmpty()) return;
        View t = tileByKey.get(sections.get(0).items.get(0).key);
        if (t == null) return;
        lastDir = 0;
        if (!t.requestFocus()) t.requestFocusFromTouch();
    }

    private AppEntry findApp(String key) {
        for (AppEntry a : allApps) if (a.key.equals(key)) return a;
        return null;
    }

    // ================================================================ subtitles

    private String source(AppEntry a) {
        if (a.special) return "Fire TV";
        if (a.pkg.startsWith("com.amazon.")) return "Amazon";
        return a.system ? "Built-in" : "Installed";
    }

    private String recency(AppEntry a) {
        long t = repo.lastLaunched(a.key);
        if (t == 0) return null;
        long days = (System.currentTimeMillis() - t) / 86_400_000L;
        if (days <= 0) return "Opened today";
        if (days == 1) return "Opened yesterday";
        if (days < 7) return "Opened " + days + " days ago";
        return null;
    }

    private String tileSubtitle(AppEntry a) {
        String r = recency(a);
        return r != null ? r : source(a);
    }

    private String heroSubtitle(AppEntry a) {
        String r = recency(a);
        String s = source(a) + (a.special ? " · System settings" : " · App");
        return r != null ? s + " · " + r : s;
    }

    private void refreshSubtitles() {
        for (Map.Entry<String, TextView> e : subtitleByKey.entrySet()) {
            AppEntry a = findApp(e.getKey());
            if (a != null) e.getValue().setText(tileSubtitle(a));
        }
        if (shownHero != null) heroSub.setText(heroSubtitle(shownHero));
    }

    // ================================================================ tiles

    private View makeTile(AppEntry a, int sectionIdx, int col) {
        FrameLayout cell = new FrameLayout(this);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setLongClickable(true);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) cell.setDefaultFocusHighlightEnabled(false);

        FrameLayout card = new FrameLayout(this);
        rounded(card, radius);
        card.setElevation(3 * dp);
        card.setCameraDistance(9000 * dp);
        fillArt(card, a, tileH);

        View gloss = new View(this);
        gloss.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF}));
        gloss.setAlpha(0f);
        card.addView(gloss, match());

        View shine = new View(this);
        shine.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x47FFFFFF, 0x00FFFFFF}));
        shine.setRotation(18f);
        shine.setTranslationX(-tileW);
        card.addView(shine, new FrameLayout.LayoutParams(Math.round(tileW * 0.42f), Math.round(tileH * 1.9f),
                Gravity.CENTER_VERTICAL));
        cell.addView(card, new FrameLayout.LayoutParams(tileW, tileH, Gravity.TOP));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(a.label);
        name.setTextColor(0xE6FFFFFF);
        name.setTypeface(fMedium);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.022f);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        TextView sub = new TextView(this);
        sub.setText(tileSubtitle(a));
        sub.setTextColor(0x8CFFFFFF);
        sub.setTypeface(fMedium);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.019f);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(0, Math.round(screenH * 0.002f), 0, 0);
        labels.addView(sub);
        subtitleByKey.put(a.key, sub);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        llp.topMargin = tileH + Math.round(screenH * 0.013f);
        cell.addView(labels, llp);

        cell.setOnFocusChangeListener((v, has) -> {
            card.animate().cancel();
            shine.animate().cancel();
            if (has) {
                float rx = 0f, ry = 0f;   // arrive tilted from the direction we came from, then settle
                switch (lastDir) {
                    case KeyEvent.KEYCODE_DPAD_RIGHT: ry = -10f; break;
                    case KeyEvent.KEYCODE_DPAD_LEFT: ry = 10f; break;
                    case KeyEvent.KEYCODE_DPAD_DOWN: rx = 8f; break;
                    case KeyEvent.KEYCODE_DPAD_UP: rx = -8f; break;
                }
                card.setRotationX(rx);
                card.setRotationY(ry);
                card.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).rotationX(0f).rotationY(0f)
                        .translationZ(26 * dp).setDuration(560).setInterpolator(SPRING).start();
                gloss.animate().alpha(1f).setDuration(220).start();
                shine.setTranslationX(-tileW * 0.75f);
                shine.animate().translationX(tileW * 1.25f).setStartDelay(70).setDuration(760)
                        .setInterpolator(EASE).start();
                name.setTextColor(Color.WHITE);
                labels.animate().translationY(tileH * 0.06f).setDuration(420).setInterpolator(SPRING).start();
                onTileFocused(a, sectionIdx, col);
            } else {
                card.animate().scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f).translationZ(0f)
                        .setDuration(240).setInterpolator(EASE).start();
                gloss.animate().alpha(0f).setDuration(160).start();
                shine.setTranslationX(-tileW);
                name.setTextColor(0xE6FFFFFF);
                labels.animate().translationY(0f).setDuration(200).setInterpolator(EASE).start();
            }
        });
        cell.setOnClickListener(v -> launch(a, card));
        cell.setOnLongClickListener(v -> { showOptions(a); return true; });
        cell.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_MENU) {
                if (ev.getAction() == KeyEvent.ACTION_UP) showOptions(a);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && col == 0) {   // left edge opens the sidebar
                if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) openSidebar();
                return true;
            }
            return false;
        });
        return cell;
    }

    /** Banner full-bleed; odd-shaped banners and icons sit on a gradient from their own colors. */
    private void fillArt(FrameLayout card, AppEntry a, int h) {
        card.removeAllViews();
        boolean goodBanner = a.banner != null && Math.abs(a.bannerAspect - 16f / 9f) < 0.25f;
        if (goodBanner) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(Blur.copy(a.banner));
            card.addView(iv, match());
            return;
        }
        View bg = new View(this);
        bg.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, a.tileColors));
        card.addView(bg, match());
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (a.banner != null) {
            iv.setImageDrawable(Blur.copy(a.banner));
            int p = Math.round(h * 0.1f);
            iv.setPadding(p, p, p, p);
            card.addView(iv, match());
        } else {
            iv.setImageDrawable(Blur.copy(a.icon));
            int s = Math.round(h * 0.56f);
            card.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
    }

    private void onTileFocused(AppEntry a, int sectionIdx, int col) {
        focusedKey = a.key;
        if (sectionIdx != focusedSection || vAnim == null) scrollToSection(sectionIdx);
        focusedSection = sectionIdx;
        scrollRow(sections.get(sectionIdx), col);
        if (sectionIdx == 0) updateDots(col, Math.min(10, sections.get(0).items.size()));
        ui.removeCallbacks(pendingShelf);
        pendingShelf = () -> updateShelf(a);
        ui.postDelayed(pendingShelf, 110);   // don't thrash while the D-pad is held
    }

    private void updateShelf(AppEntry a) {
        if (a != shownHero) {
            shownHero = a;
            fillArt(heroLogo, a, Math.round(screenW * 0.2f * 9 / 16));
            heroTitle.setText(a.label);
            heroSub.setText(heroSubtitle(a));
            heroInfo.animate().cancel();
            heroInfo.setTranslationY(screenH * 0.015f);
            heroInfo.setScaleX(0.985f);
            heroInfo.setScaleY(0.985f);
            heroInfo.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(SPRING_SOFT).start();
        }
        if (a.heroBg == null) {
            io.execute(() -> {
                Bitmap b = Blur.heroBackdrop(a.banner != null ? a.banner : a.icon);
                ui.post(() -> {
                    if (a.heroBg == null) a.heroBg = b;
                    if (a == shownHero) showBackdrop(a.heroBg);
                });
            });
        } else {
            showBackdrop(a.heroBg);
        }
    }

    private void showBackdrop(Bitmap b) {
        if (b == null || b == shownBg) return;
        shownBg = b;
        backB.animate().cancel();
        ImageView incoming = backBShowing ? backA : backB;
        incoming.setImageBitmap(b);
        incoming.setScaleX(1.08f);   // slow "Ken Burns" drift
        incoming.setScaleY(1.08f);
        incoming.animate().scaleX(1f).scaleY(1f).setDuration(7000).setInterpolator(EASE).start();
        backB.animate().alpha(backBShowing ? 0f : 1f).setDuration(520).start();
        backBShowing = !backBShowing;
    }

    private void updateDots(int index, int count) {
        dots.removeAllViews();
        dots.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
        int d = Math.round(6 * dp), m = Math.round(3.5f * dp);
        for (int i = 0; i < count; i++) {
            View v = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(d);
            g.setColor(i == index ? Color.WHITE : 0x66FFFFFF);
            v.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(i == index ? d * 3 : d, d);
            lp.leftMargin = m;
            lp.rightMargin = m;
            dots.addView(v, lp);
        }
    }

    private void scrollToSection(int idx) {
        if (idx >= sections.size()) return;
        View block = sections.get(idx).block;
        if (block.getHeight() == 0) { content.post(() -> scrollToSection(idx)); return; }
        int target = idx == 0 ? 0 : Math.max(0, block.getTop() - Math.round(screenH * 0.11f));
        if (vAnim != null) vAnim.cancel();
        vAnim = ValueAnimator.ofInt(scroll.getScrollY(), target);
        vAnim.setDuration(600);
        vAnim.setInterpolator(SPRING_SOFT);
        vAnim.addUpdateListener(an -> scroll.scrollTo(0, (int) an.getAnimatedValue()));
        vAnim.start();
    }

    /** Keep the focused tile in view: it walks across the first 4 slots, then the row slides. */
    private void scrollRow(Section sec, int col) {
        HRow row = sec.row;
        int target = Math.max(0, col - 3) * (tileW + gap);
        if (row.getWidth() > 0 && row.getChildCount() > 0) {
            int max = Math.max(0, row.getChildAt(0).getWidth() + row.getPaddingLeft() + row.getPaddingRight() - row.getWidth());
            target = Math.min(target, max);
        }
        if (target == row.getScrollX()) return;
        if (sec.anim != null) sec.anim.cancel();
        sec.anim = ValueAnimator.ofInt(row.getScrollX(), target);
        sec.anim.setDuration(560);
        sec.anim.setInterpolator(SPRING_SOFT);
        sec.anim.addUpdateListener(an -> row.scrollTo((int) an.getAnimatedValue(), 0));
        sec.anim.start();
    }

    private void launch(AppEntry a, View from) {
        if (AppRepo.SETTINGS_KEY.equals(a.key)) { showSettings(); return; }
        try {
            Bundle opts = from == null ? null : ActivityOptions.makeScaleUpAnimation(
                    from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            startActivity(a.intent, opts);
            repo.markLaunched(a.key);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    // ================================================================ sidebar & panels

    private static final class Item {
        final String text;
        final Drawable icon;
        final boolean tintIcon, section, submenu;
        final Runnable act;

        Item(String text, Drawable icon, boolean tintIcon, boolean section, boolean submenu, Runnable act) {
            this.text = text; this.icon = icon; this.tintIcon = tintIcon;
            this.section = section; this.submenu = submenu; this.act = act;
        }
    }

    private Item action(String text, int iconRes, Runnable act) {
        return new Item(text, iconRes == 0 ? null : getDrawable(iconRes), true, false, false, act);
    }

    private Item submenu(String text, int iconRes, Runnable act) {
        return new Item(text, iconRes == 0 ? null : getDrawable(iconRes), true, false, true, act);
    }

    private Item section(String text) {
        return new Item(text, null, false, true, false, null);
    }

    private void openSidebar() {
        List<Item> items = new ArrayList<>();
        items.add(action("Home", R.drawable.ic_home, () -> { }));
        AppEntry settings = findApp(AppRepo.SETTINGS_KEY);
        if (settings != null) items.add(submenu("Settings", R.drawable.ic_settings, this::showSettings));
        items.add(action("Sleep", R.drawable.ic_sleep, this::sleepNow));
        Intent mirror = new Intent().setClassName("com.amazon.cast.sink",
                "com.amazon.cast.sink.DisplayMirroringSinkActivity");
        if (getPackageManager().resolveActivity(mirror, 0) != null) {
            items.add(action("Mirroring", R.drawable.ic_cast, () -> open(mirror)));
        }
        items.add(submenu("Hidden Apps", R.drawable.ic_eye, this::showHidden));
        items.add(action(redirectEnabled() ? "Home Button: Halo" : "Home Button", R.drawable.ic_open,
                this::setupHomeButton));
        items.add(action("Reset Layout", R.drawable.ic_refresh, () -> { repo.reset(); focusedKey = null; reload(); }));
        int n = 0;
        for (AppEntry a : favorites) {
            if (n == 0) items.add(section("Favorites"));
            items.add(new Item(a.label, Blur.copy(a.icon), false, false, false, () -> launch(a, null)));
            if (++n == 4) break;
        }
        showPanel(null, items, true);
    }

    private void showOptions(AppEntry a) {
        List<Item> items = new ArrayList<>();
        int fi = favorites.indexOf(a);
        if (fi >= 0) {
            if (fi > 0) items.add(action("Move Left", 0, () -> moveFavorite(a, fi - 1)));
            if (fi < favorites.size() - 1) items.add(action("Move Right", 0, () -> moveFavorite(a, fi + 1)));
            items.add(action("Remove from Favorites", 0, () -> setFavorite(a, false)));
        } else {
            items.add(action("Add to Favorites", 0, () -> setFavorite(a, true)));
        }
        items.add(action("Hide", 0, () -> hide(a)));
        if (!a.special) {
            items.add(action("App Info", 0, () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.pkg)))));
            if (!a.system) items.add(action("Uninstall", 0, () -> open(new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + a.pkg)))));
        }
        showPanel(a.label, items, false);
    }

    /**
     * Fire's main Settings menu lives inside Amazon's home app, so Halo opens the pages
     * Fire lets other apps open directly, plus the full menu with the Home redirect paused.
     */
    private void showSettings() {
        List<Item> items = new ArrayList<>();
        items.add(section("Settings"));
        String sp = "com.amazon.tv.settings.v2";
        String[][] pages = {
                {"Network", sp + ".tv.network.NetworkActivity"},
                {"Remotes & Bluetooth", sp + ".tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity"},
                {"Applications", sp + ".tv.applications.ApplicationsActivity"},
                {"My Fire TV", sp + ".tv.device.DeviceActivity"},
        };
        for (String[] pg : pages) {
            Intent i = new Intent().setClassName(sp, pg[1]);
            if (getPackageManager().resolveActivity(i, 0) != null) items.add(action(pg[0], 0, () -> open(i)));
        }
        AppEntry all = findApp(AppRepo.SETTINGS_KEY);
        if (all != null) {
            items.add(action("All Settings", 0, () -> {
                HomeRedirectService.pauseFor(4000);
                open(new Intent(all.intent));
            }));
        }
        showPanel(null, items, true);
    }

    private void showHidden() {
        List<Item> items = new ArrayList<>();
        items.add(section("Hidden Apps"));
        for (AppEntry e : allApps) {
            if (!e.hidden) continue;
            items.add(new Item(e.label, Blur.copy(e.icon), false, false, false, () -> {
                repo.setHidden(e.key, false);
                e.hidden = false;
                focusedKey = e.key;
                rebuild();
            }));
        }
        if (items.size() == 1) items.add(section("No hidden apps"));
        showPanel(null, items, true);
    }

    /** A floating frosted-glass panel: the left sidebar, or centered over the screen. */
    private void showPanel(String title, List<Item> items, boolean sidebar) {
        boolean replacing = overlay != null;
        closeOverlayNow();
        Bitmap glass = snapshot();

        FrameLayout o = new FrameLayout(this);
        o.setClickable(true);
        overlayDim = new View(this);
        overlayDim.setBackgroundColor(sidebar ? 0x4D000000 : 0x8C000000);
        o.addView(overlayDim, match());

        FrameLayout panel = new FrameLayout(this);
        float corner = screenH * 0.03f;
        rounded(panel, Math.round(corner));
        panel.setElevation(30 * dp);
        panel.setBackground(new GlassDrawable(glass, panel, screenW, screenH, 0x99202024));
        GradientDrawable rim = new GradientDrawable();
        rim.setColor(Color.TRANSPARENT);
        rim.setCornerRadius(corner);
        rim.setStroke(Math.max(1, Math.round(dp)), 0x24FFFFFF);
        panel.setForeground(rim);

        int pw = Math.round(screenW * (sidebar ? 0.175f : 0.25f));
        FrameLayout.LayoutParams plp;
        if (sidebar) {
            plp = new FrameLayout.LayoutParams(pw, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START);
            plp.leftMargin = Math.round(screenW * 0.026f);
            plp.topMargin = Math.round(screenH * 0.045f);
            plp.bottomMargin = Math.round(screenH * 0.045f);
        } else {
            plp = new FrameLayout.LayoutParams(pw, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(screenW * 0.006f);
        list.setPadding(pad, pad, pad, pad);

        if (sidebar) list.addView(sidebarHeader());
        if (title != null) {
            TextView t = smallText(title, 0x99FFFFFF);
            t.setPadding(pad * 2, pad, pad * 2, pad);
            list.addView(t);
        }
        View first = null;
        for (Item it : items) {
            if (it.section) {
                TextView s = smallText(it.text, 0x80FFFFFF);
                s.setPadding(pad * 2, Math.round(screenH * 0.024f), pad * 2, Math.round(screenH * 0.008f));
                list.addView(s);
                continue;
            }
            View row = panelRow(it);
            list.addView(row);
            if (first == null) first = row;
        }
        panel.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        o.addView(panel, plp);

        overlay = o;
        overlayPanel = panel;
        overlayIsSidebar = sidebar;
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.addView(o, match());

        overlayDim.setAlpha(replacing ? 1f : 0f);
        overlayDim.animate().alpha(1f).setDuration(220).start();
        if (sidebar) {
            panel.setTranslationX(replacing ? 0f : -(pw + plp.leftMargin + 40 * dp));
            panel.setAlpha(replacing ? 0f : 1f);
            panel.animate().translationX(0f).alpha(1f).setDuration(520).setInterpolator(SPRING_SOFT).start();
        } else {
            panel.setAlpha(0f);
            panel.setScaleX(0.92f);
            panel.setScaleY(0.92f);
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(SPRING_SOFT).start();
        }
        if (first != null) {
            final View f = first;
            f.post(f::requestFocus);
        }
    }

    private View sidebarHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(screenW * 0.006f);
        h.setPadding(pad, Math.round(screenH * 0.012f), pad * 2, Math.round(screenH * 0.045f));

        // Avatar circle with an initial
        TextView avatar = new TextView(this);
        avatar.setText("H");
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextColor(Color.WHITE);
        avatar.setTypeface(fSemi);
        avatar.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.024f);
        GradientDrawable av = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF6E7BFF, 0xFF3A2C8F});
        av.setShape(GradientDrawable.OVAL);
        avatar.setBackground(av);
        int s = Math.round(screenH * 0.048f);
        h.addView(avatar, new LinearLayout.LayoutParams(s, s));

        TextView name = new TextView(this);
        name.setText("Halo");
        name.setTextColor(Color.WHITE);
        name.setTypeface(fMedium);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.024f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = Math.round(screenW * 0.007f);
        h.addView(name, nlp);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("H:mm");
        clock.setTextColor(0x99FFFFFF);
        clock.setTypeface(fMedium);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.021f);
        h.addView(clock);
        return h;
    }

    private View panelRow(Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int ph = Math.round(screenW * 0.006f), pv = Math.round(screenH * 0.01f);
        row.setPadding(ph, pv, ph * 2, pv);
        row.setFocusable(true);
        row.setClickable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) row.setDefaultFocusHighlightEnabled(false);

        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(Color.WHITE);
        on.setCornerRadius(screenH * 0.016f);
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        row.setBackground(bg);

        ColorStateList fg = new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF111114, Color.WHITE});

        if (it.icon != null) {
            int s = Math.round(screenH * 0.04f);
            FrameLayout disc = new FrameLayout(this);
            disc.setDuplicateParentStateEnabled(true);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(it.icon);
            iv.setDuplicateParentStateEnabled(true);
            if (it.tintIcon) {
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(0x26FFFFFF);
                disc.setBackground(circle);
                iv.setImageTintList(fg);
                int is = Math.round(s * 0.55f);
                disc.addView(iv, new FrameLayout.LayoutParams(is, is, Gravity.CENTER));
            } else {
                rounded(disc, s / 2);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                disc.addView(iv, match());
            }
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(s, s);
            dlp.rightMargin = Math.round(screenW * 0.0075f);
            row.addView(disc, dlp);
        } else {
            row.setPadding(ph * 2, pv, ph * 2, pv);
        }

        TextView t = new TextView(this);
        t.setText(it.text);
        t.setTextColor(fg);
        t.setTypeface(fMedium);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.023f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setDuplicateParentStateEnabled(true);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> {
            if (!it.submenu) closeOverlay();
            it.act.run();
        });
        return row;
    }

    private TextView smallText(String s, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(fMedium);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, screenH * 0.02f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** A tiny, blurred copy of the current screen for the frosted-glass panels. */
    private Bitmap snapshot() {
        int sw = Math.max(1, screenW / 8), sh = Math.max(1, screenH / 8);
        Bitmap b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF0A0A0E);
        if (root.getWidth() > 0) {
            c.scale(sw / (float) root.getWidth(), sh / (float) root.getHeight());
            try { root.draw(c); } catch (Exception ignored) { }
        }
        Blur.blurInPlace(b, 4, 3);
        return b;
    }

    private void closeOverlay() {
        if (overlay == null) return;
        FrameLayout o = overlay;
        View p = overlayPanel, d = overlayDim;
        boolean sidebar = overlayIsSidebar;
        overlay = null;
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        d.animate().alpha(0f).setDuration(200).start();
        if (sidebar) {
            p.animate().translationX(-(p.getWidth() + screenW * 0.05f)).setDuration(260).setInterpolator(EASE)
                    .withEndAction(() -> root.removeView(o)).start();
        } else {
            p.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(180).setInterpolator(EASE)
                    .withEndAction(() -> root.removeView(o)).start();
        }
        focusKey(focusedKey);
    }

    private void closeOverlayNow() {
        if (overlay == null) return;
        root.removeView(overlay);
        overlay = null;
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }

    // ================================================================ actions

    private List<String> favoriteKeys() {
        List<String> keys = new ArrayList<>();
        for (AppEntry a : favorites) keys.add(a.key);
        return keys;
    }

    private void moveFavorite(AppEntry a, int to) {
        List<String> keys = favoriteKeys();
        keys.remove(a.key);
        keys.add(Math.max(0, Math.min(to, keys.size())), a.key);
        repo.saveFavorites(keys);
        focusedKey = a.key;
        rebuild();
    }

    private void setFavorite(AppEntry a, boolean fav) {
        List<String> keys = favoriteKeys();
        keys.remove(a.key);
        if (fav) keys.add(a.key);
        repo.saveFavorites(keys);
        focusedKey = a.key;
        rebuild();
        Toast.makeText(this, fav ? a.label + " added to Favorites" : a.label + " removed from Favorites",
                Toast.LENGTH_SHORT).show();
    }

    private void hide(AppEntry a) {
        repo.setHidden(a.key, true);
        a.hidden = true;
        if (a.key.equals(focusedKey)) focusedKey = null;
        rebuild();
        Toast.makeText(this, a.label + " hidden. Find it in the sidebar under Hidden Apps.", Toast.LENGTH_SHORT).show();
    }

    private boolean redirectEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    private void sleepNow() {
        HomeRedirectService s = HomeRedirectService.instance;
        if (s == null || !s.sleep()) {
            Toast.makeText(this, "Turn on the Halo Home button to use Sleep", Toast.LENGTH_LONG).show();
        }
    }

    private void setupHomeButton() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(this, "Turn on \"Halo Home button\"", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Enable it over ADB (see README)", Toast.LENGTH_LONG).show();
        }
    }

    private void open(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show(); }
    }

    // ================================================================ helpers

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static void rounded(View v, int r) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }
}
