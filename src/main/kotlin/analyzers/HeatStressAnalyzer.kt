package dev.eknath.analyzers

import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.config.Thresholds
import dev.eknath.owm.ForecastItem
import dev.eknath.owm.ForecastType

object HeatStressAnalyzer {
    fun analyze(slots: List<ForecastItem>, forecastType: ForecastType): Insight {
        val consecutiveThreshold = when (forecastType) {
            ForecastType.FORECAST_5DAY -> 2   // 2 × 3h = 6h
            ForecastType.HOURLY_4DAY -> 6     // 6 × 1h = 6h
        }

        var longestRun = 0
        var currentRun = 0
        var runStart: ForecastItem? = null
        var bestRunStart: ForecastItem? = null
        var bestRunEnd: ForecastItem? = null
        var peakTemp = Double.MIN_VALUE
        var hasWarning = false

        for (slot in slots) {
            if (slot.main.temp > peakTemp) peakTemp = slot.main.temp
            if (slot.main.temp > Thresholds.HEAT_TEMP_DANGER_C) {
                if (currentRun == 0) runStart = slot
                currentRun++
                if (currentRun > longestRun) {
                    longestRun = currentRun
                    bestRunStart = runStart
                    bestRunEnd = slot
                }
            } else {
                currentRun = 0
                runStart = null
            }
            if (slot.main.temp >= Thresholds.HEAT_TEMP_WARNING_C) hasWarning = true
        }

        val severity = when {
            longestRun >= consecutiveThreshold -> Severity.HIGH
            hasWarning -> Severity.MEDIUM
            else -> Severity.LOW
        }

        return Insight(
            category = "HEAT_STRESS",
            severity = severity,
            formatArgs = listOf(peakTemp.toInt()),
            windowStart = if (severity == Severity.HIGH) bestRunStart?.dt else null,
            windowEnd = if (severity == Severity.HIGH) bestRunEnd?.dt else null,
            iconCode = bestRunStart?.weather?.firstOrNull()?.icon
        )
    }
}
