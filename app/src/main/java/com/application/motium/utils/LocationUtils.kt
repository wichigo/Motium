package com.application.motium.utils

import android.location.Location
import com.application.motium.domain.model.LocationPoint
import kotlin.math.*

object LocationUtils {

    /**
     * Calculate distance between two points using Haversine formula
     * @param lat1 Latitude of first point
     * @param lon1 Longitude of first point
     * @param lat2 Latitude of second point
     * @param lon2 Longitude of second point
     * @return Distance in kilometers
     */
    fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val a = sin(dLat / 2).pow(2.0) + sin(dLon / 2).pow(2.0) * cos(lat1Rad) * cos(lat2Rad)
        val c = 2 * asin(sqrt(a))

        return earthRadiusKm * c
    }

    /**
     * Calculate distance between two LocationPoint objects
     */
    fun calculateDistanceKm(point1: LocationPoint, point2: LocationPoint): Double {
        return calculateDistanceKm(
            point1.latitude, point1.longitude,
            point2.latitude, point2.longitude
        )
    }

    /**
     * Calculate distance between two Android Location objects
     */
    fun calculateDistanceKm(location1: Location, location2: Location): Double {
        return calculateDistanceKm(
            location1.latitude, location1.longitude,
            location2.latitude, location2.longitude
        )
    }

    /**
     * Calculate speed in km/h from two location points
     */
    fun calculateSpeedKmh(point1: LocationPoint, point2: LocationPoint): Double {
        val distanceKm = calculateDistanceKm(point1, point2)
        val timeDiffMs = (point2.timestamp - point1.timestamp).inWholeMilliseconds
        val timeDiffHours = timeDiffMs / (1000.0 * 60.0 * 60.0)

        return if (timeDiffHours > 0) distanceKm / timeDiffHours else 0.0
    }

    /**
     * Check if a location point is accurate enough for tracking
     */
    fun isAccurateEnough(location: Location, thresholdMeters: Float = Constants.GPS_ACCURACY_THRESHOLD_METERS): Boolean {
        return location.hasAccuracy() && location.accuracy <= thresholdMeters
    }

    /**
     * Check if a location point seems valid (not obviously wrong)
     */
    fun isValidLocation(location: Location): Boolean {
        return location.latitude != 0.0 &&
               location.longitude != 0.0 &&
               location.latitude >= -90.0 &&
               location.latitude <= 90.0 &&
               location.longitude >= -180.0 &&
               location.longitude <= 180.0
    }

    /**
     * Filter out invalid or inaccurate location points from a trace
     */
    fun filterValidPoints(points: List<LocationPoint>, accuracyThresholdM: Float = Constants.GPS_ACCURACY_THRESHOLD_METERS): List<LocationPoint> {
        return points.filter { point ->
            // Basic coordinate validation
            point.latitude != 0.0 &&
            point.longitude != 0.0 &&
            point.latitude >= -90.0 &&
            point.latitude <= 90.0 &&
            point.longitude >= -180.0 &&
            point.longitude <= 180.0 &&
            // Accuracy check if available
            (point.accuracy == null || point.accuracy <= accuracyThresholdM)
        }
    }

    /**
     * Reduce the number of points in a trace by removing points that are too close together
     */
    fun simplifyTrace(points: List<LocationPoint>, minDistanceMeters: Double = 10.0): List<LocationPoint> {
        if (points.isEmpty()) return emptyList()

        val simplified = mutableListOf<LocationPoint>()
        simplified.add(points.first())

        for (i in 1 until points.size) {
            val lastPoint = simplified.last()
            val currentPoint = points[i]
            val distanceKm = calculateDistanceKm(lastPoint, currentPoint)
            val distanceMeters = distanceKm * 1000

            if (distanceMeters >= minDistanceMeters) {
                simplified.add(currentPoint)
            }
        }

        // Always include the last point if it's different from the last added point
        if (points.last() != simplified.last()) {
            simplified.add(points.last())
        }

        return simplified
    }

    /**
     * Check if the user appears to be moving based on speed
     */
    fun isMoving(speedKmh: Double, threshold: Double = Constants.MIN_SPEED_START_TRIP_KMH): Boolean {
        return speedKmh >= threshold
    }

    /**
     * Check if the user appears to be stationary based on speed
     */
    fun isStationary(speedKmh: Double, threshold: Double = Constants.MIN_SPEED_STOP_TRIP_KMH): Boolean {
        return speedKmh <= threshold
    }
}