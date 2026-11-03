package dev.art.optimizer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;

public class PackageReceiver extends BroadcastReceiver {

    public static boolean isDeviceCharging(Context context) {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, ifilter);
            if (batteryStatus == null) return false;
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                   status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        if (!Intent.ACTION_PACKAGE_ADDED.equals(action) && !Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        if (!AppPreferences.isAutoInstallEnabled(context)) {
            return;
        }

        Uri data = intent.getData();
        if (data == null) return;
        final String packageName = data.getSchemeSpecificPart();
        if (packageName == null || packageName.equals(context.getPackageName())) {
            return;
        }

        AppLog.log(context, "[EVENTO] App instalada o actualizada: " + packageName);

        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!ShizukuRunner.waitForBinder(5000)) {
                    AppLog.log(context, "[AUTO-INSTALL] " + packageName + ": Shizuku no disponible.");
                    return;
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {}

                // Falso = modo segundo plano (con nice 19 para cero lag)
                ShizukuRunner.optimizePackage(context, packageName, false);
            }
        }, "art-pkg-optimizer").start();
    }
}
