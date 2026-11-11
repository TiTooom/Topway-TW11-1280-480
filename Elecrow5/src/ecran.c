// Écran RGB 800x480 + LVGL 9.
//
// Pourquoi c'est plus fluide que les démos Elecrow (Arduino 2.x) :
//  - « bounce buffer » : le DMA de l'écran lit dans de petits tampons en RAM interne,
//    remplis depuis la PSRAM ; l'image ne décroche plus quand la PSRAM est chargée ;
//  - LVGL dessine en RAM interne (rapide) par bandes, puis copie la bande dans l'image
//    en PSRAM ; seules les zones modifiées sont redessinées ;
//  - PSRAM octale 80 MHz, processeur 240 MHz (sdkconfig.defaults).

#include "ecran.h"
#include "carte.h"
#include "tactile.h"

#include "driver/ledc.h"
#include "esp_heap_caps.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_rgb.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "lvgl.h"

static const char *TAG = "ECRAN";

// Hauteur des bandes dessinées par LVGL (2 tampons en RAM interne).
#define LIGNES_PAR_BANDE    30
// Bounce buffer : 10 lignes (2 tampons, en RAM interne, gérés par esp_lcd).
#define LIGNES_BOUNCE       10

// Cadence de rafraîchissement de LVGL calée sur celle de la dalle (arrondie au-dessus) :
// dessiner plus vite ne sert à rien, la dalle n'affiche pas les images en trop.
// 14 MHz / (855 x 504 points) = 32,5 images/s -> 31 ms. Mesure du 11/11 à 15 ms :
// test d'animation à 33 % de processeur pour ~63 passages/s, dont la moitié invisibles.
#define POINTS_PAR_IMAGE ((uint64_t)(ECRAN_LARGEUR + ECRAN_HSYNC_PULSE + ECRAN_HSYNC_BACK + ECRAN_HSYNC_FRONT) * \
                          (ECRAN_HAUTEUR + ECRAN_VSYNC_PULSE + ECRAN_VSYNC_BACK + ECRAN_VSYNC_FRONT))
#define PERIODE_RAFRAICHISSEMENT_MS ((uint32_t)((POINTS_PAR_IMAGE * 1000 + ECRAN_PCLK_HZ - 1) / ECRAN_PCLK_HZ))

static esp_lcd_panel_handle_t s_dalle;
static SemaphoreHandle_t s_verrou;
static bool s_tactile_ok;

void ecran_verrouiller(void)   { xSemaphoreTakeRecursive(s_verrou, portMAX_DELAY); }
void ecran_deverrouiller(void) { xSemaphoreGiveRecursive(s_verrou); }

static uint32_t horloge_ms(void)
{
    return (uint32_t)(esp_timer_get_time() / 1000);
}

// Statistiques d'affichage, journalisées toutes les 5 s (diagnostic de la charge) :
// bandes envoyées, points redessinés, temps passé dans LVGL et dans la copie vers la dalle.
static uint32_t st_bandes, st_points;
static int64_t st_us_lvgl, st_us_copie, st_debut;

static void copier_bande(lv_display_t *disp, const lv_area_t *zone, uint8_t *pixels)
{
    int64_t t0 = esp_timer_get_time();
    st_bandes++;
    st_points += (uint32_t)lv_area_get_size(zone);
    esp_lcd_panel_draw_bitmap(s_dalle, zone->x1, zone->y1, zone->x2 + 1, zone->y2 + 1, pixels);
    st_us_copie += esp_timer_get_time() - t0;
    lv_display_flush_ready(disp);
}

static void lire_tactile(lv_indev_t *indev, lv_indev_data_t *donnees)
{
    (void)indev;
    uint16_t x, y;
    if (s_tactile_ok && tactile_lire(&x, &y)) {
        donnees->point.x = x;
        donnees->point.y = y;
        donnees->state = LV_INDEV_STATE_PRESSED;
    } else {
        donnees->state = LV_INDEV_STATE_RELEASED;
    }
}

