package dev.linjian.peek;

/** Pure state machine for one pending stable app and one rolling aggregation window. */
public final class ForegroundEventModel {
    public enum Kind { NONE, IGNORED, SCHEDULE_STABLE, CANCELLED_RETURN, BASELINE, STABLE_TRANSITION, SUMMARY, MERGED_REPEAT }

    public static final class Decision {
        public final Kind kind;
        public final long token;
        public final String fromPackage;
        public final String toPackage;
        public final int transitionCount;
        public final long windowMs;

        private Decision(Kind kind, long token, String fromPackage, String toPackage, int count, long windowMs) {
            this.kind = kind; this.token = token; this.fromPackage = safe(fromPackage); this.toPackage = safe(toPackage);
            this.transitionCount = count; this.windowMs = Math.max(0, windowMs);
        }
        private static Decision of(Kind kind) { return new Decision(kind, 0, "", "", 0, 0); }
    }

    private String lastMeaningful = "";
    private String pendingPackage = "";
    private String pendingOrigin = "";
    private long pendingToken = 0;
    private String windowOrigin = "";
    private String windowEnd = "";
    private long windowStartedAt = 0;
    private long windowLastAt = 0;
    private int transitionCount = 0;

    public void reset() {
        lastMeaningful = ""; pendingPackage = ""; pendingOrigin = ""; pendingToken++;
        clearWindow();
    }

    public Decision observe(String packageName, boolean meaningful) {
        String pkg = safe(packageName);
        if (!meaningful || pkg.isEmpty()) return Decision.of(Kind.IGNORED);
        if (pkg.equals(pendingPackage)) return Decision.of(Kind.NONE);
        if (pkg.equals(lastMeaningful)) {
            if (!pendingPackage.isEmpty()) {
                pendingPackage = ""; pendingOrigin = ""; pendingToken++;
                return Decision.of(Kind.CANCELLED_RETURN);
            }
            return Decision.of(Kind.NONE);
        }
        pendingPackage = pkg;
        pendingOrigin = lastMeaningful;
        pendingToken++;
        return new Decision(Kind.SCHEDULE_STABLE, pendingToken, pendingOrigin, pkg, 0, 0);
    }

    public Decision confirmStable(long token, String packageName, long nowMs) {
        String pkg = safe(packageName);
        if (token != pendingToken || pendingPackage.isEmpty() || !pendingPackage.equals(pkg)) return Decision.of(Kind.NONE);
        String from = pendingOrigin;
        pendingPackage = ""; pendingOrigin = "";
        if (lastMeaningful.isEmpty()) {
            lastMeaningful = pkg;
            return new Decision(Kind.BASELINE, token, "", pkg, 0, 0);
        }
        if (pkg.equals(lastMeaningful)) return Decision.of(Kind.NONE);
        String actualFrom = lastMeaningful;
        lastMeaningful = pkg;
        if (windowOrigin.isEmpty()) {
            windowOrigin = actualFrom;
            windowStartedAt = nowMs;
            transitionCount = 1;
        } else {
            transitionCount++;
        }
        windowEnd = pkg;
        windowLastAt = nowMs;
        return new Decision(Kind.STABLE_TRANSITION, token, actualFrom, pkg, transitionCount, nowMs - windowStartedAt);
    }

    public Decision flush(long nowMs, long mergeWindowMs) {
        if (windowOrigin.isEmpty() || nowMs - windowLastAt < mergeWindowMs) return Decision.of(Kind.NONE);
        Decision result = new Decision(windowOrigin.equals(windowEnd) ? Kind.MERGED_REPEAT : Kind.SUMMARY,
                0, windowOrigin, windowEnd, transitionCount, nowMs - windowStartedAt);
        clearWindow();
        return result;
    }

    public String currentMeaningful() { return lastMeaningful; }
    public String pendingPackage() { return pendingPackage; }
    public void cancelPending() { pendingPackage = ""; pendingOrigin = ""; pendingToken++; }

    private void clearWindow() {
        windowOrigin = ""; windowEnd = ""; windowStartedAt = 0; windowLastAt = 0; transitionCount = 0;
    }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
