package dev.eknath.analyzers

import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.config.Thresholds
import dev.eknath.owm.ForecastItem

object FieldWorkabilityAnalyzer {
    fun analyze(slots: List<ForecastItem>): Insight {
        val worstSlot = slots.maxByOrNull { it.pop } ?: return Insight("FIELD_WORKABILITY", Severity.LOW)
        val pop = worstSlot.pop

        val severity = when {
            pop > Thresholds.FIELD_POP_HIGH -> Severity.HIGH
            pop > Thresholds.FIELD_POP_MEDIUM -> Severity.MEDIUM
            else -> Severity.LOW
        }

        return Insight(
            category = "FIELD_WORKABILITY",
            severity = severity,
            formatArgs = listOf((pop * 100).toInt()),
            windowStart = worstSlot.dt,
            windowEnd = worstSlot.dt
        )
    }
}
