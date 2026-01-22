package com.application.motium.utils

import com.application.motium.domain.model.FuelType
import com.application.motium.domain.model.VehiclePower
import com.application.motium.domain.model.VehicleType

/**
 * Calculateur de taux kilométrique selon le barème officiel français 2025
 * Source: https://www.impots.gouv.fr/bareme-kilometrique-2025
 */
object FrenchMileageCalculator {

    /**
     * Calcule le taux kilométrique en €/km selon le barème français
     * @param vehicleType Type de véhicule
     * @param power Puissance du véhicule
     * @param fuelType Type de carburant
     * @param totalMileage Kilométrage total réel du véhicule en km (perso + pro)
     * @return Taux kilométrique en €/km
     */
    fun calculateMileageRate(
        vehicleType: VehicleType,
        power: VehiclePower?,
        fuelType: FuelType?,
        totalMileage: Double
    ): Double {
        // Pour un véhicule neuf (0 km), on utilise le taux minimal
        val effectiveMileage = if (totalMileage <= 0) 5000.0 else totalMileage

        return when (vehicleType) {
            VehicleType.CAR -> calculateCarMileageRate(power, effectiveMileage)
            VehicleType.MOTORCYCLE -> calculateMotorcycleMileageRate(power, effectiveMileage)
            VehicleType.SCOOTER -> calculateScooterMileageRate(effectiveMileage)
            VehicleType.BIKE -> 0.25 // Forfait vélo : 0,25 €/km
        }
    }

    /**
     * Calcul pour les voitures selon la puissance fiscale
     */
    private fun calculateCarMileageRate(power: VehiclePower?, totalMileage: Double): Double {
        val cv = power?.cv?.replace("CV", "")?.toIntOrNull() ?: 5 // Défaut 5 CV

        return when {
            cv <= 3 -> calculateByMileageBrackets(
                totalMileage,
                upTo5000 = 0.529,
                from5001to20000Base = 2645.0,
                from5001to20000Rate = 0.316,
                above20000 = 0.370
            )
            cv == 4 -> calculateByMileageBrackets(
                totalMileage,
                upTo5000 = 0.606,
                from5001to20000Base = 3030.0,
                from5001to20000Rate = 0.340,
                above20000 = 0.407
            )
            cv == 5 -> calculateByMileageBrackets(
                totalMileage,
                upTo5000 = 0.636,
                from5001to20000Base = 3180.0,
                from5001to20000Rate = 0.357,
                above20000 = 0.427
            )
            cv == 6 -> calculateByMileageBrackets(
                totalMileage,
                upTo5000 = 0.665,
                from5001to20000Base = 3325.0,
                from5001to20000Rate = 0.374,
                above20000 = 0.447
            )
            cv == 7 -> calculateByMileageBrackets(
                totalMileage,
                upTo5000 = 0.697,
                from5001to20000Base = 3485.0,
                from5001to20000Rate = 0.394,
                above20000 = 0.470
            )
            cv >= 8 -> calculateByMileageBrackets(
                totalMileage,
                upTo5000 = 0.728,
                from5001to20000Base = 3640.0,
                from5001to20000Rate = 0.412,
                above20000 = 0.493
            )
            else -> calculateByMileageBrackets( // Défaut 5 CV
                totalMileage,
                upTo5000 = 0.636,
                from5001to20000Base = 3180.0,
                from5001to20000Rate = 0.357,
                above20000 = 0.427
            )
        }
    }

    /**
     * Calcul pour les motos selon la puissance
     */
    private fun calculateMotorcycleMileageRate(power: VehiclePower?, totalMileage: Double): Double {
        val cv = power?.cv?.replace("CV", "")?.toIntOrNull() ?: 3

        return when {
            cv <= 2 -> calculateByMileageBrackets(
                totalMileage,
                upTo5000 = 0.395,
                from5001to20000Base = 1975.0,
                from5001to20000Rate = 0.099,
                above20000 = 0.234
            )
            cv <= 5 -> calculateByMileageBrackets(
                totalMileage,
                upTo5000 = 0.468,
                from5001to20000Base = 2340.0,
                from5001to20000Rate = 0.082,
                above20000 = 0.260
            )
            else -> calculateByMileageBrackets( // > 5 CV
                totalMileage,
                upTo5000 = 0.606,
                from5001to20000Base = 3030.0,
                from5001to20000Rate = 0.340,
                above20000 = 0.407
            )
        }
    }

    /**
     * Calcul pour les scooters (forfait)
     */
    private fun calculateScooterMileageRate(totalMileage: Double): Double {
        return 0.315 // Forfait scooter : 0,315 €/km
    }

    /**
     * Calcule selon les tranches de kilométrage
     * Barème français :
     * - Jusqu'à 5 000 km : d × taux
     * - De 5 001 à 20 000 km : (base + (d - 5000) × taux) / d
     * - Au-delà de 20 000 km : d × taux fixe
     */
    private fun calculateByMileageBrackets(
        totalMileage: Double,
        upTo5000: Double,
        from5001to20000Base: Double,
        from5001to20000Rate: Double,
        above20000: Double
    ): Double {
        return when {
            totalMileage <= 5000 -> upTo5000
            totalMileage <= 20000 -> {
                val total = from5001to20000Base + ((totalMileage - 5000) * from5001to20000Rate)
                total / totalMileage
            }
            else -> above20000
        }
    }

}