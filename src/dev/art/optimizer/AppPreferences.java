package dev.art.optimizer;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class AppPreferences {
    private static final String PREF_NAME = "art_optimizer_prefs";
    private static final String KEY_AUTO_INSTALL = "auto_install";
    private static final String KEY_OPTIMIZE_ON_SUSPEND = "optimize_on_suspend";
    private static final String KEY_DAILY_MAINTENANCE = "daily_maintenance";
    private static final String KEY_AVOID_HEAVY_TASKS = "avoid_heavy_tasks";
    private static final String KEY_EXCLUDED_PACKAGES = "excluded_packages";

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isAutoInstallEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_AUTO_INSTALL, true);
    }

    public static void setAutoInstallEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_INSTALL, enabled).apply();
    }

    public static boolean isOptimizeOnSuspendEnabled(Context context) {
        // Por defecto FALSE: el usuario prefiere no optimizar en reposo a menos que lo active
        return getPrefs(context).getBoolean(KEY_OPTIMIZE_ON_SUSPEND, false);
    }

    public static void setOptimizeOnSuspendEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_OPTIMIZE_ON_SUSPEND, enabled).apply();
    }

    public static boolean isDailyMaintenanceEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_DAILY_MAINTENANCE, true);
    }

    public static void setDailyMaintenanceEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_DAILY_MAINTENANCE, enabled).apply();
    }


    public static boolean isAvoidHeavyTasks(Context context) {
        // Por defecto TRUE: pausa si la CPU está muy cargada o el dispositivo caliente (jugando)
        return getPrefs(context).getBoolean(KEY_AVOID_HEAVY_TASKS, true);
    }

    public static void setAvoidHeavyTasks(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_AVOID_HEAVY_TASKS, enabled).apply();
    }

    public static boolean isPackageExcluded(Context context, String packageName) {
        return getPrefs(context).getStringSet(KEY_EXCLUDED_PACKAGES, Collections.<String>emptySet()).contains(packageName);
    }

    public static void setPackageExcluded(Context context, String packageName, boolean excluded) {
        Set<String> current = new HashSet<>(getPrefs(context).getStringSet(KEY_EXCLUDED_PACKAGES, Collections.<String>emptySet()));
        if (excluded) {
            current.add(packageName);
        } else {
            current.remove(packageName);
        }
        getPrefs(context).edit().putStringSet(KEY_EXCLUDED_PACKAGES, current).apply();
    }
}
