package dev.eknath.service

import dev.eknath.analyzers.*
import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.cache.ForecastCache
import dev.eknath.owm.ForecastItem
import dev.eknath.owm.ForecastResponse
import dev.eknath.owm.ForecastType
import dev.eknath.owm.OwmClient

class IntelligenceService(
    private val owmClient: OwmClient,
    private val forecastCache: ForecastCache
) {
    suspend fun getForecast(lat: Double, lon: Double, type: ForecastType): ForecastResponse {
        return forecastCache.get(lat, lon, type)
            ?: owmClient.fetchForecast(lat, lon, type).also {
                forecastCache.put(lat, lon, type, it)
            }
    }

    // Groups all forecast slots by calendar date (UTC) and runs analyzers per day.
    // Returns a LinkedHashMap to preserve chronological day order.
    fun runAnalyzersByDay(
        forecast: ForecastResponse,
        forecastType: ForecastType,
        categories: List<String>?
    ): LinkedHashMap<String, List<Insight>> {
        val result = LinkedHashMap<String, List<Insight>>()
        forecast.list
            .groupBy { it.dtTxt.take(10) }   // "YYYY-MM-DD"
            .forEach { (date, slots) ->
                result[date] = runAnalyzers(slots, forecastType, categories)
            }
        return result
    }

    fun runAnalyzers(
        slots: List<ForecastItem>,
        forecastType: ForecastType,
        categories: List<String>?
    ): List<Insight> {
        val filter = categories?.map { it.uppercase() }?.toSet()

        val all = mapOf(
            "SPRAYING" to { SprayingAnalyzer.analyze(slots) },
            "CHEMICAL_EFFICACY" to { ChemicalEfficacyAnalyzer.analyze(slots) },
            "FUNGAL_RISK" to { FungalRiskAnalyzer.analyze(slots, forecastType) },
            "FIELD_WORKABILITY" to { FieldWorkabilityAnalyzer.analyze(slots) },
            "HEAT_STRESS" to { HeatStressAnalyzer.analyze(slots, forecastType) },
            "HARVEST_WINDOW" to { HarvestWindowAnalyzer.analyze(slots) },
            "SPRAYING_WINDOW" to { SprayingWindowAnalyzer.analyze(slots) }
        )

        return all
            .filter { filter == null || it.key in filter }
            .map { it.value() }
    }

    fun overallSeverity(insights: List<Insight>): Severity =
        insights.map { it.severity }.minByOrNull { it.ordinal } ?: Severity.LOW
}
