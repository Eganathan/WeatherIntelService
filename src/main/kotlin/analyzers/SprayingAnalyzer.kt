package dev.eknath.analyzers

import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.config.Thresholds
import dev.eknath.owm.ForecastItem

object SprayingAnalyzer {
    fun analyze(slots: List<ForecastItem>): Insight {
        var worstSeverity = Severity.LOW
        var worstSlot: ForecastItem? = null

        for (slot in slots) {
            val windKmh = slot.wind.speed * 3.6
            val severity = when {
                windKmh > Thresholds.SPRAYING_WIND_HIGH_MIN_KMPH ||
                windKmh < Thresholds.SPRAYING_WIND_CALM_MAX_KMPH -> Severity.HIGH
                windKmh >= Thresholds.SPRAYING_WIND_MEDIUM_HIGH_KMPH ||
                windKmh <= Thresholds.SPRAYING_WIND_MEDIUM_CALM_KMPH -> Severity.MEDIUM
                else -> Severity.LOW
            }
            if (severity.ordinal < worstSeverity.ordinal) {
                worstSeverity = severity
                worstSlot = slot
            }
        }

        return Insight(
            category = "SPRAYING",
            severity = worstSeverity,
            windowStart = worstSlot?.dt,
            windowEnd = worstSlot?.dt
        )
    }
}
