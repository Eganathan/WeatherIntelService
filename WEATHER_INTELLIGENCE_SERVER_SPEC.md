# FarmManagementWeatherIntelligence: Server Migration Specification

**Version**: 1.0
**Date**: 2026-03-10
**Status**: Draft — for backend team

---

## 1. Background & Motivation

The weather intelligence layer currently runs entirely on each client device (Android SDK,Web,). It fetches raw forecast data from OpenWeatherMap and runs seven analysis algorithms locally to produce agronomic insights. Moving this to the server has several advantages:

| Problem (client-side) | Benefit (server-side) |
|---|---|
| Logic duplicated across Android, iOS, web | Single implementation, one place to fix/update |
| Threshold changes require an app release | Update thresholds instantly, no deployment |
| Client always fetches all 40/96 forecast slots even when not needed | Server fetches once, many clients benefit |
| No crop/region-specific tuning possible | Thresholds can vary by crop type, region, season |
| No historical insight data | Server can store history, build trend analysis |
| Analysis on low-end devices is a battery concern | Server-grade compute, no device constraints |

---

## 2. Current Client-Side Logic — Complete Reference

This section documents every analyzer exactly as implemented in the Android SDK so the server team can reproduce it faithfully.

### 2.1 Data Source

Raw forecast data comes from **OpenWeatherMap**:

| Endpoint | Type | Slots | Interval | Tier |
|---|---|---|---|---|
| `GET /data/2.5/forecast` | `FORECAST_5DAY` | up to 40 | 3 hours | Free |
| `GET pro.openweathermap.org/data/2.5/forecast/hourly` | `HOURLY_4DAY` | up to 96 | 1 hour | Paid |

**Unit system**: metric (`units=metric`)
**Wind speed from API**: m/s → convert to km/h by ×3.6 before threshold comparisons
**PoP (Probability of Precipitation)**: 0.0–1.0 (not percentage)

### 2.2 Key Fields Used Per Forecast Slot

Each `ForecastItem` (one time slot) has these fields used by the analyzers:

```
dt            Long    Unix timestamp UTC (seconds) — marks start of slot
dt_txt        String  "YYYY-MM-DD HH:mm:ss" UTC
main.temp     Double  Temperature in °C (metric)
main.humidity Int     Relative humidity %
wind.speed    Double  Wind speed in m/s (convert ×3.6 for km/h)
pop           Double  Probability of precipitation 0.0–1.0
sys.pod       String  "d" = daytime, "n" = nighttime
```

### 2.3 Severity Levels

Three levels, ordered from most to least critical:

```
HIGH   (0) — act now / dangerous condition
MEDIUM (1) — caution / borderline
LOW    (2) — safe / optimal
```

Overall report severity = worst (lowest ordinal) across all insights.

---

### 2.4 Analyzer 1: Spraying Safety

**Category**: `SPRAYING`
**Logic**: Finds the single worst wind slot across the forecast.

```
wind_kmh = slot.wind.speed × 3.6

Severity per slot:
  HIGH   → wind_kmh > 15.0  (drift risk)
         OR wind_kmh < 2.0   (thermal inversion — spray stays suspended)
  MEDIUM → wind_kmh in [10.0, 15.0]  OR  wind_kmh in [2.0, 3.0]
  LOW    → wind_kmh in (3.0, 10.0)  — optimal spray window

Report = severity of the worst (most critical) slot found.
windowStart/End = that worst slot's dt timestamp.
```

---

### 2.5 Analyzer 2: Chemical Efficacy

**Category**: `CHEMICAL_EFFICACY`
**Logic**: Finds the single hottest slot. High temperatures evaporate pesticide before it acts.

```
Severity per slot:
  HIGH   → temp > 32.0 °C  (rapid evaporation)
  MEDIUM → temp in [28.0, 32.0] °C
  LOW    → temp < 28.0 °C  (optimal efficacy; note: <15°C cold-efficacy not yet modelled)

Report = severity of the hottest slot.
windowStart/End = that slot's dt.
```

---

### 2.6 Analyzer 3: Fungal Risk

**Category**: `FUNGAL_RISK`
**Logic**: Tracks *consecutive* slots above humidity danger threshold.
Ten or more hours of sustained humidity >85% is the industry trigger for blight risk.

```
HUMIDITY_WARNING = 70%
HUMIDITY_DANGER  = 85%

Consecutive slot thresholds (both = ~10 hours):
  FORECAST_5DAY (3h slots) → 4 consecutive slots  (4 × 3h = 12h, covers ≥10h)
  HOURLY_4DAY   (1h slots) → 10 consecutive slots (10 × 1h = 10h)

Algorithm:
  1. Scan slots left to right
  2. Track the longest consecutive run where humidity > 85%
  3. Record the start/end timestamps of that run

Severity:
  HIGH   → longest_run >= consecutive_threshold
  MEDIUM → no sustained run BUT any slot has humidity > 70%
  LOW    → all slots ≤ 70%

windowStart = first dt of the worst consecutive run
windowEnd   = last dt of the worst consecutive run
```

