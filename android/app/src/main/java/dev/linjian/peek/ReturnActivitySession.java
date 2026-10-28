package dev.linjian.peek;

/** One resumed-use window, independent of which meaningful app is in front. */
public final class ReturnActivitySession {
    private String type = "";
    private String day = "";
    private String packageName = "";
    private long inactiveSeconds;
    private long readyAtMs;

    public boolean start(String candidateType, String candidateDay, String pkg,
                         long nowMs, long idleSeconds, long sustainedMs) {
        if (isPending() || candidateType == null || candidateType.isEmpty()
                || pkg == null || pkg.isEmpty()) return false;
        type = candidateType;
        day = candidateDay;
        packageName = pkg;
        inactiveSeconds = idleSeconds;
        readyAtMs = nowMs + sustainedMs;
        return true;
    }

    public void observe(String pkg, boolean meaningful) {
        if (isPending() && meaningful && pkg != null && !pkg.isEmpty()) packageName = pkg;
    }

    public boolean isPending() { return !type.isEmpty(); }
    public boolean isReady(long nowMs) { return isPending() && nowMs >= readyAtMs; }
    public long remainingMs(long nowMs) { return Math.max(0, readyAtMs - nowMs); }
    public String type() { return type; }
    public String day() { return day; }
    public String packageName() { return packageName; }
    public long inactiveSeconds() { return inactiveSeconds; }

    public void reset() {
        type = "";
        day = "";
        packageName = "";
        inactiveSeconds = 0;
        readyAtMs = 0;
    }
}
