package dev.eknath.owm

import dev.eknath.config.AppConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class OwmClient {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun fetchForecast(lat: Double, lon: Double, type: ForecastType): ForecastResponse {
        val url = when (type) {
            ForecastType.FORECAST_5DAY ->
                "https://api.openweathermap.org/data/2.5/forecast?lat=$lat&lon=$lon&units=metric&appid=${AppConfig.owmApiKey}"
            ForecastType.HOURLY_4DAY ->
                "https://pro.openweathermap.org/data/2.5/forecast/hourly?lat=$lat&lon=$lon&units=metric&appid=${AppConfig.owmApiKey}"
        }
        return http.get(url).body()
    }

    suspend fun fetchCurrentWeather(lat: Double, lon: Double): CurrentWeatherResponse {
        val url = "https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lon&units=metric&appid=${AppConfig.owmApiKey}"
        return http.get(url).body()
    }

    fun close() = http.close()
}
