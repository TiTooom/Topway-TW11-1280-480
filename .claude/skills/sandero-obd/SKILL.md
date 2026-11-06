---
name: sandero-obd
description: Lecture OBD2 dans Sandero (Outils → OBD2) par un module ELM327 en Bluetooth, Wi-Fi ou USB, comme Torque — fichiers Obd/ObdPids/ObdUsb/ObdActivity, séquence AT, PID mode 01/09, codes défaut 03/07/0A, CSV, faux module pour l'émulateur, pièges du Bluetooth Topway. À charger pour toute fonction utilisant les valeurs du véhicule.
---

# OBD2 dans Sandero (v0.11)

## Fichiers
| Fichier | Rôle |
|---|---|
| `Obd` | Singleton + fil `obd` : liaison, init ELM, boucle de lecture, valeurs (`values`, `display`), codes défaut, terminal, recherche de la conso, CSV, journal brut (400 lignes), `autoStart` |
| `ObdPids` | Table des PID mode 01 (nom FR, unité, formule SAE J1979), décodage des DTC, petites descriptions P0xxx |
| `ObdUsb` | Pilotes série USB sans bibliothèque : FTDI 0403, CH340 1A86, CP210x 10C4, PL2303 067B, CDC-ACM |
| `ObdActivity` | Écran Outils → OBD2 : liaison, module, protocole, carburant, CSV, connexion auto, « Chercher la conso » ; onglets Valeurs / Défauts / Infos / Terminal |

