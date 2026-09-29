package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Process-local timers and semantic candidate generation for foreground activity. */
public final class ForegroundEventCoordinator {
    private static final String KEY_LAST_ACTIVITY_AT = "event_last_meaningful_activity_at_v2";
    private static final String KEY_MORNING_DAY = "event_morning_return_day_v2";
    private static final String KEY_AFTERNOON_DAY = "event_afternoon_return_day_v2";
    private static final String KEY_LATE_NIGHT_ID = "event_late_night_id_v2";
    private static final String KEY_MEAL_ID = "event_meal_window_id_v2";

    private static final Object LOCK = new Object();
    private static final ForegroundEventModel MODEL = new ForegroundEventModel();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Set<String> SHOPPING_PACKAGES = new HashSet<>();
    private static Context appContext;
    private static int lifecycleGeneration = 0;
    private static Runnable stableRunnable;
    private static Runnable mergeRunnable;
    private static Runnable longSessionRunnable;
    private static Runnable shoppingRunnable;
    private static Runnable returnRunnable;
    private static Runnable lateNightRunnable;
    private static String activePackage = "";
    private static long activeSessionStartedAt = 0;
    private static boolean longSessionEmitted = false;
    private static long shoppingStartedAt = 0;
    private static boolean shoppingEmitted = false;
    private static String returnCandidatePackage = "";

    private ForegroundEventCoordinator() { }

    public static void onServiceConnected(Context ctx) {
        synchronized (LOCK) {
            appContext = ctx.getApplicationContext();
            lifecycleGeneration++;
            cancelAllCallbacks();
            MODEL.reset();
            activePackage = "";
            activeSessionStartedAt = 0;
            longSessionEmitted = false;
            clearShopping();
            DebugState.append(ctx, "event_policy lifecycle_reset pending_discarded");
        }
    }

    public static void onServiceDisconnected(Context ctx) {
        synchronized (LOCK) {
            lifecycleGeneration++;
            cancelAllCallbacks();
            MODEL.reset();
            activePackage = "";
            activeSessionStartedAt = 0;
            clearShopping();
            appContext = null;
        }
    }

    public static void onForegroundPackage(Context ctx, String packageName) {
        String pkg = packageName == null ? "" : packageName.trim();
        if (pkg.isEmpty()) return;
        PackageClassifier.Category category = PackageClassifier.classify(pkg, ctx.getPackageName());
        ActivityEventStore.recordForegroundTrace(ctx, pkg, category);
        if (!AppPrefs.get(ctx).getBoolean(AppPrefs.KEY_JOURNEY_ENABLED, true)) return;
        synchronized (LOCK) {
            if (appContext == null) appContext = ctx.getApplicationContext();
            if (!PackageClassifier.isMeaningful(category) || category == PackageClassifier.Category.SETTINGS) {
                if (!MODEL.pendingPackage().isEmpty()) { MODEL.cancelPending(); cancelStable(); }
                ActivityEventStore.logRule(ctx, PackageClassifier.ignoredRule(category), "app_open", pkg);
                return;
            }
            long now = System.currentTimeMillis();
            if (pkg.equals(MODEL.currentMeaningful()) && MODEL.pendingPackage().isEmpty()) {
                SharedPreferences prefs = AppPrefs.get(ctx);
                long previousActivityAt = prefs.getLong(KEY_LAST_ACTIVITY_AT, 0);
                prefs.edit().putLong(KEY_LAST_ACTIVITY_AT, now).apply();
                scheduleReturnCandidate(ctx, pkg, previousActivityAt, now, lifecycleGeneration);
                maybeEmitMealCandidate(ctx, pkg, now);
                maybeEmitLongSession(ctx, pkg, now);
                maybeEmitShopping(ctx, now);
                maybeEmitLateNight(ctx, pkg, now);
                return;
            }
            ForegroundEventModel.Decision decision = MODEL.observe(pkg, true);
            if (decision.kind == ForegroundEventModel.Kind.CANCELLED_RETURN) {
                cancelStable();
                SharedPreferences prefs = AppPrefs.get(ctx);
                long previousActivityAt = prefs.getLong(KEY_LAST_ACTIVITY_AT, 0);
                prefs.edit().putLong(KEY_LAST_ACTIVITY_AT, now).apply();
                scheduleReturnCandidate(ctx, pkg, previousActivityAt, now, lifecycleGeneration);
                maybeEmitMealCandidate(ctx, pkg, now);
                ActivityEventStore.logRule(ctx, "cancelled_return_to_origin", "app_open", pkg);
                return;
            }
            if (decision.kind != ForegroundEventModel.Kind.SCHEDULE_STABLE) return;
            cancelStable();
            final int generation = lifecycleGeneration;
            final long token = decision.token;
            stableRunnable = () -> confirmStable(generation, token, pkg);
            HANDLER.postDelayed(stableRunnable, AppPrefs.foregroundStableMs(ctx));
        }
    }

