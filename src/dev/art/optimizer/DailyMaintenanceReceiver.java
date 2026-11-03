package dev.art.optimizer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

public class DailyMaintenanceReceiver extends BroadcastReceiver {

    public static final String ACTION_DAILY = "dev.art.optimizer.ACTION_DAILY_MAINTENANCE";
    private static final long INTERVAL_24_HOURS = 24 * 60 * 60 * 1000L;

    public static void scheduleDaily(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent intent = new Intent(context, DailyMaintenanceReceiver.class);
            intent.setAction(ACTION_DAILY);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pi = PendingIntent.getBroadcast(context, 1001, intent, flags);

            long triggerAt = SystemClock.elapsedRealtime() + INTERVAL_24_HOURS;
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            } else if (Build.VERSION.SDK_INT >= 19) {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            ArtJobService.schedule(context);
            scheduleDaily(context);
            return;
        }

        if (ACTION_DAILY.equals(action)) {
            scheduleDaily(context);

            if (!AppPreferences.isDailyMaintenanceEnabled(context)) {
                return;
            }

            AppLog.log(context, "[MANTENIMIENTO] Ejecutando barrido diario programado por alarma...");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (!ShizukuRunner.waitForBinder(5000)) {
                        AppLog.log(context, "[MANTENIMIENTO] Shizuku no disponible en segundo plano.");
                        return;
                    }
                    if (!SystemLoadMonitor.isSafeToOptimize(context)) {
                        AppLog.log(context, "[MANTENIMIENTO] Sistema ocupado. Pospuesto.");
                        return;
                    }
                    // Falso = prioridad baja (nice 19) para segundo plano
                    ShizukuRunner.optimizeAllUserPackages(context, false, null);
                }
            }, "art-daily-optimizer").start();
        }
    }
}
