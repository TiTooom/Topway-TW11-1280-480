// Interface Elecrow5.
//
// Accueil : copie de l'écran d'accueil de l'appli Android Sandero (MainActivity, thème nuit),
// adaptée de 1280x480 à 800x480 : même hauteur et mêmes tailles de texte, largeurs resserrées.
//  - colonne de gauche : heure (Roboto Thin 96), date, voiture avec halo à la couleur de la
//    source, dock « Réglages » et « Applis » ;
//  - carte audio : pastilles Radio / Bluetooth, pastille Bluetooth, vignette de la source,
//    titre / artiste / album (défilants), présélections radio, 5 boutons, curseur de volume.
// Couleurs, tailles et icônes relevées dans Sandero (MainActivity, Theme, Icon, VolumeSlider,
// CarView). Ressources générées par outils/generer.mjs (src/ressources/).
//
// Pas encore de MCU (tuner, Bluetooth, volume) : les boutons journalisent seulement ;
// l'heure part de l'heure de compilation (pas d'horloge sauvegardée sur la carte).
// Pages secondaires (Radio, Musique, OBD2, Outils, Applis) : celles de la démo.

#include "interface.h"
#include "ecran.h"
#include "ressources.h"

#include <stdio.h>
#include <string.h>
#include <time.h>

#include "esp_log.h"
#include "esp_timer.h"
#include "lvgl.h"

static const char *TAG = "UI";

// --- Palette nuit de Sandero (Theme.apply(true)) -----------------------------------
#define C_TEXTE         0xF3F6FA
#define C_ATTENUE       0x8E9AAC
#define C_FOND_HAUT     0x101826
#define C_FOND_MILIEU   0x080B12
#define C_FOND_BAS      0x05070B
#define C_CARTE_A       0x1A2233
#define C_CARTE_B       0x0F131B
#define C_CARTE_C       0x0B0E14
#define C_CARTE_BASE    0x0E121A
#define C_ACCENT_RADIO  0xF59E0B
#define C_ACCENT_BT     0x3B82F6
#define C_POINT_GRIS    0x6B7280
// « Verre » de Sandero : blanc à 0x14 (8 %), contour blanc à 0x1F (12 %).
#define OPA_VERRE       0x14
#define OPA_CONTOUR     0x1F
#define OPA_PISTE       0x1F

// Anciennes couleurs de la démo (pages secondaires).
#define COUL_FOND       0x101418
#define COUL_BARRE      0x181E24
#define COUL_TUILE      0x222A33
#define COUL_TUILE_APP  0x2E3944
#define COUL_ACCENT     0xF28C28
#define COUL_TEXTE      0xE8ECEF
#define COUL_TEXTE_2    0x8A96A3

// --- Géométrie (800x480) ------------------------------------------------------------
#define MARGE_G         20
#define MARGE_H         20
#define COL_L           280                 // colonne de gauche
#define CARTE_X         (MARGE_G + COL_L + 14)
#define CARTE_L         (800 - 16 - CARTE_X)
#define CARTE_H         (480 - 2 * MARGE_H)
#define DOCK_H          66
#define VOITURE_ZONE_Y  162                 // sous l'heure et la date (voir generer.mjs)
#define CARTE_PAD_G     24
#define CARTE_PAD_H     20
#define VOLUME_L        60
#define VOLUME_X        (CARTE_L - 14 - VOLUME_L)
#define CONTENU_L       (VOLUME_X - 12 - CARTE_PAD_G)    // 360
#define VIGNETTE        140
#define INFO_X          (CARTE_PAD_G + VIGNETTE + 20)
#define INFO_L          (CARTE_PAD_G + CONTENU_L - INFO_X)
#define COMMANDES_H     84
#define VOLUME_MAX      30

typedef enum { MODE_RADIO, MODE_BT } mode_t_;
typedef enum { PAGE_RADIO, PAGE_MUSIQUE, PAGE_OBD, PAGE_OUTILS, NB_PAGES } page_t;

static const char *const NOMS[NB_PAGES]     = { "Radio", "Musique", "OBD2", "Outils" };
static const char *const SYMBOLES[NB_PAGES] = { LV_SYMBOL_AUDIO, LV_SYMBOL_BLUETOOTH,
                                                LV_SYMBOL_CHARGE, LV_SYMBOL_SETTINGS };

static lv_obj_t *s_accueil;
static lv_obj_t *s_applis;
static lv_obj_t *s_pages[NB_PAGES];
static lv_obj_t *s_arc;
static lv_obj_t *s_barre;

// Accueil
static lv_obj_t *s_heure, *s_date;
static lv_obj_t *s_halo;
static lv_obj_t *s_carte, *s_play;
static lv_obj_t *s_pastilles[2];
static lv_obj_t *s_vignette, *s_vignette_icone;
static lv_obj_t *s_titre, *s_artiste, *s_album;
static lv_obj_t *s_zone_progression, *s_presets, *s_preset[6];
static lv_obj_t *s_cmd_icone[5];
static lv_obj_t *s_volume, *s_volume_rempli, *s_volume_icone, *s_volume_valeur;

static lv_grad_dsc_t g_fond, g_carte, g_play, g_vignette, g_volume;
static lv_grad_dsc_t g_dock, g_reglages, g_applis;

static mode_t_ s_mode = MODE_BT;
static lv_color_t s_accent, s_accent_de, s_accent_vers;
static int s_preset_sel = -1;
static int s_volume_val = 15;

static time_t s_base_heure;   // heure de compilation

// --- Outils communs ---------------------------------------------------------------

static lv_color_t hex(uint32_t c) { return lv_color_hex(c); }

// blend(a, b, t) de Sandero : a * (1 - t) + b * t.
static lv_color_t melange(lv_color_t a, lv_color_t b, float t)
{
    return lv_color_mix(b, a, (uint8_t)(t * 255.0f + 0.5f));
}

