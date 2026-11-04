package fr.sandero.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Journal des 1000 dernières actions, avec l'heure (heure corrigée, voir {@link Clock}).
 * Gardé en mémoire et réécrit sur disque en arrière-plan (au plus toutes les 2 s) :
 *   - stockage interne : files/journal.txt
 *   - copie accessible : Android/data/fr.sandero.launcher/files/journal.txt
 * Les lignes identiques consécutives sont regroupées (« ×12 ») pour ne pas noyer le journal.
 * Ne lève jamais d'exception : un journal ne doit pas pouvoir faire planter l'appli.
 */
final class Journal {

    private static final int MAX = 1000;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static String lastMsg = "";
    private static int repeat;
    private static File internal, external;
    private static Handler io;
    private static boolean flushScheduled;

    private Journal() {
    }

    /** À appeler une fois au démarrage (SanderoApp). */
    static synchronized void init(Context c) {
        if (io != null) return;
        try {
            internal = new File(c.getFilesDir(), "journal.txt");
            File ext = c.getExternalFilesDir(null);
            external = ext != null ? new File(ext, "journal.txt") : null;
            HandlerThread t = new HandlerThread("journal");
            t.start();
            io = new Handler(t.getLooper());
            if (internal.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(internal))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        lines.addLast(l);
                        if (lines.size() > MAX) lines.removeFirst();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Ajoute une ligne : catégorie courte (APP, MCU, CAM…) et message. */
    static void log(String cat, String msg) {
        try {
            String m = "[" + cat + "] " + msg;
            synchronized (Journal.class) {
                if (m.equals(lastMsg) && !lines.isEmpty()) {
                    repeat++;
                    lines.removeLast();
                    lines.addLast(Clock.stamp() + "  " + m + "  ×" + (repeat + 1));
                } else {
                    lastMsg = m;
                    repeat = 0;
                    lines.addLast(Clock.stamp() + "  " + m);
                    if (lines.size() > MAX) lines.removeFirst();
                }
                scheduleFlush();
            }
        } catch (Throwable ignored) {
        }
    }

    static void error(String cat, String msg, Throwable t) {
        log(cat, msg + " : " + (t == null ? "" : t.getClass().getSimpleName() + " " + t.getMessage()));
    }

    private static void scheduleFlush() {
        if (io == null || flushScheduled) return;
        flushScheduled = true;
        io.postDelayed(Journal::flush, 2000);
    }

    /** Écrit tout de suite (avant un plantage, par exemple). */
    static void flushNow() {
        flush();
    }

    private static void flush() {
        String text;
        synchronized (Journal.class) {
            flushScheduled = false;
            StringBuilder sb = new StringBuilder(lines.size() * 80);
            for (String l : lines) sb.append(l).append('\n');
            text = sb.toString();
        }
        write(internal, text);
        write(external, text);
    }

    private static void write(File f, String text) {
        if (f == null) return;
        try {
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(text.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static synchronized List<String> snapshot() {
        return new ArrayList<>(lines);
    }

    static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    static synchronized void clear() {
        lines.clear();
        lastMsg = "";
        scheduleFlush();
    }

    static File file() {
        return internal;
    }
}
