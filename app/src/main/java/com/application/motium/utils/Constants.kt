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

    // Mileage Rates (in EUR per km)
    object MileageRates {
        // Car rates by power (CV)
        const val CAR_3CV_RATE = 0.537
        const val CAR_4CV_RATE = 0.603
        const val CAR_5CV_RATE = 0.631
        const val CAR_6CV_RATE = 0.661
        const val CAR_7CV_PLUS_RATE = 0.685

        // Two-wheeler rates
        const val MOTORCYCLE_RATE = 0.395
        const val SCOOTER_RATE = 0.315
        const val BIKE_RATE = 0.25
    }

    // Nominatim API
    const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org/"
    const val NOMINATIM_USER_AGENT = "Motium/1.0"

    // Subscription limits
    const val FREE_PLAN_TRIP_LIMIT = 10
    const val PREMIUM_PLAN_PRICE_EUR = 5
    const val LIFETIME_PLAN_PRICE_EUR = 150

    // Export
    const val EXPORT_DATE_FORMAT = "dd/MM/yyyy"
    const val EXPORT_TIME_FORMAT = "HH:mm"
    const val EXPORT_FILENAME_DATE_FORMAT = "yyyyMMdd"
}