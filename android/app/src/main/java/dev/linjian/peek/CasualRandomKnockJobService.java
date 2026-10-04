package dev.linjian.peek;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Inexact, persisted scheduler wakeup. Foreground activity remains the fallback. */
public final class CasualRandomKnockJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        CasualRandomKnockScheduler.initialize(this);
        return false;
    }
    @Override public boolean onStopJob(JobParameters params) { return false; }
}
