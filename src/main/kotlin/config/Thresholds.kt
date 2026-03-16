package dev.eknath.config

object Thresholds {
    // Spraying Safety
    const val SPRAYING_WIND_HIGH_MIN_KMPH = 15.0
    const val SPRAYING_WIND_CALM_MAX_KMPH = 2.0
    const val SPRAYING_WIND_MEDIUM_HIGH_KMPH = 10.0
    const val SPRAYING_WIND_MEDIUM_CALM_KMPH = 3.0

    // Chemical Efficacy
    const val CHEMICAL_TEMP_HIGH_C = 32.0
    const val CHEMICAL_TEMP_MEDIUM_C = 28.0

    // Fungal Risk
    const val FUNGAL_HUMIDITY_DANGER_PCT = 85
    const val FUNGAL_HUMIDITY_WARNING_PCT = 70
    const val FUNGAL_SUSTAINED_HOURS = 10

    // Heat Stress
    const val HEAT_TEMP_DANGER_C = 38.0
    const val HEAT_TEMP_WARNING_C = 35.0
    const val HEAT_SUSTAINED_HOURS = 6

    // Field Workability
    const val FIELD_POP_HIGH = 0.70
    const val FIELD_POP_MEDIUM = 0.30

    // Harvest Window
    const val HARVEST_POP_IDEAL_MAX = 0.30
    const val HARVEST_POP_MARGINAL_MAX = 0.50
    const val HARVEST_WIND_IDEAL_MIN_KMPH = 3.0
    const val HARVEST_WIND_IDEAL_MAX_KMPH = 15.0
    const val HARVEST_WIND_MARGINAL_MIN_KMPH = 1.0
    const val HARVEST_WIND_MARGINAL_MAX_KMPH = 20.0
    const val HARVEST_MIN_WINDOW_SLOTS = 2

    // Spraying Window
    const val SPRAY_WIND_MIN_KMPH = 3.0
    const val SPRAY_WIND_MAX_KMPH = 10.0
    const val SPRAY_TEMP_MIN_C = 15.0
    const val SPRAY_TEMP_MAX_C = 28.0
    const val SPRAY_HUMIDITY_MAX_PCT = 70
    const val SPRAY_POP_MAX = 0.30
    const val SPRAY_MIN_SCORE = 2
}
