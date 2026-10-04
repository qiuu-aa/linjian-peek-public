package dev.linjian.peek;

import android.app.KeyguardManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.PowerManager;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

/** Inexact persisted jobs plus confirmed foreground events; never stores an upload queue. */
public final class CasualRandomKnockScheduler {
    private static final String PLAN = "casual_random_plan_v1";
    private static final String LAST_IMPORTANT = "casual_random_last_important_sent_v1";
    private static final int JOB_ID = 3090101;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static String observed = "";
    private static long observedAt;
    private static long scheduledAt;
    private static Runnable stableCheck;
    private CasualRandomKnockScheduler() { }

    public static synchronized void initialize(Context ctx) { check(ctx); }
    public static synchronized void resetForeground() {
        observed = ""; observedAt = 0;
        if (stableCheck != null) HANDLER.removeCallbacks(stableCheck);
        stableCheck = null;
    }
    public static synchronized void onForeground(Context ctx) {
        String pkg = confirmedPackage();
        long now = System.currentTimeMillis();
        if (!pkg.equals(observed)) {
            observed = pkg; observedAt = now;
            if (stableCheck != null) HANDLER.removeCallbacks(stableCheck);
            Context app = ctx.getApplicationContext();
            stableCheck = () -> check(app);
            HANDLER.postDelayed(stableCheck, AppPrefs.foregroundStableMs(ctx));
        }
        check(ctx);
    }
    public static synchronized void settingsChanged(Context ctx, boolean enabled, int count) {
        SharedPreferences p = AppPrefs.get(ctx);
        if (enabled == p.getBoolean(AppPrefs.KEY_CASUAL_RANDOM_ENABLED, true)
                && CasualRandomKnockPolicy.clamp(count) == AppPrefs.casualRandomCount(ctx)) return;
        p.edit().putBoolean(AppPrefs.KEY_CASUAL_RANDOM_ENABLED, enabled)
                .putInt(AppPrefs.KEY_CASUAL_RANDOM_COUNT, CasualRandomKnockPolicy.clamp(count)).commit();
        CasualRandomKnockPolicy old = CasualRandomKnockPolicy.decode(p.getString(PLAN, ""));
        if (!enabled && old != null) { old.cancelPending(); p.edit().putString(PLAN, old.encode()).commit(); }
        else save(ctx, CasualRandomKnockPolicy.replan(old, System.currentTimeMillis(), enabled, count,
                p.getLong(LAST_IMPORTANT, 0), new Random()));
        log(ctx, enabled ? "随机敲敲：已按剩余时段重新安排" : "随机敲敲：已关闭，取消未发生计划", "settings");
        scheduledAt = 0;
        check(ctx);
    }
    public static boolean networkAvailable(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || cm.getActiveNetwork() == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception ignored) { return false; }
    }
    public static void recordSuccessfulEvent(Context ctx, JSONObject event) {
        if ("phone".equals(event.optString("source")))
            AppPrefs.get(ctx).edit().putLong(LAST_IMPORTANT, System.currentTimeMillis()).commit();
    }
    public static synchronized void check(Context ctx) {
        long now = System.currentTimeMillis();
        SharedPreferences p = AppPrefs.get(ctx);
        boolean enabled = p.getBoolean(AppPrefs.KEY_CASUAL_RANDOM_ENABLED, true);
        CasualRandomKnockPolicy old = CasualRandomKnockPolicy.decode(p.getString(PLAN, ""));
        if (old != null && old.day != CasualRandomKnockPolicy.dayStart(now)) {
            for (CasualRandomKnockPolicy.Target t : old.targets) if ("pending".equals(t.status))
                log(ctx, "随机敲敲：跨日放弃未发生目标", "abandoned");
        }
        CasualRandomKnockPolicy plan = CasualRandomKnockPolicy.ensure(old, now, enabled,
                AppPrefs.casualRandomCount(ctx), p.getLong(LAST_IMPORTANT, 0), new Random());
        if (plan != old) log(ctx, "随机敲敲：生成今日 " + plan.plannedCount() + " 个目标", "planned");
        if (!save(ctx, plan)) return;
        String pkg = confirmedPackage();
        PackageClassifier.Category category = PackageClassifier.classify(pkg, ctx.getPackageName());
        boolean safeApp = !pkg.isEmpty() && PackageClassifier.isMeaningful(category) && !PackageClassifier.isChatGpt(pkg);
        boolean stable = pkg.equals(observed) && observedAt > 0 && now - observedAt >= AppPrefs.foregroundStableMs(ctx);
        long next = plan.day + 24 * 60 * CasualRandomKnockPolicy.MINUTE + 630 * CasualRandomKnockPolicy.MINUTE;
        for (CasualRandomKnockPolicy.Target t : plan.targets) {
            CasualRandomKnockPolicy.Decision decision = plan.evaluate(t, now, enabled, interactive(ctx),
                    safeApp, stable, networkAvailable(ctx), p.getLong(LAST_IMPORTANT, 0));
            if (decision == CasualRandomKnockPolicy.Decision.ABANDON) {
                t.status = "abandoned"; save(ctx, plan);
                log(ctx, "随机敲敲：目标已超时，放弃且不补发", "abandoned");
            } else if (decision == CasualRandomKnockPolicy.Decision.FIRE) {
                if (!plan.claim(t, now) || !save(ctx, plan)) continue;
                try {
                    JSONObject metadata = new JSONObject().put("event_id", t.id)
                            .put("knock_index", plan.firedCount()).put("plan_total", plan.plannedCount())
                            .put("requested_count", plan.requested).put("planned_at", iso(t.at))
                            .put("planned_at_ms", t.at).put("actual_at", iso(now)).put("actual_at_ms", now)
                            .put("expires_at_ms", Math.min(t.at + CasualRandomKnockPolicy.GRACE, plan.day + 1350 * CasualRandomKnockPolicy.MINUTE))
                            .put("delay_minutes", (now - t.at) / CasualRandomKnockPolicy.MINUTE)
                            .put("package_category", category.name().toLowerCase(Locale.US));
                    boolean emitted = ActivityEventStore.emitCandidate(ctx, new JSONObject().put("id", t.id).put("source", "phone")
                            .put("type", "casual_random_candidate").put("action", "casual_random_knock")
                            .put("title", "日常随机敲敲").put("package_name", pkg).put("status", "completed")
                            .put("metadata_json", metadata), false);
                    if (emitted) log(ctx, "随机敲敲：触发今日第 " + plan.firedCount() + " 次（延迟 "
                            + ((now - t.at) / CasualRandomKnockPolicy.MINUTE) + " 分钟）", "triggered");
                    else log(ctx, "随机敲敲：冷却或发送条件变化，跳过且不补发", "skipped");
                } catch (Exception ignored) { log(ctx, "随机敲敲：发送失败，不建立重试队列", "skipped"); }
            } else if (decision == CasualRandomKnockPolicy.Decision.DEFER) {
                if (!t.id.equals(p.getString("casual_random_last_deferred", ""))) {
                    p.edit().putString("casual_random_last_deferred", t.id).apply();
                    log(ctx, "随机敲敲：当前条件不合适，等待有效前台活动", "skipped");
                }
            }
            if ("pending".equals(t.status) && enabled)
                next = Math.min(next, t.at > now ? t.at : Math.min(t.at + CasualRandomKnockPolicy.GRACE + 1000, plan.day + 1350 * CasualRandomKnockPolicy.MINUTE));
        }
        schedule(ctx, enabled ? Math.max(now + 1000, next) : 0);
    }
    private static boolean save(Context ctx, CasualRandomKnockPolicy plan) {
        if (plan.encode().equals(AppPrefs.get(ctx).getString(PLAN, ""))) return true;
        return AppPrefs.get(ctx).edit().putString(PLAN, plan.encode()).commit();
    }
    private static void schedule(Context ctx, long at) {
        try {
            JobScheduler jobs = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (jobs == null) return;
            if (at == 0) { jobs.cancel(JOB_ID); scheduledAt = 0; return; }
            if (at == scheduledAt) {
                // getPendingJob requires API 24; the public APK supports API 23.
                for (JobInfo pending : jobs.getAllPendingJobs()) if (pending.getId() == JOB_ID) return;
            }
            long delay = Math.max(1000, at - System.currentTimeMillis());
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, CasualRandomKnockJobService.class))
                    .setMinimumLatency(delay).setOverrideDeadline(delay + 15 * CasualRandomKnockPolicy.MINUTE)
                    .setPersisted(true).build();
            if (jobs.schedule(job) == JobScheduler.RESULT_SUCCESS) scheduledAt = at;
        } catch (Exception ignored) { DebugState.append(ctx, "随机敲敲：后台计划不可用，保留前台检查兜底"); }
    }
    static String confirmedPackage() {
        ScreenshotService service = ScreenshotService.getInstance();
        if (service == null) return "";
        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            return root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
        } catch (Exception ignored) { return ""; }
        finally { if (root != null) root.recycle(); }
    }
    private static boolean interactive(Context ctx) {
        try {
            PowerManager power = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            KeyguardManager keyguard = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
        } catch (Exception ignored) { return false; }
    }
    public static synchronized boolean canUploadNow(Context ctx, JSONObject event) {
        JSONObject metadata = event.optJSONObject("metadata_json");
        long now = System.currentTimeMillis();
        return metadata != null && now <= metadata.optLong("expires_at_ms", 0)
                && CasualRandomKnockPolicy.available(now)
                && AppPrefs.get(ctx).getBoolean(AppPrefs.KEY_CASUAL_RANDOM_ENABLED, true)
                && interactive(ctx) && networkAvailable(ctx)
                && now - AppPrefs.get(ctx).getLong(LAST_IMPORTANT, 0) >= CasualRandomKnockPolicy.GAP
                && event.optString("package_name").equals(observed)
                && observedAt > 0 && now - observedAt >= AppPrefs.foregroundStableMs(ctx)
                && event.optString("package_name").equals(confirmedPackage());
    }
    private static String iso(long ms) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        // Avoid the X pattern unsupported by older supported Android versions.
        return format.format(new Date(ms)) + "+08:00";
    }
    private static void log(Context ctx, String title, String action) {
        DebugState.append(ctx, title);
        try { ActivityEventStore.add(ctx, new JSONObject().put("source", "companion").put("type", "activity")
                .put("title", title).put("action", "casual_random_" + action).put("status", "completed")
                .put("metadata_json", new JSONObject().put("local_only", true)), false); }
        catch (Exception ignored) { }
    }
}
