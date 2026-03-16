# FarmManagementWeatherIntelligence — API Reference

**Base URL**: `https://<your-appsail-url>`
**Version**: v1
**Auth**: All endpoints require the header `X-API-Key: <your key>`

---

## Authentication

Every request must include:

```
X-API-Key: <WI_SERVICE_KEY value>
```

Missing or wrong key returns:

```json
HTTP 401
{
  "error": "unauthorized",
  "message": "Invalid or missing X-API-Key header"
}
```

---

## Endpoints

### 1. GET /v1/current

Returns the current (real-time) weather conditions for a location.

#### Query Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `lat` | Double | Yes | Latitude (e.g. `13.08`) |
| `lon` | Double | Yes | Longitude (e.g. `80.27`) |

#### Example Request

```
GET /v1/current?lat=13.08&lon=80.27
X-API-Key: your-key-here
```

#### Response — 200 OK

```json
{
  "dt": 1741600000,
  "location": {
    "lat": 13.08,
    "lon": 80.27,
    "city": "Chennai",
    "country": "IN"
  },
  "condition": "Rain",
  "description": "moderate rain",
  "icon_url": "https://openweathermap.org/img/wn/10d@2x.png",
  "temp": 29.5,
  "humidity": 72,
  "wind_speed_kmh": 13.0,
  "visibility": 6000,
  "sunrise": 1741564800,
  "sunset": 1741608000
}
```

#### Response Fields

| Field | Type | Description |
|---|---|---|
| `dt` | Long | Unix timestamp (seconds) of the observation |
| `location` | Object | City, country, and coordinates |
| `condition` | String | OWM condition group (e.g. `Rain`, `Clear`, `Clouds`) |
| `description` | String | Detailed condition description (e.g. `moderate rain`) |
| `icon_url` | String | 100×100px OWM icon URL for the current condition |
| `temp` | Double | Temperature in °C |
| `humidity` | Int | Relative humidity % |
| `wind_speed_kmh` | Double | Wind speed in km/h |
| `visibility` | Int | Visibility in metres |
| `sunrise` | Long | Unix timestamp of sunrise |
| `sunset` | Long | Unix timestamp of sunset |

---

### 2. GET /v1/intelligence

Returns agronomic insights for a location by running all 7 weather analyzers against the forecast.

#### Query Parameters

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `lat` | Double | Yes | — | Latitude (e.g. `13.08`) |
| `lon` | Double | Yes | — | Longitude (e.g. `80.27`) |
| `type` | String | No | `forecast_5day` | `forecast_5day` (free, 40 slots × 3h) or `hourly_4day` (paid, 96 slots × 1h) |
| `lang` | String | No | `en` | Response language: `en`, `hi`, `gu`, `kn`, `mr`, `ta`, `te`, `pa`, `bn`, `ml`. Falls back to `en` for unsupported codes. |
| `categories` | String | No | all | Comma-separated filter. e.g. `spraying,fungal_risk`. Omit to get all 7. |

#### Category values

`SPRAYING`, `CHEMICAL_EFFICACY`, `FUNGAL_RISK`, `FIELD_WORKABILITY`, `HEAT_STRESS`, `HARVEST_WINDOW`, `SPRAYING_WINDOW`

#### Example Request

```
GET /v1/intelligence?lat=13.08&lon=80.27&lang=ta&categories=spraying,fungal_risk
X-API-Key: your-key-here
```

