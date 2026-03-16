# Weather Intelligence Service — Implementation Plan

> **Note:** This plan is your **learning roadmap**. Each task gives you the exact code to write yourself — don't copy-paste, type it out so you understand every line. Ask for explanation on anything that's unclear.

**Goal:** Build a Ktor REST microservice that fetches OWM forecasts, runs 7 agronomic analyzers, and returns localised insights — deployed on Zoho Catalyst AppSail.

**Architecture:** Layered package structure: `config` → `owm` → `cache` → `analyzers` → `service` → `i18n` → `api`. Each layer has one responsibility and no layer talks upward. Routes call service; service calls analyzers; analyzers are pure functions.

**Tech Stack:** Ktor 2.3.12, Kotlin/JVM 21, Kotlinx Serialization, Ktor CIO HTTP client, in-memory TTL cache, Java `.properties` files for i18n, Gradle fat JAR for AppSail.

**Spec:** `docs/superpowers/specs/2026-03-12-weather-intelligence-design.md`
**OWM algorithm reference:** `WEATHER_INTELLIGENCE_SERVER_SPEC.md`

---

## Chunk 1: Project Setup + Config Layer

### Task 1: Add Dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Verify your Kotlin version in `gradle/libs.versions.toml`**

Open the file and check the `[versions]` section. You should see:
```toml
kotlin = "2.x.y"
```
Make sure this is a **real, published Kotlin version** (e.g. `2.1.20`). The `kotlin-serialization` plugin you're about to add uses `version.ref = "kotlin"`, so if the version is wrong, the plugin resolution will fail.

If you're unsure what version to use, check https://kotlinlang.org/docs/releases.html for the latest stable release and update the `kotlin` version entry to match.

- [ ] **Step 2: Add the new version entries in `gradle/libs.versions.toml`**

In the `[versions]` section add:
```toml
kotlinx-serialization = "1.7.3"
```

In the `[libraries]` section add:
```toml
ktor-client-core                = { module = "io.ktor:ktor-client-core",                version.ref = "ktor" }
ktor-client-cio                 = { module = "io.ktor:ktor-client-cio",                 version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-server-status-pages        = { module = "io.ktor:ktor-server-status-pages",        version.ref = "ktor" }
kotlinx-serialization-json      = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
```

In the `[plugins]` section add:
```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 3: Open `build.gradle.kts` and add the serialization plugin**

In the `plugins { }` block add below the existing aliases:
```kotlin
alias(libs.plugins.kotlin.serialization)
```

- [ ] **Step 4: Add the new dependencies in `build.gradle.kts`**

In the `dependencies { }` block add:
```kotlin
implementation(libs.ktor.client.core)
implementation(libs.ktor.client.cio)
implementation(libs.ktor.client.content.negotiation)
implementation(libs.ktor.serialization.kotlinx.json)
implementation(libs.ktor.server.status.pages)
implementation(libs.kotlinx.serialization.json)
```

- [ ] **Step 5: Sync and verify the build compiles cleanly**

```bash
./gradlew build
```
Expected: `BUILD SUCCESSFUL` — no compilation errors.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit -m "build: add serialization, ktor client, and status-pages dependencies"
```

---

### Task 2: Threshold Constants

**Files:**
- Create: `src/main/kotlin/dev/eknath/config/Thresholds.kt`
- Create: `src/test/kotlin/dev/eknath/config/ThresholdsTest.kt`

- [ ] **Step 1: Write the failing test first**

Create `src/test/kotlin/dev/eknath/config/ThresholdsTest.kt`:
```kotlin
package dev.eknath.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ThresholdsTest {

    @Test
    fun `spraying wind high threshold is 15 km/h`() {
        assertEquals(15.0, Thresholds.SPRAYING_WIND_HIGH_KMH)
    }

    @Test
    fun `fungal humidity danger is 85 percent`() {
        assertEquals(85, Thresholds.FUNGAL_HUMIDITY_DANGER_PCT)
    }

    @Test
    fun `heat stress danger temp is 38 degrees`() {
        assertEquals(38.0, Thresholds.HEAT_TEMP_DANGER_C)
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
./gradlew test --tests "dev.eknath.config.ThresholdsTest"
```
Expected: FAIL — `Thresholds` class does not exist yet.

- [ ] **Step 3: Create `src/main/kotlin/dev/eknath/config/Thresholds.kt`**

```kotlin
package dev.eknath.config

object Thresholds {
    // Analyzer 1: SPRAYING
    const val SPRAYING_WIND_HIGH_KMH = 15.0
    const val SPRAYING_WIND_CALM_MAX_KMH = 2.0
    const val SPRAYING_WIND_MEDIUM_HIGH_KMH = 10.0
    const val SPRAYING_WIND_MEDIUM_CALM_KMH = 3.0

    // Analyzer 2: CHEMICAL_EFFICACY
    const val CHEM_TEMP_HIGH_C = 32.0
    const val CHEM_TEMP_MEDIUM_C = 28.0

    // Analyzer 3: FUNGAL_RISK
    const val FUNGAL_HUMIDITY_DANGER_PCT = 85
    const val FUNGAL_HUMIDITY_WARNING_PCT = 70

    // Analyzer 4: FIELD_WORKABILITY
    const val FIELD_POP_HIGH = 0.70
    const val FIELD_POP_MEDIUM = 0.30

    // Analyzer 5: HEAT_STRESS
    const val HEAT_TEMP_DANGER_C = 38.0
    const val HEAT_TEMP_WARNING_C = 35.0

    // Analyzer 6: HARVEST_WINDOW
    const val HARVEST_POP_IDEAL_MAX = 0.30
    const val HARVEST_POP_MARGINAL_MAX = 0.50
    const val HARVEST_WIND_IDEAL_MIN_KMH = 3.0
    const val HARVEST_WIND_IDEAL_MAX_KMH = 15.0
    const val HARVEST_WIND_MARGINAL_MIN_KMH = 1.0
    const val HARVEST_WIND_MARGINAL_MAX_KMH = 20.0
    const val HARVEST_MIN_WINDOW_SLOTS = 2

    // Analyzer 7: SPRAYING_WINDOW
    const val SPRAY_WIN_WIND_MIN_KMH = 3.0
    const val SPRAY_WIN_WIND_MAX_KMH = 10.0
    const val SPRAY_WIN_TEMP_MIN_C = 15.0
    const val SPRAY_WIN_TEMP_MAX_C = 28.0
    const val SPRAY_WIN_HUMIDITY_MAX_PCT = 70
    const val SPRAY_WIN_POP_MAX = 0.30
    const val SPRAY_WIN_MIN_SCORE = 2
}
```

- [ ] **Step 4: Run tests — confirm pass**

```bash
./gradlew test --tests "dev.eknath.config.ThresholdsTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/eknath/config/Thresholds.kt src/test/kotlin/dev/eknath/config/ThresholdsTest.kt
git commit -m "feat: add Thresholds constants for all 7 analyzers"
```

---

### Task 3: App Config (Environment Variables)

**Files:**
- Create: `src/main/kotlin/dev/eknath/config/AppConfig.kt`
- Create: `src/test/kotlin/dev/eknath/config/AppConfigTest.kt`

> **Why**: AppConfig is the single place all environment config is read. Centralising it means if AppSail changes how it injects variables, you fix it in one place.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/eknath/config/AppConfigTest.kt`:
```kotlin
package dev.eknath.config

import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigTest {

    @Test
    fun `port defaults to 8080 when PORT env var is absent`() {
        // AppConfig reads System.getenv("PORT") — in test env it is not set
        // so it should default to 8080
        val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
        assertEquals(8080, port)
    }
}
```

- [ ] **Step 2: Run to confirm pass (this test passes without AppConfig existing)**

```bash
./gradlew test --tests "dev.eknath.config.AppConfigTest"
```
Expected: PASS (the logic is inline — this verifies the default behaviour).

- [ ] **Step 3: Create `src/main/kotlin/dev/eknath/config/AppConfig.kt`**

```kotlin
package dev.eknath.config

object AppConfig {
    val owmApiKey: String by lazy {
        System.getenv("OWM_API_KEY")
            ?: error("OWM_API_KEY environment variable is required but not set")
    }

    val apiKey: String by lazy {
        System.getenv("API_KEY")
            ?: error("API_KEY environment variable is required but not set")
    }

    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080
}
```

> **Why `by lazy`**: The `lazy` delegate defers reading the environment variable until the first time the property is accessed (i.e. when the server actually starts). If you used eager initialization (`val owmApiKey = ...` without `lazy`), any test that causes `AppConfig` to load — even indirectly — would throw immediately in CI environments where `OWM_API_KEY` is not set. With `lazy`, the fail-fast crash still happens at startup, but the test suite can run cleanly.

- [ ] **Step 4: Run all config tests**

```bash
./gradlew test --tests "dev.eknath.config.*"
```
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/eknath/config/AppConfig.kt src/test/kotlin/dev/eknath/config/AppConfigTest.kt
git commit -m "feat: add AppConfig to read env vars (OWM_API_KEY, API_KEY, PORT)"
```

---

## Chunk 2: OWM Layer (Models + Client + Cache)

### Task 4: OWM Data Models

**Files:**
- Create: `src/main/kotlin/dev/eknath/owm/OwmModels.kt`
- Create: `src/test/kotlin/dev/eknath/owm/OwmModelsTest.kt`