static lv_color_t eclaircir(lv_color_t c, float t) { return melange(c, lv_color_white(), t); }

// Dégradé en diagonale (haut-gauche → bas-droite), comme GradientDrawable TL_BR.
static void degrade_diag(lv_grad_dsc_t *g, const lv_color_t *couleurs, int n)
{
    lv_gradient_init_stops(g, couleurs, NULL, NULL, n);
    lv_grad_linear_init(g, 0, 0, LV_PCT(100), LV_PCT(100), LV_GRAD_EXTEND_PAD);
}

static void sans_style(lv_obj_t *o)
{
    lv_obj_remove_style_all(o);
    lv_obj_remove_flag(o, LV_OBJ_FLAG_SCROLLABLE);
}

static lv_obj_t *boite(lv_obj_t *parent, int x, int y, int l, int h)
{
    lv_obj_t *o = lv_obj_create(parent);
    sans_style(o);
    lv_obj_set_pos(o, x, y);
    lv_obj_set_size(o, l, h);
    return o;
}

// Fond « verre » : blanc translucide + contour fin (pastilles, boutons).
static void verre(lv_obj_t *o, int rayon)
{
    lv_obj_set_style_radius(o, rayon, 0);
    lv_obj_set_style_bg_color(o, lv_color_white(), 0);
    lv_obj_set_style_bg_opa(o, OPA_VERRE, 0);
    lv_obj_set_style_bg_opa(o, 0x40, LV_STATE_PRESSED);   // « ripple » d'Android
    lv_obj_set_style_border_width(o, 1, 0);
    lv_obj_set_style_border_color(o, lv_color_white(), 0);
    lv_obj_set_style_border_opa(o, OPA_CONTOUR, 0);
}

static lv_obj_t *icone(lv_obj_t *parent, const lv_image_dsc_t *img, lv_color_t couleur)
{
    lv_obj_t *i = lv_image_create(parent);
    lv_image_set_src(i, img);
    lv_obj_set_style_image_recolor(i, couleur, 0);
    lv_obj_set_style_image_recolor_opa(i, LV_OPA_COVER, 0);
    lv_obj_remove_flag(i, LV_OBJ_FLAG_CLICKABLE);
    return i;
}

static lv_obj_t *texte(lv_obj_t *parent, const lv_font_t *police, uint32_t couleur, const char *t)
{
    lv_obj_t *l = lv_label_create(parent);
    lv_obj_set_style_text_font(l, police, 0);
    lv_obj_set_style_text_color(l, hex(couleur), 0);
    lv_label_set_text(l, t);
    return l;
}

static lv_obj_t *nouvel_ecran(void)
{
    lv_obj_t *scr = lv_obj_create(NULL);
    lv_obj_set_style_bg_color(scr, lv_color_hex(COUL_FOND), 0);
    lv_obj_set_style_text_color(scr, lv_color_hex(COUL_TEXTE), 0);
    lv_obj_remove_flag(scr, LV_OBJ_FLAG_SCROLLABLE);
    return scr;
}