    private static void confirmStable(int generation, long token, String pkg) {
        synchronized (LOCK) {
            if (appContext == null || generation != lifecycleGeneration) return;
            long now = System.currentTimeMillis();
            ForegroundEventModel.Decision decision = MODEL.confirmStable(token, pkg, now);
            stableRunnable = null;
            if (decision.kind != ForegroundEventModel.Kind.BASELINE
                    && decision.kind != ForegroundEventModel.Kind.STABLE_TRANSITION) return;
            onMeaningfulStable(appContext, pkg, now);
            if (decision.kind == ForegroundEventModel.Kind.STABLE_TRANSITION) scheduleMergeFlush(appContext, generation);
        }
    }

    private static void scheduleMergeFlush(Context ctx, int generation) {
        if (mergeRunnable != null) HANDLER.removeCallbacks(mergeRunnable);
        mergeRunnable = () -> flushMergeWindow(generation);
        HANDLER.postDelayed(mergeRunnable, AppPrefs.foregroundMergeMs(ctx));
    }

    private static void flushMergeWindow(int generation) {
        synchronized (LOCK) {
            if (appContext == null || generation != lifecycleGeneration) return;
            long now = System.currentTimeMillis();
            ForegroundEventModel.Decision decision = MODEL.flush(now, AppPrefs.foregroundMergeMs(appContext));
            if (decision.kind == ForegroundEventModel.Kind.NONE) {
                scheduleMergeFlush(appContext, generation);
                return;
            }
            mergeRunnable = null;
            if (decision.kind == ForegroundEventModel.Kind.MERGED_REPEAT) {
                ActivityEventStore.logRule(appContext, "merged_repeat_switches", "app_open", decision.toPackage);
                return;
            }
            if (PackageClassifier.isChatGpt(decision.toPackage)) {
                ActivityEventStore.logRule(appContext, "suppressed_chatgpt_open", "app_open", decision.toPackage);
                return;
            }
            try {
                JSONObject metadata = new JSONObject()
                        .put("aggregated", true)
                        .put("from_package", decision.fromPackage)
                        .put("to_package", decision.toPackage)
                        .put("transition_count", decision.transitionCount)
                        .put("window_seconds", Math.max(1, decision.windowMs / 1000L));
                ActivityEventStore.emitCandidate(appContext, new JSONObject()
                        .put("source", "phone").put("type", "app_open")
                        .put("title", "前台应用切换摘要").put("package_name", decision.toPackage)
                        .put("action", "foreground_changed").put("status", "completed")
                        .put("metadata_json", metadata), false);
            } catch (Exception ignored) { }
        }
    }

    private static void onMeaningfulStable(Context ctx, String pkg, long now) {
        SharedPreferences prefs = AppPrefs.get(ctx);
        long previousActivityAt = prefs.getLong(KEY_LAST_ACTIVITY_AT, 0);
        prefs.edit().putLong(KEY_LAST_ACTIVITY_AT, now).apply();
        activePackage = pkg;
        activeSessionStartedAt = now;
        longSessionEmitted = false;
        scheduleLongSession(ctx, pkg, lifecycleGeneration);
        updateShoppingSession(ctx, pkg, now, lifecycleGeneration);
        scheduleReturnCandidate(ctx, pkg, previousActivityAt, now, lifecycleGeneration);
        scheduleLateNightCandidate(ctx, pkg, now, lifecycleGeneration);
        maybeEmitMealCandidate(ctx, pkg, now);
    }