static void tache_affichage(void *arg)
{
    (void)arg;
    for (;;) {
        ecran_verrouiller();
        int64_t t0 = esp_timer_get_time();
        uint32_t attente = lv_timer_handler();
        int64_t t1 = esp_timer_get_time();
        ecran_deverrouiller();
        st_us_lvgl += t1 - t0;
        if (t1 - st_debut >= 5000000) {
            if (st_debut) {
                int64_t duree = t1 - st_debut;
                ESP_LOGI(TAG, "stats 5 s : %lu bandes, %lu points/s, LVGL %d %%, copie %d %%",
                         (unsigned long)st_bandes, (unsigned long)(st_points * 1000000LL / duree),
                         (int)(st_us_lvgl * 100 / duree), (int)(st_us_copie * 100 / duree));
            }
            st_bandes = st_points = 0;
            st_us_lvgl = st_us_copie = 0;
            st_debut = t1;
        }
        if (attente < 2) attente = 2;
        if (attente > 20) attente = 20;
        vTaskDelay(pdMS_TO_TICKS(attente));
    }
}

static void retro_demarrer(void)
{
    ledc_timer_config_t minuterie = {
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .duty_resolution = LEDC_TIMER_10_BIT,
        .timer_num = LEDC_TIMER_0,
        .freq_hz = 5000,
        .clk_cfg = LEDC_AUTO_CLK,
    };
    ledc_channel_config_t canal = {
        .gpio_num = BROCHE_RETRO,
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .channel = LEDC_CHANNEL_0,
        .timer_sel = LEDC_TIMER_0,
        .duty = 0,
    };
    if (ledc_timer_config(&minuterie) != ESP_OK || ledc_channel_config(&canal) != ESP_OK) {
        ESP_LOGE(TAG, "retroeclairage : PWM impossible");
    }
}

void ecran_luminosite(uint8_t pourcent)
{
    if (pourcent > 100) pourcent = 100;
    uint32_t rapport = (1023u * pourcent) / 100u;
    ledc_set_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0, rapport);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0);
}

static bool dalle_demarrer(void)
{
    esp_lcd_rgb_panel_config_t cfg = {
        .clk_src = LCD_CLK_SRC_DEFAULT,
        .timings = {
            .pclk_hz = ECRAN_PCLK_HZ,
            .h_res = ECRAN_LARGEUR,
            .v_res = ECRAN_HAUTEUR,
            .hsync_pulse_width = ECRAN_HSYNC_PULSE,
            .hsync_back_porch = ECRAN_HSYNC_BACK,
            .hsync_front_porch = ECRAN_HSYNC_FRONT,
            .vsync_pulse_width = ECRAN_VSYNC_PULSE,
            .vsync_back_porch = ECRAN_VSYNC_BACK,
            .vsync_front_porch = ECRAN_VSYNC_FRONT,
            .flags.pclk_active_neg = 1,
        },
        .data_width = 16,
        .bits_per_pixel = 16,
        .num_fbs = 1,
        .bounce_buffer_size_px = ECRAN_LARGEUR * LIGNES_BOUNCE,
        .hsync_gpio_num = BROCHE_HSYNC,
        .vsync_gpio_num = BROCHE_VSYNC,
        .de_gpio_num = BROCHE_DE,
        .pclk_gpio_num = BROCHE_PCLK,
        .disp_gpio_num = -1,
        .data_gpio_nums = BROCHES_DONNEES,
        .flags.fb_in_psram = 1,
    };
    esp_err_t err = esp_lcd_new_rgb_panel(&cfg, &s_dalle);
    if (err == ESP_OK) err = esp_lcd_panel_reset(s_dalle);
    if (err == ESP_OK) err = esp_lcd_panel_init(s_dalle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "dalle RGB : echec (%s)", esp_err_to_name(err));
        return false;
    }
    ESP_LOGI(TAG, "dalle RGB %dx%d a %d MHz", ECRAN_LARGEUR, ECRAN_HAUTEUR, ECRAN_PCLK_HZ / 1000000);
    return true;
}

