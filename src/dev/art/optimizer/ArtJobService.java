package dev.art.optimizer;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

public class ArtJobService extends JobService {

    public static final int JOB_ID = 2026;
    private static final long INTERVAL_24H = 24 * 60 * 60 * 1000L;

    public static void schedule(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;

            ComponentName component = new ComponentName(context, ArtJobService.class);
            JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, component);

            if (Build.VERSION.SDK_INT >= 24) {
                builder.setPeriodic(INTERVAL_24H, 60 * 60 * 1000L);
            } else {
                builder.setPeriodic(INTERVAL_24H);
            }

            builder.setPersisted(true);

            if (Build.VERSION.SDK_INT >= 26) {
                builder.setRequiresBatteryNotLow(true);
            }

            int result = js.schedule(builder.build());
            if (result == JobScheduler.RESULT_SUCCESS) {
                AppLog.log(context, "[JOBSCHEDULER] Programado correctamente (cada 24h).");
            } else {
                AppLog.log(context, "[JOBSCHEDULER] Fallo al registrar en el sistema.");
                DailyMaintenanceReceiver.scheduleDaily(context);
            }
        } catch (Throwable t) {
            AppLog.log(context, "[JOBSCHEDULER] Error al programar: " + t.getMessage());
            DailyMaintenanceReceiver.scheduleDaily(context);
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!AppPreferences.isDailyMaintenanceEnabled(this)) {
            jobFinished(params, false);
            return false;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Esperar a que el IPC de Shizuku esté listo si el proceso acaba de arrancar en segundo plano
                    if (!ShizukuRunner.waitForBinder(5000)) {
                        AppLog.log(ArtJobService.this, "[JOBSCHEDULER] Shizuku sin conexión/permisos en segundo plano. Reintentando...");
                        jobFinished(params, true);
                        return;
                    }

                    if (!SystemLoadMonitor.isSafeToOptimize(ArtJobService.this)) {
                        AppLog.log(ArtJobService.this, "[JOBSCHEDULER] Sistema ocupado. Reprogramando para reintentar más tarde.");
                        jobFinished(params, true);
                        return;
                    }

                    AppLog.log(ArtJobService.this, "[JOBSCHEDULER] Condiciones ideales. Iniciando mantenimiento de fondo...");
                    // Falso = prioridad baja (nice 19) para segundo plano
                    ShizukuRunner.optimizeAllUserPackages(ArtJobService.this, false, null);
                    jobFinished(params, false);
                } catch (Throwable t) {
                    AppLog.log(ArtJobService.this, "[JOBSCHEDULER] Error: " + t.getMessage());
                    jobFinished(params, false);
                }
            }
        }, "art-jobscheduler").start();

        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        AppLog.log(this, "[JOBSCHEDULER] Trabajo pausado por el sistema.");
        return true;
    }
}