> **Why these classes**: The OWM API returns JSON. Kotlinx Serialization maps that JSON to these data classes automatically. The `@SerialName` annotation handles cases where the JSON key name differs from your Kotlin property name (e.g. `dt_txt` → `dtTxt`). `ignoreUnknownKeys = true` in the client means extra JSON fields from OWM won't crash your app.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/eknath/owm/OwmModelsTest.kt`:
```kotlin
package dev.eknath.owm

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class OwmModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialises a minimal forecast item from JSON`() {
        val raw = """
            {
              "dt": 1741600000,
              "dt_txt": "2026-03-10 12:00:00",
              "main": { "temp": 31.5, "humidity": 78 },
              "wind": { "speed": 4.2 },
              "pop": 0.15,
              "sys": { "pod": "d" }
            }
        """.trimIndent()

        val item = json.decodeFromString<ForecastItem>(raw)

        assertEquals(1741600000L, item.dt)
        assertEquals(31.5, item.main.temp)
        assertEquals(78, item.main.humidity)
        assertEquals(4.2, item.wind.speed)
        assertEquals(0.15, item.pop)
        assertEquals("d", item.sys?.pod)
    }

    @Test
    fun `deserialises city name and country from ForecastResponse`() {
        val raw = """
            {
              "city": { "name": "Chennai", "country": "IN" },
              "list": []
            }
        """.trimIndent()

        val response = json.decodeFromString<ForecastResponse>(raw)

        assertEquals("Chennai", response.city.name)
        assertEquals("IN", response.city.country)
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
./gradlew test --tests "dev.eknath.owm.OwmModelsTest"
```
Expected: FAIL — `ForecastItem`, `ForecastResponse` not found.

- [ ] **Step 3: Create `src/main/kotlin/dev/eknath/owm/OwmModels.kt`**

```kotlin
package dev.eknath.owm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ForecastResponse(
    val city: City,
    val list: List<ForecastItem>
)

@Serializable
data class City(
    val name: String,
    val country: String
)

@Serializable
data class ForecastItem(
    val dt: Long,
    @SerialName("dt_txt") val dtTxt: String = "",
    val main: Main,
    val wind: Wind,
    val pop: Double = 0.0,
    val sys: Sys? = null
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
    val pod: String? = null  // "d" = daytime, "n" = nighttime
)
```

Also add a convenience extension in the same file — you'll use it in every analyzer:
```kotlin
/** Wind speed converted from m/s to km/h as per OWM docs */
val ForecastItem.windKmh: Double get() = wind.speed * 3.6

/** True if this slot is during daytime */
val ForecastItem.isDaytime: Boolean get() = sys?.pod == "d"
```

- [ ] **Step 4: Run tests — confirm pass**

```bash
./gradlew test --tests "dev.eknath.owm.OwmModelsTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/eknath/owm/OwmModels.kt src/test/kotlin/dev/eknath/owm/OwmModelsTest.kt
git commit -m "feat: add OWM forecast data models with serialization"
```

---

### Task 5: Forecast Type Enum

**Files:**
- Create: `src/main/kotlin/dev/eknath/owm/ForecastType.kt`

> **Why a separate file**: `ForecastType` is used by the cache, the client, the service, and the routes. Keeping it in its own file prevents circular imports.

- [ ] **Step 1: Create `src/main/kotlin/dev/eknath/owm/ForecastType.kt`**

```kotlin
package dev.eknath.owm

enum class ForecastType(val value: String, val slotHours: Int) {
    FORECAST_5DAY("forecast_5day", 3),
    HOURLY_4DAY("hourly_4day", 1);

    companion object {
        fun from(value: String?): ForecastType =
            entries.find { it.value == value } ?: FORECAST_5DAY
    }
}
```

> The `slotHours` field (3 or 1) is used by the consecutive-run analyzers (FungalRisk, HeatStress) to compute how many slots = 10 hours or 6 hours.

- [ ] **Step 2: Build to verify no compile errors**

```bash
./gradlew build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/eknath/owm/ForecastType.kt
git commit -m "feat: add ForecastType enum with slot-hour metadata"
```

---

### Task 6: OWM HTTP Client

**Files:**
- Create: `src/main/kotlin/dev/eknath/owm/OwmClient.kt`

> **Note**: We don't write a unit test for `OwmClient` because it makes real HTTP calls. You'll test it manually in Task 17 when you do the first end-to-end run. Mocking HTTP clients adds complexity without much value at this stage.

- [ ] **Step 1: Create `src/main/kotlin/dev/eknath/owm/OwmClient.kt`**

```kotlin
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

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun fetchForecast(lat: Double, lon: Double, type: ForecastType): ForecastResponse {
        val url = when (type) {
            ForecastType.FORECAST_5DAY ->
                "https://api.openweathermap.org/data/2.5/forecast"
            ForecastType.HOURLY_4DAY ->
                "https://pro.openweathermap.org/data/2.5/forecast/hourly"
        }
        return httpClient.get(url) {
            parameter("lat", lat)
            parameter("lon", lon)
            parameter("appid", AppConfig.owmApiKey)
            parameter("units", "metric")
        }.body()
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

```bash
./gradlew build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/eknath/owm/OwmClient.kt
git commit -m "feat: add OwmClient to fetch forecast from OpenWeatherMap"
```

---

### Task 7: In-Memory Forecast Cache

**Files:**
- Create: `src/main/kotlin/dev/eknath/cache/ForecastCache.kt`
- Create: `src/test/kotlin/dev/eknath/cache/ForecastCacheTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/dev/eknath/cache/ForecastCacheTest.kt`:
```kotlin
package dev.eknath.cache

import dev.eknath.owm.*
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ForecastCacheTest {

    private fun emptyForecast() = ForecastResponse(
        city = City("TestCity", "IN"),
        list = emptyList()
    )

    @Test
    fun `returns null for an unknown key`() {
        val cache = ForecastCache()
        assertNull(cache.get("unknown:key"))
    }

    @Test
    fun `returns data immediately after put`() {
        val cache = ForecastCache()
        val key = cache.key(13.08, 80.27, ForecastType.FORECAST_5DAY)
        cache.put(key, emptyForecast(), ForecastType.FORECAST_5DAY)
        assertNotNull(cache.get(key))
    }

    @Test
    fun `rounds lat and lon to 2 decimal places in key`() {
        val cache = ForecastCache()
        val key = cache.key(13.0812345, 80.271111, ForecastType.FORECAST_5DAY)
        assert(key.startsWith("13.08:80.27")) { "Key was: $key" }
    }

    @Test
    fun `expires_at in seconds is TTL end divided by 1000`() {
        val cache = ForecastCache()
        val key = cache.key(13.08, 80.27, ForecastType.FORECAST_5DAY)
        val before = System.currentTimeMillis()
        cache.put(key, emptyForecast(), ForecastType.FORECAST_5DAY)
        val expiresAtSec = cache.expiresAtSeconds(key)!!
        val expectedMin = (before + 3 * 60 * 60 * 1000L) / 1000
        assert(expiresAtSec >= expectedMin) { "expiresAtSec $expiresAtSec was less than expected $expectedMin" }
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
./gradlew test --tests "dev.eknath.cache.ForecastCacheTest"
```
Expected: FAIL — `ForecastCache` not found.

- [ ] **Step 3: Create `src/main/kotlin/dev/eknath/cache/ForecastCache.kt`**

```kotlin
package dev.eknath.cache

import dev.eknath.owm.ForecastResponse
import dev.eknath.owm.ForecastType
import java.util.concurrent.ConcurrentHashMap

private data class CacheEntry(
    val data: ForecastResponse,
    val expiresAtMillis: Long
)

class ForecastCache {

    private val store = ConcurrentHashMap<String, CacheEntry>()

    fun key(lat: Double, lon: Double, type: ForecastType): String {
        val latR = "%.2f".format(lat)
        val lonR = "%.2f".format(lon)
        return "$latR:$lonR:${type.value}"
    }

    fun get(key: String): ForecastResponse? {
        val entry = store[key] ?: return null
        return if (System.currentTimeMillis() < entry.expiresAtMillis) entry.data else null
    }

    fun put(key: String, data: ForecastResponse, type: ForecastType) {
        val ttlMs = when (type) {
            ForecastType.FORECAST_5DAY -> 3 * 60 * 60 * 1000L   // 3 hours
            ForecastType.HOURLY_4DAY  -> 1 * 60 * 60 * 1000L    // 1 hour
        }
        store[key] = CacheEntry(data, System.currentTimeMillis() + ttlMs)
    }

    /** Returns the cache expiry time in Unix seconds (for use in API response's expires_at field) */
    fun expiresAtSeconds(key: String): Long? = store[key]?.expiresAtMillis?.div(1000)
}
```

- [ ] **Step 4: Run tests — confirm pass**

```bash
./gradlew test --tests "dev.eknath.cache.ForecastCacheTest"
```
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/eknath/cache/ForecastCache.kt src/test/kotlin/dev/eknath/cache/ForecastCacheTest.kt
git commit -m "feat: add in-memory ForecastCache with TTL (3h/1h)"
```

---

## Chunk 3: Analyzer Models + All 7 Analyzers

### Task 8: Analyzer Models (Severity, Insight, IntelligenceReport)

**Files:**
- Create: `src/main/kotlin/dev/eknath/analyzers/models/Severity.kt`
- Create: `src/main/kotlin/dev/eknath/analyzers/models/Insight.kt`
- Create: `src/main/kotlin/dev/eknath/analyzers/models/IntelligenceReport.kt`

- [ ] **Step 1: Create `Severity.kt`**

```kotlin
package dev.eknath.analyzers.models

/**
 * Severity levels ordered from most to least critical.
 * Use .ordinal for comparison: HIGH(0) < MEDIUM(1) < LOW(2)
 * Overall report severity = the one with the lowest ordinal.
 */
enum class Severity {
    HIGH,   // Act now / dangerous condition
    MEDIUM, // Caution / borderline
    LOW     // Safe / optimal
}
```

- [ ] **Step 2: Create `Insight.kt`**

```kotlin
package dev.eknath.analyzers.models

data class Insight(
    val category: String,
    val severity: Severity,
    val formatArgs: List<Any> = emptyList(),
    val windowStart: Long? = null,  // Unix seconds (from OWM dt field)
    val windowEnd: Long? = null
)
```

- [ ] **Step 3: Create `IntelligenceReport.kt`**

```kotlin
package dev.eknath.analyzers.models

data class Location(
    val lat: Double,
    val lon: Double,
    val city: String,
    val country: String
)

data class IntelligenceReport(
    val overallSeverity: Severity,
    val insights: List<Insight>,
    val generatedAt: Long,   // Unix seconds
    val expiresAt: Long,     // Unix seconds
    val location: Location
)
```

- [ ] **Step 4: Build to verify it compiles**

```bash
./gradlew build
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/eknath/analyzers/models/
git commit -m "feat: add analyzer models (Severity, Insight, IntelligenceReport)"
```

---

### Task 9: Analyzer 1 — SprayingAnalyzer

**Files:**
- Create: `src/main/kotlin/dev/eknath/analyzers/SprayingAnalyzer.kt`
- Create: `src/test/kotlin/dev/eknath/analyzers/SprayingAnalyzerTest.kt`

> **Algorithm**: Finds the single worst wind slot. HIGH if wind > 15 km/h (drift) OR < 2 km/h (thermal inversion). MEDIUM if wind in [10, 15] or [2, 3]. LOW if wind in (3, 10).

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/dev/eknath/analyzers/SprayingAnalyzerTest.kt`:
```kotlin
package dev.eknath.analyzers

import dev.eknath.analyzers.models.Severity
import dev.eknath.owm.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SprayingAnalyzerTest {

    private fun slot(windMs: Double) = ForecastItem(
        dt = 1000L, main = Main(temp = 25.0, humidity = 60),
        wind = Wind(speed = windMs), pop = 0.1
    )

    @Test
    fun `HIGH severity when wind exceeds 15 km/h`() {
        // 15.0 km/h = 4.167 m/s, so 4.2 m/s = 15.12 km/h → HIGH
        val result = SprayingAnalyzer.analyze(listOf(slot(4.2)))
        assertEquals(Severity.HIGH, result.severity)
    }

    @Test
    fun `HIGH severity when wind is below 2 km/h (thermal inversion)`() {
        // 0.5 m/s = 1.8 km/h → below 2.0 → HIGH
        val result = SprayingAnalyzer.analyze(listOf(slot(0.5)))
        assertEquals(Severity.HIGH, result.severity)
    }

    @Test
    fun `LOW severity when wind is in optimal range (3 to 10 km/h)`() {
        // 1.67 m/s = ~6 km/h → optimal
        val result = SprayingAnalyzer.analyze(listOf(slot(1.67)))
        assertEquals(Severity.LOW, result.severity)
    }

    @Test
    fun `returns worst slot across multiple slots`() {
        val slots = listOf(slot(1.67), slot(4.5))  // LOW then HIGH
        val result = SprayingAnalyzer.analyze(slots)
        assertEquals(Severity.HIGH, result.severity)
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
./gradlew test --tests "dev.eknath.analyzers.SprayingAnalyzerTest"
```
Expected: FAIL.

- [ ] **Step 3: Create `src/main/kotlin/dev/eknath/analyzers/SprayingAnalyzer.kt`**

```kotlin
package dev.eknath.analyzers

import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.config.Thresholds
import dev.eknath.owm.ForecastItem
import dev.eknath.owm.windKmh

object SprayingAnalyzer {
    const val CATEGORY = "SPRAYING"

    fun analyze(slots: List<ForecastItem>): Insight {
        var worstSeverity = Severity.LOW
        var worstSlot = slots.first()

        for (slot in slots) {
            val severity = severityFor(slot.windKmh)
            if (severity.ordinal < worstSeverity.ordinal) {
                worstSeverity = severity
                worstSlot = slot
            }
        }

        return Insight(
            category = CATEGORY,
            severity = worstSeverity,
            formatArgs = listOf(worstSlot.windKmh.toInt()),
            windowStart = worstSlot.dt,
            windowEnd = worstSlot.dt
        )
    }

    private fun severityFor(windKmh: Double): Severity = when {
        windKmh > Thresholds.SPRAYING_WIND_HIGH_KMH -> Severity.HIGH
        windKmh < Thresholds.SPRAYING_WIND_CALM_MAX_KMH -> Severity.HIGH
        windKmh >= Thresholds.SPRAYING_WIND_MEDIUM_HIGH_KMH -> Severity.MEDIUM
        windKmh <= Thresholds.SPRAYING_WIND_MEDIUM_CALM_KMH -> Severity.MEDIUM
        else -> Severity.LOW
    }
}
```

- [ ] **Step 4: Run tests — confirm pass**

```bash
./gradlew test --tests "dev.eknath.analyzers.SprayingAnalyzerTest"
```
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/eknath/analyzers/SprayingAnalyzer.kt src/test/kotlin/dev/eknath/analyzers/SprayingAnalyzerTest.kt
git commit -m "feat: add SprayingAnalyzer (worst wind slot)"
```

---

### Task 10: Analyzer 2 — ChemicalEfficacyAnalyzer

**Files:**
- Create: `src/main/kotlin/dev/eknath/analyzers/ChemicalEfficacyAnalyzer.kt`
- Create: `src/test/kotlin/dev/eknath/analyzers/ChemicalEfficacyAnalyzerTest.kt`

> **Algorithm**: Finds the hottest slot. HIGH > 32°C, MEDIUM ≥ 28°C, LOW < 28°C.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.eknath.analyzers

import dev.eknath.analyzers.models.Severity
import dev.eknath.owm.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ChemicalEfficacyAnalyzerTest {

    private fun slot(tempC: Double) = ForecastItem(
        dt = 1000L, main = Main(temp = tempC, humidity = 60),
        wind = Wind(speed = 2.0), pop = 0.1
    )

    @Test
    fun `HIGH when temperature exceeds 32 degrees`() {
        assertEquals(Severity.HIGH, ChemicalEfficacyAnalyzer.analyze(listOf(slot(33.0))).severity)
    }

    @Test
    fun `MEDIUM when temperature is between 28 and 32 degrees`() {
        assertEquals(Severity.MEDIUM, ChemicalEfficacyAnalyzer.analyze(listOf(slot(30.0))).severity)
    }

    @Test
    fun `LOW when temperature is below 28 degrees`() {
        assertEquals(Severity.LOW, ChemicalEfficacyAnalyzer.analyze(listOf(slot(25.0))).severity)
    }

    @Test
    fun `reports the hottest slot across multiple`() {
        val slots = listOf(slot(25.0), slot(34.0), slot(29.0))
        val result = ChemicalEfficacyAnalyzer.analyze(slots)
        assertEquals(Severity.HIGH, result.severity)
        assertEquals(34, result.formatArgs.first())
    }
}
```

- [ ] **Step 2: Run to confirm fail, then create `ChemicalEfficacyAnalyzer.kt`**

```kotlin
package dev.eknath.analyzers

import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.config.Thresholds
import dev.eknath.owm.ForecastItem

object ChemicalEfficacyAnalyzer {
    const val CATEGORY = "CHEMICAL_EFFICACY"

    fun analyze(slots: List<ForecastItem>): Insight {
        val hottest = slots.maxBy { it.main.temp }
        val severity = when {
            hottest.main.temp > Thresholds.CHEM_TEMP_HIGH_C   -> Severity.HIGH
            hottest.main.temp >= Thresholds.CHEM_TEMP_MEDIUM_C -> Severity.MEDIUM
            else -> Severity.LOW
        }
        return Insight(
            category = CATEGORY,
            severity = severity,
            formatArgs = listOf(hottest.main.temp.toInt()),
            windowStart = hottest.dt,
            windowEnd = hottest.dt
        )
    }
}
```

- [ ] **Step 3: Run tests — confirm pass**

```bash
./gradlew test --tests "dev.eknath.analyzers.ChemicalEfficacyAnalyzerTest"
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/eknath/analyzers/ChemicalEfficacyAnalyzer.kt src/test/kotlin/dev/eknath/analyzers/ChemicalEfficacyAnalyzerTest.kt
git commit -m "feat: add ChemicalEfficacyAnalyzer (hottest slot)"
```

---

### Task 11: Analyzer 3 — FungalRiskAnalyzer

**Files:**
- Create: `src/main/kotlin/dev/eknath/analyzers/FungalRiskAnalyzer.kt`
- Create: `src/test/kotlin/dev/eknath/analyzers/FungalRiskAnalyzerTest.kt`

> **Algorithm**: Tracks the longest *consecutive* run of slots where humidity > 85%. HIGH if run ≥ threshold (4 slots for 3h data = 12h, 10 slots for 1h data = 10h). MEDIUM if any slot > 70% but no sustained run. LOW if all ≤ 70%.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.eknath.analyzers

import dev.eknath.analyzers.models.Severity
import dev.eknath.owm.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FungalRiskAnalyzerTest {

    private fun slot(humidity: Int, dt: Long = 0L) = ForecastItem(
        dt = dt, main = Main(temp = 25.0, humidity = humidity),
        wind = Wind(speed = 2.0), pop = 0.1
    )

    @Test
    fun `HIGH when 4 consecutive slots above 85 percent humidity (3h slot type)`() {
        val slots = (1..4).map { slot(humidity = 90, dt = it.toLong()) }
        val result = FungalRiskAnalyzer.analyze(slots, ForecastType.FORECAST_5DAY)
        assertEquals(Severity.HIGH, result.severity)
    }

    @Test
    fun `MEDIUM when humidity exceeds 70 but no sustained run`() {
        val slots = listOf(slot(75), slot(60), slot(72))
        val result = FungalRiskAnalyzer.analyze(slots, ForecastType.FORECAST_5DAY)
        assertEquals(Severity.MEDIUM, result.severity)
    }

    @Test
    fun `LOW when all slots at or below 70 percent`() {
        val slots = listOf(slot(65), slot(70), slot(68))
        val result = FungalRiskAnalyzer.analyze(slots, ForecastType.FORECAST_5DAY)
        assertEquals(Severity.LOW, result.severity)
    }

    @Test
    fun `window start and end mark the HIGH run boundaries`() {
        val slots = (1..4).map { slot(humidity = 90, dt = it.toLong() * 10800) }
        val result = FungalRiskAnalyzer.analyze(slots, ForecastType.FORECAST_5DAY)
        assertNotNull(result.windowStart)
        assertNotNull(result.windowEnd)
        assertEquals(10800L, result.windowStart)
        assertEquals(4 * 10800L, result.windowEnd)
    }
}
```

- [ ] **Step 2: Run to confirm fail, then create `FungalRiskAnalyzer.kt`**

```kotlin
package dev.eknath.analyzers

import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.config.Thresholds
import dev.eknath.owm.ForecastItem
import dev.eknath.owm.ForecastType

object FungalRiskAnalyzer {
    const val CATEGORY = "FUNGAL_RISK"

    fun analyze(slots: List<ForecastItem>, type: ForecastType): Insight {
        val threshold = if (type == ForecastType.FORECAST_5DAY) 4 else 10

        var longestRun = 0
        var currentRun = 0
        var bestStart: Long? = null
        var bestEnd: Long? = null
        var runStart: Long? = null

        for (slot in slots) {
            if (slot.main.humidity > Thresholds.FUNGAL_HUMIDITY_DANGER_PCT) {
                if (currentRun == 0) runStart = slot.dt
                currentRun++
                if (currentRun > longestRun) {
                    longestRun = currentRun
                    bestStart = runStart
                    bestEnd = slot.dt
                }
            } else {
                currentRun = 0
                runStart = null
            }
        }

        val severity = when {
            longestRun >= threshold -> Severity.HIGH
            slots.any { it.main.humidity > Thresholds.FUNGAL_HUMIDITY_WARNING_PCT } -> Severity.MEDIUM
            else -> Severity.LOW
        }

        return Insight(
            category = CATEGORY,
            severity = severity,
            windowStart = if (severity == Severity.HIGH) bestStart else null,
            windowEnd = if (severity == Severity.HIGH) bestEnd else null
        )
    }
}
```

- [ ] **Step 3: Run tests — confirm pass**

```bash
./gradlew test --tests "dev.eknath.analyzers.FungalRiskAnalyzerTest"
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/eknath/analyzers/FungalRiskAnalyzer.kt src/test/kotlin/dev/eknath/analyzers/FungalRiskAnalyzerTest.kt
git commit -m "feat: add FungalRiskAnalyzer (consecutive humidity run)"
```

---

### Task 12: Analyzer 4 — FieldWorkabilityAnalyzer

**Files:**
- Create: `src/main/kotlin/dev/eknath/analyzers/FieldWorkabilityAnalyzer.kt`
- Create: `src/test/kotlin/dev/eknath/analyzers/FieldWorkabilityAnalyzerTest.kt`

> **Algorithm**: Finds the single worst PoP (probability of precipitation) slot. HIGH > 0.70, MEDIUM in (0.30, 0.70], LOW ≤ 0.30.

- [ ] **Step 1: Write tests, run to fail, then implement**

Tests:
```kotlin
package dev.eknath.analyzers

import dev.eknath.analyzers.models.Severity
import dev.eknath.owm.*
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldWorkabilityAnalyzerTest {

    private fun slot(pop: Double) = ForecastItem(
        dt = 1000L, main = Main(temp = 25.0, humidity = 60),
        wind = Wind(speed = 2.0), pop = pop
    )

    @Test
    fun `HIGH when pop exceeds 0_70`() =
        assertEquals(Severity.HIGH, FieldWorkabilityAnalyzer.analyze(listOf(slot(0.75))).severity)

    @Test
    fun `MEDIUM when pop is between 0_30 and 0_70`() =
        assertEquals(Severity.MEDIUM, FieldWorkabilityAnalyzer.analyze(listOf(slot(0.50))).severity)

    @Test
    fun `LOW when pop is below 0_30`() =
        assertEquals(Severity.LOW, FieldWorkabilityAnalyzer.analyze(listOf(slot(0.20))).severity)

    @Test
    fun `LOW at exactly 0_30 pop (boundary — spec uses less-than-or-equal)`() =
        assertEquals(Severity.LOW, FieldWorkabilityAnalyzer.analyze(listOf(slot(0.30))).severity)

    @Test
    fun `formatArgs contains pop as integer percentage`() {
        val result = FieldWorkabilityAnalyzer.analyze(listOf(slot(0.72)))
        assertEquals(72, result.formatArgs.first())
    }
}
```

Implementation:
```kotlin
package dev.eknath.analyzers

import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.config.Thresholds
import dev.eknath.owm.ForecastItem

object FieldWorkabilityAnalyzer {
    const val CATEGORY = "FIELD_WORKABILITY"

    fun analyze(slots: List<ForecastItem>): Insight {
        val worst = slots.maxBy { it.pop }
        val severity = when {
            worst.pop > Thresholds.FIELD_POP_HIGH   -> Severity.HIGH
            worst.pop > Thresholds.FIELD_POP_MEDIUM -> Severity.MEDIUM
            else -> Severity.LOW
        }
        return Insight(
            category = CATEGORY,
            severity = severity,
            formatArgs = listOf((worst.pop * 100).toInt()),
            windowStart = worst.dt,
            windowEnd = worst.dt
        )
    }
}
```

- [ ] **Step 2: Run tests — confirm pass**

```bash
./gradlew test --tests "dev.eknath.analyzers.FieldWorkabilityAnalyzerTest"
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/eknath/analyzers/FieldWorkabilityAnalyzer.kt src/test/kotlin/dev/eknath/analyzers/FieldWorkabilityAnalyzerTest.kt
git commit -m "feat: add FieldWorkabilityAnalyzer (worst PoP slot)"
```

---

### Task 13: Analyzer 5 — HeatStressAnalyzer

**Files:**
- Create: `src/main/kotlin/dev/eknath/analyzers/HeatStressAnalyzer.kt`
- Create: `src/test/kotlin/dev/eknath/analyzers/HeatStressAnalyzerTest.kt`

> **Algorithm**: Same consecutive-run pattern as FungalRisk. HIGH if ≥ 2 consecutive slots > 38°C (3h type) or ≥ 6 slots (1h type). MEDIUM if any slot ≥ 35°C but no run. Also tracks peak temp for formatArgs.

- [ ] **Step 1: Write tests, run to fail, then implement**

Tests:
```kotlin
package dev.eknath.analyzers

import dev.eknath.analyzers.models.Severity
import dev.eknath.owm.*
import kotlin.test.Test
import kotlin.test.assertEquals

class HeatStressAnalyzerTest {

    private fun slot(tempC: Double, dt: Long = 0L) = ForecastItem(
        dt = dt, main = Main(temp = tempC, humidity = 50),
        wind = Wind(speed = 2.0), pop = 0.1
    )

    @Test
    fun `HIGH when 2 consecutive slots exceed 38 degrees (3h type)`() {
        val slots = listOf(slot(39.0, 1L), slot(40.0, 2L))
        assertEquals(Severity.HIGH, HeatStressAnalyzer.analyze(slots, ForecastType.FORECAST_5DAY).severity)
    }

    @Test
    fun `MEDIUM when a slot reaches 35 degrees but no sustained run`() {
        val slots = listOf(slot(36.0), slot(34.0))
        assertEquals(Severity.MEDIUM, HeatStressAnalyzer.analyze(slots, ForecastType.FORECAST_5DAY).severity)
    }

    @Test
    fun `MEDIUM at exactly 35 degrees (boundary — spec uses greater-than-or-equal)`() {
        val slots = listOf(slot(35.0), slot(30.0))
        assertEquals(Severity.MEDIUM, HeatStressAnalyzer.analyze(slots, ForecastType.FORECAST_5DAY).severity)
    }

    @Test
    fun `LOW when all slots below 35 degrees`() {
        val slots = listOf(slot(30.0), slot(32.0))
        assertEquals(Severity.LOW, HeatStressAnalyzer.analyze(slots, ForecastType.FORECAST_5DAY).severity)
    }

    @Test
    fun `formatArgs contains peak temperature as integer`() {
        val slots = listOf(slot(36.0), slot(41.0), slot(39.0))
        val result = HeatStressAnalyzer.analyze(slots, ForecastType.FORECAST_5DAY)
        assertEquals(41, result.formatArgs.first())
    }
}
```

Implementation:
```kotlin
package dev.eknath.analyzers

import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.config.Thresholds
import dev.eknath.owm.ForecastItem
import dev.eknath.owm.ForecastType

object HeatStressAnalyzer {
    const val CATEGORY = "HEAT_STRESS"

    fun analyze(slots: List<ForecastItem>, type: ForecastType): Insight {
        val threshold = if (type == ForecastType.FORECAST_5DAY) 2 else 6

        var longestRun = 0
        var currentRun = 0
        var bestStart: Long? = null
        var bestEnd: Long? = null
        var runStart: Long? = null
        val peakTemp = slots.maxOf { it.main.temp }

        for (slot in slots) {
            if (slot.main.temp > Thresholds.HEAT_TEMP_DANGER_C) {
                if (currentRun == 0) runStart = slot.dt
                currentRun++
                if (currentRun > longestRun) {
                    longestRun = currentRun
                    bestStart = runStart
                    bestEnd = slot.dt
                }
            } else {
                currentRun = 0
                runStart = null
            }
        }

        val severity = when {
            longestRun >= threshold -> Severity.HIGH
            slots.any { it.main.temp >= Thresholds.HEAT_TEMP_WARNING_C } -> Severity.MEDIUM
            else -> Severity.LOW
        }

        return Insight(
            category = CATEGORY,
            severity = severity,
            formatArgs = listOf(peakTemp.toInt()),
            windowStart = if (severity == Severity.HIGH) bestStart else null,
            windowEnd = if (severity == Severity.HIGH) bestEnd else null
        )
    }
}
```

- [ ] **Step 2: Run tests and commit**

```bash
./gradlew test --tests "dev.eknath.analyzers.HeatStressAnalyzerTest"
git add src/main/kotlin/dev/eknath/analyzers/HeatStressAnalyzer.kt src/test/kotlin/dev/eknath/analyzers/HeatStressAnalyzerTest.kt
git commit -m "feat: add HeatStressAnalyzer (consecutive heat run)"
```

---

### Task 14: Analyzer 6 — HarvestWindowAnalyzer

**Files:**
- Create: `src/main/kotlin/dev/eknath/analyzers/HarvestWindowAnalyzer.kt`
- Create: `src/test/kotlin/dev/eknath/analyzers/HarvestWindowAnalyzerTest.kt`

> **Algorithm**: Scores each daytime slot (nighttime always = 0). Score 2 = ideal (pop < 0.30 AND wind in [3, 15] km/h). Score 1 = marginal (pop < 0.50 AND wind in [1, 20] km/h). Finds longest consecutive run of score ≥ 1. HIGH if no window (< 2 slots), LOW if all ideal, MEDIUM if mixed.

- [ ] **Step 1: Write tests, run to fail, then implement**

Tests:
```kotlin
package dev.eknath.analyzers

import dev.eknath.analyzers.models.Severity
import dev.eknath.owm.*
import kotlin.test.Test
import kotlin.test.assertEquals

class HarvestWindowAnalyzerTest {

    private fun daytimeSlot(pop: Double, windMs: Double, dt: Long = 1L) = ForecastItem(
        dt = dt, main = Main(temp = 25.0, humidity = 60),
        wind = Wind(speed = windMs), pop = pop,
        sys = Sys(pod = "d")
    )

    private fun nightSlot() = ForecastItem(
        dt = 1L, main = Main(temp = 20.0, humidity = 60),
        wind = Wind(speed = 2.0), pop = 0.1,
        sys = Sys(pod = "n")
    )

    @Test
    fun `HIGH when fewer than 2 suitable daytime slots exist`() {
        val slots = listOf(nightSlot(), nightSlot())
        assertEquals(Severity.HIGH, HarvestWindowAnalyzer.analyze(slots).severity)
    }

    @Test
    fun `LOW when window found and all slots are ideal`() {
        // pop 0.1 < 0.30, wind 2.5 m/s = 9 km/h in [3, 15] → score 2
        val slots = listOf(daytimeSlot(0.1, 2.5, 1L), daytimeSlot(0.1, 2.5, 2L))
        assertEquals(Severity.LOW, HarvestWindowAnalyzer.analyze(slots).severity)
    }

    @Test
    fun `MEDIUM when window found but includes marginal slots`() {
        // score 1: pop 0.4 (< 0.50) wind 2.0 m/s = 7.2 km/h in [1, 20]
        val slots = listOf(daytimeSlot(0.4, 2.0, 1L), daytimeSlot(0.4, 2.0, 2L))
        assertEquals(Severity.MEDIUM, HarvestWindowAnalyzer.analyze(slots).severity)
    }
}
```

Implementation:
```kotlin
package dev.eknath.analyzers

import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.config.Thresholds
import dev.eknath.owm.ForecastItem
import dev.eknath.owm.isDaytime
import dev.eknath.owm.windKmh

object HarvestWindowAnalyzer {
    const val CATEGORY = "HARVEST_WINDOW"

    fun analyze(slots: List<ForecastItem>): Insight {
        val scores = slots.map { slotScore(it) }

        // Find longest consecutive run of score >= 1
        var bestStart = 0
        var bestLen = 0
        var runStart = 0
        var runLen = 0

        for (i in scores.indices) {
            if (scores[i] >= 1) {
                if (runLen == 0) runStart = i
                runLen++
                if (runLen > bestLen) {
                    bestLen = runLen
                    bestStart = runStart
                }
            } else {
                runLen = 0
            }
        }

        if (bestLen < Thresholds.HARVEST_MIN_WINDOW_SLOTS) {
            return Insight(category = CATEGORY, severity = Severity.HIGH)
        }

        val windowSlots = slots.subList(bestStart, bestStart + bestLen)
        val allIdeal = windowSlots.indices.all { scores[bestStart + it] == 2 }
        val bestPop = windowSlots.minOf { it.pop }

        return Insight(
            category = CATEGORY,
            severity = if (allIdeal) Severity.LOW else Severity.MEDIUM,
            formatArgs = listOf((bestPop * 100).toInt()),
            windowStart = windowSlots.first().dt,
            windowEnd = windowSlots.last().dt
        )
    }

    private fun slotScore(slot: ForecastItem): Int {
        if (!slot.isDaytime) return 0
        val wind = slot.windKmh
        return when {
            slot.pop < Thresholds.HARVEST_POP_IDEAL_MAX &&
                    wind in Thresholds.HARVEST_WIND_IDEAL_MIN_KMH..Thresholds.HARVEST_WIND_IDEAL_MAX_KMH -> 2
            slot.pop < Thresholds.HARVEST_POP_MARGINAL_MAX &&
                    wind in Thresholds.HARVEST_WIND_MARGINAL_MIN_KMH..Thresholds.HARVEST_WIND_MARGINAL_MAX_KMH -> 1
            else -> 0
        }
    }
}
```

- [ ] **Step 2: Run tests and commit**

```bash
./gradlew test --tests "dev.eknath.analyzers.HarvestWindowAnalyzerTest"
git add src/main/kotlin/dev/eknath/analyzers/HarvestWindowAnalyzer.kt src/test/kotlin/dev/eknath/analyzers/HarvestWindowAnalyzerTest.kt
git commit -m "feat: add HarvestWindowAnalyzer (composite daytime window)"
```

---

### Task 15: Analyzer 7 — SprayingWindowAnalyzer

**Files:**
- Create: `src/main/kotlin/dev/eknath/analyzers/SprayingWindowAnalyzer.kt`
- Create: `src/test/kotlin/dev/eknath/analyzers/SprayingWindowAnalyzerTest.kt`

> **Algorithm**: Scores each daytime slot 0–4 (one point per criterion: wind in [3,10] km/h, temp in [15,28]°C, humidity < 70%, pop < 0.30). Finds longest consecutive run of score ≥ 2. HIGH = no suitable slot. LOW = best slot scores 4/4. MEDIUM = best slot scores 2 or 3.

- [ ] **Step 1: Write tests, run to fail, then implement**

Tests:
```kotlin
package dev.eknath.analyzers

import dev.eknath.analyzers.models.Severity
import dev.eknath.owm.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SprayingWindowAnalyzerTest {

    private fun slot(windMs: Double, tempC: Double, humidity: Int, pop: Double, pod: String = "d") = ForecastItem(
        dt = 1L, main = Main(temp = tempC, humidity = humidity),
        wind = Wind(speed = windMs), pop = pop, sys = Sys(pod = pod)
    )

    @Test
    fun `HIGH when no daytime slot scores at least 2`() {
        // All criteria fail
        val bad = slot(windMs = 5.0, tempC = 35.0, humidity = 80, pop = 0.5)
        assertEquals(Severity.HIGH, SprayingWindowAnalyzer.analyze(listOf(bad)).severity)
    }

    @Test
    fun `LOW when best slot meets all 4 criteria`() {
        // wind 1.67 m/s = 6 km/h ✓, temp 22 ✓, humidity 60 ✓, pop 0.1 ✓ → score 4
        val perfect = slot(windMs = 1.67, tempC = 22.0, humidity = 60, pop = 0.1)
        assertEquals(Severity.LOW, SprayingWindowAnalyzer.analyze(listOf(perfect, perfect)).severity)
    }

    @Test
    fun `nighttime slots always score 0`() {
        val night = slot(windMs = 1.67, tempC = 22.0, humidity = 60, pop = 0.1, pod = "n")
        assertEquals(Severity.HIGH, SprayingWindowAnalyzer.analyze(listOf(night)).severity)
    }
}
```

Implementation:
```kotlin
package dev.eknath.analyzers

import dev.eknath.analyzers.models.Insight
import dev.eknath.analyzers.models.Severity
import dev.eknath.config.Thresholds
import dev.eknath.owm.ForecastItem
import dev.eknath.owm.isDaytime
import dev.eknath.owm.windKmh

object SprayingWindowAnalyzer {
    const val CATEGORY = "SPRAYING_WINDOW"

    fun analyze(slots: List<ForecastItem>): Insight {
        val scores = slots.map { slotScore(it) }

        // Find longest consecutive run with score >= 2
        var bestStart = 0
        var bestLen = 0
        var runStart = 0
        var runLen = 0

        for (i in scores.indices) {
            if (scores[i] >= Thresholds.SPRAY_WIN_MIN_SCORE) {
                if (runLen == 0) runStart = i
                runLen++
                if (runLen > bestLen) {
                    bestLen = runLen
                    bestStart = runStart
                }
            } else {
                runLen = 0
            }
        }

        if (bestLen < 1) {
            return Insight(category = CATEGORY, severity = Severity.HIGH)
        }

        val windowSlots = slots.subList(bestStart, bestStart + bestLen)
        val windowScores = scores.subList(bestStart, bestStart + bestLen)

        // Best slot = highest score, ties broken by lowest pop
        val bestIdx = windowScores.indices.maxWith(
            compareBy({ windowScores[it] }, { -windowSlots[it].pop })
        )
        val bestSlot = windowSlots[bestIdx]
        val bestScore = windowScores[bestIdx]

        val severity = if (bestScore == 4) Severity.LOW else Severity.MEDIUM

        return Insight(
            category = CATEGORY,
            severity = severity,
            formatArgs = listOf(bestSlot.windKmh.toInt(), bestSlot.main.temp.toInt()),
            windowStart = windowSlots.first().dt,
            windowEnd = windowSlots.last().dt
        )
    }

    private fun slotScore(slot: ForecastItem): Int {
        if (!slot.isDaytime) return 0
        var score = 0
        if (slot.windKmh in Thresholds.SPRAY_WIN_WIND_MIN_KMH..Thresholds.SPRAY_WIN_WIND_MAX_KMH) score++
        if (slot.main.temp in Thresholds.SPRAY_WIN_TEMP_MIN_C..Thresholds.SPRAY_WIN_TEMP_MAX_C) score++
        if (slot.main.humidity < Thresholds.SPRAY_WIN_HUMIDITY_MAX_PCT) score++
        if (slot.pop < Thresholds.SPRAY_WIN_POP_MAX) score++
        return score
    }
}
```

- [ ] **Step 2: Run all analyzer tests together**

```bash
./gradlew test --tests "dev.eknath.analyzers.*"
```
Expected: all PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/eknath/analyzers/SprayingWindowAnalyzer.kt src/test/kotlin/dev/eknath/analyzers/SprayingWindowAnalyzerTest.kt
git commit -m "feat: add SprayingWindowAnalyzer (4-criteria composite score)"
```

---

## Chunk 4: i18n + IntelligenceService

### Task 16: i18n Message Bundles

**Files:**
- Create: `src/main/kotlin/dev/eknath/i18n/MessageBundle.kt`
- Create: `src/main/resources/i18n/messages_en.properties` (+ 5 more language files)
- Create: `src/test/kotlin/dev/eknath/i18n/MessageBundleTest.kt`

> **How this works**: Java's `ResourceBundle` loads `.properties` files from the classpath. `MessageFormat.format()` substitutes `{0}`, `{1}` placeholders with the formatArgs values. Files must be in `src/main/resources/` to be included on the classpath.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.eknath.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

class MessageBundleTest {

    @Test
    fun `resolves English title for SPRAYING HIGH`() {
        val title = MessageBundle.title("SPRAYING", "HIGH", "en")
        assert(title.isNotBlank()) { "Title should not be blank" }
    }

    @Test
    fun `resolves English description with format arg for SPRAYING HIGH`() {
        val desc = MessageBundle.description("SPRAYING", "HIGH", "en", listOf(18))
        assertContains(desc, "18")
    }

    @Test
    fun `falls back to English for unknown language`() {
        val title = MessageBundle.title("SPRAYING", "HIGH", "zz")
        val expected = MessageBundle.title("SPRAYING", "HIGH", "en")
        assertEquals(expected, title)
    }
}
```

- [ ] **Step 2: Run to confirm fail**

```bash
./gradlew test --tests "dev.eknath.i18n.MessageBundleTest"
```

- [ ] **Step 3: Create `src/main/resources/i18n/messages_en.properties`**

```properties
# ---- SPRAYING ----
SPRAYING.HIGH.title=Avoid Spraying \u2014 High Wind
SPRAYING.HIGH.description=Wind speed is {0} km/h. Spray drift risk is high.
SPRAYING.MEDIUM.title=Spray with Caution
SPRAYING.MEDIUM.description=Wind speed is {0} km/h. Conditions are borderline for spraying.
SPRAYING.LOW.title=Good Spray Window
SPRAYING.LOW.description=Wind is {0} km/h. Conditions are optimal for spraying.

# ---- CHEMICAL_EFFICACY ----
CHEMICAL_EFFICACY.HIGH.title=Avoid Spraying \u2014 Extreme Heat
CHEMICAL_EFFICACY.HIGH.description=Temperature is {0}\u00b0C. Pesticide will evaporate before it acts.
CHEMICAL_EFFICACY.MEDIUM.title=Spray Early Morning
CHEMICAL_EFFICACY.MEDIUM.description=Temperature is {0}\u00b0C. Chemical efficacy may be reduced.
CHEMICAL_EFFICACY.LOW.title=Good Chemical Efficacy
CHEMICAL_EFFICACY.LOW.description=Temperature is {0}\u00b0C. Optimal conditions for chemical uptake.

# ---- FUNGAL_RISK ----
FUNGAL_RISK.HIGH.title=High Fungal Disease Risk
FUNGAL_RISK.HIGH.description=Sustained high humidity detected. Apply preventive fungicide now.
FUNGAL_RISK.MEDIUM.title=Moderate Fungal Risk
FUNGAL_RISK.MEDIUM.description=Humidity is elevated. Monitor crops closely.
FUNGAL_RISK.LOW.title=Low Fungal Risk
FUNGAL_RISK.LOW.description=Humidity levels are normal. No action needed.

# ---- FIELD_WORKABILITY ----
FIELD_WORKABILITY.HIGH.title=Field Entry Not Advised
FIELD_WORKABILITY.HIGH.description={0}% chance of rain. Soil may be saturated and muddy.
FIELD_WORKABILITY.MEDIUM.title=Field Entry Possible with Caution
FIELD_WORKABILITY.MEDIUM.description={0}% chance of rain. Plan for possible delays.
FIELD_WORKABILITY.LOW.title=Good Field Conditions
FIELD_WORKABILITY.LOW.description={0}% chance of rain. Dry conditions expected.

# ---- HEAT_STRESS ----
HEAT_STRESS.HIGH.title=Severe Heat Stress Risk
HEAT_STRESS.HIGH.description=Sustained temperatures above 38\u00b0C. Peak: {0}\u00b0C. Irrigate immediately.
HEAT_STRESS.MEDIUM.title=Heat Stress Risk
HEAT_STRESS.MEDIUM.description=Temperatures reaching {0}\u00b0C. Watch for heat stress symptoms.
HEAT_STRESS.LOW.title=No Heat Stress
HEAT_STRESS.LOW.description=Temperatures are within safe range. Peak: {0}\u00b0C.

# ---- HARVEST_WINDOW ----
HARVEST_WINDOW.HIGH.title=No Harvest Window Found
HARVEST_WINDOW.HIGH.description=No suitable dry, calm daytime period in the forecast.
HARVEST_WINDOW.MEDIUM.title=Marginal Harvest Window
HARVEST_WINDOW.MEDIUM.description=A harvest window exists but conditions are not ideal. Rain chance: {0}%.
HARVEST_WINDOW.LOW.title=Ideal Harvest Window
HARVEST_WINDOW.LOW.description=Excellent dry and calm period for harvest. Rain chance: {0}%.

# ---- SPRAYING_WINDOW ----
SPRAYING_WINDOW.HIGH.title=No Suitable Spray Window
SPRAYING_WINDOW.HIGH.description=No slot meets the minimum conditions for safe, effective spraying.
SPRAYING_WINDOW.MEDIUM.title=Acceptable Spray Window
SPRAYING_WINDOW.MEDIUM.description=A spray window found. Wind: {0} km/h, Temp: {1}\u00b0C.
SPRAYING_WINDOW.LOW.title=Optimal Spray Window
SPRAYING_WINDOW.LOW.description=All conditions met. Wind: {0} km/h, Temp: {1}\u00b0C. Ideal time to spray.
```

- [ ] **Step 4: Create the 5 other language files**

Create the same key structure for each language. For now, you can copy `messages_en.properties` and translate the values into each language. Files to create:
- `src/main/resources/i18n/messages_hi.properties` (Hindi)
- `src/main/resources/i18n/messages_gu.properties` (Gujarati)
- `src/main/resources/i18n/messages_kn.properties` (Kannada)
- `src/main/resources/i18n/messages_mr.properties` (Marathi)
- `src/main/resources/i18n/messages_ta.properties` (Tamil)

For now, copy the English file for each and update the translations later. The structure (keys) must be identical across all files.

- [ ] **Step 5: Create `src/main/kotlin/dev/eknath/i18n/MessageBundle.kt`**

```kotlin
package dev.eknath.i18n

import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

object MessageBundle {

    private val supportedLangs = setOf("en", "hi", "gu", "kn", "mr", "ta")

    private fun bundle(lang: String): ResourceBundle {
        // NOTE: ResourceBundle uses dot-notation for baseName, NOT slash-notation.
        // "i18n.messages" resolves to i18n/messages_en.properties on the classpath.
        val locale = if (lang in supportedLangs) Locale(lang) else Locale("en")
        return try {
            ResourceBundle.getBundle("i18n.messages", locale)
        } catch (e: Exception) {
            ResourceBundle.getBundle("i18n.messages", Locale("en"))
        }
    }

    fun title(category: String, severity: String, lang: String): String =
        resolve("$category.$severity.title", lang)

    fun description(category: String, severity: String, lang: String, args: List<Any> = emptyList()): String {
        val pattern = resolve("$category.$severity.description", lang)
        return if (args.isEmpty()) pattern
        else MessageFormat.format(pattern, *args.toTypedArray())
    }

    private fun resolve(key: String, lang: String): String =
        try { bundle(lang).getString(key) } catch (e: Exception) {
            try { bundle("en").getString(key) } catch (e2: Exception) { key }
        }
}
```

- [ ] **Step 6: Run tests — confirm pass**

```bash
./gradlew test --tests "dev.eknath.i18n.MessageBundleTest"
```
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/dev/eknath/i18n/ src/main/resources/i18n/
git commit -m "feat: add i18n MessageBundle with 6-language .properties files"
```

---

### Task 17: IntelligenceService (Orchestrator)

**Files:**
- Create: `src/main/kotlin/dev/eknath/service/IntelligenceService.kt`
- Create: `src/test/kotlin/dev/eknath/service/IntelligenceServiceTest.kt`

> **Why**: This class is the only place that knows about all the other layers. Routes call this — they don't talk to the cache, OWM client, or analyzers directly.

- [ ] **Step 1: Write a failing integration test**

```kotlin
package dev.eknath.service

import dev.eknath.analyzers.models.Severity
import dev.eknath.cache.ForecastCache
import dev.eknath.owm.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class IntelligenceServiceTest {

    private fun makeForecast(humidity: Int = 60, tempC: Double = 25.0, windMs: Double = 2.0, pop: Double = 0.1): ForecastResponse {
        val slot = ForecastItem(
            dt = System.currentTimeMillis() / 1000,
            main = Main(temp = tempC, humidity = humidity),
            wind = Wind(speed = windMs),
            pop = pop,
            sys = Sys(pod = "d")
        )
        return ForecastResponse(city = City("TestCity", "IN"), list = listOf(slot))
    }

    @Test
    fun `returns a report with 7 insights for a forecast with one slot`() {
        val cache = ForecastCache()
        val forecast = makeForecast()
        val key = cache.key(13.08, 80.27, ForecastType.FORECAST_5DAY)
        cache.put(key, forecast, ForecastType.FORECAST_5DAY)

        // Provide cache directly — no real OWM call needed in this test
        val service = IntelligenceService(cache, owmClient = null)
        val report = service.buildReport(
            forecast = forecast,
            lat = 13.08, lon = 80.27,
            type = ForecastType.FORECAST_5DAY,
            lang = "en",
            cacheKey = key
        )

        assertNotNull(report)
        assertEquals(7, report.insights.size)
    }

    @Test
    fun `overall severity is the worst insight severity`() {
        // Create a forecast slot that causes HIGH spraying (wind 5 m/s = 18 km/h)
        val cache = ForecastCache()
        val forecast = makeForecast(windMs = 5.0)
        val key = cache.key(13.08, 80.27, ForecastType.FORECAST_5DAY)
        cache.put(key, forecast, ForecastType.FORECAST_5DAY)

        val service = IntelligenceService(cache, owmClient = null)
        val report = service.buildReport(forecast, 13.08, 80.27, ForecastType.FORECAST_5DAY, "en", key)

        assertEquals(Severity.HIGH, report.overallSeverity)
    }
}
```

- [ ] **Step 2: Run to confirm fail**

```bash
./gradlew test --tests "dev.eknath.service.IntelligenceServiceTest"
```

- [ ] **Step 3: Create `src/main/kotlin/dev/eknath/service/IntelligenceService.kt`**

```kotlin
package dev.eknath.service

import dev.eknath.analyzers.*
import dev.eknath.analyzers.models.*
import dev.eknath.cache.ForecastCache
import dev.eknath.i18n.MessageBundle
import dev.eknath.owm.*

class IntelligenceService(
    private val cache: ForecastCache,
    private val owmClient: OwmClient?
) {

    suspend fun getReport(
        lat: Double, lon: Double,
        type: ForecastType,
        lang: String,
        categories: Set<String>? = null
    ): IntelligenceReport {
        val key = cache.key(lat, lon, type)
        val forecast = cache.get(key)
            ?: owmClient!!.fetchForecast(lat, lon, type).also { cache.put(key, it, type) }

        return buildReport(forecast, lat, lon, type, lang, key, categories)
    }

    fun buildReport(
        forecast: ForecastResponse,
        lat: Double, lon: Double,
        type: ForecastType,
        lang: String,
        cacheKey: String,
        categories: Set<String>? = null
    ): IntelligenceReport {
        val slots = forecast.list

        val allInsights = listOf(
            SprayingAnalyzer.analyze(slots),
            ChemicalEfficacyAnalyzer.analyze(slots),
            FungalRiskAnalyzer.analyze(slots, type),
            FieldWorkabilityAnalyzer.analyze(slots),
            HeatStressAnalyzer.analyze(slots, type),
            HarvestWindowAnalyzer.analyze(slots),
            SprayingWindowAnalyzer.analyze(slots)
        )

        // categories comes in as uppercase strings (e.g. "SPRAYING") matching analyzer CATEGORY constants
        // categories comes in as uppercase strings (e.g. "SPRAYING") matching analyzer CATEGORY constants
        val filtered = if (categories.isNullOrEmpty()) allInsights
        else allInsights.filter { it.category.uppercase() in categories }

        // Localisation (title/description strings) happens in the API layer via MessageBundle,
        // not here. Insight stays as a pure data model — no i18n concern in the service layer.
        val overall = filtered.minByOrNull { it.severity.ordinal }?.severity ?: Severity.LOW
        val expiresAt = cache.expiresAtSeconds(cacheKey) ?: (System.currentTimeMillis() / 1000 + 3600)

        return IntelligenceReport(
            overallSeverity = overall,
            insights = filtered,
            generatedAt = System.currentTimeMillis() / 1000,
            expiresAt = expiresAt,
            location = Location(
                lat = lat, lon = lon,
                city = forecast.city.name,
                country = forecast.city.country
            )
        )
    }
}
```

> **Note on localisation**: The `Insight` model carries raw `category`, `severity`, and `formatArgs`. The route layer calls `MessageBundle` to produce the final `title` and `description` strings for the JSON response. This keeps the service layer language-agnostic.

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests "dev.eknath.service.IntelligenceServiceTest"
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/eknath/service/IntelligenceService.kt src/test/kotlin/dev/eknath/service/IntelligenceServiceTest.kt
git commit -m "feat: add IntelligenceService orchestrating cache, OWM, and 7 analyzers"
```

---

## Chunk 5: API Layer + Wiring

### Task 18: API Response Models

**Files:**
- Create: `src/main/kotlin/dev/eknath/api/ApiModels.kt`

- [ ] **Step 1: Create `src/main/kotlin/dev/eknath/api/ApiModels.kt`**

```kotlin
package dev.eknath.api

import dev.eknath.analyzers.models.IntelligenceReport
import dev.eknath.analyzers.models.Severity
import dev.eknath.i18n.MessageBundle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LocationResponse(
    val lat: Double,
    val lon: Double,
    val city: String,
    val country: String
)

@Serializable
data class InsightResponse(
    val category: String,
    val severity: String,
    val title: String,
    val description: String,
    @SerialName("format_args") val formatArgs: List<String>,
    @SerialName("window_start") val windowStart: Long?,
    @SerialName("window_end") val windowEnd: Long?
)

@Serializable
data class IntelligenceResponse(
    @SerialName("overall_severity") val overallSeverity: String,
    @SerialName("forecast_type") val forecastType: String,
    @SerialName("generated_at") val generatedAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
    val location: LocationResponse,
    val insights: List<InsightResponse>
)

fun IntelligenceReport.toResponse(forecastType: String, lang: String): IntelligenceResponse {
    return IntelligenceResponse(
        overallSeverity = overallSeverity.name,
        forecastType = forecastType,
        generatedAt = generatedAt,
        expiresAt = expiresAt,
        location = LocationResponse(location.lat, location.lon, location.city, location.country),
        insights = insights.map { insight ->
            InsightResponse(
                category = insight.category,
                severity = insight.severity.name,
                title = MessageBundle.title(insight.category, insight.severity.name, lang),
                description = MessageBundle.description(insight.category, insight.severity.name, lang, insight.formatArgs),
                formatArgs = insight.formatArgs.map { it.toString() },
                windowStart = insight.windowStart,
                windowEnd = insight.windowEnd
            )
        }
    )
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew build
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/eknath/api/ApiModels.kt
git commit -m "feat: add API response DTOs with i18n mapping"
```

---

### Task 19: API Key Authentication

**Files:**
- Create: `src/main/kotlin/dev/eknath/api/ApiKeyAuth.kt`

- [ ] **Step 1: Create `src/main/kotlin/dev/eknath/api/ApiKeyAuth.kt`**

```kotlin
package dev.eknath.api

import dev.eknath.config.AppConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*

val ApiKeyPlugin = createRouteScopedPlugin("ApiKeyAuth") {
    onCall { call ->
        val provided = call.request.header("X-API-Key")
        if (provided != AppConfig.apiKey) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing API key"))
            return@onCall
        }
    }
}
```

> **Why `createRouteScopedPlugin`**: This lets you apply the auth check per route group rather than globally. You install it on the `/v1` route block.

- [ ] **Step 2: Build to verify**

```bash
./gradlew build
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/eknath/api/ApiKeyAuth.kt
git commit -m "feat: add ApiKeyAuth plugin for X-API-Key header validation"
```

---

### Task 20: Forecast Route

**Files:**
- Create: `src/main/kotlin/dev/eknath/api/ForecastRoutes.kt`

- [ ] **Step 1: Create `src/main/kotlin/dev/eknath/api/ForecastRoutes.kt`**

```kotlin
package dev.eknath.api

import dev.eknath.service.IntelligenceService
import dev.eknath.owm.ForecastType
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.forecastRoutes(service: IntelligenceService) {
    // NOTE: ApiKeyPlugin is installed at the /v1 route block level in Routing.kt,
    // NOT inside the handler lambda. install() is a Route-level function.
    // This function is called inside route("/v1") { } so the full path is /v1/forecast.
    get("/forecast") {
        val lat = call.parameters["lat"]?.toDoubleOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "lat and lon are required"))
        val lon = call.parameters["lon"]?.toDoubleOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "lat and lon are required"))
        val type = ForecastType.from(call.parameters["type"])
        val count = call.parameters["count"]?.toIntOrNull()

        val forecast = service.getRawForecast(lat, lon, type)
        val list = if (count != null) forecast.copy(list = forecast.list.take(count)) else forecast
        call.respond(list)
    }
}
```

> You'll need to add a `getRawForecast` method to `IntelligenceService` — it follows the same cache-or-fetch pattern as `getReport` but returns `ForecastResponse` directly.

- [ ] **Step 2: Add `getRawForecast` to `IntelligenceService.kt`**

```kotlin
suspend fun getRawForecast(lat: Double, lon: Double, type: ForecastType): ForecastResponse {
    val key = cache.key(lat, lon, type)
    return cache.get(key)
        ?: owmClient!!.fetchForecast(lat, lon, type).also { cache.put(key, it, type) }
}
```

- [ ] **Step 3: Build to verify**

```bash
./gradlew build
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/eknath/api/ForecastRoutes.kt
git commit -m "feat: add GET /v1/forecast route (raw OWM passthrough with cache)"
```

---

### Task 21: Intelligence Route

**Files:**
- Create: `src/main/kotlin/dev/eknath/api/IntelligenceRoutes.kt`

- [ ] **Step 1: Create `src/main/kotlin/dev/eknath/api/IntelligenceRoutes.kt`**

```kotlin
package dev.eknath.api

