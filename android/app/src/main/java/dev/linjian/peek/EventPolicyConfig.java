package dev.linjian.peek;

/** Central defaults for phone-to-Slack candidate generation. */
public final class EventPolicyConfig {
    public static final String TIME_ZONE = "Asia/Shanghai";

    public static final int FOREGROUND_STABLE_SECONDS = 4;
    public static final int FOREGROUND_STABLE_SECONDS_MIN = 3;
    public static final int FOREGROUND_STABLE_SECONDS_MAX = 5;
    public static final int FOREGROUND_MERGE_MINUTES = 5;
    public static final int GLOBAL_COOLDOWN_MINUTES = 15;

    public static final int RETURN_SUSTAINED_MINUTES = 2;
    public static final int MORNING_INACTIVE_MINUTES = 240;
    public static final int AFTERNOON_INACTIVE_MINUTES = 90;
    public static final int LONG_APP_SESSION_MINUTES = 45;
    public static final int SHOPPING_MIN_MINUTES = 2;
    public static final int LATE_NIGHT_SUSTAINED_MINUTES = 10;
    public static final int LATE_NIGHT_SECOND_REMINDER_MINUTES = 30;
    public static final int LATE_NIGHT_STRICT_REMINDER_MINUTES = 60;
    public static final int LATE_NIGHT_REPEAT_MINUTES = 30;

    public static final int MORNING_START_MINUTE = 6 * 60;
    public static final int MORNING_END_MINUTE = 11 * 60;
    public static final int AFTERNOON_START_MINUTE = 13 * 60;
    public static final int AFTERNOON_END_MINUTE = 18 * 60;
    public static final int LATE_NIGHT_START_MINUTE = 23 * 60 + 30;
    public static final int LATE_NIGHT_END_MINUTE = 6 * 60;
    public static final int LUNCH_START_MINUTE = 11 * 60 + 30;
    public static final int LUNCH_END_MINUTE = 13 * 60 + 30;
    public static final int DINNER_START_MINUTE = 17 * 60 + 30;
    public static final int DINNER_END_MINUTE = 20 * 60;

    public static final long GUIDIAN_DEDUPE_MS = 10 * 60_000L;
    public static final long EDIT_DEDUPE_MS = 60_000L;
    public static final long LATE_NIGHT_TYPE_COOLDOWN_MS = 6 * 60 * 60_000L;
    public static final long LONG_SESSION_TYPE_COOLDOWN_MS = 4 * 60 * 60_000L;
    public static final long SHOPPING_TYPE_COOLDOWN_MS = 90 * 60_000L;
    public static final long RETURN_TYPE_COOLDOWN_MS = 6 * 60 * 60_000L;
    public static final long MEAL_TYPE_COOLDOWN_MS = 3 * 60 * 60_000L;
    public static final long FOREGROUND_TYPE_COOLDOWN_MS = 5 * 60_000L;

    private EventPolicyConfig() { }

    public static long minutes(int value) { return value * 60_000L; }
}