static void style_plat(lv_obj_t *o, uint32_t fond)
{
    lv_obj_set_style_bg_color(o, lv_color_hex(fond), 0);
    lv_obj_set_style_bg_color(o, lv_color_hex(COUL_TUILE_APP), LV_STATE_PRESSED);
    lv_obj_set_style_bg_opa(o, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(o, 0, 0);
    lv_obj_set_style_shadow_width(o, 0, 0);
    lv_obj_set_style_radius(o, 14, 0);
    lv_obj_remove_flag(o, LV_OBJ_FLAG_SCROLLABLE);
}

static lv_obj_t *barre_titre(lv_obj_t *scr, const char *titre)
{
    lv_obj_t *b = lv_obj_create(scr);
    lv_obj_set_size(b, 800, 56);
    lv_obj_align(b, LV_ALIGN_TOP_MID, 0, 0);
    style_plat(b, COUL_BARRE);
    lv_obj_set_style_radius(b, 0, 0);

    lv_obj_t *t = lv_label_create(b);
    lv_label_set_text(t, titre);
    lv_obj_set_style_text_font(t, &lv_font_montserrat_28, 0);
    lv_obj_set_style_text_color(t, lv_color_hex(COUL_ACCENT), 0);
    lv_obj_align(t, LV_ALIGN_LEFT_MID, 8, 0);
    return b;
}

static void aller_accueil(lv_event_t *e)
{
    (void)e;
    lv_screen_load(s_accueil);
}

static void ouvrir_page(page_t p, const char *depuis)
{
    if (p < NB_PAGES && s_pages[p]) {
        ESP_LOGI(TAG, "page %s (%s)", NOMS[p], depuis);
        lv_screen_load(s_pages[p]);
    }
}

static void aller_page(lv_event_t *e)
{
    ouvrir_page((page_t)(intptr_t)lv_event_get_user_data(e), "applis");
}

static void bouton_retour(lv_obj_t *barre)
{
    lv_obj_t *b = lv_button_create(barre);
    lv_obj_set_size(b, 140, 44);
    lv_obj_align(b, LV_ALIGN_RIGHT_MID, -4, 0);
    style_plat(b, COUL_TUILE);
    lv_obj_add_event_cb(b, aller_accueil, LV_EVENT_CLICKED, NULL);
    lv_obj_t *l = lv_label_create(b);
    lv_label_set_text(l, LV_SYMBOL_LEFT " Retour");
    lv_obj_center(l);
}

// --- Heure et date ------------------------------------------------------------------

static const char *const JOURS[] = { "DIMANCHE", "LUNDI", "MARDI", "MERCREDI", "JEUDI",
                                     "VENDREDI", "SAMEDI" };
static const char *const MOIS[] = { "JANVIER", "FÉVRIER", "MARS", "AVRIL", "MAI", "JUIN",
                                    "JUILLET", "AOÛT", "SEPTEMBRE", "OCTOBRE", "NOVEMBRE",
                                    "DÉCEMBRE" };

// Heure de compilation (__DATE__ « Nov 11 2026 », __TIME__ « 19:05:12 »).
static time_t heure_compilation(void)
{
    static const char *const ABR = "JanFebMarAprMayJunJulAugSepOctNovDec";
    char m[4] = { 0 };
    int j = 1, a = 2026, h = 0, mi = 0, s = 0;
    sscanf(__DATE__, "%3s %d %d", m, &j, &a);
    sscanf(__TIME__, "%d:%d:%d", &h, &mi, &s);
    const char *p = strstr(ABR, m);
    struct tm t = { .tm_year = a - 1900, .tm_mon = p ? (int)(p - ABR) / 3 : 0, .tm_mday = j,
                    .tm_hour = h, .tm_min = mi, .tm_sec = s, .tm_isdst = 0 };
    return mktime(&t);
}

static void maj_heure(lv_timer_t *t)
{
    (void)t;
    time_t maintenant = s_base_heure + (time_t)(esp_timer_get_time() / 1000000);
    struct tm tm;
    localtime_r(&maintenant, &tm);
    char hm[8], date[40];
    snprintf(hm, sizeof hm, "%02d:%02d", tm.tm_hour, tm.tm_min);
    snprintf(date, sizeof date, "%s %d %s", JOURS[tm.tm_wday % 7], tm.tm_mday, MOIS[tm.tm_mon % 12]);
    // Seulement si le texte change : pas de redessin inutile chaque seconde.
    if (strcmp(lv_label_get_text(s_heure), hm) != 0) lv_label_set_text(s_heure, hm);
    if (strcmp(lv_label_get_text(s_date), date) != 0) lv_label_set_text(s_date, date);
}

// --- Couleur d'ambiance (applyAccent / animateAccent de Sandero) ----------------------

static void appliquer_accent(lv_color_t c)
{
    s_accent = c;
    g_carte.stops[0].color = melange(c, hex(C_CARTE_BASE), 0.62f);
    lv_obj_invalidate(s_carte);
    g_play.stops[0].color = eclaircir(c, 0.35f);
    g_play.stops[1].color = c;
    lv_obj_invalidate(s_play);
    g_volume.stops[0].color = eclaircir(c, 0.35f);
    g_volume.stops[1].color = c;
    lv_obj_invalidate(s_volume_rempli);
    lv_obj_set_style_image_recolor(s_halo, c, 0);
}

static void anim_accent(void *var, int32_t v)
{
    (void)var;
    appliquer_accent(lv_color_mix(s_accent_vers, s_accent_de, (uint8_t)v));
}

static void animer_accent(lv_color_t cible)
{
    if (lv_color_eq(cible, s_accent)) return;
    s_accent_de = s_accent;
    s_accent_vers = cible;
    lv_anim_delete(&s_accent, anim_accent);
    lv_anim_t a;
    lv_anim_init(&a);
    lv_anim_set_var(&a, &s_accent);
    lv_anim_set_exec_cb(&a, anim_accent);
    lv_anim_set_values(&a, 0, 255);
    lv_anim_set_duration(&a, 700);
    lv_anim_start(&a);
}

// --- Source (setMode / renderRadio / renderBtSafe de Sandero) ------------------------

static void styler_pastille(lv_obj_t *p, bool choisie, uint32_t accent)
{
    if (choisie) {
        lv_obj_set_style_bg_color(p, hex(accent), 0);
        lv_obj_set_style_bg_opa(p, 0x55, 0);
        lv_obj_set_style_border_color(p, hex(accent), 0);
        lv_obj_set_style_border_opa(p, LV_OPA_COVER, 0);
    } else {
        lv_obj_set_style_bg_color(p, lv_color_white(), 0);
        lv_obj_set_style_bg_opa(p, OPA_VERRE, 0);
        lv_obj_set_style_border_color(p, lv_color_white(), 0);
        lv_obj_set_style_border_opa(p, OPA_CONTOUR, 0);
    }
}

static void styler_presets(void)
{
    for (int i = 0; i < 6; i++) styler_pastille(s_preset[i], i == s_preset_sel, C_ACCENT_RADIO);
}

static void choisir_mode(mode_t_ m, bool utilisateur)
{
    ESP_LOGI(TAG, "source %s (%s)", m == MODE_RADIO ? "Radio" : "Bluetooth",
             utilisateur ? "choix utilisateur" : "demarrage");
    s_mode = m;
    styler_pastille(s_pastilles[MODE_RADIO], m == MODE_RADIO, C_ACCENT_RADIO);
    styler_pastille(s_pastilles[MODE_BT], m == MODE_BT, C_ACCENT_BT);

    bool radio = m == MODE_RADIO;
    static const lv_image_dsc_t *const ICONES[2][5] = {
        { &icone_scan_24, &icone_rew_30, &icone_power_40, &icone_fwd_30, &icone_radio_24 },
        { &icone_stop_24, &icone_prev_30, &icone_play_40, &icone_next_30, &icone_open_24 },
    };
    for (int i = 0; i < 5; i++) lv_image_set_src(s_cmd_icone[i], ICONES[radio ? 0 : 1][i]);

    // Vignette de la source (setArt).
    lv_color_t c = hex(radio ? C_ACCENT_RADIO : C_ACCENT_BT);
    g_vignette.stops[0].color = eclaircir(c, 0.15f);
    g_vignette.stops[1].color = melange(c, hex(C_CARTE_C), 0.55f);
    lv_obj_invalidate(s_vignette);
    lv_image_set_src(s_vignette_icone, radio ? &icone_radio_72 : &icone_bt_72);

    // Textes : pas encore de tuner ni de Bluetooth (pas de MCU).
    if (radio) {
        lv_label_set_text(s_titre, "Radio");
        lv_label_set_text(s_artiste, "Tuner FM à venir");
        lv_label_set_text(s_album, "Piloté par l'ESP32 « MCU »");
        lv_obj_add_flag(s_zone_progression, LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(s_presets, LV_OBJ_FLAG_HIDDEN);
    } else {
        lv_label_set_text(s_titre, "Musique Bluetooth");
        lv_label_set_text(s_artiste, "Lecture depuis le téléphone");
        lv_label_set_text(s_album, "");
        lv_obj_add_flag(s_presets, LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(s_zone_progression, LV_OBJ_FLAG_HIDDEN);
    }
    if (utilisateur) animer_accent(c);
    else appliquer_accent(c);
}

static void pastille_touchee(lv_event_t *e)
{
    mode_t_ m = (mode_t_)(intptr_t)lv_event_get_user_data(e);
    if (m != s_mode) choisir_mode(m, true);
}

static void pastille_bt_touchee(lv_event_t *e)
{
    (void)e;
    ouvrir_page(PAGE_MUSIQUE, "pastille Bluetooth");
}

static void commande(lv_event_t *e)
{
    int n = (int)(intptr_t)lv_event_get_user_data(e);
    bool long_appui = lv_event_get_code(e) == LV_EVENT_LONG_PRESSED;
    ESP_LOGI(TAG, "bouton %d/5%s - %s (pas encore de MCU)", n + 1, long_appui ? " (appui long)" : "",
             s_mode == MODE_RADIO ? "Radio" : "Bluetooth");
    if (s_mode == MODE_BT && n == 4 && !long_appui) ouvrir_page(PAGE_MUSIQUE, "bouton");
}

static void preset_touche(lv_event_t *e)
{
    s_preset_sel = (int)(intptr_t)lv_event_get_user_data(e);
    ESP_LOGI(TAG, "preselection %d (pas encore de tuner)", s_preset_sel + 1);
    styler_presets();
}

// --- Curseur de volume (VolumeSlider de Sandero) ------------------------------------

static void volume_afficher(void)
{
    int h = (CARTE_H - 2 * CARTE_PAD_H) * s_volume_val / VOLUME_MAX;
    lv_obj_set_height(s_volume_rempli, h);
    lv_image_set_src(s_volume_icone, s_volume_val == 0 ? &icone_vol_down_24 : &icone_vol_up_24);
    lv_obj_set_style_image_recolor_opa(s_volume_icone, s_volume_val == 0 ? 120 : 230, 0);
    lv_label_set_text_fmt(s_volume_valeur, "%d", s_volume_val);
}

static void volume_toucher(lv_event_t *e)
{
    static int32_t y_depart;
    static int valeur_depart;
    static bool bouge;
    lv_event_code_t code = lv_event_get_code(e);
    lv_point_t p;
    lv_indev_get_point(lv_indev_active(), &p);
    int h = CARTE_H - 2 * CARTE_PAD_H;

    if (code == LV_EVENT_PRESSED) {
        y_depart = p.y;
        valeur_depart = s_volume_val;
        bouge = false;
        lv_obj_remove_flag(s_volume_valeur, LV_OBJ_FLAG_HIDDEN);
    } else if (code == LV_EVENT_PRESSING) {
        int32_t dy = y_depart - p.y;   // glissement relatif : le doigt n'a pas à être sur le niveau
        if (dy > h / 50 || dy < -h / 50) bouge = true;
        if (bouge) {
            int v = valeur_depart + (int)((dy * VOLUME_MAX + (dy >= 0 ? h / 2 : -h / 2)) / h);
            if (v < 0) v = 0;
            if (v > VOLUME_MAX) v = VOLUME_MAX;
            if (v != s_volume_val) {
                s_volume_val = v;
                volume_afficher();
            }
        }
    } else if (code == LV_EVENT_RELEASED || code == LV_EVENT_PRESS_LOST) {
        if (!bouge && code == LV_EVENT_RELEASED) {
            // Appui simple : en haut monte, en bas baisse d'un cran.
            lv_area_t z;
            lv_obj_get_coords(s_volume, &z);
            int v = s_volume_val + (p.y < (z.y1 + z.y2) / 2 ? 1 : -1);
            s_volume_val = v < 0 ? 0 : v > VOLUME_MAX ? VOLUME_MAX : v;
            volume_afficher();
        }
        lv_obj_add_flag(s_volume_valeur, LV_OBJ_FLAG_HIDDEN);
        ESP_LOGI(TAG, "volume regle a %d/%d (curseur, pas encore de MCU)", s_volume_val, VOLUME_MAX);
    }
}

// --- Accueil : colonne de gauche ----------------------------------------------------

static void voiture_appui_long(lv_event_t *e)
{
    (void)e;
    ouvrir_page(PAGE_OUTILS, "appui long sur la voiture");
}

static void dock_touche(lv_event_t *e)
{
    int n = (int)(intptr_t)lv_event_get_user_data(e);
    if (n == 0) {
        ouvrir_page(PAGE_OUTILS, "dock Reglages");
    } else {
        ESP_LOGI(TAG, "dock : Applis");
        lv_screen_load(s_applis);
    }
}

static void tuile_dock(lv_obj_t *parent, int x, int n, const lv_image_dsc_t *img, lv_grad_dsc_t *g_rond,
                       const char *nom, const char *sous_titre)
{
    int l = (COL_L - 12) / 2;
    lv_obj_t *t = boite(parent, x, CARTE_H - DOCK_H, l, DOCK_H);
    lv_obj_set_style_radius(t, 22, 0);
    lv_obj_set_style_bg_opa(t, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_grad(t, &g_dock, 0);
    lv_obj_set_style_border_width(t, 1, 0);
    lv_obj_set_style_border_color(t, lv_color_white(), 0);
    lv_obj_set_style_border_opa(t, OPA_CONTOUR, 0);
    lv_obj_set_style_border_opa(t, 0x60, LV_STATE_PRESSED);
    lv_obj_add_flag(t, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(t, dock_touche, LV_EVENT_CLICKED, (void *)(intptr_t)n);

    lv_obj_t *rond = boite(t, 10, (DOCK_H - 38) / 2, 38, 38);
    lv_obj_set_style_radius(rond, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_opa(rond, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_grad(rond, g_rond, 0);
    lv_obj_remove_flag(rond, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_center(icone(rond, img, lv_color_white()));

    lv_obj_t *n1 = texte(t, &roboto_medium_16, C_TEXTE, nom);
    lv_obj_set_pos(n1, 58, 13);
    lv_obj_set_width(n1, l - 58 - 8);
    lv_label_set_long_mode(n1, LV_LABEL_LONG_DOT);
    lv_obj_t *n2 = texte(t, &roboto_regular_12, C_ATTENUE, sous_titre);
    lv_obj_set_pos(n2, 58, 36);
    lv_obj_set_width(n2, l - 58 - 8);
    lv_label_set_long_mode(n2, LV_LABEL_LONG_DOT);
}

static void creer_colonne_gauche(lv_obj_t *scr)
{
    lv_obj_t *col = boite(scr, MARGE_G, MARGE_H, COL_L, CARTE_H);

    // Heure : Roboto Thin 96, approche -0,02 em ; chiffres placés comme dans Sandero.
    s_heure = texte(col, &roboto_thin_96, C_TEXTE, "00:00");
    lv_obj_set_style_text_letter_space(s_heure, -2, 0);
    lv_obj_set_pos(s_heure, 0, 19);

    s_date = texte(col, &roboto_regular_17, C_ATTENUE, "");
    lv_obj_set_style_text_letter_space(s_date, 2, 0);
    lv_obj_set_pos(s_date, 4, 116);

    // Voiture : halo (recoloré à l'accent), puis ombre + photo. Appui long : Outils.
    lv_obj_t *v = boite(col, 0, VOITURE_ZONE_Y - MARGE_H, VOITURE_L, VOITURE_H);
    lv_obj_add_flag(v, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(v, voiture_appui_long, LV_EVENT_LONG_PRESSED, NULL);
    s_halo = icone(v, &voiture_halo, hex(C_ACCENT_BT));
    lv_obj_set_pos(s_halo, VOITURE_HALO_X, VOITURE_HALO_Y);
    lv_obj_t *photo = lv_image_create(v);
    lv_image_set_src(photo, &voiture);
    lv_obj_set_pos(photo, VOITURE_X, VOITURE_Y);
    lv_obj_remove_flag(photo, LV_OBJ_FLAG_CLICKABLE);

    // Dock : Réglages (page Outils de la carte) et Applis (pages de la démo).
    tuile_dock(col, 0, 0, &icone_settings_22, &g_reglages, "Réglages", "Outils");
    tuile_dock(col, (COL_L - 12) / 2 + 12, 1, &icone_apps_22, &g_applis, "Applis", "4 applis");
}

// --- Accueil : carte audio ----------------------------------------------------------

static lv_obj_t *pastille(lv_obj_t *parent, const lv_image_dsc_t *img, const char *nom)
{
    lv_obj_t *p = lv_obj_create(parent);
    sans_style(p);
    lv_obj_set_size(p, LV_SIZE_CONTENT, 44);
    verre(p, 22);
    lv_obj_set_style_pad_left(p, 12, 0);
    lv_obj_set_style_pad_right(p, 14, 0);
    lv_obj_set_flex_flow(p, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(p, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(p, 8, 0);
    lv_obj_add_flag(p, LV_OBJ_FLAG_CLICKABLE);
    if (img) icone(p, img, hex(C_TEXTE));
    texte(p, &roboto_regular_16, C_TEXTE, nom);
    return p;
}

static lv_obj_t *bouton_rond(lv_obj_t *parent, int x, int taille, int n)
{
    lv_obj_t *b = boite(parent, x, CARTE_H - CARTE_PAD_H - COMMANDES_H + (COMMANDES_H - taille) / 2,
                        taille, taille);
    if (n == 2) {
        // Lecture : rond plein au dégradé de l'accent.
        lv_obj_set_style_radius(b, LV_RADIUS_CIRCLE, 0);
        lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
        lv_obj_set_style_bg_grad(b, &g_play, 0);
        lv_obj_set_style_border_color(b, lv_color_white(), LV_STATE_PRESSED);
        lv_obj_set_style_border_opa(b, 0x60, LV_STATE_PRESSED);
        lv_obj_set_style_border_width(b, 3, LV_STATE_PRESSED);
        s_play = b;
    } else {
        verre(b, LV_RADIUS_CIRCLE);
    }
    lv_obj_add_flag(b, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(b, commande, LV_EVENT_SHORT_CLICKED, (void *)(intptr_t)n);
    lv_obj_add_event_cb(b, commande, LV_EVENT_LONG_PRESSED, (void *)(intptr_t)n);
    s_cmd_icone[n] = icone(b, &icone_stop_24, n == 2 ? lv_color_white() : hex(C_TEXTE));
    lv_obj_center(s_cmd_icone[n]);
    return b;
}

static void creer_carte(lv_obj_t *scr)
{
    s_carte = boite(scr, CARTE_X, MARGE_H, CARTE_L, CARTE_H);
    lv_obj_set_style_radius(s_carte, 30, 0);
    lv_obj_set_style_bg_opa(s_carte, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_grad(s_carte, &g_carte, 0);
    lv_obj_set_style_border_width(s_carte, 1, 0);
    lv_obj_set_style_border_color(s_carte, lv_color_white(), 0);
    lv_obj_set_style_border_opa(s_carte, OPA_CONTOUR, 0);

    // Haut : pastilles de source, pastille Bluetooth à droite.
    lv_obj_t *haut = boite(s_carte, CARTE_PAD_G, CARTE_PAD_H, CONTENU_L, 44);
    s_pastilles[MODE_RADIO] = pastille(haut, &icone_radio_20, "Radio");
    s_pastilles[MODE_BT] = pastille(haut, &icone_bt_20, "Bluetooth");
    lv_obj_set_pos(s_pastilles[MODE_RADIO], 0, 0);
    lv_obj_update_layout(s_pastilles[MODE_RADIO]);
    lv_obj_set_pos(s_pastilles[MODE_BT], lv_obj_get_width(s_pastilles[MODE_RADIO]) + 8, 0);
    for (int i = 0; i < 2; i++) {
        lv_obj_add_event_cb(s_pastilles[i], pastille_touchee, LV_EVENT_CLICKED, (void *)(intptr_t)i);
    }

    // État du téléphone inconnu tant que le MCU n'est pas là : « Bluetooth », point gris.
    lv_obj_t *bt = pastille(haut, NULL, "Bluetooth");
    lv_obj_set_style_pad_left(bt, 14, 0);
    lv_obj_set_style_pad_right(bt, 16, 0);
    lv_obj_set_style_pad_column(bt, 10, 0);
    lv_obj_t *point = lv_obj_create(bt);
    sans_style(point);
    lv_obj_set_size(point, 10, 10);
    lv_obj_set_style_radius(point, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_color(point, hex(C_POINT_GRIS), 0);
    lv_obj_set_style_bg_opa(point, LV_OPA_COVER, 0);
    lv_obj_move_to_index(point, 0);
    lv_obj_t *bt_texte = lv_obj_get_child(bt, 1);
    lv_obj_set_style_text_font(bt_texte, &roboto_regular_15, 0);
    lv_obj_align(bt, LV_ALIGN_TOP_RIGHT, 0, 0);
    lv_obj_add_event_cb(bt, pastille_bt_touchee, LV_EVENT_CLICKED, NULL);

    // Milieu : vignette + infos, centrés verticalement entre le haut et les boutons.
    int milieu_y = CARTE_PAD_H + 44;
    int milieu_h = CARTE_H - CARTE_PAD_H - COMMANDES_H - milieu_y;
    s_vignette = boite(s_carte, CARTE_PAD_G, milieu_y + (milieu_h - VIGNETTE) / 2, VIGNETTE, VIGNETTE);
    lv_obj_set_style_radius(s_vignette, 22, 0);
    lv_obj_set_style_bg_opa(s_vignette, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_grad(s_vignette, &g_vignette, 0);
    s_vignette_icone = icone(s_vignette, &icone_bt_72, lv_color_white());
    lv_obj_center(s_vignette_icone);

    lv_obj_t *info = lv_obj_create(s_carte);
    sans_style(info);
    lv_obj_set_size(info, INFO_L, LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(info, LV_FLEX_FLOW_COLUMN);
    lv_obj_align(info, LV_ALIGN_TOP_LEFT, INFO_X, 0);

    s_titre = texte(info, &roboto_medium_36, C_TEXTE, "");
    s_artiste = texte(info, &roboto_regular_23, 0xFFFFFF, "");
    lv_obj_set_style_text_opa(s_artiste, 0xD9, 0);
    lv_obj_set_style_pad_top(s_artiste, 6, 0);
    s_album = texte(info, &roboto_regular_18, C_ATTENUE, "");
    lv_obj_set_style_pad_top(s_album, 4, 0);
    lv_obj_t *lignes[] = { s_titre, s_artiste, s_album };
    for (int i = 0; i < 3; i++) {
        lv_obj_set_width(lignes[i], INFO_L);
        lv_label_set_long_mode(lignes[i], LV_LABEL_LONG_SCROLL_CIRCULAR);   // « marquee »
    }

    // Barre de progression et durées : place gardée (aucune durée connue sans MCU).
    s_zone_progression = boite(info, 0, 0, INFO_L, 18 + 24 + 18);

    s_presets = lv_obj_create(info);
    sans_style(s_presets);
    lv_obj_set_size(s_presets, INFO_L, 16 + 46);
    lv_obj_set_style_pad_top(s_presets, 16, 0);
    lv_obj_set_flex_flow(s_presets, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(s_presets, LV_FLEX_ALIGN_SPACE_BETWEEN, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    for (int i = 0; i < 6; i++) {
        lv_obj_t *p = lv_obj_create(s_presets);
        sans_style(p);
        lv_obj_set_size(p, (INFO_L - 5 * 4) / 6, 46);
        verre(p, 22);
        lv_obj_add_flag(p, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_add_event_cb(p, preset_touche, LV_EVENT_CLICKED, (void *)(intptr_t)i);
        char n[4];
        snprintf(n, sizeof n, "%d", i + 1);
        lv_obj_center(texte(p, &roboto_regular_15, C_TEXTE, n));
        s_preset[i] = p;
    }
    styler_presets();
    lv_obj_add_flag(s_presets, LV_OBJ_FLAG_HIDDEN);

    lv_obj_update_layout(info);
    lv_obj_set_y(info, milieu_y + (milieu_h - lv_obj_get_height(info)) / 2);

    // Bas : 5 boutons (72/86/112/86/72 dans Sandero, resserrés à 52/62/84/62/52).
    static const int TAILLES[5] = { 52, 62, 84, 62, 52 };
    int total = 0;
    for (int i = 0; i < 5; i++) total += TAILLES[i];
    int ecart = (CONTENU_L - total) / 6, x = CARTE_PAD_G + ecart;
    for (int i = 0; i < 5; i++) {
        bouton_rond(s_carte, x, TAILLES[i], i);
        x += TAILLES[i] + ecart;
    }

    // Curseur de volume vertical : capsule, remplissage depuis le bas, haut-parleur en bas.
    int vh = CARTE_H - 2 * CARTE_PAD_H;
    s_volume = boite(s_carte, VOLUME_X, CARTE_PAD_H, VOLUME_L, vh);
    lv_obj_set_style_radius(s_volume, VOLUME_L / 2, 0);
    lv_obj_set_style_clip_corner(s_volume, true, 0);
    lv_obj_set_style_bg_color(s_volume, lv_color_white(), 0);
    lv_obj_set_style_bg_opa(s_volume, OPA_PISTE, 0);
    lv_obj_add_flag(s_volume, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(s_volume, volume_toucher, LV_EVENT_PRESSED, NULL);
    lv_obj_add_event_cb(s_volume, volume_toucher, LV_EVENT_PRESSING, NULL);
    lv_obj_add_event_cb(s_volume, volume_toucher, LV_EVENT_RELEASED, NULL);
    lv_obj_add_event_cb(s_volume, volume_toucher, LV_EVENT_PRESS_LOST, NULL);

    s_volume_rempli = lv_obj_create(s_volume);
    sans_style(s_volume_rempli);
    lv_obj_set_width(s_volume_rempli, VOLUME_L);
    lv_obj_align(s_volume_rempli, LV_ALIGN_BOTTOM_MID, 0, 0);
    lv_obj_set_style_bg_opa(s_volume_rempli, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_grad(s_volume_rempli, &g_volume, 0);
    lv_obj_remove_flag(s_volume_rempli, LV_OBJ_FLAG_CLICKABLE);

    s_volume_icone = icone(s_volume, &icone_vol_up_24, lv_color_white());
    lv_obj_align(s_volume_icone, LV_ALIGN_TOP_MID, 0, vh - VOLUME_L * 62 / 100 - 12);
    s_volume_valeur = texte(s_volume, &roboto_regular_23, 0xFFFFFF, "");
    lv_obj_align(s_volume_valeur, LV_ALIGN_TOP_MID, 0, VOLUME_L * 62 / 100 - 21);
    lv_obj_add_flag(s_volume_valeur, LV_OBJ_FLAG_HIDDEN);
    volume_afficher();
}

static void preparer_degrades(void)
{
    lv_color_t fond[3] = { hex(C_FOND_HAUT), hex(C_FOND_MILIEU), hex(C_FOND_BAS) };
    degrade_diag(&g_fond, fond, 3);
    // Carte : dans Sandero le dégradé est en diagonale ; ici vertical (calculé par ligne).
    // Mesure du 11/11 avec titres défilants : diagonale 40 %, image précalculée en PSRAM
    // 51 % (lecture PSRAM lente), vertical 18 %, couleur unie 16 % de processeur.
    lv_color_t carte[3] = { hex(C_CARTE_A), hex(C_CARTE_B), hex(C_CARTE_C) };
    lv_gradient_init_stops(&g_carte, carte, NULL, NULL, 3);
    g_carte.dir = LV_GRAD_DIR_VER;
    lv_color_t deux[2] = { hex(C_ACCENT_BT), hex(C_ACCENT_BT) };
    degrade_diag(&g_play, deux, 2);
    degrade_diag(&g_vignette, deux, 2);
    lv_color_t dock[2] = { hex(C_CARTE_A), hex(C_CARTE_B) };
    degrade_diag(&g_dock, dock, 2);
    lv_color_t reglages[2] = { hex(0x94A3B8), hex(0x475569) };
    degrade_diag(&g_reglages, reglages, 2);
    lv_color_t applis[2] = { hex(0xE879F9), hex(0x9333EA) };
    degrade_diag(&g_applis, applis, 2);
    // Volume : clair en haut du remplissage, accent en bas (dégradé vertical simple).
    lv_gradient_init_stops(&g_volume, deux, NULL, NULL, 2);
    g_volume.dir = LV_GRAD_DIR_VER;
}

static void creer_accueil(void)
{
    preparer_degrades();
    s_accueil = lv_obj_create(NULL);
    lv_obj_remove_flag(s_accueil, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_bg_opa(s_accueil, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_grad(s_accueil, &g_fond, 0);
    lv_obj_set_style_text_color(s_accueil, hex(C_TEXTE), 0);

    creer_colonne_gauche(s_accueil);
    creer_carte(s_accueil);

    s_base_heure = heure_compilation();
    maj_heure(NULL);
    lv_timer_create(maj_heure, 1000, NULL);
    s_accent = hex(C_ACCENT_BT);
    choisir_mode(MODE_BT, false);
}

// --- Applis : l'ancienne grille de la démo ---------------------------------------------

static void creer_applis(void)
{
    s_applis = nouvel_ecran();
    bouton_retour(barre_titre(s_applis, "Applis"));
    // 4 tuiles de 182 x 360, espacées de 12 px.
    for (int i = 0; i < NB_PAGES; i++) {
        lv_obj_t *tuile = lv_button_create(s_applis);
        lv_obj_set_size(tuile, 182, 360);
        lv_obj_set_pos(tuile, 14 + i * (182 + 12), 80);
        style_plat(tuile, COUL_TUILE);
        lv_obj_add_event_cb(tuile, aller_page, LV_EVENT_CLICKED, (void *)(intptr_t)i);

        lv_obj_t *ic = lv_label_create(tuile);
        lv_label_set_text(ic, SYMBOLES[i]);
        lv_obj_set_style_text_font(ic, &lv_font_montserrat_48, 0);
        lv_obj_set_style_text_color(ic, lv_color_hex(COUL_ACCENT), 0);
        lv_obj_align(ic, LV_ALIGN_CENTER, 0, -30);

        lv_obj_t *nom = lv_label_create(tuile);
        lv_label_set_text(nom, NOMS[i]);
        lv_obj_set_style_text_font(nom, &lv_font_montserrat_28, 0);
        lv_obj_align(nom, LV_ALIGN_CENTER, 0, 50);
    }
}

// --- Pages simples ------------------------------------------------------------------

static void creer_page_vide(page_t p, const char *t)
{
    lv_obj_t *scr = nouvel_ecran();
    bouton_retour(barre_titre(scr, NOMS[p]));
    lv_obj_t *l = lv_label_create(scr);
    lv_label_set_text(l, t);
    lv_obj_set_style_text_color(l, lv_color_hex(COUL_TEXTE_2), 0);
    lv_obj_center(l);
    s_pages[p] = scr;
}

// --- Outils : luminosité et test d'animation ----------------------------------------

static void luminosite_changee(lv_event_t *e)
{
    lv_obj_t *curseur = lv_event_get_target_obj(e);
    ecran_luminosite((uint8_t)lv_slider_get_value(curseur));
}

static void anim_arc(void *obj, int32_t v)   { lv_arc_set_value(obj, v); }
static void anim_barre(void *obj, int32_t v) { lv_bar_set_value(obj, v, LV_ANIM_OFF); }

static void lancer_anim(lv_obj_t *obj, lv_anim_exec_xcb_t cb, uint32_t duree)
{
    lv_anim_t a;
    lv_anim_init(&a);
    lv_anim_set_var(&a, obj);
    lv_anim_set_exec_cb(&a, cb);
    lv_anim_set_values(&a, 0, 100);
    lv_anim_set_duration(&a, duree);
    lv_anim_set_playback_duration(&a, duree);
    lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
    lv_anim_start(&a);
}

static void test_anim(lv_event_t *e)
{
    lv_obj_t *inter = lv_event_get_target_obj(e);
    bool actif = lv_obj_has_state(inter, LV_STATE_CHECKED);
    ESP_LOGI(TAG, "test animation %s", actif ? "lance" : "arrete");
    if (actif) {
        lancer_anim(s_arc, anim_arc, 1200);
        lancer_anim(s_barre, anim_barre, 900);
    } else {
        lv_anim_delete(s_arc, anim_arc);
        lv_anim_delete(s_barre, anim_barre);
    }
}

static lv_obj_t *etiquette(lv_obj_t *parent, const char *t, int x, int y)
{
    lv_obj_t *l = lv_label_create(parent);
    lv_label_set_text(l, t);
    lv_obj_set_pos(l, x, y);
    return l;
}

static void creer_page_outils(void)
{
    lv_obj_t *scr = nouvel_ecran();
    bouton_retour(barre_titre(scr, NOMS[PAGE_OUTILS]));

    etiquette(scr, LV_SYMBOL_EYE_OPEN "  Ecran", 30, 90);
    lv_obj_t *curseur = lv_slider_create(scr);
    lv_obj_set_size(curseur, 420, 24);
    lv_obj_set_pos(curseur, 200, 92);
    lv_slider_set_range(curseur, 5, 100);
    lv_slider_set_value(curseur, 100, LV_ANIM_OFF);
    lv_obj_set_style_bg_color(curseur, lv_color_hex(COUL_ACCENT), LV_PART_INDICATOR);
    lv_obj_set_style_bg_color(curseur, lv_color_hex(COUL_ACCENT), LV_PART_KNOB);
    lv_obj_add_event_cb(curseur, luminosite_changee, LV_EVENT_VALUE_CHANGED, NULL);

    etiquette(scr, LV_SYMBOL_REFRESH "  Test animation", 30, 160);
    lv_obj_t *inter = lv_switch_create(scr);
    lv_obj_set_pos(inter, 300, 156);
    lv_obj_set_style_bg_color(inter, lv_color_hex(COUL_ACCENT), LV_PART_INDICATOR | LV_STATE_CHECKED);
    lv_obj_add_event_cb(inter, test_anim, LV_EVENT_VALUE_CHANGED, NULL);

    s_arc = lv_arc_create(scr);
    lv_obj_set_size(s_arc, 200, 200);
    lv_obj_set_pos(s_arc, 60, 220);
    lv_arc_set_range(s_arc, 0, 100);
    lv_obj_remove_flag(s_arc, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_style_arc_color(s_arc, lv_color_hex(COUL_ACCENT), LV_PART_INDICATOR);
    lv_obj_remove_style(s_arc, NULL, LV_PART_KNOB);

    s_barre = lv_bar_create(scr);
    lv_obj_set_size(s_barre, 400, 30);
    lv_obj_set_pos(s_barre, 330, 305);
    lv_bar_set_range(s_barre, 0, 100);
    lv_obj_set_style_bg_color(s_barre, lv_color_hex(COUL_ACCENT), LV_PART_INDICATOR);

    etiquette(scr, "Images/s et charge : en bas a droite", 330, 360);
    s_pages[PAGE_OUTILS] = scr;
}

void interface_creer(void)
{
    creer_page_vide(PAGE_RADIO, "Tuner FM : a venir (Si4703 par le MCU)");
    creer_page_vide(PAGE_MUSIQUE, "Musique Bluetooth : a venir (ESP32 classique, A2DP)");
    creer_page_vide(PAGE_OBD, "OBD2 : a venir (ELM327 Wi-Fi)");
    creer_page_outils();
    creer_applis();
    creer_accueil();
    lv_screen_load(s_accueil);
    ESP_LOGI(TAG, "interface prete (accueil facon Sandero)");
}
