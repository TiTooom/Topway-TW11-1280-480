---
name: sandero-obd
description: Lecture OBD2 dans Sandero (Outils → OBD2) par un module ELM327 en Bluetooth, Wi-Fi ou USB, comme Torque — fichiers Obd/ObdPids/ObdUsb/ObdActivity, séquence AT, PID mode 01/09, codes défaut 03/07/0A, CSV, faux module pour l'émulateur, pièges du Bluetooth Topway. À charger pour toute fonction utilisant les valeurs du véhicule.
---

# OBD2 dans Sandero (v0.10)

## Fichiers
| Fichier | Rôle |
|---|---|
| `Obd` | Singleton + fil `obd` : liaison, init ELM, boucle de lecture, valeurs (`values`, `display`), codes défaut, terminal, CSV, journal brut (400 lignes) |
| `ObdPids` | Table des PID mode 01 (nom FR, unité, formule SAE J1979), décodage des DTC, petites descriptions P0xxx |
| `ObdUsb` | Pilotes série USB sans bibliothèque : FTDI 0403, CH340 1A86, CP210x 10C4, PL2303 067B, CDC-ACM |
| `ObdActivity` | Écran Outils → OBD2 : liaison, module, protocole, carburant, CSV ; onglets Valeurs / Défauts / Infos / Terminal |

Les valeurs restent dans `Obd.get()` (prêtes pour l'accueil plus tard). Fermer l'écran (Retour) coupe la liaison ; passer à l'accueil la garde.

## Séquence (sûr : testé sur émulateur avec faux module, jamais sur la voiture)
1. Ouverture : Bluetooth SPP (`00001101-…`, essais insecure → secure → `createRfcommSocket(1)`), Wi-Fi TCP (`192.168.0.10:35000` par défaut), USB (essais 38400, 115200, 9600, 57600, 230400 bauds).
2. `ATZ` (ou `ATI`) → version ; `ATE0 ATL0 ATS0 ATH0 ATAT1 ATSPx` ; `AT@1` ; `ATRV` (tension batterie, lue au module, sans le véhicule).
3. `0100` jusqu'à réponse (15 s, puis nouvel essai toutes les 3 s : contact coupé). `ATDP`/`ATDPN` → protocole (6–C = CAN : octet de nombre après 43/47/4A).
4. Masques 0100/0120/…/01E0 et 0900… → PID annoncés. Infos : 0101 (voyant, nb codes), 0902 VIN, 0904, 090A. Codes : 03, 07, 0A.
5. Boucle : PID « rapides » (0C 0D 04 11 0B 10 0E 5E 49 62) à chaque tour, un PID lent par tour, ATRV tous les 5 tours. 3 « NO DATA » de suite → PID abandonné. 5 délais dépassés de suite → coupure, nouvel essai dans 5 s.
- Réponses CAN multi-trames (`014`, `0:…`, `1:…`) recollées par `Obd.messages`. Plusieurs calculateurs : une ligne chacun.
- Conso (calcul) : PID 5E si présent, sinon MAF × 3600 / (AFR × densité) (essence 14,7 / 745 g/L, GPL 15,5 / 540 ; rien en diesel sans 5E).
- **Lecture seule** : aucun effacement envoyé. Le terminal permet `04` (effacement) après confirmation.

## Liaison sur l'autoradio (probable, à vérifier)
- Le Bluetooth du téléphone est géré par `gocsdk` / com.tw.bt (voir `topway-bluetooth`). On ne sait pas encore si Android voit un `BluetoothAdapter` utilisable : bouton **« État du Bluetooth Android »** pour le savoir (journal `[OBD] diagnostic Bluetooth`). Torque était installé d'origine, donc une liaison a sans doute marché d'une façon ou d'une autre.
- Ne jamais allumer le Bluetooth Android ni lancer une recherche automatiquement : seulement sur action de l'utilisateur (peut gêner le téléphone). Jamais de TWUtil(8).
- Si Bluetooth Android absent : module **Wi-Fi** (l'autoradio perd Internet tant qu'il est sur le Wi-Fi du module) ou **USB**.
- Sandero 1 (2008–2012) : protocole attendu ISO 14230 KWP (peut-être CAN sur les dernières) ; peu de PID et pas de VIN probable sur les vieux calculateurs.

## Réglages (prefs `obd`)
`transport` (0 BT, 1 Wi-Fi, 2 USB), `bt` / `bt_name`, `wifi`, `usb` / `usb_label`, `proto` (« 0 » auto … « C »), `fuel` (0 essence, 1 diesel, 2 GPL), `csv`.
Garde-fous `Safe` : étapes `obd_bt`, `obd_usb` (bouton Réactiver dans l'écran).
CSV : `Android/data/fr.sandero.launcher/files/obd/obd_AAAAMMJJ_HHMMSS.csv` (`;`, virgule décimale, 1 ligne/s).

## Tester sans voiture
Faux module Wi-Fi (Python, KWP, VIN, P0133) : écouter `0.0.0.0:35000`, répondre `réponse\r\r>` à chaque commande terminée par `\r`. Dans l'émulateur, adresse **`10.0.2.2:35000`** (le PC). Le clavier plein écran de l'émulateur capte les appuis : écrire plutôt `shared_prefs/obd.xml` par `run-as`.

## Journal des vérifications
- 2026-11-04 v0.10 : émulateur 1280×480 et 2400×1080 avec faux module Wi-Fi : connexion, 22 PID, VIN KWP, P0133, CSV, coupure + reprise automatique OK. Décodage testé hors appareil (CAN multi-trames, 2 calculateurs, DTC CAN/KWP). **Rien de vérifié sur la voiture** (Bluetooth Android, USB, vrai calculateur).
