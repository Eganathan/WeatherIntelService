package dev.eknath

import dev.eknath.api.requireApiKey
import dev.eknath.api.ErrorResponse
import dev.eknath.api.InsightDto
import dev.eknath.api.IntelligenceResponse
import dev.eknath.api.LocationInfo
import dev.eknath.cache.ForecastCache
import dev.eknath.config.Thresholds
import dev.eknath.i18n.MessageBundle
import dev.eknath.owm.ForecastType
import dev.eknath.owm.OwmClient
import dev.eknath.service.IntelligenceService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    val owmClient = OwmClient()
    val forecastCache = ForecastCache()
    val service = IntelligenceService(owmClient, forecastCache)

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", cause.message ?: "Invalid request"))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error", "An unexpected error occurred"))
        }
    }

    routing {
        get("/") {
            call.respondText("FarmManagementWeatherIntelligence Service")
        }

        route("/v1") {
            get("/intelligence") {
                if (!call.requireApiKey()) return@get

                val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
                    ?: throw IllegalArgumentException("lat is required and must be a number")
                val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
                    ?: throw IllegalArgumentException("lon is required and must be a number")
                val forecastType = ForecastType.from(call.request.queryParameters["type"])
                val lang = call.request.queryParameters["lang"] ?: "en"
                val categories = call.request.queryParameters["categories"]
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?.takeIf { it.isNotEmpty() }

                val forecast = service.getForecast(lat, lon, forecastType)
                val insights = service.runAnalyzers(forecast, forecastType, categories)
                val overallSeverity = service.overallSeverity(insights)

                val generatedAt = System.currentTimeMillis() / 1000
                val expiresAt = forecastCache.expiresAt(lat, lon, forecastType) ?: (generatedAt + forecastType.ttlSeconds)

                val insightDtos = insights.map { insight ->
                    val (title, description) = MessageBundle.resolve(insight, lang)
                    InsightDto(
                        category = insight.category,
                        severity = insight.severity.name,
                        title = title,
                        description = description,
                        formatArgs = insight.formatArgs.map { it.toString() },
                        windowStart = insight.windowStart,
                        windowEnd = insight.windowEnd
                    )
                }

                call.respond(
                    IntelligenceResponse(
                        overallSeverity = overallSeverity.name,
                        forecastType = forecastType.value,
                        generatedAt = generatedAt,
                        expiresAt = expiresAt,
                        location = LocationInfo(
                            lat = forecast.city.coord.lat,
                            lon = forecast.city.coord.lon,
                            city = forecast.city.name,
                            country = forecast.city.country
                        ),
                        insights = insightDtos
                    )
                )
            }

            get("/forecast") {
                if (!call.requireApiKey()) return@get

                val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
                    ?: throw IllegalArgumentException("lat is required and must be a number")
                val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
                    ?: throw IllegalArgumentException("lon is required and must be a number")
                val forecastType = ForecastType.from(call.request.queryParameters["type"])
                val count = call.request.queryParameters["count"]?.toIntOrNull()

                var forecast = service.getForecast(lat, lon, forecastType)
                if (count != null && count > 0) {
                    forecast = forecast.copy(list = forecast.list.take(count), cnt = minOf(count, forecast.cnt))
                }
                call.respond(forecast)
            }

            route("/admin") {
                get("/thresholds") {
                    if (!call.requireApiKey()) return@get
                    call.respond(
                        mapOf(
                            "SPRAYING" to mapOf(
                                "wind_high_min_kmh" to Thresholds.SPRAYING_WIND_HIGH_MIN_KMPH,
                                "wind_calm_max_kmh" to Thresholds.SPRAYING_WIND_CALM_MAX_KMPH,
                                "wind_medium_high_kmh" to Thresholds.SPRAYING_WIND_MEDIUM_HIGH_KMPH,
                                "wind_medium_calm_kmh" to Thresholds.SPRAYING_WIND_MEDIUM_CALM_KMPH
                            ),
                            "CHEMICAL_EFFICACY" to mapOf(
                                "temp_high_c" to Thresholds.CHEMICAL_TEMP_HIGH_C,
                                "temp_medium_c" to Thresholds.CHEMICAL_TEMP_MEDIUM_C
                            ),
                            "FUNGAL_RISK" to mapOf(
                                "humidity_danger_pct" to Thresholds.FUNGAL_HUMIDITY_DANGER_PCT,
                                "humidity_warning_pct" to Thresholds.FUNGAL_HUMIDITY_WARNING_PCT,
                                "sustained_hours" to Thresholds.FUNGAL_SUSTAINED_HOURS
                            ),
                            "HEAT_STRESS" to mapOf(
                                "temp_danger_c" to Thresholds.HEAT_TEMP_DANGER_C,
                                "temp_warning_c" to Thresholds.HEAT_TEMP_WARNING_C,
                                "sustained_hours" to Thresholds.HEAT_SUSTAINED_HOURS
                            ),
                            "FIELD_WORKABILITY" to mapOf(
                                "pop_high" to Thresholds.FIELD_POP_HIGH,
                                "pop_medium" to Thresholds.FIELD_POP_MEDIUM
                            ),
                            "HARVEST_WINDOW" to mapOf(
                                "pop_ideal_max" to Thresholds.HARVEST_POP_IDEAL_MAX,
                                "pop_marginal_max" to Thresholds.HARVEST_POP_MARGINAL_MAX,
                                "wind_ideal_min_kmh" to Thresholds.HARVEST_WIND_IDEAL_MIN_KMPH,
                                "wind_ideal_max_kmh" to Thresholds.HARVEST_WIND_IDEAL_MAX_KMPH,
                                "wind_marginal_min_kmh" to Thresholds.HARVEST_WIND_MARGINAL_MIN_KMPH,
                                "wind_marginal_max_kmh" to Thresholds.HARVEST_WIND_MARGINAL_MAX_KMPH,
                                "min_window_slots" to Thresholds.HARVEST_MIN_WINDOW_SLOTS
                            ),
                            "SPRAYING_WINDOW" to mapOf(
                                "wind_min_kmh" to Thresholds.SPRAY_WIND_MIN_KMPH,
                                "wind_max_kmh" to Thresholds.SPRAY_WIND_MAX_KMPH,
                                "temp_min_c" to Thresholds.SPRAY_TEMP_MIN_C,
                                "temp_max_c" to Thresholds.SPRAY_TEMP_MAX_C,
                                "humidity_max_pct" to Thresholds.SPRAY_HUMIDITY_MAX_PCT,
                                "pop_max" to Thresholds.SPRAY_POP_MAX,
                                "min_score" to Thresholds.SPRAY_MIN_SCORE
                            )
                        )
                    )
                }
            }
        }
    }
}
