# Weather Intelligence Microservice — Design Spec

**Date**: 2026-03-12
**Project**: FarmManagementWeatherIntelligence API
**Platform**: Zoho Catalyst AppSail
**Framework**: Ktor 2.3.12 / Kotlin / JVM 21
**Scope**: Phase 1 + i18n (7 analyzers, 2 endpoints, in-memory cache, API key auth, 6 languages)

---

## 1. What We Are Building

A Ktor REST microservice that:
- Fetches raw weather forecast data from OpenWeatherMap (OWM)
- Runs 7 agronomic analyzers on that data
- Returns actionable insights for farmers (spray safety, harvest windows, fungal risk, etc.)
- Caches OWM responses in-memory to avoid redundant API calls
- Supports 6 languages: English, Hindi, Gujarati, Kannada, Marathi, Tamil
- Exposes two endpoints: raw forecast passthrough + full intelligence report
- Deployed as a fat JAR on Zoho Catalyst AppSail

---

## 2. Package Structure

All code lives under `src/main/kotlin/dev/eknath/`.

```
dev/eknath/
├── Application.kt          ← entry point, wires all modules
├── Routing.kt              ← registers all route groups
├── HTTP.kt                 ← CORS config (add allowHeader("X-API-Key") here)
├── Serialization.kt        ← JSON setup (keep existing)
│                           ← NOTE: delete Security.kt — auth moves to api/ApiKeyAuth.kt
│
├── config/
│   ├── AppConfig.kt        ← reads OWM_API_KEY, API_KEY, PORT from env vars at startup
│   └── Thresholds.kt       ← all hardcoded threshold constants for 7 analyzers
│
├── owm/
│   ├── OwmClient.kt        ← Ktor HTTP client that calls OWM API
│   └── OwmModels.kt        ← ForecastResponse (includes City), ForecastItem, Wind, Main, Sys
│
├── cache/
│   └── ForecastCache.kt    ← in-memory ConcurrentHashMap with TTL (stores epoch millis internally)
│
├── analyzers/
│   ├── models/
│   │   ├── Severity.kt             ← enum: HIGH, MEDIUM, LOW
│   │   ├── Insight.kt              ← data class for one analyzer's result
│   │   └── IntelligenceReport.kt   ← full report with all insights + overall severity
│   ├── SprayingAnalyzer.kt         ← category: SPRAYING (worst wind slot)
│   ├── ChemicalEfficacyAnalyzer.kt ← category: CHEMICAL_EFFICACY (hottest slot)
│   ├── FungalRiskAnalyzer.kt       ← category: FUNGAL_RISK (consecutive humidity run)
│   ├── FieldWorkabilityAnalyzer.kt ← category: FIELD_WORKABILITY (worst PoP slot)
│   ├── HeatStressAnalyzer.kt       ← category: HEAT_STRESS (consecutive temp run)
│   ├── HarvestWindowAnalyzer.kt    ← category: HARVEST_WINDOW (composite daytime window)
│   └── SprayingWindowAnalyzer.kt   ← category: SPRAYING_WINDOW (4-criteria composite score)
│                                      NOTE: SprayingAnalyzer ≠ SprayingWindowAnalyzer — these
│                                      are two distinct analyzers with different algorithms
│
├── service/
│   └── IntelligenceService.kt  ← orchestrates: cache → OWM → run all 7 analyzers → report
│
├── i18n/
│   └── MessageBundle.kt    ← loads .properties files per locale, formats strings
│
└── api/
    ├── ApiModels.kt            ← request/response DTOs (serializable data classes)
    ├── ApiKeyAuth.kt           ← validates X-API-Key header (replaces root Security.kt)
    ├── IntelligenceRoutes.kt   ← GET /v1/intelligence
    └── ForecastRoutes.kt       ← GET /v1/forecast
```

**i18n resource files** live under `src/main/resources/i18n/`:
```
i18n/
├── messages_en.properties
├── messages_hi.properties
├── messages_gu.properties
├── messages_kn.properties
├── messages_mr.properties
└── messages_ta.properties
```

---

## 3. Data Flow