---

### 2.7 Analyzer 4: Field Workability

**Category**: `FIELD_WORKABILITY`
**Logic**: Finds the single worst precipitation-probability slot.

```
Severity per slot:
  HIGH   → pop > 0.70  (soil saturation / muddy fields likely)
  MEDIUM → pop in (0.30, 0.70]  (rain possible — plan for delays)
  LOW    → pop ≤ 0.30  (dry — good for field work)

Report = severity of the worst (highest pop) slot.
formatArgs = [pop_as_percentage_int]  e.g. pop=0.72 → 72
windowStart/End = that slot's dt.
```

---

### 2.8 Analyzer 5: Heat Stress

**Category**: `HEAT_STRESS`
**Logic**: Tracks *consecutive* slots above danger temperature. Sustained heat (≥6h above 38°C) is far more damaging than brief spikes.

```
TEMP_WARNING = 35.0 °C
TEMP_DANGER  = 38.0 °C

Consecutive slot thresholds (both = 6 hours):
  FORECAST_5DAY (3h slots) → 2 consecutive slots  (2 × 3h = 6h)
  HOURLY_4DAY   (1h slots) → 6 consecutive slots  (6 × 1h = 6h)

Algorithm (same pattern as Fungal Risk):
  1. Scan slots
  2. Track longest consecutive run where temp > 38.0 °C
  3. Also track peak temperature across all slots

Severity:
  HIGH   → longest_run >= consecutive_threshold
  MEDIUM → no sustained run BUT any slot has temp >= 35.0 °C
  LOW    → all slots < 35.0 °C

formatArgs = [peak_temp_as_int_celsius]
windowStart/End = start/end of the worst consecutive run (HIGH only)
```

---

### 2.9 Analyzer 6: Harvest Window

**Category**: `HARVEST_WINDOW`
**Logic**: Composite slot scoring to find the best consecutive dry+calm period for harvest. Nighttime slots are always unsuitable.

```
Slot score (per slot):
  Nighttime (sys.pod == "n") → score = 0  (harvesting is a daytime operation)
  Otherwise:
    wind_kmh = slot.wind.speed × 3.6
    score = 2  if pop < 0.30 AND wind_kmh in [3.0, 15.0]   (ideal)
    score = 1  if pop < 0.50 AND wind_kmh in [1.0, 20.0]   (marginal)
    score = 0  otherwise                                     (unsuitable)

Algorithm:
  1. Score all slots
  2. Find the longest consecutive run of score >= 1
  3. Check if the best run is all-ideal (all scores = 2) or mixed

MIN_WINDOW_SLOTS = 2 consecutive suitable slots required

Severity:
  HIGH   → bestRunLen < 2 (no window found at all)
  MEDIUM → window found but includes marginal slots (some score = 1)
  LOW    → window found and all slots are ideal (all score = 2)

formatArgs = [best_pop_percentage_in_window]
windowStart = dt of first slot in best window
windowEnd   = dt of last slot in best window
```

---

### 2.10 Analyzer 7: Spraying Window

**Category**: `SPRAYING_WINDOW`
**Logic**: Four-criteria composite score to find the best spraying opportunity. Nighttime slots always score 0.

```
Slot score (0–4 points, each criterion = 1 point):
  Nighttime (sys.pod == "n") → score = 0 immediately
  Otherwise (daytime):
    +1 if wind_kmh in [3.0, 10.0]   (safe drift range)
    +1 if temp in [15.0, 28.0] °C   (optimal chemical efficacy)
    +1 if humidity < 70%             (below fungal risk threshold)
    +1 if pop < 0.30                 (low wash-off risk)

Algorithm:
  1. Score all slots
  2. Find the longest consecutive run of score >= 2
  3. Within that window, find the single best slot (highest score, then lowest pop)

MIN_WINDOW_SLOTS = 1 slot with score >= 2

Severity:
  HIGH   → no slot scores >= 2 (no suitable window)
  MEDIUM → window found, best slot score = 2 or 3
  LOW    → window found, best slot score = 4 (all four criteria met)

formatArgs = [wind_kmh_of_best_slot, temp_celsius_of_best_slot]
windowStart = dt of first slot in best window
windowEnd   = dt of last slot in best window
```

---

## 3. Recommended Server Architecture

### 3.1 Proposed Service Structure

