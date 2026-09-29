package dev.linjian.peek;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** Unified, bounded activity event store. Local writes never depend on network availability. */
public final class ActivityEventStore {
    private static final String KEY_EVENTS = "activity_events_v1";
    private static final String KEY_LAST_PACKAGE = "activity_last_foreground_package_v1";
    private static final int MAX_EVENTS = 500;
    private static final long FOREGROUND_STABLE_MS = 4000L;
    private static final Object FOREGROUND_LOCK = new Object();
    private static final Handler FOREGROUND_HANDLER = new Handler(Looper.getMainLooper());
    private static String pendingForegroundPackage = "";
    private static Runnable pendingForegroundCommit;

    private ActivityEventStore() { }

    public static synchronized JSONObject add(Context ctx, JSONObject input, boolean upload) {
        JSONObject event = normalize(ctx, input);
        try {
            SharedPreferences p = AppPrefs.get(ctx);
            JSONArray old = new JSONArray(p.getString(KEY_EVENTS, "[]"));
            JSONArray kept = new JSONArray();
            kept.put(event);
            for (int i = 0; i < old.length() && kept.length() < MAX_EVENTS; i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && !event.optString("id").equals(item.optString("id"))) kept.put(item);
            }
            p.edit().putString(KEY_EVENTS, kept.toString()).apply();
        } catch (Exception ignored) { }
        if (upload) uploadAsync(ctx.getApplicationContext(), event);
        return event;
    }

    public static void recordForegroundChange(Context ctx, String packageName) {
        if (!AppPrefs.get(ctx).getBoolean(AppPrefs.KEY_JOURNEY_ENABLED, true)) return;
        final Context appCtx = ctx.getApplicationContext();
        final String pkg = packageName == null ? "" : packageName.trim();
        if (pkg.isEmpty() || isTransientSystemPackage(appCtx, pkg)) return;

        synchronized (FOREGROUND_LOCK) {
            SharedPreferences p = AppPrefs.get(appCtx);
            String previous = p.getString(KEY_LAST_PACKAGE, "");

            // Returning to the last stable app before the candidate settles means the
            // intermediate app was only a transient hop. Drop the whole hop.
            if (pkg.equals(previous)) {
                cancelPendingForegroundLocked();
                return;
            }

            if (pkg.equals(pendingForegroundPackage)) return;
            cancelPendingForegroundLocked();
            pendingForegroundPackage = pkg;
            pendingForegroundCommit = () -> commitForegroundChange(appCtx, pkg);
            FOREGROUND_HANDLER.postDelayed(pendingForegroundCommit, FOREGROUND_STABLE_MS);
        }
    }

    private static void commitForegroundChange(Context ctx, String pkg) {
        synchronized (FOREGROUND_LOCK) {
            if (!pkg.equals(pendingForegroundPackage)) return;
            pendingForegroundPackage = "";
            pendingForegroundCommit = null;
        }

        if (!AppPrefs.get(ctx).getBoolean(AppPrefs.KEY_JOURNEY_ENABLED, true)) return;
        if (isTransientSystemPackage(ctx, pkg)) return;

        SharedPreferences p = AppPrefs.get(ctx);
        String previous = p.getString(KEY_LAST_PACKAGE, "");
        if (pkg.equals(previous)) return;

        p.edit().putString(KEY_LAST_PACKAGE, pkg).apply();
        String app = appLabel(ctx, pkg);
        String previousLabel = previous.isEmpty() ? "" : appLabel(ctx, previous);
        String subtitle = previousLabel.isEmpty() ? "" : "离开 " + previousLabel;
        try {
            add(ctx, new JSONObject().put("source", "phone").put("type", "app_open")
                    .put("title", titleForPackage(ctx, pkg, app)).put("subtitle", subtitle)
                    .put("app_name", app).put("package_name", pkg).put("action", "foreground_changed")
                    .put("dedupe_seconds", 300)
                    .put("status", "completed").put("metadata_json", new JSONObject().put("previous_package", previous)), true);
        } catch (Exception ignored) { }
    }

    private static void cancelPendingForegroundLocked() {
        if (pendingForegroundCommit != null) {
            FOREGROUND_HANDLER.removeCallbacks(pendingForegroundCommit);
        }
        pendingForegroundCommit = null;
        pendingForegroundPackage = "";
    }

    private static boolean isTransientSystemPackage(Context ctx, String pkg) {
        String value = pkg == null ? "" : pkg.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return true;
        if (value.equals("com.android.systemui")
                || value.contains("inputmethod")
                || value.contains(".ime")
                || value.contains("keyboard")) {
            return true;
        }

        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            PackageManager pm = ctx.getPackageManager();
            for (ResolveInfo info : pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)) {
                if (info != null && info.activityInfo != null && pkg.equals(info.activityInfo.packageName)) {
                    return true;
                }
            }
        } catch (Exception ignored) { }

        return value.contains("launcher");
    }

    public static void recordPhone(Context ctx, String type, String title, String subtitle) {
        if (!AppPrefs.get(ctx).getBoolean(AppPrefs.KEY_JOURNEY_ENABLED, true)) return;
        try { add(ctx, new JSONObject().put("source", "phone").put("type", type).put("title", title).put("subtitle", subtitle).put("status", "completed"), true); }
        catch (Exception ignored) { }
    }

    public static JSONArray list(Context ctx, String source, int limit, boolean todayOnly) {
        JSONArray out = new JSONArray();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        try {
            JSONArray all = new JSONArray(AppPrefs.get(ctx).getString(KEY_EVENTS, "[]"));
            for (int i = 0; i < all.length() && out.length() < Math.max(1, limit); i++) {
                JSONObject e = all.optJSONObject(i);
                if (e == null) continue;
                if (source != null && !source.isEmpty() && !source.equals(e.optString("source"))) continue;
                if (todayOnly && !e.optString("local_date", "").equals(today)) continue;
                out.put(e);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static JSONArray todayJourney(Context ctx, int limit) {
        return listCategory(ctx, Math.max(1, limit), true, true);
    }

    public static JSONArray companionActions(Context ctx, int limit) {
        return listCategory(ctx, Math.max(1, limit), false, false);
    }

    private static JSONArray listCategory(Context ctx, int limit, boolean todayOnly, boolean phoneCategory) {
        JSONArray out = new JSONArray();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        try {
            JSONArray all = new JSONArray(AppPrefs.get(ctx).getString(KEY_EVENTS, "[]"));
            for (int i = 0; i < all.length() && out.length() < limit; i++) {
                JSONObject e = all.optJSONObject(i); if (e == null) continue;
                if (todayOnly && !today.equals(e.optString("local_date", ""))) continue;
                String source = e.optString("source", ""), type = e.optString("type", "");
                boolean matches = phoneCategory
                        ? ("phone".equals(source) || "app_open".equals(type) || "guidian_return".equals(type) || "screen_break_trigger".equals(type))
                        : ("companion".equals(source) || "assistant".equals(source) || "command".equals(type) || "notification".equals(type) || "weather".equals(type) || "calendar".equals(type) || "status_check".equals(type));
                if (matches) out.put(e);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static synchronized void mergeRemote(Context ctx, JSONArray remote) {
        if (remote == null) return;
        try {
            JSONArray local = new JSONArray(AppPrefs.get(ctx).getString(KEY_EVENTS, "[]"));
            java.util.ArrayList<JSONObject> items = new java.util.ArrayList<>();
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (int source = 0; source < 2; source++) {
                // Server copies are authoritative for command status updates; unsynced local phone events are appended afterwards.
                JSONArray arr = source == 0 ? remote : local;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i); if (e == null) continue;
                    hydrateLocalTime(e);
                    String id = e.optString("id", ""); if (id.isEmpty() || ids.add(id)) items.add(e);
                }
            }
            java.util.Collections.sort(items, (a, b) -> Long.compare(timeOf(b), timeOf(a)));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < items.size() && i < MAX_EVENTS; i++) kept.put(items.get(i));
            AppPrefs.get(ctx).edit().putString(KEY_EVENTS, kept.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static JSONObject normalize(Context ctx, JSONObject input) {
        JSONObject e = new JSONObject();
        long now = System.currentTimeMillis();
        try {
            e.put("id", clean(input.optString("id", ""), 100).isEmpty() ? UUID.randomUUID().toString() : clean(input.optString("id"), 100));
            e.put("device_id", clean(input.optString("device_id", AppPrefs.device(ctx)), 80));
            e.put("created_at", clean(input.optString("created_at", isoNow()), 40));
            e.put("created_at_ms", input.optLong("created_at_ms", now));
            e.put("local_date", new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(now)));
            e.put("local_time", new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(now)));
            for (String key : new String[]{"source", "type", "title", "subtitle", "app_name", "package_name", "action", "status"})
                e.put(key, clean(input.optString(key, key.equals("source") ? "phone" : (key.equals("status") ? "completed" : "")), key.equals("subtitle") ? 220 : 120));
            Object metadata = input.opt("metadata_json");
            e.put("metadata_json", metadata == null ? new JSONObject() : metadata);
        } catch (Exception ignored) { }
        return e;
    }

    private static void uploadAsync(Context ctx, JSONObject event) {
        String base = AppPrefs.server(ctx), token = AppPrefs.token(ctx);
        if (base == null || base.trim().isEmpty() || token == null || token.trim().isEmpty()) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base.replaceAll("/+$", "") + "/api/activity/events").openConnection();
                c.setRequestMethod("POST"); c.setConnectTimeout(7000); c.setReadTimeout(7000); c.setDoOutput(true);
                c.setRequestProperty("X-Auth-Token", token); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                JSONObject payload = new JSONObject(event.toString());
                if (event.has("dedupe_seconds")) payload.put("dedupe_seconds", event.optInt("dedupe_seconds", 0));
                try (OutputStream out = c.getOutputStream()) { out.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
                c.getResponseCode(); c.disconnect();
            } catch (Exception ignored) { }
        }, "activity-event-upload").start();
    }

    private static String appLabel(Context ctx, String pkg) {
        try { ApplicationInfo info = ctx.getPackageManager().getApplicationInfo(pkg, 0); return String.valueOf(ctx.getPackageManager().getApplicationLabel(info)); }
        catch (Exception ignored) { return pkg; }
    }
    private static String titleForPackage(Context ctx, String pkg, String app) {
        if (pkg.equals(ctx.getPackageName())) return "打开掌心窗";
        return "打开" + (app == null || app.isEmpty() ? "应用" : app);
    }
    private static void hydrateLocalTime(JSONObject e) {
        if (e == null || e.has("local_date")) return;
        long ms = timeOf(e); if (ms <= 0) ms = System.currentTimeMillis();
        try { e.put("created_at_ms", ms); e.put("local_date", new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(ms))); e.put("local_time", new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms))); }
        catch (Exception ignored) { }
    }
    private static long timeOf(JSONObject e) { long ms = e.optLong("created_at_ms", 0); if (ms > 0) return ms; try { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US); f.setTimeZone(java.util.TimeZone.getTimeZone("UTC")); Date d = f.parse(e.optString("created_at")); return d == null ? 0 : d.getTime(); } catch (Exception ignored) { return 0; } }
    private static String isoNow() { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US); f.setTimeZone(java.util.TimeZone.getTimeZone("UTC")); return f.format(new Date()); }
    private static String clean(String s, int max) { String v = s == null ? "" : s.trim(); return v.length() <= max ? v : v.substring(0, max); }
}
