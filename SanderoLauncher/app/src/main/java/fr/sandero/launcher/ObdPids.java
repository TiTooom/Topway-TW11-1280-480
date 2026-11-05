package fr.sandero.launcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Table des valeurs OBD2 « mode 01 » (norme SAE J1979 / ISO 15031-5) : nom, unité, formule.
 * Les formules viennent de la norme (sûr) ; quelles valeurs la Sandero donne vraiment
 * dépend de son calculateur (lu au branchement : PID pris en charge).
 *
 * d[] = octets de données reçus après « 41 PID » (A = d[0], B = d[1]…).
 * Ids ≥ 0x1000 : valeurs calculées par Sandero (pas des PID du véhicule).
 */
final class ObdPids {

    interface F { double f(int[] d); }
    interface T { String t(int[] d); }

    static final class P {
        final int pid, n, dec;
        final String name, unit;
        final boolean fast, once;
        final F f;
        final T t;
        final int order;

        P(int pid, String name, String unit, int n, int dec, boolean fast, boolean once, F f, T t, int order) {
            this.pid = pid; this.name = name; this.unit = unit; this.n = n; this.dec = dec;
            this.fast = fast; this.once = once; this.f = f; this.t = t; this.order = order;
        }

        /** Décode les octets ; renvoie false si trop courts. */
        boolean decode(int[] d, Obd.Value v) {
            if (d.length < n) return false;
            if (t != null) {
                v.text = t.t(d);
                v.num = Double.NaN;
            } else {
                v.num = f.f(d);
                v.text = null;
            }
            return true;
        }

        String format(Obd.Value v) {
            if (v == null) return "…";
            if (v.noData) return "pas de réponse";
            if (v.text != null) return v.text;
            if (Double.isNaN(v.num)) return "…";
            return String.format(Locale.FRANCE, "%." + dec + "f", v.num);
        }
    }

    // Valeurs calculées
    static final int ID_VOLT = 0x1000, ID_LH = 0x1001, ID_L100 = 0x1002;

    private static final List<P> ALL = new ArrayList<>();
    private static final Map<Integer, P> MAP = new HashMap<>();

    static P get(int pid) {
        return MAP.get(pid);
    }

    static List<P> all() {
        return ALL;
    }