Les valeurs restent dans `Obd.get()` (prêtes pour l'accueil plus tard). Fermer l'écran (Retour) coupe la liaison **sauf si la connexion auto est active** ; passer à l'accueil la garde.

## Séquence
1. Ouverture : Bluetooth SPP (`00001101-…`, essais insecure → secure → `createRfcommSocket(1)`), Wi-Fi TCP (`192.168.0.10:35000` par défaut), USB (essais 38400, 115200, 9600, 57600, 230400 bauds).
   - Bluetooth Android éteint → `enable()` (2e essai à 6 s, attente 15 s max, puis 1,5 s de marge). v0.11.
2. `ATZ` (ou `ATI`) → version ; `ATE0 ATL0 ATS0 ATH0 ATAT1 ATSPx` ; `AT@1` ; `ATRV`.
3. `0100` jusqu'à réponse (15 s, puis nouvel essai toutes les 3 s : contact coupé). `ATDP`/`ATDPN` → protocole.
4. Masques 0100/0120/… et 0900… → PID annoncés. Infos : 0101, 0902 VIN, 0904, 090A. Codes : 03, 07, 0A.
5. Boucle : PID « rapides » (0C 0D 04 11 0B 10 0E 5E A2 49 62) à chaque tour, un lent par tour, ATRV tous les 5 tours.
   - 3 « NO DATA » de suite → PID **mis de côté et redemandé toutes les 15 s** (v0.10 l'abandonnait pour toujours → valeurs figées).
   - **8 s sans aucune réponse** → « Le véhicule ne répond plus » : valeurs en « pas de réponse », `ATPC`, retour à l'étape 3 (sans couper le module).
   - 5 délais dépassés de suite (pas de `>`) → coupure, nouvel essai dans 5 s.
- Conso (calcul) : PID 5E (L/h), sinon A2 (mg/coup × 4 cyl × tr/min/2 × 60 / densité ; diesel 832 g/L), sinon MAF (essence 14,7 / 745 g/L, GPL 15,5 / 540 ; rien en diesel).
- **Lecture seule** : aucun effacement envoyé. Le terminal permet `04` après confirmation.

## Recherche de la conso (bouton, v0.11)
Dans le fil OBD, boucle en pause : `015E 01A2 0110 019D 019E 0183` (même non annoncés ; ceux qui répondent passent en lecture rapide), puis service Renault **21** : `2180` en-tête standard, sinon `ATSH817AF1` (calculateur moteur 7A, **probable**) ; si refus `7F 21` → `10C0` puis `2180`. Ensuite `2101…21FF`, chaque bloc `61 xx …` copié au journal (`[OBD] Renault 21xx → …`). Retour : `1081` si session ouverte, `ATSHC133F1`. Résultat aussi dans l'onglet Infos.
- But : comparer les blocs au ralenti et vers 2500 tr/min pour trouver l'octet « débit / quantité injectée », puis l'ajouter comme valeur calculée. **Rien de décodé pour l'instant.**

## Connexion automatique (v0.11)
Pref `obd.auto`. `Obd.autoStart` est appelé par `SanderoService.onCreate` (« démarrage ») et au réveil (SCREEN_ON / USER_PRESENT), avec le dernier module choisi. Allume le Bluetooth Android si besoin.

## Liaison sur l'autoradio — sûr (voiture, 05/11/2026)
- Android voit un `BluetoothAdapter` (« QUAD-CORE T3 p1 »), **éteint à chaque démarrage** de l'autoradio. Le premier `enable()` est parfois sans effet, le second marche.
- Allumé : la musique du téléphone (com.tw.bt) hache 4-5 s puis repart ; appairage du téléphone intact.
- Module « OBDII » (ELM327 v1.5, « OBDII to RS232 Interpreter ») : connexion **insecure** OK alors qu'Android le dit « appairé non ». Recherche Bluetooth : trouve aussi le téléphone (normal).
- Jamais de TWUtil(8).

## Sandero 1 dCi de référence — sûr (05/11/2026)
- Protocole **ISO 14230-4 KWP fast (A5)**, trouvé après 3 « UNABLE TO CONNECT » quand le contact venait d'être mis (~25 s), immédiat ensuite.
- PID mode 01 : `01 04 05 0B 0C 0D 0F 1C 20 21 23 30 31` (pas de 5E, ni MAF 10, ni 40+). Mode 09 annonce 08–0E mais **VIN non fourni**.
- PID 01 octet B bit 3 = 0 (« essence ») alors que c'est un diesel (pression rampe 23 : 225 bar ralenti, 592 bar en roulant) → plus affiché.
- Ralenti moteur chaud : 806 tr/min, charge 44 %, MAP 102 kPa, batterie 14,4 V. 31 = 65535 km et 30 = 255 : compteurs plafonnés (codes jamais effacés), affichés « (plafond) ».
- Code en attente **P0670** (boîtier de préchauffage) vu deux fois.
- Moteur coupé contact mis (~14:09) : tous les PID → NO DATA (ATRV 13,0 V).

## Réglages (prefs `obd`)
`transport` (0 BT, 1 Wi-Fi, 2 USB), `bt` / `bt_name`, `wifi`, `usb` / `usb_label`, `proto` (« 0 » auto … « C »), `fuel` (0 essence, 1 diesel, 2 GPL), `csv`, `auto`.
Garde-fous `Safe` : étapes `obd_bt`, `obd_usb` (bouton Réactiver dans l'écran). Connexion auto ignorée en mode sans échec.
CSV : `Android/data/fr.sandero.launcher/files/obd/obd_AAAAMMJJ_HHMMSS.csv` (`;`, virgule décimale, 1 ligne/s).

## Tester sans voiture
Faux module Wi-Fi (Python, KWP, VIN, P0133) : écouter `0.0.0.0:35000`, répondre `réponse\r\r>` à chaque commande terminée par `\r`. Dans l'émulateur, adresse **`10.0.2.2:35000`** (le PC). Le clavier plein écran de l'émulateur capte les appuis : écrire plutôt `shared_prefs/obd.xml` par `run-as`. ObdActivity n'est pas exportée (`am start` refusé) : passer par Outils.

## Journal des vérifications
- 2026-11-04 v0.10 : émulateur 1280×480 et 2400×1080 avec faux module Wi-Fi : connexion, 22 PID, VIN KWP, P0133, CSV, coupure + reprise automatique OK.
- 2026-11-05 v0.10 (voiture) : Bluetooth Android + ELM327 v1.5 OK, KWP fast, 13 PID, valeurs cohérentes en roulant ; défauts trouvés : connexion à refaire à chaque fois, boucle « Bluetooth éteint » sans fin, PID abandonnés pour toujours après l'arrêt du moteur (valeurs figées), pas de conso (aucun PID carburant).
- 2026-11-05 v0.11 : corrigé (allumage BT, connexion auto, PID redemandés, véhicule recherché à nouveau, recherche de conso). Compilé, accueil vérifié sur émulateur ; **OBD v0.11 pas encore essayé sur la voiture**.
- 2026-11-06 (v0.11, voiture) : Sandero dCi en KWP FAST ; code P0670 (bougies de préchauffage) mémorisé + en attente. Recherche conso : 015E/01A2/0110/019D/019E/0183 → 7F 01 12 ; 2180 → 7F 21 11 et 10C0 → 7F 10 11 (service 21 et session non pris en charge, en-tête standard) ; en-tête 817AF1 jamais essayé. Rail 225 bar au ralenti (diesel).
- 2026-11-06 (v0.12) : recherche conso : `7F 21 11` = service 21 absent → en-tête suivant (817AF1) au lieu de balayer 255 blocs ; 10C0 seulement si réponse `50` ; en-tête C133F1 remis après tout ATSH.
- 2026-11-06 (v0.13, voiture) : BT Android éteint au départ (« Allumé : non », 0 appairé) → allumé par l'appli au 2e essai ; ELM327 v1.5, KWP FAST, 13 PID, VIN non fourni, P0670 toujours mémorisé + en attente ; MAP 100 kPa, rampe 228 bar, 14,2 V, 797 tr/min, 68 °C au ralenti ; 0x21/0x30/0x31 saturés (0/255/65535 = plafonds). Recherche conso v0.12 : fin en ≈ 3 s (au lieu de 70 s), aucun PID conso trouvé. Incohérence mineure : « OBDII · appairé non » alors que 2 appareils appairés listés.
