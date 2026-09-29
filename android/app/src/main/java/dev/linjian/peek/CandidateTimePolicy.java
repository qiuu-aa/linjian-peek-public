package dev.linjian.peek;

/** Pure time-window decisions; callers provide Asia/Shanghai minute-of-day values. */
public final class CandidateTimePolicy {
    private CandidateTimePolicy() { }

    public static String returnCandidateType(int minuteOfDay, long inactiveMs,
                                             long morningThresholdMs, long afternoonThresholdMs) {
        if (minuteOfDay >= EventPolicyConfig.MORNING_START_MINUTE
                && minuteOfDay < EventPolicyConfig.MORNING_END_MINUTE
                && inactiveMs >= morningThresholdMs) return "morning_return_candidate";
        if (minuteOfDay >= EventPolicyConfig.AFTERNOON_START_MINUTE
                && minuteOfDay < EventPolicyConfig.AFTERNOON_END_MINUTE
                && inactiveMs >= afternoonThresholdMs) return "afternoon_return_candidate";
        return "";
    }

    public static String mealWindow(int minuteOfDay) {
        if (minuteOfDay >= EventPolicyConfig.LUNCH_START_MINUTE && minuteOfDay < EventPolicyConfig.LUNCH_END_MINUTE) return "lunch";
        if (minuteOfDay >= EventPolicyConfig.DINNER_START_MINUTE && minuteOfDay < EventPolicyConfig.DINNER_END_MINUTE) return "dinner";
        return "";
    }

    public static boolean isLateNight(int minuteOfDay) {
        return minuteOfDay >= EventPolicyConfig.LATE_NIGHT_START_MINUTE || minuteOfDay < EventPolicyConfig.LATE_NIGHT_END_MINUTE;
    }
}
