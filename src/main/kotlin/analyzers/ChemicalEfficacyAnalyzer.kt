package dev.eknath.analyzers

import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.config.Thresholds
import dev.eknath.owm.ForecastItem

object ChemicalEfficacyAnalyzer {
    fun analyze(slots: List<ForecastItem>): Insight {
        val hottestSlot = slots.maxByOrNull { it.main.temp } ?: return Insight("CHEMICAL_EFFICACY", Severity.LOW)
        val temp = hottestSlot.main.temp

        val severity = when {
            temp > Thresholds.CHEMICAL_TEMP_HIGH_C -> Severity.HIGH
            temp >= Thresholds.CHEMICAL_TEMP_MEDIUM_C -> Severity.MEDIUM
            else -> Severity.LOW
        }

        return Insight(
            category = "CHEMICAL_EFFICACY",
            severity = severity,
            windowStart = hottestSlot.dt,
            windowEnd = hottestSlot.dt
        )
    }
}
