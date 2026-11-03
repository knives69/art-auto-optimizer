package dev.art.optimizer;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import java.io.BufferedReader;
import java.io.FileReader;

public class SystemLoadMonitor {

    /**
     * Comprueba si la pantalla está encendida y activa.
     */
    public static boolean isScreenOn(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (Build.VERSION.SDK_INT >= 20) {
                    return pm.isInteractive();
                } else {
                    return pm.isScreenOn();
                }
            }
        } catch (Throwable ignored) {}
        return true;
    }

    /**
     * Obtiene el porcentaje de batería actual (0 - 100).
     */
    public static int getBatteryLevel(Context context) {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    return (int) ((level / (float) scale) * 100);
                }
            }
        } catch (Throwable ignored) {}
        return 100;
    }

    /**
     * Lee la carga media del sistema en el último minuto desde /proc/loadavg.
     * En Android 10+, SELinux restringe el acceso directo para apps comunes,
     * pero a través de Shizuku (UID shell 2000) se lee con total precisión.
     */
    public static float getCpuLoad1m() {
        try {
            ShizukuRunner.CommandResult res = ShizukuRunner.exec("cat", "/proc/loadavg");
            if (res.exitCode == 0 && !res.stdout.trim().isEmpty()) {
                String[] parts = res.stdout.trim().split("\\s+");
                if (parts.length > 0) {
                    return Float.parseFloat(parts[0]);
                }
            }
        } catch (Throwable ignored) {}

        // Fallback local por si Shizuku aún no está inicializado
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/loadavg"));
            String line = reader.readLine();
            reader.close();
            if (line != null && !line.trim().isEmpty()) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length > 0) {
                    return Float.parseFloat(parts[0]);
                }
            }
        } catch (Throwable ignored) {}
        return -1f;
    }

    /**
     * Comprueba el estado térmico del dispositivo (Android 10+).
     * Retorna true si el dispositivo está fresco (sin estrangulamiento térmico).
     */
    public static boolean isThermalCool(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    // 0 = NONE, 1 = LIGHT (calor normal)
                    // 2 = MODERATE, 3 = SEVERE (jugando o recalentándose)
                    int status = pm.getCurrentThermalStatus();
                    return status <= PowerManager.THERMAL_STATUS_LIGHT;
                }
            }
        } catch (Throwable ignored) {}
        return true;
    }

    /**
     * Evalúa si el momento es seguro para optimizar sin molestar al usuario:
     * - Carga de CPU moderada (no jugando a un juego pesado).
     * - Batería superior al 15% (para no apagar el dispositivo).
     * - Temperatura controlada.
     */
    public static boolean isSafeToOptimize(Context context) {
        // 1. Comprobar batería mínima de seguridad
        if (getBatteryLevel(context) < 15 && !PackageReceiver.isDeviceCharging(context)) {
            AppLog.log(context, "[SISTEMA] Batería baja (<15%). Pospuesto para proteger batería.");
            return false;
        }

        // 2. Si la TV está en reposo (pantalla apagada) y no se activó "Optimizar en reposo (suspend)"
        if (!AppPreferences.isOptimizeOnSuspendEnabled(context) && !isScreenOn(context)) {
            AppLog.log(context, "[SISTEMA] TV en reposo (suspend). Pospuesto hasta encendido.");
            return false;
        }

        // 3. Si el usuario activó "Evitar tareas pesadas"
        if (AppPreferences.isAvoidHeavyTasks(context)) {
            // Comprobamos temperatura
            if (!isThermalCool(context)) {
                AppLog.log(context, "[SISTEMA] Dispositivo con temperatura alta (posible juego activo). Pospuesto.");
                return false;
            }

            // Comprobamos carga de CPU (si el loadavg es muy alto, hay tarea pesada)
            float load = getCpuLoad1m();
            int cores = Runtime.getRuntime().availableProcessors();
            if (load > 0 && cores > 0 && load > (cores * 0.85f)) {
                AppLog.log(context, "[SISTEMA] CPU ocupada (carga: " + load + " en " + cores + " núcleos). Pospuesto.");
                return false;
            }
        }

        return true;
    }
}