```
Client Request
  │
  ▼
X-API-Key check (401 if missing/wrong)  ← ApiKeyAuth.kt
  │
  ▼
Parse query params: lat, lon, type, lang, categories, crop
  │
  ▼
IntelligenceService.getReport(lat, lon, type, lang, categories)
  │
  ├── ForecastCache.get("${lat_2dp}:${lon_2dp}:${type}")
  │     ├── HIT  → use cached ForecastResponse
  │     └── MISS → OwmClient.fetch(lat, lon, type) → store in cache with TTL
  │
  ├── OWM ForecastResponse contains:
  │     ├── city.name, city.country   ← used for location in response
  │     └── list[]  ← the forecast slots (ForecastItem) passed to analyzers
  │
  ├── Run each of 7 Analyzers
  │     └── analyzer.analyze(slots: List<ForecastItem>, thresholds: Thresholds): Insight
  │
  └── Assemble IntelligenceReport
        ├── overall_severity = worst severity across all insights (lowest ordinal: HIGH=0)
        └── expires_at = cache TTL end time (in seconds for JSON: store millis, divide by 1000)
  │
  ▼
MessageBundle.resolve(category, severity, lang, formatArgs)
  → fills in title + description strings per locale
  │
  ▼
200 OK JSON response
```

---

## 4. API Endpoints

### GET /v1/intelligence

**Headers**: `X-API-Key: <key>` (required)

**Query params**:
| Param | Type | Required | Default | Notes |
|---|---|---|---|---|
| lat | Double | yes | — | Latitude |
| lon | Double | yes | — | Longitude |
| type | String | no | forecast_5day | `forecast_5day` or `hourly_4day` |
| lang | String | no | en | `en`, `hi`, `gu`, `kn`, `mr`, `ta` — unknown codes fall back to `en` |
| categories | String | no | all | Comma-separated filter e.g. `spraying,fungal_risk` |
| crop | String | no | null | Accepted and stored, not used for threshold tuning in Phase 1 |

**Response 200**:
```json
{
  "overall_severity": "HIGH",
  "forecast_type": "forecast_5day",
  "generated_at": 1741600000,
  "expires_at": 1741610800,
  "location": {
    "lat": 13.08,
    "lon": 80.27,
    "city": "Chennai",
    "country": "IN"
  },
  "insights": [
    {
      "category": "SPRAYING",
      "severity": "HIGH",
      "title": "Avoid Spraying — High Wind",
      "description": "Wind speed is 18 km/h. Spray drift risk is high.",
      "format_args": [18],
      "window_start": 1741600000,
      "window_end": 1741600000
    }
  ]
}
```

---

### GET /v1/forecast

**Headers**: `X-API-Key: <key>` (required)

**Query params**: `lat`, `lon`, `type` (same as above), `count` (optional, limits slots returned)

**Response**: Mirrors OWM ForecastResponse JSON (served from cache when available)

---

## 5. Caching Strategy (In-Memory, Phase 1)

```
Cache key:  "${lat_2dp}:${lon_2dp}:${type}"
            e.g. "13.08:80.27:forecast_5day"

TTL:
  forecast_5day → 3 hours (10_800_000 ms)
  hourly_4day   → 1 hour  (3_600_000 ms)

Implementation:
  - Use a ConcurrentHashMap<String, CacheEntry>
  - CacheEntry holds: ForecastResponse + expiresAtMillis (System.currentTimeMillis() + TTL_ms)
  - On read: if System.currentTimeMillis() > expiresAtMillis → treat as miss, fetch fresh
  - expiresAtMillis is internal (milliseconds). When writing to API response JSON:
    expires_at = expiresAtMillis / 1000  ← must divide by 1000 to get Unix seconds
```

Round lat/lon to 2 decimal places (~1 km) so nearby locations share the same cache entry.

---

## 6. Authentication

- Header: `X-API-Key`
- Value: loaded from `API_KEY` environment variable at startup via `AppConfig.kt`
- Applied to all `/v1/*` routes via `ApiKeyAuth.kt`
- Returns `401 Unauthorized` with `{"error": "Invalid or missing API key"}` on failure
- The existing `Security.kt` at the project root should be **deleted** — auth is handled entirely in `api/ApiKeyAuth.kt`
- `HTTP.kt` must be updated to add `allowHeader("X-API-Key")` to the CORS config, otherwise browser clients will be blocked on preflight requests

---

## 7. The 7 Analyzers — Summary

Each analyzer is a standalone object/class with a single `analyze()` function. No state, no I/O.