import dev.eknath.owm.ForecastType
import dev.eknath.service.IntelligenceService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.intelligenceRoutes(service: IntelligenceService) {
    // NOTE: ApiKeyPlugin is installed at the /v1 route block level in Routing.kt.
    // This function is called inside route("/v1") { } so the full path is /v1/intelligence.
    get("/intelligence") {
        val lat = call.parameters["lat"]?.toDoubleOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "lat and lon are required"))
        val lon = call.parameters["lon"]?.toDoubleOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "lat and lon are required"))

        val type = ForecastType.from(call.parameters["type"])
        val lang = call.parameters["lang"] ?: "en"
        val categories = call.parameters["categories"]
            ?.split(",")?.map { it.trim().uppercase() }?.toSet()
        // NOTE: categories are normalized to UPPERCASE here to match the analyzer CATEGORY constants
        // (e.g. "spraying" → "SPRAYING"). IntelligenceService must filter on uppercase too.

        val report = service.getReport(lat, lon, type, lang, categories)
        call.respond(report.toResponse(type.value, lang))
    }
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew build
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/eknath/api/IntelligenceRoutes.kt
git commit -m "feat: add GET /v1/intelligence route"
```

---

### Task 22: Wire Everything Together

**Files:**
- Modify: `src/main/kotlin/dev/eknath/Routing.kt`
- Modify: `src/main/kotlin/dev/eknath/Serialization.kt`
- Modify: `src/main/kotlin/dev/eknath/Application.kt`
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Update `Serialization.kt` to install Kotlinx JSON**

```kotlin
package dev.eknath

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        })
    }
}
```

- [ ] **Step 2: Update `Routing.kt` to wire all routes and StatusPages**

```kotlin
package dev.eknath

