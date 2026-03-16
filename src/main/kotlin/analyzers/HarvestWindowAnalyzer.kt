package dev.eknath.analyzers

import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.config.Thresholds
import dev.eknath.owm.ForecastItem

object HarvestWindowAnalyzer {
    fun analyze(slots: List<ForecastItem>): Insight {
        data class ScoredSlot(val slot: ForecastItem, val score: Int)

        val scored = slots.map { slot ->
            val score = if (slot.sys.pod == "n") 0 else {
                val windKmh = slot.wind.speed * 3.6
                when {
                    slot.pop < Thresholds.HARVEST_POP_IDEAL_MAX &&
                    windKmh in Thresholds.HARVEST_WIND_IDEAL_MIN_KMPH..Thresholds.HARVEST_WIND_IDEAL_MAX_KMPH -> 2
                    slot.pop < Thresholds.HARVEST_POP_MARGINAL_MAX &&
                    windKmh in Thresholds.HARVEST_WIND_MARGINAL_MIN_KMPH..Thresholds.HARVEST_WIND_MARGINAL_MAX_KMPH -> 1
                    else -> 0
                }
            }
            ScoredSlot(slot, score)
        }

        // Find longest consecutive run with score >= 1
        var bestRunLen = 0
        var bestRunAllIdeal = false
        var bestRunStart: ForecastItem? = null
        var bestRunEnd: ForecastItem? = null
        var bestPopInWindow = 1.0

        var currentRun = mutableListOf<ScoredSlot>()
        for (ss in scored) {
            if (ss.score >= 1) {
                currentRun.add(ss)
                if (currentRun.size > bestRunLen) {
                    bestRunLen = currentRun.size
                    bestRunAllIdeal = currentRun.all { it.score == 2 }
                    bestRunStart = currentRun.first().slot
                    bestRunEnd = currentRun.last().slot
                    bestPopInWindow = currentRun.minOf { it.slot.pop }
                }
            } else {
                currentRun = mutableListOf()
            }
        }

        val severity = when {
            bestRunLen < Thresholds.HARVEST_MIN_WINDOW_SLOTS -> Severity.HIGH
            !bestRunAllIdeal -> Severity.MEDIUM
            else -> Severity.LOW
        }

        return Insight(
            category = "HARVEST_WINDOW",
            severity = severity,
            formatArgs = listOf((bestPopInWindow * 100).toInt()),
            windowStart = bestRunStart?.dt,
            windowEnd = bestRunEnd?.dt
        )
    }
}