| # | Class | Category constant | Key Logic |
|---|---|---|---|
| 1 | SprayingAnalyzer | `SPRAYING` | Worst wind slot: HIGH if >15 km/h or <2 km/h |
| 2 | ChemicalEfficacyAnalyzer | `CHEMICAL_EFFICACY` | Hottest slot: HIGH if >32°C |
| 3 | FungalRiskAnalyzer | `FUNGAL_RISK` | Longest consecutive humidity >85% run |
| 4 | FieldWorkabilityAnalyzer | `FIELD_WORKABILITY` | Worst PoP slot: HIGH if >0.70 |
| 5 | HeatStressAnalyzer | `HEAT_STRESS` | Longest consecutive temp >38°C run |
| 6 | HarvestWindowAnalyzer | `HARVEST_WINDOW` | Best consecutive daytime dry+calm window |
| 7 | SprayingWindowAnalyzer | `SPRAYING_WINDOW` | 4-criteria composite score per slot |

Full algorithm detail for each is in `WEATHER_INTELLIGENCE_SERVER_SPEC.md` sections 2.4–2.10.

---

## 8. i18n

- **Format**: Java `.properties` files, one per language
- **Key pattern**: `{CATEGORY}.{SEVERITY}.title` and `{CATEGORY}.{SEVERITY}.description`
- **Format args**: descriptions use `{0}`, `{1}` placeholders (Java `MessageFormat` style)
- **Fallback**: if lang not found or key missing → fall back to `en`
- **Supported langs**: `en`, `hi`, `gu`, `kn`, `mr`, `ta`

**Complete key list** (42 keys per language file, 252 strings total across 6 files):

The 7 category names exactly as used in code:
`SPRAYING`, `CHEMICAL_EFFICACY`, `FUNGAL_RISK`, `FIELD_WORKABILITY`, `HEAT_STRESS`, `HARVEST_WINDOW`, `SPRAYING_WINDOW`

Each category × 3 severities (`HIGH`, `MEDIUM`, `LOW`) × 2 types (`title`, `description`):

```properties
# Example: messages_en.properties
SPRAYING.HIGH.title=Avoid Spraying — High Wind
SPRAYING.HIGH.description=Wind speed is {0} km/h. Spray drift risk is high.
SPRAYING.MEDIUM.title=Spray with Caution
SPRAYING.MEDIUM.description=Wind speed is {0} km/h. Borderline spray conditions.
SPRAYING.LOW.title=Good Spray Window
SPRAYING.LOW.description=Wind is {0} km/h. Optimal spray conditions.
# ... repeat for all 7 categories
```

---

## 9. Error Handling

Use the Ktor **StatusPages** plugin (`ktor-server-status-pages`) to centralize all error responses. Without it, you would need manual try/catch blocks in every route handler.

| Condition | HTTP Status | Response body |
|---|---|---|
| Missing/wrong API key | 401 | `{"error": "Invalid or missing API key"}` |
| Missing lat or lon | 400 | `{"error": "lat and lon are required"}` |
| OWM unreachable / timeout | 503 | `{"error": "Forecast service unavailable"}` |
| OWM returns error (bad key, quota exceeded) | 502 | `{"error": "Upstream forecast error: <owm message>"}` |
| Unknown lang | — | Silent fallback to `en` |
| Unknown type param | — | Default to `forecast_5day` |

---

## 10. AppSail Deployment Notes

1. **Build**: `./gradlew buildFatJar` → produces `build/libs/*-all.jar`

2. **Port — important**: AppSail injects the port via the `$PORT` environment variable.
   The `${PORT:8080}` fallback syntax is **HOCON syntax only** — it does NOT work in `application.yaml`.
   Instead, read the port in `AppConfig.kt` at startup:
   ```
   val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080
   ```
   Then pass it programmatically when starting the server in `Application.kt`.

3. **Environment variables to configure in AppSail's environment panel**:
   - `OWM_API_KEY` — your OpenWeatherMap API key
   - `API_KEY` — the static key clients will send in `X-API-Key`
   - `PORT` — injected automatically by AppSail (do not set manually)

4. **AppSail expects**: a process that listens on `$PORT` — Ktor/Netty does this once you wire the port correctly.

---

## 11. Dependencies to Add

### `gradle/libs.versions.toml` changes

Add to `[versions]`:
```toml
kotlinx-serialization = "1.7.3"
```