import dev.eknath.api.*
import dev.eknath.cache.ForecastCache
import dev.eknath.owm.OwmClient
import dev.eknath.service.IntelligenceService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    val cache = ForecastCache()
    val owmClient = OwmClient()
    val service = IntelligenceService(cache, owmClient)

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Bad request")))
        }
        exception<Exception> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Unexpected error"))
        }
    }

    routing {
        get("/health") {
            call.respondText("OK")
        }
        // All /v1 routes share the API key auth plugin installed here at route scope.
        // install() MUST be called on a Route node — not inside a handler lambda.
        route("/v1") {
            install(ApiKeyPlugin)
            intelligenceRoutes(service)
            forecastRoutes(service)
        }
    }
}
```

- [ ] **Step 3: Update `Application.kt` to use `AppConfig.port`**

Switch from `EngineMain` (which reads port from YAML only) to `embeddedServer` so `AppConfig.port` drives the port directly. This ensures AppSail's `$PORT` env var is respected.

```kotlin
package dev.eknath

import dev.eknath.config.AppConfig
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    embeddedServer(Netty, port = AppConfig.port, module = Application::module).start(wait = true)
}

fun Application.module() {
    configureHTTP()
    configureSerialization()
    configureRouting()
}
```

> **Why `embeddedServer` instead of `EngineMain`**: `EngineMain` reads its port from `application.yaml` and cannot easily pick up an env var. `embeddedServer` lets you pass `AppConfig.port` directly, which reads `$PORT` at startup. AppSail injects the port via `$PORT` — this is the correct way to handle it.

- [ ] **Step 4: Simplify `application.yaml`**

Since `embeddedServer` handles the port, remove the deployment section from `application.yaml`. The modules list is also no longer needed with `embeddedServer`. Replace the file contents with:
```yaml
ktor:
  application:
    modules:
      - dev.eknath.ApplicationKt.module
