package dev.linjian.peek;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;

/** Pure, persistable daily plan. No Android timers, network or private content. */
public final class CasualRandomKnockPolicy {
    public static final long MINUTE = 60_000L;
    public static final long GAP = 120 * MINUTE;
    public static final long GRACE = 90 * MINUTE;
    public enum Decision { FUTURE, DEFER, FIRE, ABANDON, DONE }
    public static final class Target {
        public final String id;
        public final long at;
        public String status;
        public long actual;
        Target(String id, long at, String status, long actual) {
            this.id = id; this.at = at; this.status = status; this.actual = actual;
        }
    }
    public final long day;
    public final int requested;
    public int serial;
    public final List<Target> targets = new ArrayList<>();

    private CasualRandomKnockPolicy(long day, int requested) {
        this.day = day; this.requested = clamp(requested);
    }
    public static int clamp(int count) { return Math.max(1, Math.min(3, count)); }
    public static int targetLimit(int count) { return clamp(count); }
    public static long dayStart(long now) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        c.setTimeInMillis(now); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
    public static boolean available(long now) {
        int minute = (int) ((now - dayStart(now)) / MINUTE);
        return minute >= 630 && minute < 1350 && !(minute >= 690 && minute < 840)
                && !(minute >= 1050 && minute < 1170);
    }
    public static CasualRandomKnockPolicy ensure(CasualRandomKnockPolicy old, long now,
                                                boolean enabled, int count, long lastImportant, Random random) {
        if (old != null && old.day == dayStart(now) && old.requested == clamp(count)) {
            if (!enabled) old.cancelPending();
            return old;
        }
        return replan(old, now, enabled, count, lastImportant, random);
    }
    public static CasualRandomKnockPolicy replan(CasualRandomKnockPolicy old, long now,
                                                boolean enabled, int count, long lastImportant, Random random) {
        CasualRandomKnockPolicy plan = new CasualRandomKnockPolicy(dayStart(now), count);
        if (old != null && old.day == plan.day) {
            plan.serial = old.serial;
            for (Target t : old.targets) if ("fired".equals(t.status)) plan.targets.add(t);
        }
        if (!enabled) return plan;
        int remaining = Math.max(0, targetLimit(count) - plan.firedCount());
        List<Long> choices = new ArrayList<>();
        long earliest = Math.max(now + MINUTE, lastImportant > 0 ? lastImportant + GAP : now + MINUTE);
        for (long at = plan.day + 630 * MINUTE; at < plan.day + 1350 * MINUTE; at += MINUTE) {
            if (at < earliest || !available(at)) continue;
            boolean spaced = true;
            for (Target t : plan.targets) if ("fired".equals(t.status)
                    && (Math.abs(at - t.at) < GAP || at - t.actual < GAP)) spaced = false;
            if (spaced) choices.add(at);
        }
        List<Long> selected = new ArrayList<>();
        int wanted = Math.min(remaining, capacity(choices, earliest));
        while (wanted > 0) {
            List<Long> feasible = new ArrayList<>();
            for (long at : choices) if (at >= earliest && capacity(choices, at + GAP) >= wanted - 1) feasible.add(at);
            long at = feasible.get(random.nextInt(feasible.size()));
            selected.add(at); earliest = at + GAP; wanted--;
        }
        for (long at : selected) {
            plan.serial++;
            plan.targets.add(new Target("random-" + UUID.randomUUID(), at, "pending", 0));
        }
        return plan;
    }
    /** Greedy packing on sorted eligible minutes gives the maximum feasible count. */
    private static int capacity(List<Long> choices, long earliest) {
        int count = 0;
        for (long at : choices) if (at >= earliest) { count++; earliest = at + GAP; }
        return count;
    }
    public void cancelPending() {
        for (Target t : targets) if ("pending".equals(t.status)) t.status = "cancelled";
    }
    public int firedCount() {
        int n = 0; for (Target t : targets) if ("fired".equals(t.status)) n++; return n;
    }
    public int plannedCount() {
        int n = 0; for (Target t : targets) if ("pending".equals(t.status) || "fired".equals(t.status) || "abandoned".equals(t.status)) n++; return n;
    }
    public Decision evaluate(Target t, long now, boolean enabled, boolean interactive,
                              boolean confirmedApp, boolean stable, boolean network, long lastImportant) {
        if (!"pending".equals(t.status) || !enabled) return Decision.DONE;
        if (dayStart(now) != day || now > t.at + GRACE || now >= day + 1350 * MINUTE) return Decision.ABANDON;
        if (now < t.at) return Decision.FUTURE;
        if (!available(now) || !interactive || !confirmedApp || !stable || !network
                || (lastImportant > 0 && now - lastImportant < GAP)) return Decision.DEFER;
        for (Target other : targets) if ("fired".equals(other.status) && now - other.actual < GAP) return Decision.DEFER;
        return Decision.FIRE;
    }
    /** Claim persisted before the upload attempt, giving at most once even after crashes. */
    public boolean claim(Target t, long now) {
        if (!"pending".equals(t.status)) return false;
        t.status = "fired"; t.actual = now; return true;
    }
    public String encode() {
        StringBuilder s = new StringBuilder().append(day).append('|').append(requested).append('|').append(serial);
        for (Target t : targets) s.append('|').append(t.id).append(',').append(t.at).append(',').append(t.status).append(',').append(t.actual);
        return s.toString();
    }
    public static CasualRandomKnockPolicy decode(String raw) {
        try {
            String[] parts = raw.split("\\|");
            CasualRandomKnockPolicy plan = new CasualRandomKnockPolicy(Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
            plan.serial = Integer.parseInt(parts[2]);
            for (int i = 3; i < parts.length; i++) {
                String[] t = parts[i].split(",");
                plan.targets.add(new Target(t[0], Long.parseLong(t[1]), t[2], Long.parseLong(t[3])));
            }
            return plan;
        } catch (Exception ignored) { return null; }
    }
}
