package com.application.motium.utils

object Constants {
    // Database
    const val DATABASE_NAME = "motium_database"

    // Trip Detection
    const val MIN_SPEED_START_TRIP_KMH = 5.0
    const val MIN_SPEED_STOP_TRIP_KMH = 2.0
    const val MIN_DURATION_START_TRIP_MS = 2 * 60 * 1000L // 2 minutes
    const val MIN_DURATION_STOP_TRIP_MS = 4 * 60 * 1000L // 4 minutes

    // GPS Tracking
    const val GPS_UPDATE_INTERVAL_MS = 5000L // 5 seconds
    const val GPS_FASTEST_UPDATE_INTERVAL_MS = 2000L // 2 seconds
    const val GPS_MIN_DISTANCE_METERS = 10f
    const val GPS_ACCURACY_THRESHOLD_METERS = 20f

    // Service
    const val LOCATION_SERVICE_NOTIFICATION_ID = 1001
    const val LOCATION_SERVICE_CHANNEL_ID = "trip_tracking_channel"
    const val LOCATION_SERVICE_CHANNEL_NAME = "Trip Tracking"

    // Trip Types
    const val TRIP_TYPE_PROFESSIONAL = "PRO"
    const val TRIP_TYPE_PERSONAL = "PRIVATE"

    // Vehicle Types
    const val VEHICLE_TYPE_CAR = "CAR"
    const val VEHICLE_TYPE_MOTORCYCLE = "MOTORCYCLE"
    const val VEHICLE_TYPE_SCOOTER = "SCOOTER"
    const val VEHICLE_TYPE_BIKE = "BIKE"

    /**
     * Mileage Rates (in EUR per km) - Barème kilométrique 2024
     *
     * Ces taux correspondent à la PREMIÈRE TRANCHE (0-5000 km pour voitures).
     * Pour les calculs complets avec tranches progressives, utiliser MileageAllowanceCalculator.
     */
    object MileageRates {
        // Car rates by power (CV) - Tranche 1 (0-5000 km)
        const val CAR_3CV_RATE = 0.529
        const val CAR_4CV_RATE = 0.606
        const val CAR_5CV_RATE = 0.636
        const val CAR_6CV_RATE = 0.665
        const val CAR_7CV_PLUS_RATE = 0.697

        // Two-wheeler rates - Tranche 1 (0-3000 km)
        const val MOTORCYCLE_RATE = 0.395
        const val SCOOTER_RATE = 0.315
        const val BIKE_RATE = 0.25
    }

    // Private Server APIs (self-hosted via domain)
    object PrivateServer {
        // Nominatim geocoding (search + reverse)
        const val NOMINATIM_BASE_URL = "https://nominatim.motium.app"
        const val NOMINATIM_SEARCH_URL = "$NOMINATIM_BASE_URL/search"
        const val NOMINATIM_REVERSE_URL = "$NOMINATIM_BASE_URL/reverse"
        const val NOMINATIM_COUNTRY_CODES = "fr,de,it,es,ch,be,lu"

        // OSRM routing (route + match)
        const val OSRM_BASE_URL = "https://osrm.motium.app"
        const val OSRM_ROUTE_URL = "$OSRM_BASE_URL/route/v1/driving"
        const val OSRM_MATCH_URL = "$OSRM_BASE_URL/match/v1/driving"

        // PMTiles tile server (MVT vector tiles)
        const val TILES_BASE_URL = "https://tiles.motium.app"
        const val TILES_URL_PATTERN = "$TILES_BASE_URL/europe-ouest/{z}/{x}/{y}.mvt"

        // MapLibre style configuration
        // Remote style hosted on private tile server
        const val MAPLIBRE_STYLE_PRIVATE = "https://mapstyle.motium.app/style.json"
        // Demo tiles (for testing)
        const val MAPLIBRE_STYLE_DEMO = "https://demotiles.maplibre.org/style.json"
        // OSM raster tiles style
        const val MAPLIBRE_STYLE_OSM_RASTER = "asset://osm_raster_style.json"

        // Use private tiles (true) or fallback (false)
        const val USE_PRIVATE_TILES = true
        const val MAPLIBRE_STYLE_FALLBACK = MAPLIBRE_STYLE_DEMO
    }

    const val NOMINATIM_USER_AGENT = "Motium/1.0"

    // Subscription pricing
    const val PREMIUM_PLAN_PRICE_EUR = 5
    const val LIFETIME_PLAN_PRICE_EUR = 150

    // Export
    const val EXPORT_DATE_FORMAT = "dd/MM/yyyy"
    const val EXPORT_TIME_FORMAT = "HH:mm"
    const val EXPORT_FILENAME_DATE_FORMAT = "yyyyMMdd"
}
