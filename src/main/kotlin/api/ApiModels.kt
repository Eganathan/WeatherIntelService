package dev.eknath.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IntelligenceResponse(
    @SerialName("overall_severity") val overallSeverity: String,
    @SerialName("forecast_type") val forecastType: String,
    @SerialName("generated_at") val generatedAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
    val location: LocationInfo,
    val insights: List<InsightDto>
)

@Serializable
data class LocationInfo(
    val lat: Double,
    val lon: Double,
    val city: String,
    val country: String
)

@Serializable
data class InsightDto(
    val category: String,
    val severity: String,
    val title: String,
    val description: String,
    @SerialName("format_args") val formatArgs: List<String> = emptyList(),
    @SerialName("window_start") val windowStart: Long? = null,
    @SerialName("window_end") val windowEnd: Long? = null
)

@Serializable
data class ErrorResponse(val error: String, val message: String)