```
farm-management-weather-intelligence-service
├── Intelligence API          ← Client-facing REST endpoints
├── Forecast Fetcher          ← Calls OpenWeatherMap, caches results
├── Analyzer Engine           ← Runs all 7 analyzers (port of current SDK logic)
├── Insight Cache             ← Redis / in-memory, keyed by (lat, lon, type)
└── Threshold Config          ← DB-backed, hot-reloadable, per-crop overrides
```

### 3.2 Recommended API Endpoints

#### Primary Endpoint — Get Intelligence Report

```
GET /v1/intelligence

Query params:
  lat        Double   required   Latitude
  lon        Double   required   Longitude
  type       String   optional   "forecast_5day" | "hourly_4day"  (default: forecast_5day)
  categories String   optional   Comma-separated filter e.g. "spraying,fungal_risk"
                                 Omit = return all 7 categories
  crop       String   optional   Crop type hint for threshold tuning e.g. "rice", "cotton"
  units      String   optional   "metric" | "imperial"  (default: metric)

Response: 200 OK
{
  "overall_severity": "HIGH" | "MEDIUM" | "LOW",
  "forecast_type": "forecast_5day" | "hourly_4day",
  "generated_at": 1741600000,      // Unix epoch seconds
  "expires_at": 1741610800,        // when client should re-request
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
      "window_start": 1741600000,  // Unix epoch seconds, nullable
      "window_end": 1741600000
    },
    ...
  ]
}
```

#### Secondary Endpoint — Get Raw Forecast (passthrough, with caching)

```
GET /v1/forecast

Query params:
  lat    Double   required
  lon    Double   required
  type   String   optional   "forecast_5day" | "hourly_4day"
  count  Int      optional   Limit number of slots

Response: Mirrors OWM ForecastResponse JSON (already cached server-side)
```

#### Admin Endpoint — Threshold Management

```
GET  /v1/admin/thresholds              List all thresholds
PUT  /v1/admin/thresholds/{analyzer}  Update thresholds for one analyzer
POST /v1/admin/thresholds/reload       Hot-reload without restart
```

---

### 3.3 Response Text (i18n on Server)

Currently the Android SDK uses Android string resources for titles and descriptions. On the server, texts should be returned in full (not as resource IDs) with locale support:

```
GET /v1/intelligence?lat=13.08&lon=80.27&lang=ta    ← Tamil
GET /v1/intelligence?lat=13.08&lon=80.27&lang=hi    ← Hindi
GET /v1/intelligence?lat=13.08&lon=80.27&lang=en    ← English (default)
```

Supported language codes to match current SDK localizations: `en`, `hi`, `gu`, `kn`, `mr`, `ta`.

Text strings per severity per analyzer (21 title + 21 description strings total for the 7 analyzers × 3 severity levels) should live in a server-side i18n bundle, not hardcoded.

---

### 3.4 Caching Strategy

The server should cache at two levels:

#### Level 1 — Raw Forecast Cache (OWM → Server)

```
Key:   forecast:{lat_2dp}:{lon_2dp}:{type}
       e.g. forecast:13.08:80.27:forecast_5day
TTL:   FORECAST_5DAY → 3 hours
       HOURLY_4DAY   → 1 hour
Store: Redis or in-memory
```

Rounding lat/lon to 2 decimal places (~1 km resolution) allows nearby requests to share the same cached forecast, reducing OWM API calls significantly.

#### Level 2 — Intelligence Report Cache (Server → Client)

```
Key:   intelligence:{lat_2dp}:{lon_2dp}:{type}:{categories_hash}:{lang}:{crop}
TTL:   Same as forecast cache TTL for the requested type
Store: Redis
```

The `expires_at` field in the response tells the client exactly when to re-fetch, avoiding unnecessary polling.

---

### 3.5 Threshold Configuration

Move all hardcoded thresholds to a database table so they can be changed without a code deployment:

```sql
CREATE TABLE analyzer_thresholds (
    id            SERIAL PRIMARY KEY,
    analyzer      VARCHAR(50),     -- e.g. "SPRAYING", "FUNGAL_RISK"
    crop          VARCHAR(50),     -- NULL = default for all crops
    param_key     VARCHAR(50),     -- e.g. "wind_danger_kmh", "humidity_danger_pct"
    param_value   DECIMAL(10,3),
    updated_at    TIMESTAMP
);
```

**Default threshold values** (from current SDK):