Add to `[libraries]`:
```toml
ktor-client-core                 = { module = "io.ktor:ktor-client-core",                 version.ref = "ktor" }
ktor-client-cio                  = { module = "io.ktor:ktor-client-cio",                  version.ref = "ktor" }
ktor-client-content-negotiation  = { module = "io.ktor:ktor-client-content-negotiation",  version.ref = "ktor" }
ktor-serialization-kotlinx-json  = { module = "io.ktor:ktor-serialization-kotlinx-json",  version.ref = "ktor" }
ktor-server-status-pages         = { module = "io.ktor:ktor-server-status-pages",         version.ref = "ktor" }
kotlinx-serialization-json       = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
```

Add to `[plugins]`:
```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

### `build.gradle.kts` changes

Add to plugins block:
```kotlin
alias(libs.plugins.kotlin.serialization)
```

Add to dependencies block:
```kotlin
implementation(libs.ktor.client.core)
implementation(libs.ktor.client.cio)
implementation(libs.ktor.client.content.negotiation)
implementation(libs.ktor.serialization.kotlinx.json)
implementation(libs.ktor.server.status.pages)
implementation(libs.kotlinx.serialization.json)
```

Note: `ktor-serialization-kotlinx-json` (Ktor module) and `kotlinx-serialization-json` (JetBrains module) serve different roles — the Ktor one is the bridge between Ktor's content negotiation and the JetBrains serialization library. Both are needed.

---

## 12. Build Order (Step-by-Step Learning Path)

Follow this sequence — each step is self-contained and buildable before moving to the next.

1. **Add dependencies** — update `libs.versions.toml` and `build.gradle.kts` as per Section 11. Run `./gradlew build` to confirm no errors.

2. **`config/Thresholds.kt`** — define all threshold constants as a data class or object (values from `WEATHER_INTELLIGENCE_SERVER_SPEC.md` Section 3.5).

3. **`config/AppConfig.kt`** — read `OWM_API_KEY`, `API_KEY`, and `PORT` from `System.getenv()`. This is the single place all environment config is loaded.

4. **`owm/OwmModels.kt`** — define Kotlinx serializable data classes for OWM's JSON response:
   - `ForecastResponse` (holds `city: City` and `list: List<ForecastItem>`)
   - `City` (holds `name: String`, `country: String`)
   - `ForecastItem` (holds `dt`, `dtTxt`, `main: Main`, `wind: Wind`, `pop`, `sys: Sys`)
   - `Main`, `Wind`, `Sys` (small nested classes)

5. **`owm/OwmClient.kt`** — create a Ktor HTTP client, implement `fetch(lat, lon, type): ForecastResponse`. Two OWM endpoints depending on type.

6. **`cache/ForecastCache.kt`** — `ConcurrentHashMap` with TTL. Methods: `get(key)`, `put(key, value, ttlMs)`. Remember: store `expiresAtMillis` internally; divide by 1000 for JSON.

7. **`analyzers/models/`** — define `Severity` enum, `Insight` data class, `IntelligenceReport` data class.

8. **`analyzers/`** — implement each of the 7 analyzers. Work through them in spec order (SprayingAnalyzer first — it's the simplest). Each has a single `analyze(slots, thresholds): Insight` function.

9. **`service/IntelligenceService.kt`** — the orchestrator. Takes `(lat, lon, type, lang, categories)`, uses `ForecastCache` + `OwmClient`, runs all 7 analyzers, returns `IntelligenceReport`.

10. **`i18n/MessageBundle.kt`** + all 6 `.properties` files — load properties files from classpath by locale, resolve keys, apply `MessageFormat` for format args.

11. **`api/ApiKeyAuth.kt`** — Ktor plugin or interceptor that reads `X-API-Key` header, compares to `AppConfig.apiKey`, returns 401 if invalid. **Delete `Security.kt`** at the root.

12. **`api/ApiModels.kt`** — Kotlinx serializable DTOs for the HTTP response (mirrors the JSON shape in Section 4).

13. **`api/ForecastRoutes.kt`** and **`api/IntelligenceRoutes.kt`** — wire routes to service calls.

14. **`HTTP.kt`** — add `allowHeader("X-API-Key")` to the CORS config.

15. **`Routing.kt`** and **`Application.kt`** — register all routes, install StatusPages error handling, wire `AppConfig.port` to the server.

16. **Test locally** — run `./gradlew run`, call endpoints with curl or Postman.

17. **Deploy to AppSail** — `./gradlew buildFatJar`, upload, set env vars, start.
