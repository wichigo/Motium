package com.application.motium.domain.model

import kotlinx.datetime.Instant

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
    val totalMileagePerso: Double = 0.0,
    val totalMileagePro: Double = 0.0,
    val createdAt: Instant,
    val updatedAt: Instant
)

enum class VehicleType(val displayName: String) {
    CAR("Voiture"),
    MOTORCYCLE("Moto"),
    SCOOTER("Scooter"),
    BIKE("Vélo")
}

enum class VehiclePower(val displayName: String, val cv: String, val rate: Double) {
    CV_3("3 CV et moins", "3CV", 0.537),
    CV_4("4 CV", "4CV", 0.603),
    CV_5("5 CV", "5CV", 0.631),
    CV_6("6 CV", "6CV", 0.661),
    CV_7_PLUS("7 CV et plus", "7CV+", 0.685)
}

enum class FuelType(val displayName: String) {
    GASOLINE("Essence"),
    DIESEL("Diesel"),
    ELECTRIC("Électrique"),
    HYBRID("Hybride"),
    OTHER("Autre")
}