| Analyzer | Parameter | Default Value |
|---|---|---|
| SPRAYING | wind_high_min_kmh | 15.0 |
| SPRAYING | wind_calm_max_kmh | 2.0 |
| SPRAYING | wind_medium_high_kmh | 10.0 |
| SPRAYING | wind_medium_calm_kmh | 3.0 |
| CHEMICAL_EFFICACY | temp_high_c | 32.0 |
| CHEMICAL_EFFICACY | temp_medium_c | 28.0 |
| FUNGAL_RISK | humidity_danger_pct | 85 |
| FUNGAL_RISK | humidity_warning_pct | 70 |
| FUNGAL_RISK | sustained_hours | 10 |
| HEAT_STRESS | temp_danger_c | 38.0 |
| HEAT_STRESS | temp_warning_c | 35.0 |
| HEAT_STRESS | sustained_hours | 6 |
| FIELD_WORKABILITY | pop_high | 0.70 |
| FIELD_WORKABILITY | pop_medium | 0.30 |
| HARVEST_WINDOW | pop_ideal_max | 0.30 |
| HARVEST_WINDOW | pop_marginal_max | 0.50 |
| HARVEST_WINDOW | wind_ideal_min_kmh | 3.0 |
| HARVEST_WINDOW | wind_ideal_max_kmh | 15.0 |
| HARVEST_WINDOW | wind_marginal_min_kmh | 1.0 |
| HARVEST_WINDOW | wind_marginal_max_kmh | 20.0 |
| HARVEST_WINDOW | min_window_slots | 2 |
| SPRAYING_WINDOW | wind_min_kmh | 3.0 |
| SPRAYING_WINDOW | wind_max_kmh | 10.0 |
| SPRAYING_WINDOW | temp_min_c | 15.0 |
| SPRAYING_WINDOW | temp_max_c | 28.0 |
| SPRAYING_WINDOW | humidity_max_pct | 70 |
| SPRAYING_WINDOW | pop_max | 0.30 |
| SPRAYING_WINDOW | min_score | 2 |

---

### 3.6 Client SDK Changes After Migration

Once the server is live, the Android SDK changes are minimal:

1. Replace local analyzer engine calls with a single API call to `/v1/intelligence`
2. Remove the 7 `*InsightAnalyzer` classes and `AgronomicAnalyzer` from the SDK
3. Keep `AgronomicInsight`, `IntelligenceReport`, `SeverityLevel` as data-only models (for deserializing server response)
4. `WeatherManager.intelligenceState` still auto-updates — just driven by a network response instead of local computation
5. `AgronomicAnalyzer.Builder` can be removed; the `categories` query param replaces it

---

## 4. Improvements Possible on Server (not feasible client-side)

### 4.1 Crop-Specific Thresholds
Different crops have different tolerances. Rice can tolerate higher humidity before fungal risk triggers compared to grape vines. The server can accept a `crop` parameter and apply crop-tuned thresholds from the threshold config table.

### 4.2 Regional Threshold Tuning
Temperature danger thresholds appropriate for crops in Tamil Nadu may differ from Maharashtra. Thresholds can be varied by geobounded region.

### 4.3 Historical Insight Logging
Store every generated report in a time-series table. This enables:
- "Was last week good for spraying?" queries
- Trend dashboards showing how often HIGH risk weeks occur per season
- Alerting when a region transitions from LOW → HIGH over multiple days

### 4.4 Push Notifications / Webhooks
Server can proactively detect when severity changes for a subscribed field location and push an alert to the client — without the client needing to poll.

### 4.5 Composite Multi-Day Summaries
Current logic looks at individual slots. Server can generate a daily summary (e.g. "best 3-hour window per day this week") as an additional response field.

### 4.6 Confidence Scoring
OWM returns a `temp_kf` confidence parameter per slot. Server can weight insights by forecast confidence and downgrade severity of slots with low confidence scores.

---

## 5. Suggested Tech Stack

| Component | Recommendation | Rationale |
|---|---|---|
| API layer | **Ktor** (Kotlin) or **FastAPI** (Python) | Ktor shares model classes with Android SDK; FastAPI is faster to prototype |
| Forecast cache | **Redis** with TTL | Simple, fast, cluster-ready |
| Threshold store | **PostgreSQL** | Structured, auditable, supports per-crop rows |
| i18n | **ICU message format** files per locale | Industry standard, handles plurals/format args |
| OWM calls | Single shared HTTP client with retry + circuit breaker | Avoid thundering herd on cache miss |

---

## 6. Migration Approach (Phased)

### Phase 1 — Server mirrors client (zero behavior change)
- Port all 7 analyzers to server exactly as documented in Section 2
- Expose `/v1/intelligence` returning same structure
- SDK calls server but still has local fallback if server is unreachable
- Run both in parallel, compare outputs to validate

### Phase 2 — Server is source of truth
- Remove local analyzer code from SDK
- Server returns full localized text (not string resource IDs)
- Enable threshold config DB

### Phase 3 — Enhancements
- Crop-specific thresholds
- Historical logging
- Push alerts / webhooks
- Daily summary field in response

---

*Document prepared from source analysis of the FarmManagementWeatherIntelligence Android SDK intelligence layer.*
