package dev.linjian.peek;

/** Pure staged-reminder timing and metadata for one continuous late-night session. */
public final class LateNightFollowupPolicy {
    private LateNightFollowupPolicy() { }

    /** Stage numbers are one-based. Stage 1 is the first gentle reminder. */
    public static int thresholdMinutesForStage(int stage) {
        if (stage <= 1) return EventPolicyConfig.LATE_NIGHT_SUSTAINED_MINUTES;
        if (stage == 2) return EventPolicyConfig.LATE_NIGHT_SECOND_REMINDER_MINUTES;
        return EventPolicyConfig.LATE_NIGHT_STRICT_REMINDER_MINUTES
                + (stage - 3) * EventPolicyConfig.LATE_NIGHT_REPEAT_MINUTES;
    }

    public static String actionForStage(int stage) {
        if (stage <= 1) return "late_night_soft_checkin";
        if (stage == 2) return "late_night_followup";
        return "late_night_persistent_followup";
    }

    public static String toneForStage(int stage) {
        if (stage <= 1) return "soft";
        if (stage == 2) return "firm";
        return "strict";
    }
}
