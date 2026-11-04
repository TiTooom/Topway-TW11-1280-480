package fr.sandero.launcher;

import android.app.Application;

/** Point d'entrée du processus : installe le journal des plantages. */
public class SanderoApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Clock.init(this);
        Journal.init(this);
        Safe.installCrashHandler(this);
        String v = "?";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        Journal.log("APP", "démarrage du processus · version " + v + " · fuseau système "
                + java.util.TimeZone.getDefault().getID() + " · affichage " + Clock.zoneName()
                + " · mode sans échec " + (Safe.safeMode(this) ? "OUI" : "non"));
    }
}
