package com.application.motium.utils

import com.application.motium.domain.model.*
import kotlinx.datetime.Instant

object TripCalculator {

    /**
     * Calculate total distance from a trace of GPS points
     */
    fun calculateTotalDistanceKm(tracePoints: List<LocationPoint>): Double {
        if (tracePoints.size < 2) return 0.0

        var totalDistance = 0.0
        for (i in 1 until tracePoints.size) {
            val prevPoint = tracePoints[i - 1]
            val currentPoint = tracePoints[i]
            totalDistance += LocationUtils.calculateDistanceKm(prevPoint, currentPoint)
        }

        return totalDistance
    }

    /**
     * Calculate trip duration in milliseconds
     */
    fun calculateDurationMs(startTime: Instant, endTime: Instant?): Long {
        return if (endTime != null) {
            (endTime - startTime).inWholeMilliseconds
        } else {
            0L
        }
    }

    /**
     * Calculate mileage cost based on distance and vehicle
     */
    fun calculateMileageCost(distanceKm: Double, vehicle: Vehicle): Double {
        return distanceKm * vehicle.mileageRate
    }

    /**
     * Calculate mileage rate for a vehicle based on type and power
     */
    fun calculateMileageRate(vehicleType: VehicleType, power: VehiclePower?): Double {
        return when (vehicleType) {
            VehicleType.CAR -> power?.rate ?: Constants.MileageRates.CAR_5CV_RATE
            VehicleType.MOTORCYCLE -> Constants.MileageRates.MOTORCYCLE_RATE
            VehicleType.SCOOTER -> Constants.MileageRates.SCOOTER_RATE
            VehicleType.BIKE -> Constants.MileageRates.BIKE_RATE
        }
    }

    /**
     * Get mileage rate directly from vehicle power
     */
    fun getMileageRateForPower(power: VehiclePower): Double {
        return when (power) {
            VehiclePower.CV_3 -> Constants.MileageRates.CAR_3CV_RATE
            VehiclePower.CV_4 -> Constants.MileageRates.CAR_4CV_RATE
            VehiclePower.CV_5 -> Constants.MileageRates.CAR_5CV_RATE
            VehiclePower.CV_6 -> Constants.MileageRates.CAR_6CV_RATE
            VehiclePower.CV_7_PLUS -> Constants.MileageRates.CAR_7CV_PLUS_RATE
        }
    }

    /**
     * Estimate trip type based on time and working hours
     */
    fun estimateTripType(startTime: Instant, workingHours: WorkingHours?): TripType {
        if (workingHours == null) return TripType.PERSONAL

        // This is a simplified implementation
        // In a real app, you'd want to check the actual day of week and time
        return TripType.PERSONAL // Default for now
    }

    /**
     * Calculate summary statistics for a list of trips
     */
    fun calculateTripSummary(trips: List<Trip>): TripSummary {
        val totalTrips = trips.size
        val totalDistanceKm = trips.sumOf { it.distanceKm }
        val totalCost = trips.sumOf { it.cost }
        val totalDurationMs = trips.sumOf { it.durationMs }

        val professionalTrips = trips.filter { it.type == TripType.PROFESSIONAL }
        val personalTrips = trips.filter { it.type == TripType.PERSONAL }

        return TripSummary(
            totalTrips = totalTrips,
            totalDistanceKm = totalDistanceKm,
            totalCost = totalCost,
            totalDurationMs = totalDurationMs,
            professionalTrips = professionalTrips.size,
            personalTrips = personalTrips.size,
            professionalDistanceKm = professionalTrips.sumOf { it.distanceKm },
            personalDistanceKm = personalTrips.sumOf { it.distanceKm },
            professionalCost = professionalTrips.sumOf { it.cost },
            personalCost = personalTrips.sumOf { it.cost },
            averageDistanceKm = if (totalTrips > 0) totalDistanceKm / totalTrips else 0.0,
            averageDurationMs = if (totalTrips > 0) totalDurationMs / totalTrips else 0L
        )
    }

    /**
     * Check if user has reached their monthly trip limit
     */
    fun hasReachedTripLimit(user: User, monthlyTripCount: Int): Boolean {
        return when (user.subscription.type) {
            SubscriptionType.FREE -> monthlyTripCount >= Constants.FREE_PLAN_TRIP_LIMIT
            SubscriptionType.PREMIUM, SubscriptionType.LIFETIME -> false
        }
    }

    /**
     * Calculate average speed from distance and duration
     */
    fun calculateAverageSpeedKmh(distanceKm: Double, durationMs: Long): Double {
        if (durationMs <= 0) return 0.0
        val durationHours = durationMs / (1000.0 * 60.0 * 60.0)
        return distanceKm / durationHours
    }
}

data class TripSummary(
    val totalTrips: Int,
    val totalDistanceKm: Double,
    val totalCost: Double,
    val totalDurationMs: Long,
    val professionalTrips: Int,
    val personalTrips: Int,
    val professionalDistanceKm: Double,
    val personalDistanceKm: Double,
    val professionalCost: Double,
    val personalCost: Double,
    val averageDistanceKm: Double,
    val averageDurationMs: Long
)