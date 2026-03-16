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
data class CurrentWeatherResponse(
    val dt: Long,
    val name: String,
    val coord: Coord,
    val weather: List<WeatherCondition> = emptyList(),
    val main: Main,
    val wind: Wind,
    val sys: CurrentSys = CurrentSys(),
    val visibility: Int = 0
)

@Serializable
data class CurrentSys(
    val country: String = "",
    val sunrise: Long = 0,
    val sunset: Long = 0
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
data class WeatherCondition(
    val id: Int = 0,
    val main: String = "",
    val description: String = "",
    val icon: String = ""
)

@Serializable
data class ForecastItem(
    val dt: Long,
    @SerialName("dt_txt") val dtTxt: String = "",
    val main: Main,
    val wind: Wind,
    val pop: Double = 0.0,
    val sys: Sys = Sys(),
    val weather: List<WeatherCondition> = emptyList()
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

fun iconUrl(icon: String): String = "https://openweathermap.org/img/wn/$icon@2x.png"

enum class ForecastType(val value: String, val ttlSeconds: Long) {
    FORECAST_5DAY("forecast_5day", 3 * 3600L),
    HOURLY_4DAY("hourly_4day", 1 * 3600L);

    companion object {
        fun from(value: String?): ForecastType =
            entries.firstOrNull { it.value == value } ?: FORECAST_5DAY
    }
}
