package dev.eknath.analyzers

import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.config.Thresholds
import dev.eknath.owm.ForecastItem

object SprayingWindowAnalyzer {
    fun analyze(slots: List<ForecastItem>): Insight {
        data class ScoredSlot(val slot: ForecastItem, val score: Int)

        val scored = slots.map { slot ->
            val score = if (slot.sys.pod == "n") 0 else {
                val windKmh = slot.wind.speed * 3.6
                var pts = 0
                if (windKmh in Thresholds.SPRAY_WIND_MIN_KMPH..Thresholds.SPRAY_WIND_MAX_KMPH) pts++
                if (slot.main.temp in Thresholds.SPRAY_TEMP_MIN_C..Thresholds.SPRAY_TEMP_MAX_C) pts++
                if (slot.main.humidity < Thresholds.SPRAY_HUMIDITY_MAX_PCT) pts++
                if (slot.pop < Thresholds.SPRAY_POP_MAX) pts++
                pts
            }
            ScoredSlot(slot, score)
        }

        // Find longest consecutive run with score >= 2
        var bestRunLen = 0
        var bestSlot: ForecastItem? = null
        var bestSlotScore = 0
        var bestRunStart: ForecastItem? = null
        var bestRunEnd: ForecastItem? = null

        var currentRun = mutableListOf<ScoredSlot>()
        for (ss in scored) {
            if (ss.score >= Thresholds.SPRAY_MIN_SCORE) {
                currentRun.add(ss)
                if (currentRun.size > bestRunLen) {
                    bestRunLen = currentRun.size
                    bestRunStart = currentRun.first().slot
                    bestRunEnd = currentRun.last().slot
                    // Best slot = highest score, then lowest pop
                    val best = currentRun.maxWithOrNull(
                        compareBy<ScoredSlot> { it.score }.thenBy { -it.slot.pop }
                    )!!
                    bestSlot = best.slot
                    bestSlotScore = best.score
                }
            } else {
                currentRun = mutableListOf()
            }
        }

        val severity = when {
            bestRunLen < 1 -> Severity.HIGH
            bestSlotScore == 4 -> Severity.LOW
            else -> Severity.MEDIUM
        }

        val windKmh = bestSlot?.let { (it.wind.speed * 3.6).toInt() } ?: 0
        val temp = bestSlot?.main?.temp?.toInt() ?: 0

        return Insight(
            category = "SPRAYING_WINDOW",
            severity = severity,
            formatArgs = listOf(windKmh, temp),
            windowStart = bestRunStart?.dt,
            windowEnd = bestRunEnd?.dt
        )
    }
}
