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
import java.util.Iterator;
import java.util.List;

/**
 * Journal des 10 000 dernières actions, avec l'heure (heure corrigée, voir {@link Clock}).
 * Gardé en mémoire et écrit sur disque en arrière-plan (au plus toutes les 2 s) :
 *   - stockage interne : files/journal.txt
 *   - copie accessible : Android/data/fr.sandero.launcher/files/journal.txt
 * Les lignes identiques consécutives sont regroupées (« ×12 ») pour ne pas noyer le journal.
 *
 * Écriture par ajout en fin de fichier (10 000 lignes ≈ 1,2 Mo : tout réécrire toutes les 2 s
 * userait la mémoire flash). La dernière ligne reste « ouverte » (son « ×N » peut encore
 * changer) : elle n'est ajoutée qu'une fois close. Le fichier est réécrit en entier
 * seulement quand il dépasse 12 000 lignes, ou après {@link #flushNow()}.
 * Ne lève jamais d'exception : un journal ne doit pas pouvoir faire planter l'appli.
 */
final class Journal {

    private static final int MAX = 10000;
    /** Au-delà, le fichier est réécrit avec seulement les MAX dernières lignes. */
    private static final int FILE_MAX = MAX + MAX / 5;

    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static String lastMsg = "";
    private static int repeat;
    private static File internal, external;
    private static Handler io;
    private static boolean flushScheduled;

    /** Lignes closes pas encore ajoutées au fichier (toutes en fin de liste, avant la ligne ouverte). */
    private static int unwritten;
    /** Nombre de lignes dans le fichier. */
    private static int fileLines;
    /** Le fichier contient la ligne ouverte (après flushNow) : prochaine écriture = réécriture complète. */
    private static boolean rewrite = true;

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
            // Les lignes relues sont closes ; on repart d'un fichier propre à la première écriture.
            lastMsg = "";
            rewrite = true;
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
                    if (!lines.isEmpty() && !lastMsg.isEmpty()) unwritten++; // la ligne ouverte devient close
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

    /** Écrit tout de suite, ligne ouverte comprise (avant un plantage, un export…). */
    static void flushNow() {
        String text;
        synchronized (Journal.class) {
            text = join(lines.size());
            fileLines = lines.size();
            unwritten = 0;
            rewrite = true;   // la ligne ouverte est sur disque : réécrire au prochain coup
        }
        write(internal, text, false);
        write(external, text, false);
    }

    private static void flush() {
        String text;
        boolean append;
        synchronized (Journal.class) {
            flushScheduled = false;
            int closed = Math.max(0, lines.size() - (lastMsg.isEmpty() ? 0 : 1));
            if (rewrite || unwritten > closed || fileLines + unwritten > FILE_MAX) {
                text = join(closed);
                append = false;
                fileLines = closed;
                rewrite = false;
            } else {
                if (unwritten == 0) return;
                // Les « unwritten » dernières lignes closes
                StringBuilder sb = new StringBuilder(unwritten * 100);
                int skip = closed - unwritten, i = 0;
                for (Iterator<String> it = lines.iterator(); it.hasNext() && i < closed; i++) {
                    String l = it.next();
                    if (i >= skip) sb.append(l).append('\n');
                }
                text = sb.toString();
                append = true;
                fileLines += unwritten;
            }
            unwritten = 0;
        }
        write(internal, text, append);
        write(external, text, append);
    }

    /** Les n premières lignes, une par ligne. */
    private static String join(int n) {
        StringBuilder sb = new StringBuilder(n * 100);
        int i = 0;
        for (Iterator<String> it = lines.iterator(); it.hasNext() && i < n; i++) sb.append(it.next()).append('\n');
        return sb.toString();
    }

    private static void write(File f, String text, boolean append) {
        if (f == null) return;
        try {
            if (append) {
                try (FileOutputStream o = new FileOutputStream(f, true)) {
                    o.write(text.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
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
        return join(lines.size());
    }

    static synchronized void clear() {
        lines.clear();
        lastMsg = "";
        unwritten = 0;
        rewrite = true;
        scheduleFlush();
    }

    static File file() {
        return internal;
    }
}
