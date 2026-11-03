package dev.art.optimizer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity implements AppLog.LogListener {

    private static final int SHIZUKU_REQUEST_CODE = 4001;

    private TextView tvShizukuStatus;
    private Button btnShizukuAuth;
    private Button btnOptimizeNow;
    private Button btnRefreshList;
    private ProgressBar progressBar;
    private TextView tvProgressStatus;
    private TextView tvLogs;
    private ScrollView scrollLogs;
    private TextView btnClearLogs;

    // Switches y sus filas para navegación con mando de TV
    private View rowAutoInstall;
    private Switch switchAutoInstall;
    private View rowAllowSuspend;
    private Switch switchAllowSuspend;
    private View rowAvoidHeavy;
    private Switch switchAvoidHeavy;
    private View rowDaily;
    private Switch switchDaily;

    // Panel derecho: Contenedor vertical scrolleable para apps
    private TextView tvAppsSummary;
    private ScrollView scrollRightApps;
    private LinearLayout containerApps;

    private List<AppItem> currentApps = new ArrayList<>();
    private int lastRightFocusedIndex = 0;
    private View lastLeftFocusedView = null;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateShizukuUI();
                    loadAppsAsync();
                }
            });
        }
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = new Shizuku.OnBinderDeadListener() {
        @Override
        public void onBinderDead() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateShizukuUI();
                }
            });
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = new Shizuku.OnRequestPermissionResultListener() {
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateShizukuUI();
                        loadAppsAsync();
                    }
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvShizukuStatus = findViewById(R.id.tv_shizuku_status);
        btnShizukuAuth = findViewById(R.id.btn_shizuku_auth);
        btnOptimizeNow = findViewById(R.id.btn_optimize_now);
        btnRefreshList = findViewById(R.id.btn_refresh_list);
        progressBar = findViewById(R.id.progress_bar);
        tvProgressStatus = findViewById(R.id.tv_progress_status);
        tvLogs = findViewById(R.id.tv_logs);
        scrollLogs = findViewById(R.id.scroll_logs);
        btnClearLogs = findViewById(R.id.btn_clear_logs);

        tvAppsSummary = findViewById(R.id.tv_apps_summary);
        scrollRightApps = findViewById(R.id.scroll_right_apps);
        containerApps = findViewById(R.id.container_apps);

        // Configuración de filas y switches para control desde mando de TV
        rowAutoInstall = findViewById(R.id.row_auto_install);
        switchAutoInstall = findViewById(R.id.switch_auto_install);
        setupTvSwitchRow(rowAutoInstall, switchAutoInstall, new OnToggleCallback() {
            @Override
            public void onToggle(boolean isChecked) {
                AppPreferences.setAutoInstallEnabled(MainActivity.this, isChecked);
            }
        }, AppPreferences.isAutoInstallEnabled(this));

        rowAllowSuspend = findViewById(R.id.row_allow_suspend);
        switchAllowSuspend = findViewById(R.id.switch_allow_suspend);
        setupTvSwitchRow(rowAllowSuspend, switchAllowSuspend, new OnToggleCallback() {
            @Override
            public void onToggle(boolean isChecked) {
                AppPreferences.setOptimizeOnSuspendEnabled(MainActivity.this, isChecked);
            }
        }, AppPreferences.isOptimizeOnSuspendEnabled(this));


        rowAvoidHeavy = findViewById(R.id.row_avoid_heavy);
        switchAvoidHeavy = findViewById(R.id.switch_avoid_heavy);
        setupTvSwitchRow(rowAvoidHeavy, switchAvoidHeavy, new OnToggleCallback() {
            @Override
            public void onToggle(boolean isChecked) {
                AppPreferences.setAvoidHeavyTasks(MainActivity.this, isChecked);
            }
        }, AppPreferences.isAvoidHeavyTasks(this));

        rowDaily = findViewById(R.id.row_daily);
        switchDaily = findViewById(R.id.switch_daily);
        setupTvSwitchRow(rowDaily, switchDaily, new OnToggleCallback() {
            @Override
            public void onToggle(boolean isChecked) {
                AppPreferences.setDailyMaintenanceEnabled(MainActivity.this, isChecked);
                if (isChecked) {
                    ArtJobService.schedule(MainActivity.this);
                    DailyMaintenanceReceiver.scheduleDaily(MainActivity.this);
                }
            }
        }, AppPreferences.isDailyMaintenanceEnabled(this));

        // Enlace desde el panel izquierdo hacia la lista con la flecha DERECHA del mando
        View.OnKeyListener leftPanelKeyListener = new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    lastLeftFocusedView = v;
                    focusRightPanel();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    return true; // No salir del panel izquierdo hacia la nada
                }
                return false;
            }
        };

        setupLeftFocusable(btnOptimizeNow, leftPanelKeyListener);
        setupLeftFocusable(btnRefreshList, leftPanelKeyListener);
        setupLeftFocusable(rowAutoInstall, leftPanelKeyListener);
        setupLeftFocusable(rowAllowSuspend, leftPanelKeyListener);
        setupLeftFocusable(rowAvoidHeavy, leftPanelKeyListener);
        setupLeftFocusable(rowDaily, leftPanelKeyListener);
        setupLeftFocusable(btnShizukuAuth, leftPanelKeyListener);
        setupLeftFocusable(btnClearLogs, leftPanelKeyListener);

        // Bloqueo de tope arriba en el primer botón del panel izquierdo
        btnOptimizeNow.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true; // No salirse por arriba
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true;
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    lastLeftFocusedView = v;
                    focusRightPanel();
                    return true;
                }
                return false;
            }
        });

        // Navegación desde el botón limpiar logs
        btnClearLogs.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    scrollLogs.requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true;
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    lastLeftFocusedView = v;
                    focusRightPanel();
                    return true;
                }
                return false;
            }
        });

        // Navegación y desplazamiento suave por el visor de logs con el mando TV
        scrollLogs.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    lastLeftFocusedView = v;
                }
            }
        });
        scrollLogs.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    scrollLogs.smoothScrollBy(0, 90);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (scrollLogs.getScrollY() > 0) {
                        scrollLogs.smoothScrollBy(0, -90);
                        return true;
                    } else {
                        btnClearLogs.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    lastLeftFocusedView = v;
                    focusRightPanel();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    return true;
                }
                return false;
            }
        });

        // Botón autorizar Shizuku
        btnShizukuAuth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    if (Shizuku.isPreV11()) {
                        requestPermissions(new String[]{"moe.shizuku.manager.permission.API_V23"}, SHIZUKU_REQUEST_CODE);
                    } else {
                        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
                    }
                } catch (Throwable t) {
                    AppLog.log(MainActivity.this, "[ERROR] Al pedir permiso a Shizuku: " + t.getMessage());
                }
            }
        });

        // Botón optimizar todo manualmente (Máxima velocidad, sin nice)
        btnOptimizeNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runManualBatchOptimization();
            }
        });

        // Botón refrescar lista
        btnRefreshList.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadAppsAsync();
            }
        });

        // Botón limpiar logs
        btnClearLogs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLog.clear(MainActivity.this);
                tvLogs.setText("Sin actividad registrada aún.");
            }
        });

        // Shizuku listeners
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        // Iniciar servicios
        ArtJobService.schedule(this);
        DailyMaintenanceReceiver.scheduleDaily(this);

        if ("Sin actividad registrada aún.".equals(AppLog.getLogs(this))) {
            AppLog.log(this, "[LISTO] Servicio iniciado. Esperando órdenes...");
        }
        tvLogs.setText(AppLog.getLogs(this));
        lastLeftFocusedView = btnOptimizeNow;
        btnOptimizeNow.requestFocus();
    }

    private void focusRightPanel() {
        if (containerApps == null || containerApps.getChildCount() == 0) return;
        int targetIdx = Math.max(0, Math.min(lastRightFocusedIndex, containerApps.getChildCount() - 1));
        View target = containerApps.getChildAt(targetIdx);
        if (target != null) {
            target.requestFocus();
        }
    }

    private interface OnToggleCallback {
        void onToggle(boolean isChecked);
    }

    private void setupTvSwitchRow(View row, final Switch sw, final OnToggleCallback callback, boolean initialChecked) {
        sw.setChecked(initialChecked);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean newState = !sw.isChecked();
                sw.setChecked(newState);
                callback.onToggle(newState);
            }
        });
    }

    private void setupLeftFocusable(final View v, View.OnKeyListener listener) {
        if (v == null) return;
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus) {
                    lastLeftFocusedView = view;
                }
            }
        });
        v.setOnKeyListener(listener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLog.setListener(this);
        ShizukuRunner.setPackageUpdateListener(new ShizukuRunner.PackageUpdateListener() {
            @Override
            public void onPackageOptimized(final String packageName, final ShizukuRunner.PackageDexState newState) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateSingleAppInList(packageName, newState);
                    }
                });
            }
        });
        updateShizukuUI();
        tvLogs.setText(AppLog.getLogs(this));
        loadAppsAsync();
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppLog.setListener(null);
        ShizukuRunner.setPackageUpdateListener(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
    }

    private void updateShizukuUI() {
        if (!ShizukuRunner.isShizukuAvailable()) {
            tvShizukuStatus.setText("❌ Inactivo");
            tvShizukuStatus.setTextColor(0xFFFFFFFF);
            tvShizukuStatus.setBackgroundResource(R.drawable.badge_yellow);
            btnShizukuAuth.setVisibility(View.GONE);
            btnOptimizeNow.setEnabled(false);
            btnRefreshList.setEnabled(false);
            return;
        }

        if (ShizukuRunner.hasPermission()) {
            tvShizukuStatus.setText("✔ Shizuku activo");
            tvShizukuStatus.setTextColor(0xFFFFFFFF);
            tvShizukuStatus.setBackgroundResource(R.drawable.badge_green);
            btnShizukuAuth.setVisibility(View.GONE);
            btnOptimizeNow.setEnabled(true);
            btnRefreshList.setEnabled(true);
        } else {
            tvShizukuStatus.setText("⚠ Sin permiso");
            tvShizukuStatus.setTextColor(0xFFFFFFFF);
            tvShizukuStatus.setBackgroundResource(R.drawable.badge_yellow);
            btnShizukuAuth.setVisibility(View.VISIBLE);
            btnOptimizeNow.setEnabled(false);
            btnRefreshList.setEnabled(false);
        }
    }

    private void loadAppsAsync() {
        if (!ShizukuRunner.hasPermission()) return;
        tvAppsSummary.setText("Actualizando lista...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppItem> apps = ShizukuRunner.loadUserApps(MainActivity.this);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        renderAppsList(apps);
                        updateSummaryText();
                    }
                });
            }
        }, "load-apps-thread").start();
    }

    private void updateSummaryText() {
        int speedCount = 0;
        int nonOptCount = 0;
        int pendingCount = 0;
        for (AppItem a : currentApps) {
            if (a.isSpeed) speedCount++;
            else if (a.isNonOptimizable) nonOptCount++;
            else pendingCount++;
        }
        if (pendingCount == 0) {
            tvAppsSummary.setText(speedCount + " SPEED | " + nonOptCount + " omitidas | Todo optimizado ✔");
        } else {
            tvAppsSummary.setText(speedCount + " SPEED | " + nonOptCount + " omitidas | " + pendingCount + " pendientes");
        }
    }

    private void renderAppsList(final List<AppItem> apps) {
        currentApps = apps != null ? apps : new ArrayList<AppItem>();
        containerApps.removeAllViews();
        final int count = currentApps.size();
        if (count == 0) return;

        final List<View> rowViews = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            final int index = i;
            final AppItem item = currentApps.get(i);
            final View rowView = getLayoutInflater().inflate(R.layout.item_app, containerApps, false);
            rowView.setId(View.generateViewId());
            rowView.setTag(item.packageName);

            final TextView tvCursor = rowView.findViewById(R.id.tv_cursor);
            final TextView tvLabel = rowView.findViewById(R.id.tv_app_label);
            final TextView tvPackage = rowView.findViewById(R.id.tv_app_package);
            final TextView tvHint = rowView.findViewById(R.id.tv_app_hint);
            final TextView tvStatus = rowView.findViewById(R.id.tv_app_status);

            tvLabel.setText(item.label);
            tvPackage.setText(item.packageName);

            updateItemBadge(item, tvStatus, tvHint);

            rowView.setFocusable(true);
            rowView.setClickable(true);

            rowView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        lastRightFocusedIndex = index;
                        tvCursor.setVisibility(View.VISIBLE);
                        tvLabel.setTextColor(0xFFFFFFFF);
                        tvHint.setVisibility(View.VISIBLE);
                        if (item.isCompiling) {
                            tvHint.setText("Compilando con prioridad alta (sin nice)...");
                        } else if (item.isNonOptimizable) {
                            tvHint.setText("No compatible con speed (debug/stub). Omitida permanentemente.");
                        } else if (item.isSpeed) {
                            tvHint.setText("Totalmente optimizada a velocidad nativa (modo speed ✔)");
                        } else {
                            String filter = item.currentFilter != null ? item.currentFilter.toLowerCase() : "";
                            if (filter.contains("speed-profile")) {
                                tvHint.setText("Optimización parcial de Google Play. Pulsa [OK] para compilar completa (speed)");
                            } else {
                                tvHint.setText("Sin optimizar. Pulsa [OK] para compilar a máxima velocidad (speed)");
                            }
                        }
                        scrollRightApps.requestChildFocus(containerApps, v);
                    } else {
                        tvCursor.setVisibility(View.GONE);
                        tvHint.setVisibility(View.GONE);
                        tvLabel.setTextColor(0xFFCBD5E1);
                    }
                }
            });

            rowView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (item.isCompiling) return;
                    optimizeSingleAppManual(item, rowView);
                }
            });

            rowView.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (index < count - 1) {
                            View next = containerApps.getChildAt(index + 1);
                            if (next != null) {
                                next.requestFocus();
                            }
                        }
                        return true; // Consumido: nunca salta fuera de la lista
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        if (index > 0) {
                            View prev = containerApps.getChildAt(index - 1);
                            if (prev != null) {
                                prev.requestFocus();
                            }
                        }
                        return true; // Consumido: nunca salta fuera de la lista
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (lastLeftFocusedView != null && lastLeftFocusedView.isShown()) {
                            lastLeftFocusedView.requestFocus();
                        } else {
                            btnOptimizeNow.requestFocus();
                        }
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        return true; // Se mantiene en el panel derecho
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        if (!item.isCompiling) {
                            optimizeSingleAppManual(item, rowView);
                        }
                        return true;
                    }
                    return false;
                }
            });

            containerApps.addView(rowView);
            rowViews.add(rowView);
        }

        // Configuración de IDs de navegación vertical estricta entre elementos
        for (int i = 0; i < count; i++) {
            View row = rowViews.get(i);
            row.setNextFocusLeftId(lastLeftFocusedView != null ? lastLeftFocusedView.getId() : R.id.btn_optimize_now);
            row.setNextFocusRightId(row.getId());
            if (i == 0) {
                row.setNextFocusUpId(row.getId());
            } else {
                row.setNextFocusUpId(rowViews.get(i - 1).getId());
            }
            if (i == count - 1) {
                row.setNextFocusDownId(row.getId());
            } else {
                row.setNextFocusDownId(rowViews.get(i + 1).getId());
            }
        }
    }

    private void updateItemBadge(AppItem item, TextView tvStatus, TextView tvHint) {
        if (item.isCompiling) {
            tvStatus.setText("COMPILANDO...");
            tvStatus.setBackgroundResource(R.drawable.badge_yellow);
        } else if (item.isSpeed) {
            tvStatus.setText("✔ SPEED");
            tvStatus.setBackgroundResource(R.drawable.badge_green);
        } else if (item.isNonOptimizable) {
            tvStatus.setText("⚪ OMITIDA");
            tvStatus.setBackgroundResource(R.drawable.badge_gray);
        } else {
            String filter = item.currentFilter != null ? item.currentFilter.toLowerCase() : "";
            if (filter.contains("speed-profile")) {
                tvStatus.setText("⚠ PARCIAL");
            } else if (filter.contains("verify") || filter.contains("run-from-apk") || filter.contains("quicken")) {
                tvStatus.setText("⚠ SIN OPTIMIZAR");
            } else {
                tvStatus.setText("⚠ PENDIENTE");
            }
            tvStatus.setBackgroundResource(R.drawable.badge_yellow);
        }
    }

    private void updateSingleAppInList(String packageName, ShizukuRunner.PackageDexState newState) {
        if (packageName == null || newState == null) return;

        AppItem foundItem = null;
        for (AppItem a : currentApps) {
            if (packageName.equals(a.packageName)) {
                foundItem = a;
                break;
            }
        }

        if (foundItem == null) {
            loadAppsAsync();
            return;
        }

        foundItem.isSpeed = newState.isSpeed;
        foundItem.currentFilter = newState.filter;
        foundItem.isNonOptimizable = (newState.isCmdlineVerify || AppPreferences.isPackageExcluded(this, packageName)) && !newState.isSpeed;
        foundItem.isCompiling = false;

        View rowView = containerApps.findViewWithTag(packageName);
        if (rowView != null) {
            TextView tvStatus = rowView.findViewById(R.id.tv_app_status);
            TextView tvHint = rowView.findViewById(R.id.tv_app_hint);
            updateItemBadge(foundItem, tvStatus, tvHint);

            if (rowView.hasFocus()) {
                tvHint.setVisibility(View.VISIBLE);
                if (foundItem.isSpeed) {
                    tvHint.setText("Totalmente optimizada a máxima velocidad (modo speed ✔)");
                } else if (foundItem.isNonOptimizable) {
                    tvHint.setText("No compatible con speed (debug/stub). Omitida permanentemente.");
                }
            }
        }

        updateSummaryText();
    }

    /**
     * Optimiza una app individual al pulsar [OK] sobre ella en el mando de la TV.
     * Se ejecuta con PRIORIDAD ALTA (sin 'nice') para máxima velocidad.
     */
    private void optimizeSingleAppManual(final AppItem item, final View rowView) {
        item.isCompiling = true;
        final TextView tvStatus = rowView.findViewById(R.id.tv_app_status);
        final TextView tvHint = rowView.findViewById(R.id.tv_app_hint);
        updateItemBadge(item, tvStatus, tvHint);
        tvHint.setVisibility(View.VISIBLE);
        tvHint.setText("Compilando con prioridad alta (sin nice)...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                // true = PRIORIDAD ALTA (sin 'nice')
                boolean ok = ShizukuRunner.optimizePackage(MainActivity.this, item.packageName, true);
                item.isCompiling = false;
                ShizukuRunner.PackageDexState state = ShizukuRunner.getPackageDexState(item.packageName);
                item.isSpeed = state.isSpeed;
                item.isNonOptimizable = (state.isCmdlineVerify || AppPreferences.isPackageExcluded(MainActivity.this, item.packageName)) && !state.isSpeed;
                item.currentFilter = state.filter;

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateItemBadge(item, tvStatus, tvHint);
                        if (rowView.hasFocus()) {
                            tvHint.setVisibility(View.VISIBLE);
                            if (item.isNonOptimizable) {
                                tvHint.setText("No compatible con speed (debug/stub). Omitida permanentemente.");
                            } else if (item.isSpeed) {
                                tvHint.setText("Totalmente optimizada a máxima velocidad (modo speed ✔)");
                            }
                        }
                        updateSummaryText();
                    }
                });
            }
        }, "manual-single-compile").start();
    }

    /**
     * Optimiza todas las apps pendientes manualmente con PRIORIDAD ALTA (sin 'nice').
     */
    private void runManualBatchOptimization() {
        btnOptimizeNow.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvProgressStatus.setVisibility(View.VISIBLE);
        tvProgressStatus.setText("Iniciando optimización manual prioritaria...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                // true = PRIORIDAD ALTA (sin 'nice')
                ShizukuRunner.optimizeAllUserPackages(MainActivity.this, true, new ShizukuRunner.ProgressCallback() {
                    @Override
                    public void onProgress(final String currentPackage, final int current, final int total) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                tvProgressStatus.setText("(" + current + "/" + total + ") " + currentPackage);
                            }
                        });
                    }
                });

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        btnOptimizeNow.setEnabled(true);
                        progressBar.setVisibility(View.GONE);
                        tvProgressStatus.setText("¡Optimización prioritaria completada!");
                        loadAppsAsync();
                    }
                });
            }
        }, "art-manual-batch").start();
    }

    @Override
    public void onLogUpdated() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (tvLogs != null) {
                    tvLogs.setText(AppLog.getLogs(MainActivity.this));
                }
            }
        });
    }
}
