package dev.eknath.analyzers

import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.config.Thresholds
import dev.eknath.owm.ForecastItem
import dev.eknath.owm.ForecastType

object FungalRiskAnalyzer {
    fun analyze(slots: List<ForecastItem>, forecastType: ForecastType): Insight {
        val consecutiveThreshold = when (forecastType) {
            ForecastType.FORECAST_5DAY -> 4   // 4 × 3h = 12h ≥ 10h
            ForecastType.HOURLY_4DAY -> 10    // 10 × 1h = 10h
        }

        var longestRun = 0
        var currentRun = 0
        var runStart: ForecastItem? = null
        var bestRunStart: ForecastItem? = null
        var bestRunEnd: ForecastItem? = null
        var hasWarning = false

        for (slot in slots) {
            if (slot.main.humidity > Thresholds.FUNGAL_HUMIDITY_DANGER_PCT) {
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
            if (slot.main.humidity > Thresholds.FUNGAL_HUMIDITY_WARNING_PCT) hasWarning = true
        }

        val severity = when {
            longestRun >= consecutiveThreshold -> Severity.HIGH
            hasWarning -> Severity.MEDIUM
            else -> Severity.LOW
        }

        return Insight(
            category = "FUNGAL_RISK",
            severity = severity,
            windowStart = if (severity == Severity.HIGH) bestRunStart?.dt else null,
            windowEnd = if (severity == Severity.HIGH) bestRunEnd?.dt else null
        )
    }
}
