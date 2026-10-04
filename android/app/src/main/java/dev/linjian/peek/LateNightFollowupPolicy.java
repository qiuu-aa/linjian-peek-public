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

    /** One screen-on session, independent of foreground package (including ChatGPT). */
    public static final class Session {
        private long lateNightSessionStartedAt;
        private int lateNightStage;

        public long startedAt() { return lateNightSessionStartedAt; }
        public int stage() { return lateNightStage; }

        public void observe(long now, int shanghaiMinute, boolean interactive,
                            long foregroundStartedAt, long windowStartedAt) {
            if (!interactive || !CandidateTimePolicy.isLateNight(shanghaiMinute)) {
                reset();
                return;
            }
            // Also discard an old window if a delayed callback missed its end.
            if (lateNightSessionStartedAt <= 0 || lateNightSessionStartedAt < windowStartedAt) {
                long start = foregroundStartedAt > 0 ? Math.min(now, foregroundStartedAt) : now;
                lateNightSessionStartedAt = Math.max(start, windowStartedAt);
                lateNightStage = 0;
            }
        }

        public long nextThresholdAt() {
            return lateNightSessionStartedAt <= 0 ? 0 : lateNightSessionStartedAt
                    + EventPolicyConfig.minutes(thresholdMinutesForStage(lateNightStage + 1));
        }

        public int dueStage(long now) {
            return lateNightSessionStartedAt > 0 && now >= nextThresholdAt() ? lateNightStage + 1 : 0;
        }

        public void consume(int stage) {
            if (stage == lateNightStage + 1) lateNightStage = stage;
        }

        public void reset() {
            lateNightSessionStartedAt = 0;
            lateNightStage = 0;
        }
    }
}
