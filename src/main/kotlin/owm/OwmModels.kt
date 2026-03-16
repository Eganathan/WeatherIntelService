package dev.eknath.owm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ForecastResponse(
    val cod: String,
    val cnt: Int,
    val list: List<ForecastItem>,
    val city: City
)

@Serializable
data class City(
    val id: Long = 0,
    val name: String,
    val coord: Coord,
    val country: String
)

@Serializable
data class Coord(
    val lat: Double,
    val lon: Double
)

@Serializable
data class ForecastItem(
    val dt: Long,
    @SerialName("dt_txt") val dtTxt: String = "",
    val main: Main,
    val wind: Wind,
    val pop: Double = 0.0,
    val sys: Sys = Sys()
)

@Serializable
data class Main(
    val temp: Double,
    val humidity: Int
)

@Serializable
data class Wind(
    val speed: Double
)

@Serializable
data class Sys(
    val pod: String = "d"
)

enum class ForecastType(val value: String, val ttlSeconds: Long) {
    FORECAST_5DAY("forecast_5day", 3 * 3600L),
    HOURLY_4DAY("hourly_4day", 1 * 3600L);

    companion object {
        fun from(value: String?): ForecastType =
            entries.firstOrNull { it.value == value } ?: FORECAST_5DAY
    }
}