bool ecran_demarrer(void)
{
    s_verrou = xSemaphoreCreateRecursiveMutex();
    if (!s_verrou) return false;

    retro_demarrer();
    ecran_luminosite(0);  // noir le temps de l'initialisation (pas de parasites)

    // Tampons de dessin en RAM interne, réservés AVANT la dalle (ses bounce buffers
    // morcellent la RAM interne). Bandes plus basses si la place manque, PSRAM en
    // dernier recours (nettement plus lent). Essai du 11/11 : 30 lignes refusées
    // quand la dalle était créée avant.
    static const int bandes[] = { LIGNES_PAR_BANDE, 20, 12 };
    void *t1 = NULL, *t2 = NULL;
    size_t taille = 0;
    for (int i = 0; i < 3 && !t2; i++) {
        taille = ECRAN_LARGEUR * bandes[i] * sizeof(uint16_t);
        t1 = heap_caps_malloc(taille, MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
        t2 = t1 ? heap_caps_malloc(taille, MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT) : NULL;
        if (t2) {
            ESP_LOGI(TAG, "tampons LVGL : 2 x %d lignes en RAM interne", bandes[i]);
        } else {
            heap_caps_free(t1);
            t1 = NULL;
        }
    }
    if (!t2) {
        ESP_LOGW(TAG, "RAM interne insuffisante (plus grand bloc %u) : tampons LVGL en PSRAM",
                 (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL));
        taille = ECRAN_LARGEUR * LIGNES_PAR_BANDE * sizeof(uint16_t);
        t1 = heap_caps_malloc(taille, MALLOC_CAP_SPIRAM);
        t2 = heap_caps_malloc(taille, MALLOC_CAP_SPIRAM);
        if (!t1 || !t2) {
            ESP_LOGE(TAG, "tampons LVGL : memoire insuffisante");
            return false;
        }
    }

    if (!dalle_demarrer()) return false;

    s_tactile_ok = tactile_demarrer();
    if (!s_tactile_ok) ESP_LOGW(TAG, "tactile absent : affichage seul");

    lv_init();
    lv_tick_set_cb(horloge_ms);

    lv_display_t *disp = lv_display_create(ECRAN_LARGEUR, ECRAN_HAUTEUR);
    lv_display_set_color_format(disp, LV_COLOR_FORMAT_RGB565);
    lv_display_set_buffers(disp, t1, t2, taille, LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(disp, copier_bande);
    lv_timer_t *rafraichir = lv_display_get_refr_timer(disp);
    if (rafraichir) {
        lv_timer_set_period(rafraichir, PERIODE_RAFRAICHISSEMENT_MS);
        ESP_LOGI(TAG, "rafraichissement LVGL : %u ms (cadence de la dalle)",
                 (unsigned)PERIODE_RAFRAICHISSEMENT_MS);
    }

    lv_indev_t *doigt = lv_indev_create();
    lv_indev_set_type(doigt, LV_INDEV_TYPE_POINTER);
    lv_indev_set_read_cb(doigt, lire_tactile);

    // Tâche d'affichage sur le cœur 1 (le cœur 0 restera au Bluetooth/liaison série).
    if (xTaskCreatePinnedToCore(tache_affichage, "lvgl", 10 * 1024, NULL, 2, NULL, 1) != pdPASS) {
        ESP_LOGE(TAG, "tache LVGL : creation impossible");
        return false;
    }
    ESP_LOGI(TAG, "LVGL %d.%d.%d pret, RAM interne libre : %u octets",
             LVGL_VERSION_MAJOR, LVGL_VERSION_MINOR, LVGL_VERSION_PATCH,
             (unsigned)heap_caps_get_free_size(MALLOC_CAP_INTERNAL));
    return true;
}
