package dev.art.optimizer;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AppLog {
    private static final String PREF_NAME = "art_optimizer_logs";
    private static final String KEY_LOGS = "logs";
    private static final int MAX_LINES = 60;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public interface LogListener {
        void onLogUpdated();
    }

    private static LogListener listener;

    public static void setListener(LogListener l) {
        listener = l;
    }

    public static synchronized void log(Context context, String message) {
        String timestamp = DATE_FORMAT.format(new Date());
        String entry = "[" + timestamp + "] " + message;
        try {
            android.util.Log.i("ArtAutoOptimizer", message);
        } catch (Throwable ignored) {}

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String current = prefs.getString(KEY_LOGS, "");
        String[] lines = current.isEmpty() ? new String[0] : current.split("\n");

        StringBuilder sb = new StringBuilder(entry);
        int count = 1;
        for (String line : lines) {
            if (count >= MAX_LINES) break;
            sb.append("\n").append(line);
            count++;
        }

        prefs.edit().putString(KEY_LOGS, sb.toString()).commit();

        if (listener != null) {
            listener.onLogUpdated();
        }
    }

    public static String getLogs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_LOGS, "Sin actividad registrada aún.");
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply();
        if (listener != null) {
            listener.onLogUpdated();
        }
    }
}
