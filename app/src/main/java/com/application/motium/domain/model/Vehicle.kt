package com.application.motium.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Transient

data class Vehicle(
    val id: String,
    val userId: String,
    val name: String,
    val type: VehicleType,
    val licensePlate: String?,
    val power: VehiclePower?,
    val fuelType: FuelType?,
    val mileageRate: Double,
    val isDefault: Boolean = false,
    @Transient val totalMileagePerso: Double = 0.0,
    @Transient val totalMileagePro: Double = 0.0,
    val createdAt: Instant,
    val updatedAt: Instant
)

enum class VehicleType(val displayName: String) {
    CAR("Voiture"),
    MOTORCYCLE("Moto"),
    SCOOTER("Scooter"),
    BIKE("Vélo")
}

/**
 * Puissance fiscale des véhicules - Barème kilométrique 2024
 *
 * Le taux (rate) correspond à la première tranche (0-5000 km).
 * Pour les tranches suivantes, utiliser MileageAllowanceCalculator.
 */
enum class VehiclePower(val displayName: String, val cv: String, val rate: Double) {
    CV_3("3 CV et moins", "3CV", 0.529),      // Barème 2024 tranche 1
    CV_4("4 CV", "4CV", 0.606),               // Barème 2024 tranche 1
    CV_5("5 CV", "5CV", 0.636),               // Barème 2024 tranche 1
    CV_6("6 CV", "6CV", 0.665),               // Barème 2024 tranche 1
    CV_7_PLUS("7 CV et plus", "7CV+", 0.697)  // Barème 2024 tranche 1
}

enum class FuelType(val displayName: String) {
    GASOLINE("Essence"),
    DIESEL("Diesel"),
    ELECTRIC("Électrique"),
    HYBRID("Hybride"),
    OTHER("Autre")
}