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
     * Legacy method - Calculate mileage cost using fixed rate (deprecated).
     * Kept for backward compatibility.
     */
    @Deprecated("Use calculateMileageCost with tripType parameter for progressive brackets")
    fun calculateMileageCostSimple(distanceKm: Double, vehicle: Vehicle): Double {
        return distanceKm * vehicle.mileageRate
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
     * Get the current effective rate for a vehicle based on annual mileage.
     * This rate changes depending on which bracket the vehicle is in.
     *
     * @param vehicle Vehicle with power and annual mileage info
     * @param tripType Type of trip to determine which counter to use
     * @return Current effective rate €/km
     */
    fun getCurrentEffectiveRate(vehicle: Vehicle, tripType: TripType): Double {
        val annualKm = when (tripType) {
            TripType.PROFESSIONAL -> vehicle.totalMileagePro
            TripType.PERSONAL -> vehicle.totalMileagePerso
        }
        return MileageAllowanceCalculator.getEffectiveRate(vehicle.type, vehicle.power, annualKm)
    }

    /**
     * Get bracket info for display purposes.
     */
    fun getBracketInfo(vehicle: Vehicle, tripType: TripType): BracketInfo {
        val annualKm = when (tripType) {
            TripType.PROFESSIONAL -> vehicle.totalMileagePro
            TripType.PERSONAL -> vehicle.totalMileagePerso
        }
        return MileageAllowanceCalculator.getCurrentBracketInfo(vehicle.type, vehicle.power, annualKm)
    }

    /**
     * Calculate total annual allowance for a vehicle.
     */
    fun calculateAnnualAllowance(vehicle: Vehicle, tripType: TripType): Double {
        val annualKm = when (tripType) {
            TripType.PROFESSIONAL -> vehicle.totalMileagePro
            TripType.PERSONAL -> vehicle.totalMileagePerso
        }
        return MileageAllowanceCalculator.calculateAnnualAllowance(vehicle.type, vehicle.power, annualKm)
    }

    /**
     * Get mileage rate directly from vehicle power (first bracket rate).
     */
    fun getMileageRateForPower(power: VehiclePower): Double {
        return MileageAllowanceCalculator.getRatesForPower(power).bracket1Rate
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
     * Check if user has valid access (trial active or subscribed)
     * Returns true if user CANNOT create trips (no valid access)
     *
     * ⚠️ SECURITY WARNING: This method uses System.currentTimeMillis() which can be
     * manipulated. Only use this for UI display purposes.
     * For security-critical checks, use [hasNoValidAccessSecure] with TrustedTimeProvider.
     */
    fun hasNoValidAccess(user: User): Boolean {
        return !user.subscription.hasValidAccess()
    }

    /**
     * SECURE version: Check if user has NO valid access using trusted time.
     * Returns true if user CANNOT create trips (fail-secure: also returns true if time is untrusted)
     * @param user The user to check
     * @param trustedTimeMs Trusted time from TrustedTimeProvider.getTrustedTimeMs()
     * @return true if user has NO valid access or time is not trusted
     */
    fun hasNoValidAccessSecure(user: User, trustedTimeMs: Long?): Boolean {
        return !user.subscription.hasValidAccessSecure(trustedTimeMs)
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