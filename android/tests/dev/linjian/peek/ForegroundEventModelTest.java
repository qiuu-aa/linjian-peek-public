package dev.linjian.peek;

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
