// Tactile capacitif GT911 sur I2C (SDA 19, SCL 20).
// Sur les cartes v2/v3, RESET et INT du GT911 passent par un PCA9557 : on rejoue la
// séquence d'Elecrow (INT bas pendant le réarmement => adresse 0x5D). Sans PCA9557
// (anciennes cartes), on tente directement les deux adresses possibles.

#include "tactile.h"
#include "carte.h"

#include "driver/i2c_master.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "TACTILE";

#define DELAI_I2C_MS        20

// Registres du PCA9557.
#define PCA_SORTIE          0x01
#define PCA_CONFIG          0x03   // bit à 1 = entrée

// Registres du GT911 (adresses sur 16 bits, poids fort d'abord).
#define GT_ETAT             0x814E
#define GT_POINT1           0x814F
#define GT_ID_PRODUIT       0x8140

static i2c_master_bus_handle_t s_bus;
static i2c_master_dev_handle_t s_gt911;

static esp_err_t ecrire_octets(i2c_master_dev_handle_t dev, const uint8_t *d, size_t n)
{
    return i2c_master_transmit(dev, d, n, DELAI_I2C_MS);
}

static esp_err_t gt_lire(uint16_t reg, uint8_t *d, size_t n)
{
    uint8_t adr[2] = { reg >> 8, reg & 0xFF };
    return i2c_master_transmit_receive(s_gt911, adr, 2, d, n, DELAI_I2C_MS);
}

static esp_err_t gt_ecrire8(uint16_t reg, uint8_t v)
{
    uint8_t d[3] = { reg >> 8, reg & 0xFF, v };
    return ecrire_octets(s_gt911, d, 3);
}

static i2c_master_dev_handle_t ajouter(uint8_t adresse)
{
    i2c_device_config_t cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = adresse,
        .scl_speed_hz = I2C_FREQ_HZ,
    };
    i2c_master_dev_handle_t dev = NULL;
    if (i2c_master_bus_add_device(s_bus, &cfg, &dev) != ESP_OK) return NULL;
    return dev;
}

static void rearmer_par_pca9557(void)
{
    if (i2c_master_probe(s_bus, ADR_PCA9557, DELAI_I2C_MS) != ESP_OK) {
        ESP_LOGI(TAG, "pas de PCA9557 (carte v1 ?)");
        return;
    }
    i2c_master_dev_handle_t pca = ajouter(ADR_PCA9557);
    if (!pca) return;

    const uint8_t sortie_basse[] = { PCA_SORTIE, 0x00 };   // IO0 (RESET) et IO1 (INT) bas
    const uint8_t io01_sorties[] = { PCA_CONFIG, 0xFC };
    const uint8_t reset_haut[]   = { PCA_SORTIE, 0x01 };
    const uint8_t int_entree[]   = { PCA_CONFIG, 0xFE };   // IO1 relâché (INT du GT911)

    ecrire_octets(pca, sortie_basse, 2);
    ecrire_octets(pca, io01_sorties, 2);
    vTaskDelay(pdMS_TO_TICKS(20));
    ecrire_octets(pca, reset_haut, 2);
    vTaskDelay(pdMS_TO_TICKS(100));
    ecrire_octets(pca, int_entree, 2);
    i2c_master_bus_rm_device(pca);
    ESP_LOGI(TAG, "GT911 rearme par le PCA9557");
}

bool tactile_demarrer(void)
{
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = BROCHE_I2C_SDA,
        .scl_io_num = BROCHE_I2C_SCL,
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags.enable_internal_pullup = true,
    };
    if (i2c_new_master_bus(&bus_cfg, &s_bus) != ESP_OK) {
        ESP_LOGE(TAG, "bus I2C : echec");
        return false;
    }

    rearmer_par_pca9557();

    uint8_t adresse = 0;
    if (i2c_master_probe(s_bus, ADR_GT911_A, DELAI_I2C_MS) == ESP_OK) adresse = ADR_GT911_A;
    else if (i2c_master_probe(s_bus, ADR_GT911_B, DELAI_I2C_MS) == ESP_OK) adresse = ADR_GT911_B;
    if (!adresse) {
        ESP_LOGE(TAG, "GT911 introuvable (0x5D / 0x14)");
        return false;
    }
    s_gt911 = ajouter(adresse);
    if (!s_gt911) return false;

    uint8_t id[4] = { 0 };
    gt_lire(GT_ID_PRODUIT, id, 4);
    ESP_LOGI(TAG, "GT911 trouve en 0x%02X (produit %.4s)", adresse, (const char *)id);
    return true;
}

bool tactile_lire(uint16_t *x, uint16_t *y)
{
    static bool appui = false;
    static uint16_t dx, dy;

    uint8_t etat = 0;
    if (gt_lire(GT_ETAT, &etat, 1) != ESP_OK) return false;

    // Bit 7 = nouvelle mesure prête ; sinon on garde le dernier état connu.
    if (etat & 0x80) {
        uint8_t nb = etat & 0x0F;
        if (nb > 0) {
            uint8_t p[7];
            if (gt_lire(GT_POINT1, p, sizeof p) == ESP_OK) {
                dx = p[1] | (p[2] << 8);
                dy = p[3] | (p[4] << 8);
                if (dx >= ECRAN_LARGEUR) dx = ECRAN_LARGEUR - 1;
                if (dy >= ECRAN_HAUTEUR) dy = ECRAN_HAUTEUR - 1;
                appui = true;
            }
        } else {
            appui = false;
        }
        gt_ecrire8(GT_ETAT, 0);  // acquitte la mesure
    }
    *x = dx;
    *y = dy;
    return appui;
}
