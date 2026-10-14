package ru.gubkin.raspisanie;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Фоновое обновление расписания из интернета: раз в несколько часов и после последней пары. */
public class RefreshJob extends JobService {
    private static final int JOB_NOW = 1;
    private static final int JOB_PERIODIC = 2;

    static void scheduleNow(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) return;
        safeSchedule(js, new JobInfo.Builder(JOB_NOW, new ComponentName(ctx, RefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
    }

    /** Фоновое обновление — дополнительная удобность; если система отказала, приложение работает дальше */
    private static void safeSchedule(JobScheduler js, JobInfo job) {
        try {
            js.schedule(job);
        } catch (RuntimeException e) {
            android.util.Log.w("GubkinJob", "не удалось запланировать обновление", e);
        }
    }

    static void schedulePeriodic(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_PERIODIC) != null) return;
        safeSchedule(js, new JobInfo.Builder(JOB_PERIODIC, new ComponentName(ctx, RefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6L * 60 * 60 * 1000)
                .setPersisted(true) // переживает перезагрузку телефона
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Store store = new Store(this);
        if (!store.hasCreds() || store.groupId() == 0) return false;
        if (!store.isStale()) return false; // только что обновлялось — не дёргаем сервер лишний раз
        // Несколько поводов сработали почти одновременно — хватит одной попытки
        if (System.currentTimeMillis() - store.lastAttempt() < 2 * 60 * 1000L) return false;
        // Работает на главном потоке — там же нужен встроенный браузер
        Sync.refresh(this, 90000, (error, authError) -> jobFinished(params, false), null);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // система прервала — повторить позже
    }
}