```

> With `embeddedServer`, the `modules` list in YAML is ignored — the module is wired directly in `main()`. You can leave the YAML as-is or simplify it to an empty file. Keeping it doesn't hurt.

- [ ] **Step 5: Build the full project**

```bash
./gradlew build
```
Expected: `BUILD SUCCESSFUL` with all tests passing.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/eknath/Routing.kt src/main/kotlin/dev/eknath/Serialization.kt src/main/kotlin/dev/eknath/Application.kt src/main/resources/application.yaml
git commit -m "feat: wire all routes, install ContentNegotiation and StatusPages"
```

---

### Task 23: Local End-to-End Test

- [ ] **Step 1: Set env vars and run locally**

```bash
export OWM_API_KEY="your_owm_key_here"
export API_KEY="test-secret-key"
./gradlew run
```
Expected output: `Application started ... Responding at http://0.0.0.0:8080`

- [ ] **Step 2: Test the health endpoint**

```bash
curl http://localhost:8080/health
```
Expected: `OK`

- [ ] **Step 3: Test without API key — expect 401**

```bash
curl "http://localhost:8080/v1/intelligence?lat=13.08&lon=80.27"
```
Expected: `{"error":"Invalid or missing API key"}`

- [ ] **Step 4: Test the intelligence endpoint**

