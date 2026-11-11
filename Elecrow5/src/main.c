// Autoradio libre Sandero : interface sur Elecrow CrowPanel 5.0" ESP32-S3 (DIS07050H).
// Étape 1 : écran + tactile + interface de démonstration fluide.

#include "ecran.h"
#include "interface.h"

#include "esp_heap_caps.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "SANDERO";

void app_main(void)
{
    ESP_LOGI(TAG, "demarrage, PSRAM libre : %u octets",
             (unsigned)heap_caps_get_free_size(MALLOC_CAP_SPIRAM));

    if (!ecran_demarrer()) {
        ESP_LOGE(TAG, "ecran : demarrage impossible, arret");
        return;
    }

    ecran_verrouiller();
    interface_creer();
    ecran_deverrouiller();

    // Allumage après la première image : pas de flash de pixels au démarrage.
    vTaskDelay(pdMS_TO_TICKS(60));
    ecran_luminosite(100);
    ESP_LOGI(TAG, "pret");
}
