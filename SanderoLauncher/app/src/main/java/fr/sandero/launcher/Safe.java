package fr.sandero.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

/**
 * Garde-fous contre les plantages.
 *
 * - Étapes risquées (ouverture TWUtil…) : on pose un marqueur avant, on l'enlève après.
 *   Si l'appli meurt pendant l'étape (même un plantage natif impossible à intercepter),
 *   le marqueur est encore là au démarrage suivant : l'étape est alors désactivée d'office.
 * - Plantages Java : journal dans crash.txt ; deux plantages en moins de 2 minutes →
 *   « mode sans échec » (intégration Topway et caméra coupées, l'écran d'accueil reste).
 */
final class Safe {

    private Safe() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("safe", Context.MODE_PRIVATE);
    }

    /** true si l'étape peut être tentée ; false si elle a planté la dernière fois ou est désactivée. */
    static boolean begin(Context c, String step) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("disabled_" + step, false) || safeMode(c)) return false;
        if (p.getBoolean("pending_" + step, false)) {
            Journal.log("SECU", "« " + step + " » a planté la dernière fois : désactivé d'office");
            p.edit().putBoolean("pending_" + step, false).putBoolean("disabled_" + step, true)
                    .putString("why_" + step, "plantage pendant « " + step + " » le " + new Date()).commit();
            return false;
        }
        p.edit().putBoolean("pending_" + step, true).commit();
        return true;
    }

    static void end(Context c, String step) {
        prefs(c).edit().putBoolean("pending_" + step, false).commit();
    }

    static boolean disabled(Context c, String step) {
        return prefs(c).getBoolean("disabled_" + step, false);
    }

    static String why(Context c, String step) {
        return prefs(c).getString("why_" + step, "");
    }

    static void enable(Context c, String step) {
        Journal.log("SECU", "réactivation de « " + step + " »");
        prefs(c).edit().putBoolean("disabled_" + step, false).putBoolean("pending_" + step, false).apply();
    }

    static boolean safeMode(Context c) {
        return prefs(c).getBoolean("safe_mode", false);
    }

    static void setSafeMode(Context c, boolean on) {
        Journal.log("SECU", "mode sans échec : " + (on ? "activé" : "désactivé"));
        prefs(c).edit().putBoolean("safe_mode", on).commit();
    }

    /** Installé une fois au démarrage du processus (voir SanderoApp). */
    static void installCrashHandler(final Context app) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                SharedPreferences p = prefs(app);
                long now = System.currentTimeMillis();
                long last = p.getLong("last_crash", 0);
                SharedPreferences.Editor ed = p.edit().putLong("last_crash", now)
                        .putInt("crashes", p.getInt("crashes", 0) + 1);
                if (now - last < 120_000) ed.putBoolean("safe_mode", true);
                ed.commit();
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                File f = new File(app.getFilesDir(), "crash.txt");
                try (FileWriter w = new FileWriter(f, true)) {
                    w.write("==== " + new Date() + " (" + t.getName() + ")\n" + sw + "\n");
                }
                File ext = app.getExternalFilesDir(null);
                if (ext != null) {
                    try (FileWriter w = new FileWriter(new File(ext, "crash.txt"), true)) {
                        w.write("==== " + new Date() + " (" + t.getName() + ")\n" + sw + "\n");
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Journal.log("CRASH", t.getName() + " : " + e);
                StackTraceElement[] st = e.getStackTrace();
                for (int i = 0; i < Math.min(6, st.length); i++) Journal.log("CRASH", "   à " + st[i]);
                Journal.flushNow();
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(t, e);
        });
    }

    static String crashLog(Context c) {
        File f = new File(c.getFilesDir(), "crash.txt");
        if (!f.exists()) return "";
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
            try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
                r.seek(Math.max(0, f.length() - b.length));
                r.readFully(b);
            }
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
