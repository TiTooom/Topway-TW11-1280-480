# Liste explicite des sources (plus sûr qu'un GLOB avec PlatformIO + ESP-IDF).
# ressources/ : fichiers générés par outils/generer.mjs (polices Roboto, icônes, voiture).
idf_component_register(
    SRCS "main.c" "ecran.c" "tactile.c" "interface.c"
         "ressources/images.c"
         "ressources/roboto_thin_96.c"
         "ressources/roboto_regular_12.c" "ressources/roboto_regular_15.c"
         "ressources/roboto_regular_16.c" "ressources/roboto_regular_17.c"
         "ressources/roboto_regular_18.c" "ressources/roboto_regular_23.c"
         "ressources/roboto_medium_16.c" "ressources/roboto_medium_36.c"
    INCLUDE_DIRS "." "ressources")