```bash
curl -H "X-API-Key: test-secret-key" \
  "http://localhost:8080/v1/intelligence?lat=13.08&lon=80.27&lang=en"
```
Expected: JSON with `overall_severity`, 7 insights, location `Chennai`.

- [ ] **Step 5: Test the forecast passthrough**

```bash
curl -H "X-API-Key: test-secret-key" \
  "http://localhost:8080/v1/forecast?lat=13.08&lon=80.27&count=5"
```
Expected: raw OWM forecast JSON with 5 slots.

---

### Task 24: Build Fat JAR + Deploy to AppSail

- [ ] **Step 1: Build the deployable fat JAR**

```bash
./gradlew buildFatJar
```
Expected: `build/libs/weatherInteligenceApiService-0.0.1-all.jar`

- [ ] **Step 2: Test the fat JAR locally**

```bash
OWM_API_KEY="your_key" API_KEY="test-secret-key" java -jar build/libs/weatherInteligenceApiService-0.0.1-all.jar
```
Confirm it starts on port 8080 and responds correctly.

- [ ] **Step 3: Deploy to Zoho Catalyst AppSail**

In the AppSail dashboard:
1. Create a new AppSail service (Java runtime)
2. Upload the fat JAR
3. Set the start command to: `java -jar weatherInteligenceApiService-0.0.1-all.jar`
   (AppSail injects `$PORT`; `AppConfig.port` reads it automatically via `System.getenv("PORT")`)
4. Add environment variables:
   - `OWM_API_KEY` = your OWM API key
   - `API_KEY` = your chosen secret key
5. Deploy and wait for the health check to pass

- [ ] **Step 4: Smoke test the live URL**

```bash
curl -H "X-API-Key: your_api_key" \
  "https://your-appsail-url/v1/intelligence?lat=13.08&lon=80.27"
```

- [ ] **Step 5: Final commit**

```bash
git add .
git commit -m "feat: complete Phase 1 weather intelligence microservice on AppSail"
```

---

## Summary

| Chunk | Tasks | What you build |
|---|---|---|
| 1 | 1–3 | Dependencies, Thresholds, AppConfig |
| 2 | 4–7 | OWM models, ForecastType, OwmClient, ForecastCache |
| 3 | 8–15 | Severity/Insight models, all 7 analyzers with TDD |
| 4 | 16–17 | i18n bundles (6 languages), IntelligenceService |
| 5 | 18–24 | API DTOs, auth, routes, wiring, deploy to AppSail |