    private static void scheduleLongSession(Context ctx, String pkg, int generation) {
        if (longSessionRunnable != null) HANDLER.removeCallbacks(longSessionRunnable);
        longSessionRunnable = () -> {
            synchronized (LOCK) {
                if (appContext == null || generation != lifecycleGeneration) return;
                maybeEmitLongSession(appContext, pkg, System.currentTimeMillis());
            }
        };
        HANDLER.postDelayed(longSessionRunnable, AppPrefs.longAppSessionMs(ctx));
    }

    private static void updateShoppingSession(Context ctx, String pkg, long now, int generation) {
        PackageClassifier.Category category = PackageClassifier.classify(pkg, ctx.getPackageName());
        if (category != PackageClassifier.Category.SHOPPING_TAKEOUT) {
            clearShopping();
            return;
        }
        SHOPPING_PACKAGES.add(pkg);
        if (shoppingStartedAt > 0) { maybeEmitShopping(ctx, now); return; }
        shoppingStartedAt = now;
        shoppingEmitted = false;
        shoppingRunnable = () -> {
            synchronized (LOCK) {
                if (appContext == null || generation != lifecycleGeneration) return;
                maybeEmitShopping(appContext, System.currentTimeMillis());
            }
        };
        HANDLER.postDelayed(shoppingRunnable, AppPrefs.shoppingMinimumMs(ctx));
    }

    private static void scheduleReturnCandidate(Context ctx, String pkg, long previousAt, long now, int generation) {
        if (returnRunnable != null && pkg.equals(returnCandidatePackage)) return;
        if (returnRunnable != null) HANDLER.removeCallbacks(returnRunnable);
        returnRunnable = null;
        returnCandidatePackage = "";
        if (previousAt <= 0) return;
        int minute = minuteOfDay(now);
        String type = CandidateTimePolicy.returnCandidateType(minute, now - previousAt,
                AppPrefs.morningInactiveMs(ctx), AppPrefs.afternoonInactiveMs(ctx));
        if (type.isEmpty()) return;
        String day = dayId(now);
        String dayKey = "morning_return_candidate".equals(type) ? KEY_MORNING_DAY : KEY_AFTERNOON_DAY;
        if (day.equals(AppPrefs.get(ctx).getString(dayKey, ""))) return;
        scheduleReturnEmission(pkg, type, dayKey, day, Math.max(1, (now - previousAt) / 1000L), generation,
                EventPolicyConfig.minutes(EventPolicyConfig.RETURN_SUSTAINED_MINUTES));
    }

    private static void scheduleLateNightCandidate(Context ctx, String pkg, long now, int generation) {
        if (lateNightRunnable != null) HANDLER.removeCallbacks(lateNightRunnable);
        long delay = lateNightDelay(now);
        lateNightRunnable = () -> {
            synchronized (LOCK) {
                if (appContext == null || generation != lifecycleGeneration || !pkg.equals(activePackage)) return;
                maybeEmitLateNight(appContext, pkg, System.currentTimeMillis());
            }
        };
        HANDLER.postDelayed(lateNightRunnable, delay);
    }

    private static void maybeEmitMealCandidate(Context ctx, String pkg, long now) {
        int minute = minuteOfDay(now);
        String meal = CandidateTimePolicy.mealWindow(minute);
        if (meal.isEmpty()) return;
        String id = dayId(now) + ":" + meal;
        if (id.equals(AppPrefs.get(ctx).getString(KEY_MEAL_ID, ""))) return;
        try {
            boolean emitted = ActivityEventStore.emitCandidate(ctx, new JSONObject()
                    .put("source", "phone").put("type", "meal_window_candidate")
                    .put("title", "饭点窗口内出现有效手机活动").put("package_name", pkg)
                    .put("action", "next_meaningful_activity").put("status", "completed")
                    .put("metadata_json", new JSONObject().put("meal_window", meal)), false);
            if (emitted) AppPrefs.get(ctx).edit().putString(KEY_MEAL_ID, id).apply();
        } catch (Exception ignored) { }
    }