    static String hex(int[] d) {
        StringBuilder sb = new StringBuilder();
        for (int b : d) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    private static int ab(int[] d) { return d[0] * 256 + d[1]; }
    private static int cd(int[] d) { return d[2] * 256 + d[3]; }
    private static double pct(int a) { return a * 100.0 / 255.0; }
    private static double trim(int a) { return (a - 128) * 100.0 / 128.0; }

    private static void num(int pid, String name, String unit, int n, int dec, boolean fast, F f) {
        add(new P(pid, name, unit, n, dec, fast, false, f, null, ALL.size()));
    }

    private static void txt(int pid, String name, int n, boolean once, T t) {
        add(new P(pid, name, "", n, 0, false, once, null, t, ALL.size()));
    }

    private static void add(P p) {
        ALL.add(p);
        MAP.put(p.pid, p);
    }

    private static String pick(String[] names, int i) {
        return i >= 0 && i < names.length && names[i] != null ? names[i] : "code " + i;
    }

    static {
        // --- Calculées (toujours en tête)
        add(new P(ID_VOLT, "Batterie (prise OBD)", "V", 0, 1, false, false, null, null, -3));
        add(new P(ID_LH, "Conso instantanée (calcul)", "L/h", 0, 1, false, false, null, null, -2));
        add(new P(ID_L100, "Conso (calcul)", "L/100", 0, 1, false, false, null, null, -1));

        // --- Essentiel, lu à chaque tour
        num(0x0C, "Régime moteur", "tr/min", 2, 0, true, d -> ab(d) / 4.0);
        num(0x0D, "Vitesse", "km/h", 1, 0, true, d -> d[0]);
        num(0x05, "Liquide de refroidissement", "°C", 1, 0, false, d -> d[0] - 40);
        num(0x04, "Charge moteur", "%", 1, 0, true, d -> pct(d[0]));
        num(0x11, "Papillon", "%", 1, 0, true, d -> pct(d[0]));
        num(0x0B, "Pression admission (MAP)", "kPa", 1, 0, true, d -> d[0]);
        num(0x10, "Débit d'air (MAF)", "g/s", 2, 1, true, d -> ab(d) / 100.0);
        num(0x0E, "Avance à l'allumage", "°", 1, 1, true, d -> d[0] / 2.0 - 64);
        num(0x5E, "Débit carburant", "L/h", 2, 2, true, d -> ab(d) / 20.0);
        num(0x49, "Pédale d'accélérateur", "%", 1, 0, true, d -> pct(d[0]));
        num(0x62, "Couple réel", "%", 1, 0, true, d -> d[0] - 125);

        // --- Températures, carburant, pressions
        num(0x0F, "Air d'admission", "°C", 1, 0, false, d -> d[0] - 40);
        num(0x5C, "Huile moteur", "°C", 1, 0, false, d -> d[0] - 40);
        num(0x46, "Température extérieure", "°C", 1, 0, false, d -> d[0] - 40);
        num(0x2F, "Niveau de carburant", "%", 1, 0, false, d -> pct(d[0]));
        num(0x33, "Pression atmosphérique", "kPa", 1, 0, false, d -> d[0]);
        num(0x42, "Tension calculateur", "V", 2, 2, false, d -> ab(d) / 1000.0);
        num(0x0A, "Pression carburant", "kPa", 1, 0, false, d -> d[0] * 3);
        num(0x22, "Pression rampe (relative)", "kPa", 2, 0, false, d -> ab(d) * 0.079);
        num(0x23, "Pression rampe (diesel)", "bar", 2, 0, false, d -> ab(d) / 10.0);
        num(0x59, "Pression rampe absolue", "bar", 2, 0, false, d -> ab(d) / 10.0);
        num(0x5D, "Calage injection", "°", 2, 2, false, d -> ab(d) / 128.0 - 210);
        num(0xA6, "Compteur kilométrique", "km", 4, 1, false,
                d -> (((long) d[0] << 24) | (d[1] << 16) | (d[2] << 8) | d[3]) / 10.0);

        // --- Richesse
        num(0x06, "Correction court terme B1", "%", 1, 1, false, d -> trim(d[0]));
        num(0x07, "Correction long terme B1", "%", 1, 1, false, d -> trim(d[0]));
        num(0x08, "Correction court terme B2", "%", 1, 1, false, d -> trim(d[0]));
        num(0x09, "Correction long terme B2", "%", 1, 1, false, d -> trim(d[0]));
        num(0x44, "Richesse commandée (λ)", "", 2, 3, false, d -> ab(d) * 2.0 / 65536.0);
        for (int i = 0; i < 8; i++) {
            txt(0x14 + i, "Sonde O2 n°" + (i + 1), 2, false, d -> String.format(Locale.FRANCE, "%.3f V", d[0] / 200.0)
                    + (d[1] == 0xFF ? "" : String.format(Locale.FRANCE, " · %+.0f %%", trim(d[1]))));
        }
        for (int i = 0; i < 8; i++) {
            txt(0x24 + i, "Sonde large bande n°" + (i + 1) + " (tension)", 4, false, d ->
                    String.format(Locale.FRANCE, "λ %.3f · %.2f V", ab(d) * 2.0 / 65536.0, cd(d) * 8.0 / 65536.0));
        }
        for (int i = 0; i < 8; i++) {
            txt(0x34 + i, "Sonde large bande n°" + (i + 1) + " (courant)", 4, false, d ->
                    String.format(Locale.FRANCE, "λ %.3f · %.2f mA", ab(d) * 2.0 / 65536.0, cd(d) / 256.0 - 128));
        }
        for (int i = 0; i < 4; i++) {
            String[] w = {"B1 S1", "B2 S1", "B1 S2", "B2 S2"};
            num(0x3C + i, "Catalyseur " + w[i], "°C", 2, 0, false, d -> ab(d) / 10.0 - 40);
        }
        num(0x55, "Correction O2 secondaire court terme", "%", 1, 1, false, d -> trim(d[0]));
        num(0x56, "Correction O2 secondaire long terme", "%", 1, 1, false, d -> trim(d[0]));
        num(0x57, "Correction O2 secondaire court terme B2", "%", 1, 1, false, d -> trim(d[0]));
        num(0x58, "Correction O2 secondaire long terme B2", "%", 1, 1, false, d -> trim(d[0]));

        // --- Charge, papillon, pédale, couple
        num(0x43, "Charge absolue", "%", 2, 0, false, d -> ab(d) * 100.0 / 255.0);
        num(0x45, "Papillon relatif", "%", 1, 0, false, d -> pct(d[0]));
        num(0x47, "Papillon B", "%", 1, 0, false, d -> pct(d[0]));
        num(0x48, "Papillon C", "%", 1, 0, false, d -> pct(d[0]));
        num(0x4A, "Pédale E", "%", 1, 0, false, d -> pct(d[0]));
        num(0x4B, "Pédale F", "%", 1, 0, false, d -> pct(d[0]));
        num(0x4C, "Papillon commandé", "%", 1, 0, false, d -> pct(d[0]));
        num(0x5A, "Pédale relative", "%", 1, 0, false, d -> pct(d[0]));
        num(0x61, "Couple demandé", "%", 1, 0, false, d -> d[0] - 125);
        num(0x63, "Couple de référence", "Nm", 2, 0, false, d -> ab(d));
        num(0x8E, "Couple de friction", "%", 1, 0, false, d -> d[0] - 125);

        // --- EGR, canister
        num(0x2C, "EGR commandée", "%", 1, 0, false, d -> pct(d[0]));
        num(0x2D, "Erreur EGR", "%", 1, 0, false, d -> trim(d[0]));
        num(0x2E, "Purge canister commandée", "%", 1, 0, false, d -> pct(d[0]));
        num(0x32, "Pression vapeur canister", "Pa", 2, 0, false, d -> ((short) ab(d)) / 4.0);
        num(0x53, "Pression vapeur absolue", "kPa", 2, 1, false, d -> ab(d) / 200.0);
        num(0x54, "Pression vapeur", "Pa", 2, 0, false, d -> ab(d) - 32767);
        num(0x52, "Taux d'éthanol", "%", 1, 0, false, d -> pct(d[0]));
        num(0x5B, "Batterie hybride", "%", 1, 0, false, d -> pct(d[0]));

        // --- Compteurs
        txt(0x1F, "Temps depuis le démarrage", 2, false, d -> {
            int s = ab(d);
            return (s / 60) + " min " + String.format(Locale.ROOT, "%02d", s % 60) + " s";
        });
        num(0x21, "Distance avec voyant allumé", "km", 2, 0, false, d -> ab(d));
        num(0x4D, "Temps avec voyant allumé", "min", 2, 0, false, d -> ab(d));
        num(0x31, "Distance depuis effacement des codes", "km", 2, 0, false, d -> ab(d));
        num(0x4E, "Temps depuis effacement des codes", "min", 2, 0, false, d -> ab(d));
        num(0x30, "Démarrages depuis effacement des codes", "", 1, 0, false, d -> d[0]);

        // --- États (texte)
        txt(0x01, "Voyant moteur et diagnostics", 4, false, d ->
                ((d[0] & 0x80) != 0 ? "VOYANT ALLUMÉ" : "voyant éteint") + " · " + (d[0] & 0x7F) + " code(s)"
                        + ((d[1] & 0x08) != 0 ? " · diesel" : " · essence"));
        txt(0x03, "Système carburant", 1, false, d -> {
            switch (d[0]) {
                case 1: return "boucle ouverte (moteur froid)";
                case 2: return "boucle fermée (sonde O2)";
                case 4: return "boucle ouverte (charge, décélération)";
                case 8: return "boucle ouverte (défaut)";
                case 16: return "boucle fermée (défaut sonde)";
                default: return "code " + d[0];
            }
        });
        txt(0x12, "Air secondaire", 1, false, d -> {
            switch (d[0]) {
                case 1: return "en amont";
                case 2: return "en aval du catalyseur";
                case 4: return "vers l'extérieur / coupé";
                case 8: return "pompe commandée (diagnostic)";
                default: return "code " + d[0];
            }
        });
        txt(0x13, "Sondes O2 présentes", 1, true, d -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) if ((d[0] & (1 << i)) != 0) sb.append("B").append(i / 4 + 1).append("S").append(i % 4 + 1).append(' ');
            return sb.length() == 0 ? "aucune" : sb.toString().trim();
        });
        txt(0x1C, "Norme OBD du véhicule", 1, true, d -> pick(new String[]{null, "OBD-II (Californie)", "OBD (EPA)",
                "OBD et OBD-II", "OBD-I", "non OBD", "EOBD (Europe)", "EOBD et OBD-II", "EOBD et OBD",
                "EOBD, OBD et OBD-II", "JOBD (Japon)", "JOBD et OBD-II", "JOBD et EOBD", "JOBD, EOBD et OBD-II"}, d[0]));
        txt(0x1E, "Prise de force", 1, false, d -> (d[0] & 1) != 0 ? "active" : "inactive");
        txt(0x51, "Type de carburant", 1, true, d -> pick(new String[]{"non renseigné", "essence", "méthanol",
                "éthanol", "diesel", "GPL", "GNV", "propane", "électrique", "bicarburation essence",
                "bicarburation méthanol", "bicarburation éthanol", "bicarburation GPL", "bicarburation GNV",
                "bicarburation propane", "bicarburation électrique", "bicarburation électrique/thermique",
                "hybride essence", "hybride éthanol", "hybride diesel", "hybride électrique",
                "hybride électrique/thermique", "hybride régénératif", "bicarburation diesel"}, d[0]));
    }

    // ================================================================ Codes défaut

    /** Deux octets → « P0133 ». */
    static String dtc(int b1, int b2) {
        return "PCBU".charAt(b1 >> 6) + String.format(Locale.ROOT, "%d%X%X%X", (b1 >> 4) & 3, b1 & 0xF, b2 >> 4, b2 & 0xF);
    }

    private static final Map<String, String> DTC = new HashMap<>();

    static String dtcText(String code) {
        String t = DTC.get(code);
        if (t != null) return t;
        if (code.length() == 5 && code.charAt(1) != '0' && code.charAt(1) != '2') return "code propre au constructeur";
        String g = DTC.get(code.substring(0, 4) + "x");
        return g != null ? g : "";
    }

    private static void d(String c, String t) {
        DTC.put(c, t);
    }

    static {
        d("P010x", "débitmètre d'air / pression admission");
        d("P0100", "débitmètre d'air : circuit");
        d("P0101", "débitmètre d'air : plage / performance");
        d("P0102", "débitmètre d'air : signal bas");
        d("P0103", "débitmètre d'air : signal haut");
        d("P0105", "capteur pression admission : circuit");
        d("P0106", "capteur pression admission : plage / performance");
        d("P0107", "capteur pression admission : signal bas");
        d("P0108", "capteur pression admission : signal haut");
        d("P0110", "sonde température d'air : circuit");
        d("P0112", "sonde température d'air : signal bas");
        d("P0113", "sonde température d'air : signal haut");
        d("P0115", "sonde température d'eau : circuit");
        d("P0116", "sonde température d'eau : plage / performance");
        d("P0117", "sonde température d'eau : signal bas");
        d("P0118", "sonde température d'eau : signal haut");
        d("P0120", "capteur papillon / pédale : circuit");
        d("P0121", "capteur papillon : plage / performance");
        d("P0122", "capteur papillon : signal bas");
        d("P0123", "capteur papillon : signal haut");
        d("P0128", "thermostat : température d'eau trop basse");
        d("P0130", "sonde O2 amont B1 : circuit");
        d("P0131", "sonde O2 amont B1 : tension basse");
        d("P0132", "sonde O2 amont B1 : tension haute");
        d("P0133", "sonde O2 amont B1 : réponse lente");
        d("P0134", "sonde O2 amont B1 : pas d'activité");
        d("P0135", "sonde O2 amont B1 : chauffage");
        d("P0136", "sonde O2 aval B1 : circuit");
        d("P0137", "sonde O2 aval B1 : tension basse");
        d("P0138", "sonde O2 aval B1 : tension haute");
        d("P0140", "sonde O2 aval B1 : pas d'activité");
        d("P0141", "sonde O2 aval B1 : chauffage");
        d("P0171", "mélange trop pauvre (B1)");
        d("P0172", "mélange trop riche (B1)");
        d("P0190", "capteur pression rampe : circuit");
        d("P0201", "injecteur 1 : circuit");
        d("P0202", "injecteur 2 : circuit");
        d("P0203", "injecteur 3 : circuit");
        d("P0204", "injecteur 4 : circuit");
        d("P0300", "ratés d'allumage aléatoires");
        d("P0301", "ratés d'allumage cylindre 1");
        d("P0302", "ratés d'allumage cylindre 2");
        d("P0303", "ratés d'allumage cylindre 3");
        d("P0304", "ratés d'allumage cylindre 4");
        d("P0325", "capteur de cliquetis : circuit");
        d("P0335", "capteur vilebrequin : circuit");
        d("P0340", "capteur arbre à cames : circuit");
        d("P0351", "bobine d'allumage A : circuit");
        d("P0352", "bobine d'allumage B : circuit");
        d("P0380", "bougies de préchauffage : circuit");
        d("P0400", "EGR : débit");
        d("P0401", "EGR : débit insuffisant");
        d("P0403", "EGR : circuit de commande");
        d("P0420", "catalyseur : efficacité insuffisante (B1)");
        d("P0440", "canister / évaporation : système");
        d("P0443", "électrovanne de purge canister : circuit");
        d("P0455", "évaporation : grosse fuite");
        d("P0480", "motoventilateur 1 : commande");
        d("P0500", "capteur de vitesse véhicule");
        d("P0505", "régulation de ralenti");
        d("P0506", "ralenti trop bas");
        d("P0507", "ralenti trop haut");
        d("P0560", "tension batterie / alimentation");
        d("P0562", "tension batterie trop basse");
        d("P0563", "tension batterie trop haute");
        d("P0600", "calculateur : liaison série");
        d("P0601", "calculateur : mémoire");
        d("P0605", "calculateur : mémoire (ROM)");
        d("P0650", "commande du voyant moteur");
        d("P0670", "boîtier de préchauffage : circuit");
        d("P2135", "papillon / pédale : incohérence capteurs");
        d("P2138", "pédale : incohérence capteurs");
        d("U0001", "réseau CAN : défaut de communication");
        d("U0100", "perte de communication avec le calculateur moteur");
    }
}
