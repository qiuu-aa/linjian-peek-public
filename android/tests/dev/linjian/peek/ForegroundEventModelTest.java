package dev.linjian.peek;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class ForegroundEventModelTest {
    private static final long FIVE_MINUTES = 5 * 60_000L;

    public static void main(String[] args) {
        helperClassification();
        returnBeforeStableCancels();
        launcherRoundTripsNeverLeaveWechat();
        systemNavigationRoundTripNeverLeavesChrome();
        repeatedSwitchesMergeToZero();
        businessTransitionProducesOneSummary();
        quickShoppingOpenCancelsBeforeStable();
        helperUiDoesNotResetMeaningfulSession();
        onlyNewestPendingCandidateCanConfirm();
        resetDropsPendingCandidate();
        chatGptHelperRoundTripStaysOnChatGpt();
        semanticTimeWindows();
        lateNightFollowupStages();
        chatGptForegroundReceivesEveryLateNightStage();
        appSwitchesPreserveLateNightSessionAndStage();
        lateNightSessionResetsOnlyAtStopBoundaries();
        System.out.println("ForegroundEventModelTest: all scenarios passed");
    }

    private static void helperClassification() {
        check(PackageClassifier.classify("com.android.launcher3", "dev.linjian.peek") == PackageClassifier.Category.SYSTEM_UI, "launcher");
        check(PackageClassifier.classify("com.android.systemui", "dev.linjian.peek") == PackageClassifier.Category.SYSTEM_UI, "system ui");
        check(PackageClassifier.classify("com.google.android.photopicker", "dev.linjian.peek") == PackageClassifier.Category.HELPER_UI, "photo picker");
        check(PackageClassifier.classify("com.android.settings", "dev.linjian.peek") == PackageClassifier.Category.SETTINGS, "settings");
        check(PackageClassifier.classify("com.openai.chatgpt", "dev.linjian.peek") == PackageClassifier.Category.CHATGPT, "chatgpt");
        check(PackageClassifier.classify("com.sankuai.meituan", "dev.linjian.peek") == PackageClassifier.Category.SHOPPING_TAKEOUT, "shopping");
        check(PackageClassifier.isSharedBike("com.hellobike"), "shared bike excluded");
    }

    private static void returnBeforeStableCancels() {
        ForegroundEventModel model = baseline("com.tencent.mm");
        ForegroundEventModel.Decision pending = model.observe("com.android.chrome", true);
        check(pending.kind == ForegroundEventModel.Kind.SCHEDULE_STABLE, "schedule chrome");
        check(model.observe("com.tencent.mm", true).kind == ForegroundEventModel.Kind.CANCELLED_RETURN, "return cancellation");
        check(model.confirmStable(pending.token, "com.android.chrome", 5000).kind == ForegroundEventModel.Kind.NONE, "old timer cancelled");
    }

    private static void launcherRoundTripsNeverLeaveWechat() {
        ForegroundEventModel model = baseline("com.tencent.mm");
        for (int i = 0; i < 3; i++) {
            check(model.observe("com.android.launcher3", false).kind == ForegroundEventModel.Kind.IGNORED, "launcher ignored");
            check(model.observe("com.tencent.mm", true).kind == ForegroundEventModel.Kind.NONE, "wechat remains meaningful");
        }
        check(model.flush(FIVE_MINUTES, FIVE_MINUTES).kind == ForegroundEventModel.Kind.NONE, "launcher loops emit nothing");
    }

    private static void systemNavigationRoundTripNeverLeavesChrome() {
        ForegroundEventModel model = baseline("com.android.chrome");
        check(model.observe("com.android.systemui", false).kind == ForegroundEventModel.Kind.IGNORED, "navigation ignored");
        check(model.observe("com.android.chrome", true).kind == ForegroundEventModel.Kind.NONE, "chrome remains meaningful");
        check(model.flush(FIVE_MINUTES, FIVE_MINUTES).kind == ForegroundEventModel.Kind.NONE, "navigation round trip emits nothing");
    }

    private static void repeatedSwitchesMergeToZero() {
        ForegroundEventModel model = baseline("com.tencent.mm");
        stabilize(model, "com.android.chrome", 10_000);
        stabilize(model, "com.tencent.mm", 20_000);
        ForegroundEventModel.Decision result = model.flush(20_000 + FIVE_MINUTES, FIVE_MINUTES);
        check(result.kind == ForegroundEventModel.Kind.MERGED_REPEAT, "same start/end merged");
    }

    private static void businessTransitionProducesOneSummary() {
        ForegroundEventModel model = baseline("com.tencent.mm");
        stabilize(model, "com.android.chrome", 10_000);
        ForegroundEventModel.Decision result = model.flush(10_000 + FIVE_MINUTES, FIVE_MINUTES);
        check(result.kind == ForegroundEventModel.Kind.SUMMARY, "summary");
        check("com.tencent.mm".equals(result.fromPackage) && "com.android.chrome".equals(result.toPackage), "summary endpoints");
        check(model.flush(10_000 + 2 * FIVE_MINUTES, FIVE_MINUTES).kind == ForegroundEventModel.Kind.NONE, "at most one summary");
    }

    private static void quickShoppingOpenCancelsBeforeStable() {
        ForegroundEventModel model = baseline("com.tencent.mm");
        ForegroundEventModel.Decision shopping = model.observe("com.sankuai.meituan", true);
        check(shopping.kind == ForegroundEventModel.Kind.SCHEDULE_STABLE, "shopping stability timer");
        check(model.observe("com.tencent.mm", true).kind == ForegroundEventModel.Kind.CANCELLED_RETURN, "quick shopping exit");
        check(model.confirmStable(shopping.token, shopping.toPackage, 10_000).kind == ForegroundEventModel.Kind.NONE, "quick shopping emits nothing");
    }

    private static void helperUiDoesNotResetMeaningfulSession() {
        ForegroundEventModel model = baseline("com.tencent.mm");
        check(model.observe("com.android.systemui", false).kind == ForegroundEventModel.Kind.IGNORED, "notification shade ignored");
        check("com.tencent.mm".equals(model.currentMeaningful()), "meaningful session retained");
    }

    private static void onlyNewestPendingCandidateCanConfirm() {
        ForegroundEventModel model = baseline("com.tencent.mm");
        ForegroundEventModel.Decision chrome = model.observe("com.android.chrome", true);
        ForegroundEventModel.Decision maps = model.observe("com.autonavi.minimap", true);
        check(model.confirmStable(chrome.token, "com.android.chrome", 5000).kind == ForegroundEventModel.Kind.NONE, "stale candidate timer");
        check(model.confirmStable(maps.token, "com.autonavi.minimap", 6000).kind == ForegroundEventModel.Kind.STABLE_TRANSITION, "newest candidate");
    }

    private static void resetDropsPendingCandidate() {
        ForegroundEventModel model = baseline("com.tencent.mm");
        ForegroundEventModel.Decision pending = model.observe("com.android.chrome", true);
        model.reset();
        check(model.confirmStable(pending.token, "com.android.chrome", 5000).kind == ForegroundEventModel.Kind.NONE, "service restart");
    }

    private static void chatGptHelperRoundTripStaysOnChatGpt() {
        ForegroundEventModel model = baseline("com.openai.chatgpt");
        check(model.observe("com.google.android.photopicker", false).kind == ForegroundEventModel.Kind.IGNORED, "picker ignored");
        check(model.observe("com.openai.chatgpt", true).kind == ForegroundEventModel.Kind.NONE, "return to chatgpt");
        check(model.flush(FIVE_MINUTES, FIVE_MINUTES).kind == ForegroundEventModel.Kind.NONE, "no summary");
    }

    private static void semanticTimeWindows() {
        check("morning_return_candidate".equals(CandidateTimePolicy.returnCandidateType(8 * 60,
                EventPolicyConfig.minutes(300), EventPolicyConfig.minutes(240), EventPolicyConfig.minutes(90))), "morning return");
        check("afternoon_return_candidate".equals(CandidateTimePolicy.returnCandidateType(15 * 60,
                EventPolicyConfig.minutes(120), EventPolicyConfig.minutes(240), EventPolicyConfig.minutes(90))), "afternoon return");
        check(CandidateTimePolicy.returnCandidateType(15 * 60, EventPolicyConfig.minutes(20),
                EventPolicyConfig.minutes(240), EventPolicyConfig.minutes(90)).isEmpty(), "short idle is not return");
        check("lunch".equals(CandidateTimePolicy.mealWindow(12 * 60)), "lunch window");
        check("dinner".equals(CandidateTimePolicy.mealWindow(19 * 60)), "dinner window");
        check(CandidateTimePolicy.isLateNight(23 * 60 + 45), "late night");
        check(CandidateTimePolicy.isLateNight(5 * 60 + 30), "late night before six");
        check(!CandidateTimePolicy.isLateNight(6 * 60), "late night ends at six");
    }

    private static void lateNightFollowupStages() {
        check(LateNightFollowupPolicy.thresholdMinutesForStage(1) == 10, "first late-night reminder");
        check(LateNightFollowupPolicy.thresholdMinutesForStage(2) == 30, "second late-night reminder");
        check(LateNightFollowupPolicy.thresholdMinutesForStage(3) == 60, "strict late-night reminder");
        check(LateNightFollowupPolicy.thresholdMinutesForStage(4) == 90, "repeating late-night reminder");
        check("soft".equals(LateNightFollowupPolicy.toneForStage(1)), "first reminder is soft");
        check("firm".equals(LateNightFollowupPolicy.toneForStage(2)), "second reminder is firm");
        check("strict".equals(LateNightFollowupPolicy.toneForStage(3)), "later reminders are strict");
    }

    private static final long NIGHT_START = ZonedDateTime.parse("2026-10-04T23:30:00+08:00[Asia/Shanghai]")
            .toInstant().toEpochMilli();

    private static void observeNight(LateNightFollowupPolicy.Session session, long now,
                                     boolean interactive, long foregroundStart, long windowStart) {
        ZonedDateTime time = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai"));
        session.observe(now, time.getHour() * 60 + time.getMinute(), interactive, foregroundStart, windowStart);
    }

    private static void chatGptForegroundReceivesEveryLateNightStage() {
        ForegroundEventModel foreground = baseline("com.openai.chatgpt");
        check(PackageClassifier.isMeaningful(PackageClassifier.classify(foreground.currentMeaningful(), "dev.linjian.peek")),
                "ChatGPT is actual phone use");
        LateNightFollowupPolicy.Session session = new LateNightFollowupPolicy.Session();
        observeNight(session, NIGHT_START, true, NIGHT_START, NIGHT_START);
        int[] minutes = {10, 30, 60, 90, 120, 150};
        String[] tones = {"soft", "firm", "strict", "strict", "strict", "strict"};
        String[] actions = {"late_night_soft_checkin", "late_night_followup", "late_night_persistent_followup",
                "late_night_persistent_followup", "late_night_persistent_followup", "late_night_persistent_followup"};
        for (int i = 0; i < minutes.length; i++) {
            long now = NIGHT_START + EventPolicyConfig.minutes(minutes[i]);
            observeNight(session, now - 1, true, NIGHT_START, NIGHT_START);
            check(session.dueStage(now - 1) == 0, "ChatGPT must not fire early at " + minutes[i]);
            observeNight(session, now, true, NIGHT_START, NIGHT_START);
            check("com.openai.chatgpt".equals(foreground.currentMeaningful()), "still in ChatGPT");
            int stage = session.dueStage(now);
            check(stage == i + 1, "ChatGPT stage at " + minutes[i]);
            check(tones[i].equals(LateNightFollowupPolicy.toneForStage(stage)), "ChatGPT tone");
            check(actions[i].equals(LateNightFollowupPolicy.actionForStage(stage)), "ChatGPT action");
            session.consume(stage);
            check(session.dueStage(now) == 0, "same phase cannot repeat");
            check(session.startedAt() == NIGHT_START, "midnight/ChatGPT never resets clock");
        }
    }

    private static void appSwitchesPreserveLateNightSessionAndStage() {
        ForegroundEventModel foreground = baseline("com.android.chrome");
        LateNightFollowupPolicy.Session session = new LateNightFollowupPolicy.Session();
        observeNight(session, NIGHT_START, true, NIGHT_START, NIGHT_START);
        String[] packages = {"com.openai.chatgpt", "com.tencent.mm", "com.openai.chatgpt", "com.android.chrome",
                "com.openai.chatgpt"};
        int[] switchMinutes = {9, 20, 29, 59, 89};
        int[] dueMinutes = {10, 30, 60, 90};
        int dueIndex = 0;
        for (int i = 0; i < packages.length; i++) {
            long now = NIGHT_START + EventPolicyConfig.minutes(switchMinutes[i]);
            int previousStage = session.stage();
            stabilize(foreground, packages[i], now);
            observeNight(session, now, true, now, NIGHT_START); // Per-App start changed, screen-on start must not.
            check(session.startedAt() == NIGHT_START && session.stage() == previousStage, "switch retains clock and stage");
            if (i == 1) continue;
            long threshold = NIGHT_START + EventPolicyConfig.minutes(dueMinutes[dueIndex]);
            observeNight(session, threshold, true, now, NIGHT_START);
            check(session.dueStage(threshold) == dueIndex + 1, "cross-App accumulated stage");
            session.consume(++dueIndex);
        }
        check(session.stage() == 4 && "com.openai.chatgpt".equals(foreground.currentMeaningful()), "ChatGPT repeat after cross-App session");
        foreground.observe("com.android.systemui", false);
        observeNight(session, NIGHT_START + EventPolicyConfig.minutes(100), true, NIGHT_START, NIGHT_START);
        check(session.startedAt() == NIGHT_START && session.stage() == 4, "notification shade does not reset screen-on session");
    }

    private static void lateNightSessionResetsOnlyAtStopBoundaries() {
        LateNightFollowupPolicy.Session session = new LateNightFollowupPolicy.Session();
        observeNight(session, NIGHT_START, true, NIGHT_START, NIGHT_START);
        session.consume(session.dueStage(NIGHT_START + EventPolicyConfig.minutes(10)));
        long screenOff = NIGHT_START + EventPolicyConfig.minutes(20);
        observeNight(session, screenOff, false, NIGHT_START, NIGHT_START);
        check(session.startedAt() == 0 && session.stage() == 0, "screen off resets both fields");
        long screenOn = NIGHT_START + EventPolicyConfig.minutes(40);
        observeNight(session, screenOn, true, screenOn, NIGHT_START);
        check(session.dueStage(screenOn) == 0 && session.dueStage(screenOn + EventPolicyConfig.minutes(10)) == 1,
                "screen on starts a fresh soft phase");
        session.consume(1);
        session.reset(); // Same reset used by accessibility disconnect/reconnect.
        check(session.startedAt() == 0 && session.stage() == 0, "reconnect discards old session");
        long reconnected = screenOn + EventPolicyConfig.minutes(15);
        observeNight(session, reconnected, true, reconnected, NIGHT_START);
        check(session.dueStage(reconnected) == 0 && session.nextThresholdAt() == reconnected + EventPolicyConfig.minutes(10),
                "reconnect cannot replay previous phases");
        long six = NIGHT_START + EventPolicyConfig.minutes(390);
        observeNight(session, six, true, reconnected, NIGHT_START);
        check(session.startedAt() == 0 && session.stage() == 0 && session.dueStage(six) == 0, "06:00 ends the window");
        observeNight(session, six + EventPolicyConfig.minutes(30), true, reconnected, NIGHT_START);
        check(session.startedAt() == 0, "daytime cannot restart a late-night phase");
        long tomorrow = NIGHT_START + EventPolicyConfig.minutes(24 * 60);
        observeNight(session, tomorrow, true, reconnected, tomorrow);
        check(session.startedAt() == tomorrow && session.stage() == 0, "next window clamps a long screen-on session");
        // Even if the 06:00 callback was delayed, the next window must discard old phases.
        session.consume(1);
        long following = tomorrow + EventPolicyConfig.minutes(24 * 60);
        observeNight(session, following, true, reconnected, following);
        check(session.startedAt() == following && session.stage() == 0, "missed window boundary cannot preserve stale stages");
    }

    private static ForegroundEventModel baseline(String pkg) {
        ForegroundEventModel model = new ForegroundEventModel();
        ForegroundEventModel.Decision pending = model.observe(pkg, true);
        check(model.confirmStable(pending.token, pkg, 0).kind == ForegroundEventModel.Kind.BASELINE, "baseline " + pkg);
        return model;
    }

    private static void stabilize(ForegroundEventModel model, String pkg, long now) {
        ForegroundEventModel.Decision pending = model.observe(pkg, true);
        check(model.confirmStable(pending.token, pkg, now).kind == ForegroundEventModel.Kind.STABLE_TRANSITION, "stabilize " + pkg);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
