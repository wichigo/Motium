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
     * Calculate mileage cost based on distance and vehicle using PROGRESSIVE brackets.
     * Uses the French barème kilométrique 2024 with proper bracket transitions.
     *
     * @param distanceKm Distance of the new trip
     * @param vehicle Vehicle with annual mileage info
     * @param tripType Type of trip (PRO or PERSO) to use correct annual counter
     * @return Cost calculated using progressive bracket system
     */
    fun calculateMileageCost(
        distanceKm: Double,
        vehicle: Vehicle,
        tripType: TripType = TripType.PROFESSIONAL
    ): Double {
        // Get the correct annual mileage based on trip type
        val previousAnnualKm = when (tripType) {
            TripType.PROFESSIONAL -> vehicle.totalMileagePro
            TripType.PERSONAL -> vehicle.totalMileagePerso
        }

        return MileageAllowanceCalculator.calculateTripCost(
            vehicleType = vehicle.type,
            power = vehicle.power,
            previousAnnualKm = previousAnnualKm,
            tripDistanceKm = distanceKm
        )
    }

    /**
     * Calculate mileage rate for a vehicle based on type and power.
     * Returns the first bracket rate (0-5000 km for cars).
     */
    fun calculateMileageRate(vehicleType: VehicleType, power: VehiclePower?): Double {
        return when (vehicleType) {
            VehicleType.CAR -> {
                val rates = MileageAllowanceCalculator.getRatesForPower(power ?: VehiclePower.CV_5)
                rates.bracket1Rate
            }
            VehicleType.MOTORCYCLE -> Constants.MileageRates.MOTORCYCLE_RATE
            VehicleType.SCOOTER -> Constants.MileageRates.SCOOTER_RATE
            VehicleType.BIKE -> Constants.MileageRates.BIKE_RATE
        }
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