#### Response — 200 OK

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
  "forecast": [
    {
      "date": "2026-03-16",
      "overall_severity": "HIGH",
      "insights": [
        {
          "category": "SPRAYING",
          "severity": "HIGH",
          "title": "Do Not Spray — Unsafe Wind",
          "description": "Wind speed is outside the safe spraying range...",
          "format_args": [],
          "window_start": 1741600000,
          "window_end": 1741600000,
          "icon_url": "https://openweathermap.org/img/wn/50d@2x.png"
        },
        {
          "category": "FUNGAL_RISK",
          "severity": "MEDIUM",
          "title": "Elevated Fungal Risk — Monitor Closely",
          "description": "Humidity has risen above 70%...",
          "format_args": [],
          "window_start": null,
          "window_end": null,
          "icon_url": "https://openweathermap.org/img/wn/10d@2x.png"
        }
      ]
    },
    {
      "date": "2026-03-17",
      "overall_severity": "LOW",
      "insights": [...]
    }
  ]
}
```

#### Response Fields

| Field | Type | Description |
|---|---|---|
| `overall_severity` | String | Worst severity across all days and all categories |
| `forecast_type` | String | Which OWM endpoint was used |
| `generated_at` | Long | Unix timestamp (seconds) when the report was generated |
| `expires_at` | Long | Unix timestamp (seconds) when the client should re-fetch |
| `location.city` | String | City name from OWM |
| `location.country` | String | ISO 3166-1 alpha-2 country code |
| `forecast[].date` | String | Calendar date in `YYYY-MM-DD` format (UTC) |
| `forecast[].overall_severity` | String | Worst severity across all insights for that day |
| `forecast[].insights[].category` | String | One of the 7 analyzer categories |
| `forecast[].insights[].severity` | String | `HIGH`, `MEDIUM`, or `LOW` |
| `forecast[].insights[].title` | String | Localised short title |
| `forecast[].insights[].description` | String | Localised description, with format args already applied |
| `forecast[].insights[].format_args` | String[] | Raw values used in the description (e.g. wind speed, temperature) |
| `forecast[].insights[].window_start` | Long? | Unix timestamp of the relevant slot within that day, nullable |
| `forecast[].insights[].window_end` | Long? | Unix timestamp of the end of the relevant window, nullable |
| `forecast[].insights[].icon_url` | String? | 100×100px OWM icon URL for the weather condition of the relevant slot, nullable |

#### Severity semantics per analyzer

| Category | HIGH | MEDIUM | LOW |
|---|---|---|---|
| `SPRAYING` | Wind > 15 km/h or < 2 km/h | Wind 10–15 or 2–3 km/h | Wind 3–10 km/h |
| `CHEMICAL_EFFICACY` | Temp > 32 °C | Temp 28–32 °C | Temp < 28 °C |
| `FUNGAL_RISK` | ≥ 10h consecutive humidity > 85% | Any slot > 70% | All slots ≤ 70% |
| `FIELD_WORKABILITY` | PoP > 70% | PoP 30–70% | PoP ≤ 30% |
| `HEAT_STRESS` | ≥ 6h consecutive temp > 38 °C | Any slot ≥ 35 °C | All slots < 35 °C |
| `HARVEST_WINDOW` | No 2-slot window found | Window found but marginal | Window found, all ideal |
| `SPRAYING_WINDOW` | No slot scores ≥ 2/4 | Best slot scores 2–3/4 | Best slot scores 4/4 |

---

### 2. GET /v1/forecast

Returns raw OWM forecast data. Responses are cached server-side (3h for `forecast_5day`, 1h for `hourly_4day`).

#### Query Parameters

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `lat` | Double | Yes | — | Latitude |
| `lon` | Double | Yes | — | Longitude |
| `type` | String | No | `forecast_5day` | `forecast_5day` or `hourly_4day` |
| `count` | Int | No | all | Limit number of forecast slots returned |

#### Example Request

```
GET /v1/forecast?lat=13.08&lon=80.27&count=8
X-API-Key: your-key-here
```

#### Response — 200 OK

Mirrors the OpenWeatherMap forecast response structure:

```json
{
  "cod": "200",
  "cnt": 8,
  "list": [
    {
      "dt": 1741600000,
      "dt_txt": "2025-03-10 12:00:00",
      "main": {
        "temp": 31.5,
        "humidity": 72
      },
      "wind": {
        "speed": 3.2
      },
      "pop": 0.12,
      "sys": {
        "pod": "d"
      }
    }
  ],
  "city": {
    "id": 1264527,
    "name": "Chennai",
    "coord": { "lat": 13.08, "lon": 80.27 },
    "country": "IN"
  }
}
```

> **Note**: `wind.speed` is in m/s as returned by OWM. Multiply by 3.6 for km/h.

---

### 3. GET /v1/admin/thresholds

Returns the current threshold values used by all 7 analyzers.

#### Example Request

```
GET /v1/admin/thresholds
X-API-Key: your-key-here
```

#### Response — 200 OK

```json
{
  "SPRAYING": {
    "wind_high_min_kmh": 15.0,
    "wind_calm_max_kmh": 2.0,
    "wind_medium_high_kmh": 10.0,
    "wind_medium_calm_kmh": 3.0
  },
  "CHEMICAL_EFFICACY": {
    "temp_high_c": 32.0,
    "temp_medium_c": 28.0
  },
  "FUNGAL_RISK": {
    "humidity_danger_pct": 85,
    "humidity_warning_pct": 70,
    "sustained_hours": 10
  },
  "HEAT_STRESS": {
    "temp_danger_c": 38.0,
    "temp_warning_c": 35.0,
    "sustained_hours": 6
  },
  "FIELD_WORKABILITY": {
    "pop_high": 0.7,
    "pop_medium": 0.3
  },
  "HARVEST_WINDOW": {
    "pop_ideal_max": 0.3,
    "pop_marginal_max": 0.5,
    "wind_ideal_min_kmh": 3.0,
    "wind_ideal_max_kmh": 15.0,
    "wind_marginal_min_kmh": 1.0,
    "wind_marginal_max_kmh": 20.0,
    "min_window_slots": 2
  },
  "SPRAYING_WINDOW": {
    "wind_min_kmh": 3.0,
    "wind_max_kmh": 10.0,
    "temp_min_c": 15.0,
    "temp_max_c": 28.0,
    "humidity_max_pct": 70,
    "pop_max": 0.3,
    "min_score": 2
  }
}
```

---

## Error Responses

All errors follow the same shape:

```json
{
  "error": "<error_code>",
  "message": "<human readable message>"
}
```

| HTTP Status | `error` value | When |
|---|---|---|
| `400` | `bad_request` | Missing or invalid `lat`/`lon` |
| `401` | `unauthorized` | Missing or wrong `X-API-Key` |
| `500` | `internal_error` | OWM unreachable or unexpected server error |

---

## Caching

The server caches OWM responses to reduce API calls:

| Forecast type | Cache TTL | Key resolution |
|---|---|---|
| `forecast_5day` | 3 hours | lat/lon rounded to 2 decimal places |
| `hourly_4day` | 1 hour | lat/lon rounded to 2 decimal places |

The `expires_at` field in the `/v1/intelligence` response tells the client exactly when the cache expires — use it to schedule re-fetches instead of polling.

---

## Client Integration Notes

### Day 1 may be a partial day

OWM forecasts start from the next available 3-hour slot, not from midnight. If the request is made at 14:00 UTC, day 1 will only contain slots for 15:00, 18:00, and 21:00 — not a full 8-slot day. Clients should handle this gracefully:

- Use `window_start` timestamps to display actual slot times rather than assuming uniform coverage
- Label day 1 as "Today" on the client rather than implying a full-day analysis

### Use `overall_severity` per day for UI colour coding

Each `forecast[].overall_severity` reflects the worst insight for that day. This is ideal for colouring a day row or badge:

| Value | Suggested UI treatment |
|---|---|
| `HIGH` | Red — alert the user, action required |
| `MEDIUM` | Amber — caution, monitor conditions |
| `LOW` | Green — safe to proceed |

The top-level `overall_severity` gives the single worst reading across the entire forecast period — useful for a summary header or push notification trigger.

### Use `window_start` / `window_end` to show best time slots

`window_start` and `window_end` are Unix timestamps (seconds, UTC) pinpointing the specific slot that triggered the insight within that day. Convert these to local time on the client to show actionable guidance such as:

- "Best time to spray: 6 AM – 9 AM"
- "Heat stress expected: 1 PM – 4 PM"

These fields are `null` for insights where no specific slot applies (e.g. FUNGAL_RISK MEDIUM — elevated humidity but no sustained run).

### Use `expires_at` to schedule re-fetches

Do not poll on a fixed interval. Instead, store `expires_at` and refresh only when that timestamp is reached:

```
expires_at = response.expires_at          // Unix seconds
refresh_at = expires_at                   // re-fetch at this time
```

This aligns the client refresh cycle with the server cache TTL (3h for `forecast_5day`, 1h for `hourly_4day`), avoiding unnecessary API calls.
