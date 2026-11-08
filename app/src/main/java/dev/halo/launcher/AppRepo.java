package dev.halo.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds launchable apps and remembers the user's order / hidden apps. */
final class AppRepo {
    static final String SETTINGS_KEY = "halo:settings";

    private final Context ctx;
    private final PackageManager pm;
    private final SharedPreferences prefs;

    AppRepo(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.pm = ctx.getPackageManager();
        this.prefs = ctx.getSharedPreferences("halo", Context.MODE_PRIVATE);
    }

    /** Returns every app (hidden ones flagged), in display order. Call off the UI thread. */
    List<AppEntry> load() {
        Map<String, AppEntry> map = new LinkedHashMap<>();
        collect(map, Intent.CATEGORY_LEANBACK_LAUNCHER, true);   // TV apps first: they have banners
        collect(map, Intent.CATEGORY_LAUNCHER, false);
        AppEntry settings = settingsEntry();
        if (settings != null) map.put(settings.key, settings);

        List<AppEntry> all = new ArrayList<>(map.values());
        Collections.sort(all, (x, y) -> {
            if (x.special != y.special) return x.special ? 1 : -1;   // Settings last
            return x.label.compareToIgnoreCase(y.label);
        });

        Set<String> hidden = getHidden();
        for (AppEntry e : all) e.hidden = hidden.contains(e.key);
        return all;
    }

    private void collect(Map<String, AppEntry> map, String category, boolean leanback) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> list;
        try { list = pm.queryIntentActivities(q, 0); } catch (Exception e) { return; }
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null) continue;
            String pkg = ai.packageName;
            if (pkg.equals(ctx.getPackageName()) || map.containsKey(pkg)) continue;

            AppEntry e = new AppEntry();
            e.key = pkg;
            e.pkg = pkg;
            CharSequence l = ri.loadLabel(pm);
            e.label = l == null ? pkg : l.toString().trim();
            e.intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setComponent(new ComponentName(pkg, ai.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            e.system = (ai.applicationInfo.flags
                    & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if (leanback) {
                try { e.banner = ai.loadBanner(pm); } catch (Exception ignored) { }
            }
            e.icon = loadIcon(ri);
            finish(e);
            map.put(pkg, e);
        }
    }

    private AppEntry settingsEntry() {
        Intent si = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResolveInfo sr = pm.resolveActivity(si, 0);
        if (sr == null) return null;
        AppEntry e = new AppEntry();
        e.key = SETTINGS_KEY;
        e.pkg = sr.activityInfo.packageName;
        e.label = "Settings";
        e.intent = si;
        e.special = true;
        e.system = true;
        try { e.banner = sr.activityInfo.loadBanner(pm); } catch (Exception ignored) { }
        e.icon = loadIcon(sr);
        finish(e);
        return e;
    }

    private void finish(AppEntry e) {
        if (e.icon == null) e.icon = ctx.getDrawable(R.drawable.ic_launcher);
        e.tileColors = Blur.tileGradient(Blur.dominantColor(e.banner != null ? e.banner : e.icon));
        if (e.banner != null && e.banner.getIntrinsicHeight() > 0) {
            e.bannerAspect = e.banner.getIntrinsicWidth() / (float) e.banner.getIntrinsicHeight();
        }
    }

    /** Prefer the high-res icon so it stays crisp when the tile scales up. */
    private Drawable loadIcon(ResolveInfo ri) {
        try {
            int id = ri.getIconResource();
            if (id != 0) {
                Resources r = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                Drawable d = r.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) return d;
            }
        } catch (Exception ignored) { }
        try { return ri.loadIcon(pm); } catch (Exception e) { return null; }
    }

    // ---- persistence -------------------------------------------------------

    /** The user's Favorites, in order; null until they've changed it (then a default is used). */
    List<String> getFavorites() {
        String s = prefs.getString("favorites", null);
        if (s == null) return null;
        List<String> out = new ArrayList<>();
        for (String k : s.split("\n")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    void saveFavorites(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) sb.append(k).append('\n');
        prefs.edit().putString("favorites", sb.toString()).apply();
    }

    void markLaunched(String key) {
        prefs.edit().putLong("launched:" + key, System.currentTimeMillis()).apply();
    }

    long lastLaunched(String key) {
        return prefs.getLong("launched:" + key, 0L);
    }

    Set<String> getHidden() {
        return new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
    }

    void setHidden(String key, boolean hidden) {
        Set<String> s = getHidden();
        if (hidden) s.add(key); else s.remove(key);
        prefs.edit().putStringSet("hidden", s).apply();
    }

    void reset() {
        prefs.edit().remove("order").remove("favorites").remove("hidden").apply();
    }
}
