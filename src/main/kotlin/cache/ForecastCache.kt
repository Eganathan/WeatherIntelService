package dev.eknath.cache

import dev.eknath.owm.ForecastResponse
import dev.eknath.owm.ForecastType
import java.util.concurrent.ConcurrentHashMap

class ForecastCache {
    private data class Entry(val data: ForecastResponse, val expiresAt: Long)

    private val store = ConcurrentHashMap<String, Entry>()

    private fun key(lat: Double, lon: Double, type: ForecastType): String {
        val latR = "%.2f".format(lat)
        val lonR = "%.2f".format(lon)
        return "forecast:$latR:$lonR:${type.value}"
    }

    fun get(lat: Double, lon: Double, type: ForecastType): ForecastResponse? {
        val entry = store[key(lat, lon, type)] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            store.remove(key(lat, lon, type))
            return null
        }
        return entry.data
    }

    fun put(lat: Double, lon: Double, type: ForecastType, data: ForecastResponse) {
        val expiresAt = System.currentTimeMillis() + type.ttlSeconds * 1000
        store[key(lat, lon, type)] = Entry(data, expiresAt)
    }

    fun expiresAt(lat: Double, lon: Double, type: ForecastType): Long? {
        val entry = store[key(lat, lon, type)] ?: return null
        return entry.expiresAt / 1000
    }
}
