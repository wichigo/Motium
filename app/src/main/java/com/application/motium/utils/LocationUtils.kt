package com.application.motium.utils

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
}