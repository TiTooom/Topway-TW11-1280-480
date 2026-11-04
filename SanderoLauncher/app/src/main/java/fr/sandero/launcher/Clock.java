package fr.sandero.launcher;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Heure affichée par Sandero, indépendante des réglages de l'autoradio :
 * - fuseau : Europe/Paris par défaut (le système est réglé sur Asia/Shanghai) ;
 * - décalage : si le GPS donne une heure différente de l'horloge interne, on corrige l'affichage.
 */
final class Clock {

    static final String DEFAULT_TZ = "Europe/Paris";
    private static volatile TimeZone zone = TimeZone.getTimeZone(DEFAULT_TZ);
    private static volatile long offset;          // à ajouter à System.currentTimeMillis()
    private static volatile boolean gpsSynced;
    private static SharedPreferences prefs;

    private Clock() {
    }

    static void init(Context c) {
        prefs = c.getSharedPreferences("clock", Context.MODE_PRIVATE);
        String tz = prefs.getString("tz", DEFAULT_TZ);
        zone = "system".equals(tz) ? TimeZone.getDefault() : TimeZone.getTimeZone(tz);
        offset = prefs.getBoolean("gps", true) ? prefs.getLong("offset", 0) : 0;
    }

    static long now() {
        return System.currentTimeMillis() + offset;
    }

    static TimeZone zone() {
        return zone;
    }

    static String zoneName() {
        return prefs == null ? DEFAULT_TZ : prefs.getString("tz", DEFAULT_TZ);
    }

    /** Fuseau d'affichage : un identifiant (Europe/Paris…) ou « system ». */
    static void setZone(String tz) {
        prefs.edit().putString("tz", tz).apply();
        zone = "system".equals(tz) ? TimeZone.getDefault() : TimeZone.getTimeZone(tz);
        Journal.log("HEURE", "fuseau d'affichage : " + tz);
    }

    static long offset() {
        return offset;
    }

    static boolean gpsSynced() {
        return gpsSynced;
    }

    static String format(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.FRANCE);
        f.setTimeZone(zone);
        return f.format(new Date(now()));
    }

    /** Horodatage du journal. */
    static String stamp() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
        f.setTimeZone(zone);
        return f.format(new Date(now()));
    }

    /** Écoute le GPS (toutes les minutes) pour recaler l'heure si l'horloge interne dérive. */
    static void startGps(final Context c) {
        if (prefs == null) init(c);
        if (!prefs.getBoolean("gps", true)) return;
        if (Build.VERSION.SDK_INT >= 23
                && c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Journal.log("HEURE", "GPS : autorisation de localisation absente");
            return;
        }
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null || !lm.getAllProviders().contains(LocationManager.GPS_PROVIDER)) return;
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000, 0, new LocationListener() {
                @Override
                public void onLocationChanged(Location l) {
                    long delta = l.getTime() - System.currentTimeMillis();
                    boolean first = !gpsSynced;
                    gpsSynced = true;
                    // On ne corrige que les vrais écarts (plus de 20 s) pour ne pas faire sauter l'heure.
                    long next = Math.abs(delta) > 20_000 ? delta : 0;
                    if (first || Math.abs(next - offset) > 20_000) {
                        offset = next;
                        prefs.edit().putLong("offset", next).apply();
                        Journal.log("HEURE", "GPS : écart horloge " + (delta / 1000) + " s → correction " + (next / 1000) + " s");
                    }
                }

                @Override public void onStatusChanged(String p, int s, Bundle b) { }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
            }, Looper.getMainLooper());
            Journal.log("HEURE", "GPS : écoute démarrée");
        } catch (Throwable t) {
            Journal.error("HEURE", "GPS indisponible", t);
        }
    }

    static void setGps(Context c, boolean on) {
        prefs.edit().putBoolean("gps", on).apply();
        if (!on) offset = 0;
        else startGps(c);
        Journal.log("HEURE", "recalage GPS : " + (on ? "oui" : "non"));
    }

    static boolean gpsEnabled() {
        return prefs == null || prefs.getBoolean("gps", true);
    }

    /** Champ texte qui affiche l'heure corrigée, mis à jour à chaque seconde pile. */
    static final class View extends TextView {
        private final String pattern;
        private final Handler h = new Handler(Looper.getMainLooper());
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                String s = format(pattern);
                if (!s.contentEquals(getText())) setText(s);
                h.postDelayed(this, 1000 - (now() % 1000));
            }
        };

        View(Context c, String pattern) {
            super(c);
            this.pattern = pattern;
        }

        View(Context c, AttributeSet a) {
            super(c, a);
            this.pattern = "HH:mm";
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            h.post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            h.removeCallbacks(tick);
            super.onDetachedFromWindow();
        }
    }
}