    /** Travel is never inferred from an app open; callers must provide an explicit trusted signal. */
    public static void recordTrustedTravelSignal(Context ctx, String packageName, String signalType, String correlationId) {
        String signal = signalType == null ? "" : signalType.trim();
        if (PackageClassifier.isSharedBike(packageName)) return;
        if (!("rail_ticket_confirmed".equals(signal) || "metro_trip_active".equals(signal)
                || "itinerary_confirmed".equals(signal) || "ride_in_progress".equals(signal))) return;
        try {
            ActivityEventStore.emitCandidate(ctx, new JSONObject()
                    .put("source", "phone").put("type", "travel_candidate")
                    .put("title", "检测到可信行程线索").put("package_name", packageName)
                    .put("action", signal).put("status", "completed")
                    .put("metadata_json", new JSONObject().put("signal_type", signal)
                            .put("correlation_id", correlationId == null ? "" : correlationId)), false);
        } catch (Exception ignored) { }
    }

    private static void cancelStable() { if (stableRunnable != null) { HANDLER.removeCallbacks(stableRunnable); stableRunnable = null; } }
    private static void maybeEmitLongSession(Context ctx, String pkg, long now) {
        if (longSessionEmitted || !pkg.equals(activePackage) || activeSessionStartedAt <= 0
                || now - activeSessionStartedAt < AppPrefs.longAppSessionMs(ctx)) return;
        try {
            longSessionEmitted = ActivityEventStore.emitCandidate(ctx, new JSONObject()
                    .put("source", "phone").put("type", "long_app_session_candidate")
                    .put("title", "同一应用持续使用").put("package_name", pkg)
                    .put("action", "session_threshold_crossed").put("status", "completed")
                    .put("metadata_json", new JSONObject().put("session_seconds",
                            Math.max(1, (now - activeSessionStartedAt) / 1000L))), false);
        } catch (Exception ignored) { }
    }
    private static void maybeEmitShopping(Context ctx, long now) {
        if (shoppingEmitted || shoppingStartedAt <= 0 || now - shoppingStartedAt < AppPrefs.shoppingMinimumMs(ctx)
                || PackageClassifier.classify(activePackage, ctx.getPackageName()) != PackageClassifier.Category.SHOPPING_TAKEOUT) return;
        try {
            shoppingEmitted = ActivityEventStore.emitCandidate(ctx, new JSONObject()
                    .put("source", "phone").put("type", "shopping_or_takeout_candidate")
                    .put("title", "购物或外卖应用稳定使用").put("package_name", activePackage)
                    .put("action", "category_session_threshold_crossed").put("status", "completed")
                    .put("metadata_json", new JSONObject().put("package_count", SHOPPING_PACKAGES.size())
                            .put("session_seconds", Math.max(1, (now - shoppingStartedAt) / 1000L))), false);
        } catch (Exception ignored) { }
    }
    private static void maybeEmitLateNight(Context ctx, String pkg, long now) {
        int minute = minuteOfDay(now);
        if (!pkg.equals(activePackage) || !CandidateTimePolicy.isLateNight(minute)) return;
        String night = nightId(now);
        if (night.equals(AppPrefs.get(ctx).getString(KEY_LATE_NIGHT_ID, ""))) return;
        long windowStart = lateNightWindowStart(now);
        long thresholdAt = Math.max(activeSessionStartedAt, windowStart)
                + EventPolicyConfig.minutes(EventPolicyConfig.LATE_NIGHT_SUSTAINED_MINUTES);
        if (now < thresholdAt) return;
        try {
            boolean emitted = ActivityEventStore.emitCandidate(ctx, new JSONObject()
                    .put("source", "phone").put("type", "late_night_active_candidate")
                    .put("title", "深夜时段仍持续使用").put("package_name", pkg)
                    .put("action", "late_night_sustained_activity").put("status", "completed")
                    .put("metadata_json", new JSONObject().put("sustained_seconds",
                            EventPolicyConfig.LATE_NIGHT_SUSTAINED_MINUTES * 60)), false);
            if (emitted) AppPrefs.get(ctx).edit().putString(KEY_LATE_NIGHT_ID, night).apply();
        } catch (Exception ignored) { }
    }
    private static void scheduleReturnEmission(String pkg, String type, String dayKey, String day,
                                               long inactiveSeconds, int generation, long delayMs) {
        returnCandidatePackage = pkg;
        returnRunnable = () -> {
            synchronized (LOCK) {
                returnRunnable = null;
                returnCandidatePackage = "";
                if (appContext == null || generation != lifecycleGeneration || !pkg.equals(activePackage)) return;
                try {
                    boolean emitted = ActivityEventStore.emitCandidate(appContext, new JSONObject()
                            .put("source", "phone").put("type", type)
                            .put("title", "长时间无活动后重新活跃").put("package_name", pkg)
                            .put("action", "sustained_activity_resumed").put("status", "completed")
                            .put("metadata_json", new JSONObject().put("inactive_seconds", inactiveSeconds)), false);
                    if (emitted) AppPrefs.get(appContext).edit().putString(dayKey, day).apply();
                    else if (type.equals(CandidateTimePolicy.returnCandidateType(minuteOfDay(System.currentTimeMillis()),
                            EventPolicyConfig.minutes(24 * 60), AppPrefs.morningInactiveMs(appContext),
                            AppPrefs.afternoonInactiveMs(appContext)))) {
                        scheduleReturnEmission(pkg, type, dayKey, day, inactiveSeconds, generation,
                                Math.max(60_000L, AppPrefs.globalCandidateCooldownMs(appContext)));
                    }
                } catch (Exception ignored) { }
            }
        };
        HANDLER.postDelayed(returnRunnable, delayMs);
    }
    private static void cancelAllCallbacks() {
        cancelStable();
        if (mergeRunnable != null) HANDLER.removeCallbacks(mergeRunnable);
        if (longSessionRunnable != null) HANDLER.removeCallbacks(longSessionRunnable);
        if (shoppingRunnable != null) HANDLER.removeCallbacks(shoppingRunnable);
        if (returnRunnable != null) HANDLER.removeCallbacks(returnRunnable);
        if (lateNightRunnable != null) HANDLER.removeCallbacks(lateNightRunnable);
        mergeRunnable = null; longSessionRunnable = null; shoppingRunnable = null; returnRunnable = null; lateNightRunnable = null;
        returnCandidatePackage = "";
    }
    private static void clearShopping() {
        if (shoppingRunnable != null) HANDLER.removeCallbacks(shoppingRunnable);
        shoppingRunnable = null; shoppingStartedAt = 0; shoppingEmitted = false; SHOPPING_PACKAGES.clear();
    }
    private static Calendar shanghai(long ms) { Calendar c = Calendar.getInstance(TimeZone.getTimeZone(EventPolicyConfig.TIME_ZONE), Locale.CHINA); c.setTimeInMillis(ms); return c; }
    private static int minuteOfDay(long ms) { Calendar c = shanghai(ms); return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE); }
    private static String dayId(long ms) { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA); f.setTimeZone(TimeZone.getTimeZone(EventPolicyConfig.TIME_ZONE)); return f.format(new Date(ms)); }
    private static String nightId(long ms) { Calendar c = shanghai(ms); if (c.get(Calendar.HOUR_OF_DAY) < 3) c.add(Calendar.DAY_OF_MONTH, -1); return dayId(c.getTimeInMillis()); }
    private static long lateNightDelay(long now) {
        int minute = minuteOfDay(now);
        long sustained = EventPolicyConfig.minutes(EventPolicyConfig.LATE_NIGHT_SUSTAINED_MINUTES);
        if (CandidateTimePolicy.isLateNight(minute)) return sustained;
        Calendar target = shanghai(now);
        target.set(Calendar.HOUR_OF_DAY, EventPolicyConfig.LATE_NIGHT_START_MINUTE / 60);
        target.set(Calendar.MINUTE, EventPolicyConfig.LATE_NIGHT_START_MINUTE % 60);
        target.set(Calendar.SECOND, 0); target.set(Calendar.MILLISECOND, 0);
        if (target.getTimeInMillis() <= now) target.add(Calendar.DAY_OF_MONTH, 1);
        return Math.max(1000L, target.getTimeInMillis() - now + sustained);
    }
    private static long lateNightWindowStart(long now) {
        Calendar start = shanghai(now);
        if (start.get(Calendar.HOUR_OF_DAY) < EventPolicyConfig.LATE_NIGHT_END_MINUTE / 60) start.add(Calendar.DAY_OF_MONTH, -1);
        start.set(Calendar.HOUR_OF_DAY, EventPolicyConfig.LATE_NIGHT_START_MINUTE / 60);
        start.set(Calendar.MINUTE, EventPolicyConfig.LATE_NIGHT_START_MINUTE % 60);
        start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
        return start.getTimeInMillis();
    }
}
