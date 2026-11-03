package dev.art.optimizer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

public class ShizukuRunner {

    private static final Pattern SPEED_PATTERN = Pattern.compile(
        "(\\[status=speed\\]|compiler.?filter[ :=]+speed([\\s]|$))",
        Pattern.CASE_INSENSITIVE
    );

    public static class CommandResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public String output() {
            return (stdout + " " + stderr).trim();
        }
    }

    public interface ProgressCallback {
        void onProgress(String currentPackage, int current, int total);
    }

    public interface PackageUpdateListener {
        void onPackageOptimized(String packageName, PackageDexState newState);
    }

    private static volatile PackageUpdateListener packageListener;

    public static void setPackageUpdateListener(PackageUpdateListener listener) {
        packageListener = listener;
    }

    public static void notifyPackageUpdated(String packageName, PackageDexState newState) {
        PackageUpdateListener l = packageListener;
        if (l != null) {
            try {
                l.onPackageOptimized(packageName, newState);
            } catch (Throwable ignored) {}
        }
    }

    public static boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        if (!isShizukuAvailable()) return false;
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean waitForBinder(long timeoutMs) {
        if (hasPermission()) return true;
        long deadline = android.os.SystemClock.uptimeMillis() + timeoutMs;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                if (hasPermission()) return true;
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
        }
        return hasPermission();
    }

    public static CommandResult exec(String... command) {
        if (!hasPermission()) {
            return new CommandResult(-1, "", "Permiso de Shizuku no concedido");
        }

        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null) {
                return new CommandResult(-1, "", "Binder de Shizuku nulo");
            }

            IShizukuService service = IShizukuService.Stub.asInterface(binder);
            if (service == null) {
                return new CommandResult(-1, "", "No se pudo obtener el servicio Shizuku");
            }

            IRemoteProcess process = service.newProcess(command, null, null);
            if (process == null) {
                return new CommandResult(-1, "", "Fallo al instanciar el proceso remoto");
            }

            // Lectura de STDOUT
            StringBuilder stdout = new StringBuilder();
            ParcelFileDescriptor pfdOut = process.getInputStream();
            if (pfdOut != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new ParcelFileDescriptor.AutoCloseInputStream(pfdOut)
                ));
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                }
                reader.close();
            }

            // Lectura de STDERR
            StringBuilder stderr = new StringBuilder();
            ParcelFileDescriptor pfdErr = process.getErrorStream();
            if (pfdErr != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new ParcelFileDescriptor.AutoCloseInputStream(pfdErr)
                ));
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
                reader.close();
            }

            int exitCode = process.waitFor();
            return new CommandResult(exitCode, stdout.toString(), stderr.toString());

        } catch (Throwable t) {
            return new CommandResult(-1, "", t.getMessage() != null ? t.getMessage() : "Error de ejecución");
        }
    }

    public static class PackageDexState {
        public final String filter;
        public final boolean isSpeed;
        public final boolean isCmdlineVerify;

        public PackageDexState(String filter, boolean isSpeed, boolean isCmdlineVerify) {
            this.filter = filter;
            this.isSpeed = isSpeed;
            this.isCmdlineVerify = isCmdlineVerify;
        }
    }

    public static PackageDexState getPackageDexState(String packageName) {
        CommandResult res = exec("dumpsys", "package", packageName);
        if (res.exitCode != 0 || res.stdout.isEmpty()) {
            return new PackageDexState("desconocido", false, false);
        }

        String filter = "sin optimizar";
        boolean isSpeed = false;
        boolean isCmdlineVerify = false;

        for (String line : res.stdout.split("\n")) {
            String lower = line.toLowerCase();
            if (lower.contains("status=") || lower.contains("compiler filter") || lower.contains("compiler-filter")) {
                if (lower.contains("status=speed]")) {
                    filter = "speed";
                    isSpeed = true;
                } else if (lower.contains("status=speed-profile]")) {
                    filter = "speed-profile";
                } else if (lower.contains("status=verify]")) {
                    filter = "verify";
                    if (lower.contains("reason=cmdline")) {
                        isCmdlineVerify = true;
                    }
                } else if (lower.contains("status=run-from-apk]")) {
                    filter = "run-from-apk";
                } else if (lower.contains("status=quicken]")) {
                    filter = "quicken";
                }
            }
        }
        return new PackageDexState(filter, isSpeed, isCmdlineVerify);
    }

    public static String getCompilerFilter(String packageName) {
        return getPackageDexState(packageName).filter;
    }

    public static boolean isAlreadySpeed(String packageName) {
        return getPackageDexState(packageName).isSpeed;
    }

    public static boolean optimizePackage(Context context, String packageName, boolean highPriority) {
        if (!hasPermission()) {
            AppLog.log(context, "[ERROR] " + packageName + ": Shizuku sin permisos");
            return false;
        }

        // 1. Comprobación rápida para no hacer llamadas a lo tonto
        PackageDexState state = getPackageDexState(packageName);
        if (state.isSpeed) {
            AppLog.log(context, "[SKIP] " + packageName + " ya está en modo 'speed'");
            return true;
        }

        // 2. Si NO es prioridad alta (es automático de fondo), comprobar condiciones de carga
        if (!highPriority && !SystemLoadMonitor.isSafeToOptimize(context)) {
            AppLog.log(context, "[ESPERA] " + packageName + ": Pospuesto por pantalla apagada o CPU ocupada.");
            return false;
        }

        if (highPriority) {
            AppLog.log(context, "[MANUAL] Compilando " + packageName + " a MÁXIMA PRIORIDAD (sin 'nice')...");
        } else {
            AppLog.log(context, "[FONDO] Compilando " + packageName + " en modo 'speed' con prioridad baja (nice 19)...");
        }

        // 3. Detener la aplicación para que no haya archivos bloqueados
        exec("am", "force-stop", packageName);

        // 4. Compilación:
        //    - Manual (highPriority): comando directo (sin nice) para máxima velocidad de compilación
        //    - Automático (fondo): envuelto en 'nice -n 19' para cero tirones en el sistema
        CommandResult compileRes;
        if (highPriority) {
            compileRes = exec("cmd", "package", "compile", "-m", "speed", "-f", packageName);
        } else {
            compileRes = exec("nice", "-n", "19", "cmd", "package", "compile", "-m", "speed", "-f", packageName);
        }

        // 5. Verificar resultado final
        PackageDexState newState = getPackageDexState(packageName);
        if (newState.isSpeed) {
            AppPreferences.setPackageExcluded(context, packageName, false);
            AppLog.log(context, "[ÉXITO] " + packageName + " compilado correctamente en 'speed'");
            notifyPackageUpdated(packageName, newState);
            return true;
        } else {
            // Regla general: Si tras forzar compilación a speed la app NO alcanza el estado speed,
            // se clasifica inmediatamente como NO optimizable (stub, debuggable, restricción de ART, etc.).
            // Se persiste como OMITIDA, actualiza la UI de inmediato y se descarta de futuros barridos.
            AppPreferences.setPackageExcluded(context, packageName, true);
            AppLog.log(context, "[OMITIDA] " + packageName + " no admite modo speed (estado: " + newState.filter + "). Marcada como omitida.");
            notifyPackageUpdated(packageName, newState);
            return false;
        }
    }

    public static List<AppItem> loadUserApps(Context context) {
        List<AppItem> list = new ArrayList<>();
        if (!hasPermission()) return list;

        CommandResult listRes = exec("pm", "list", "packages", "-3");
        if (listRes.exitCode != 0) return list;

        PackageManager pm = context.getPackageManager();
        String myPkg = context.getPackageName();

        for (String line : listRes.stdout.split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) {
                String pkg = line.substring(8).trim();
                if (!pkg.isEmpty() && !pkg.equals(myPkg)) {
                    String label = pkg;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        CharSequence cs = pm.getApplicationLabel(ai);
                        if (cs != null && !cs.toString().trim().isEmpty()) {
                            label = cs.toString();
                        }
                    } catch (Throwable ignored) {}

                    PackageDexState state = getPackageDexState(pkg);
                    boolean isExcluded = AppPreferences.isPackageExcluded(context, pkg);
                    boolean isNonOptimizable = (state.isCmdlineVerify || state.filter.equals("run-from-apk") || isExcluded) && !state.isSpeed;
                    list.add(new AppItem(pkg, label, state.isSpeed, isNonOptimizable, state.filter));
                }
            }
        }

        // Ordenar: primero las pendientes de compilar, luego las no optimizables, luego las ya en speed
        Collections.sort(list, new Comparator<AppItem>() {
            @Override
            public int compare(AppItem a, AppItem b) {
                int scoreA = a.isSpeed ? 2 : (a.isNonOptimizable ? 1 : 0);
                int scoreB = b.isSpeed ? 2 : (b.isNonOptimizable ? 1 : 0);
                if (scoreA != scoreB) {
                    return Integer.compare(scoreA, scoreB);
                }
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        return list;
    }

    public static void optimizeAllUserPackages(Context context, boolean highPriority, ProgressCallback callback) {
        if (!hasPermission()) {
            AppLog.log(context, "[ERROR] No se puede iniciar barrido: Shizuku sin permisos");
            return;
        }

        String modeText = highPriority ? "MÁXIMA PRIORIDAD (Manual)" : "Segundo plano (nice 19)";
        AppLog.log(context, "--- Iniciando comprobación en modo " + modeText + " ---");

        List<AppItem> apps = loadUserApps(context);
        int total = apps.size();
        AppLog.log(context, "Se encontraron " + total + " aplicaciones de usuario");

        int optimizedCount = 0;
        int skippedCount = 0;

        for (int i = 0; i < total; i++) {
            AppItem item = apps.get(i);
            if (callback != null) {
                callback.onProgress(item.packageName, i + 1, total);
            }

            if (item.isSpeed) {
                skippedCount++;
                AppLog.log(context, "[OK] (" + (i + 1) + "/" + total + ") " + item.label + " ya en speed");
            } else if (item.isNonOptimizable) {
                skippedCount++;
                AppLog.log(context, "[OMITIDA] (" + (i + 1) + "/" + total + ") " + item.label + " no admite speed (verify/debug)");
            } else {
                if (!highPriority && !SystemLoadMonitor.isSafeToOptimize(context)) {
                    AppLog.log(context, "[PAUSA] Sistema ocupado o pantalla apagada. Pausando barrido.");
                    break;
                }

                boolean ok = optimizePackage(context, item.packageName, highPriority);
                if (ok) {
                    optimizedCount++;
                    item.isSpeed = true;
                    item.currentFilter = "speed";
                }

                // Si es fondo, pausa de enfriamiento; si es manual prioritario, pausa mínima de 1 seg
                try {
                    Thread.sleep(highPriority ? 1000 : 3000);
                } catch (InterruptedException ignored) {}
            }
        }

        AppLog.log(context, "--- Finalizado: " + optimizedCount + " compiladas, " + skippedCount + " ya estaban en speed ---");
    }
}
