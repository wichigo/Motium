package com.application.motium.utils

import com.application.motium.domain.model.FuelType
import com.application.motium.domain.model.VehiclePower
import com.application.motium.domain.model.VehicleType

/**
 * Barème kilométrique français 2025 - Calcul des indemnités kilométriques
 * Source: https://bpifrance-creation.fr/boiteaoutils/bareme-kilometrique-2025
 *
 * CALCUL CUMULATIF PAR TRANCHES :
 * - Tranche 1 : 0 à 5 000 km → km × taux1
 * - Tranche 2 : 5 001 à 20 000 km → km × taux2
 * - Tranche 3 : > 20 000 km → km × taux3
 *
 * Le calcul cumule les indemnités de chaque tranche traversée.
 * Exemple pour 8360 km (4CV) : (5000 × 0.606) + (3360 × 0.340) = 4172.40 €
 *
 * MAJORATION VÉHICULES ÉLECTRIQUES : +20% sur tous les taux
 *
 * Chaque véhicule a son propre compteur annuel indépendant.
 * Les trajets PRO et PERSO sont comptabilisés séparément.
 */
object MileageAllowanceCalculator {

    // Seuils des tranches en km
    private const val BRACKET_1_MAX = 5000.0
    private const val BRACKET_2_MAX = 20000.0

    // Majoration pour véhicules électriques
    private const val ELECTRIC_BONUS = 1.20  // +20%

    /**
     * Barème kilométrique 2025 pour les voitures thermiques/hybrides
     * Les taux sont pour chaque tranche individuelle (calcul cumulatif)
     */
    data class CarRateBrackets(
        val bracket1Rate: Double,      // €/km pour 0-5000 km
        val bracket2Rate: Double,      // €/km pour 5001-20000 km
        val bracket3Rate: Double       // €/km pour >20000 km
    )

    // Barème 2025 pour véhicules thermiques
    private val carRates2025: Map<VehiclePower, CarRateBrackets> = mapOf(
        VehiclePower.CV_3 to CarRateBrackets(
            bracket1Rate = 0.529,
            bracket2Rate = 0.316,
            bracket3Rate = 0.370
        ),
        VehiclePower.CV_4 to CarRateBrackets(
            bracket1Rate = 0.606,
            bracket2Rate = 0.340,
            bracket3Rate = 0.407
        ),
        VehiclePower.CV_5 to CarRateBrackets(
            bracket1Rate = 0.636,
            bracket2Rate = 0.357,
            bracket3Rate = 0.427
        ),
        VehiclePower.CV_6 to CarRateBrackets(
            bracket1Rate = 0.665,
            bracket2Rate = 0.374,
            bracket3Rate = 0.447
        ),
        VehiclePower.CV_7_PLUS to CarRateBrackets(
            bracket1Rate = 0.697,
            bracket2Rate = 0.394,
            bracket3Rate = 0.470
        )
    )

    /**
     * Barème pour les deux-roues motorisés (calcul cumulatif)
     */
    data class TwoWheelerRateBrackets(
        val bracket1Rate: Double,      // €/km pour 0-3000 km
        val bracket2Rate: Double,      // €/km pour 3001-6000 km
        val bracket3Rate: Double       // €/km pour >6000 km
    )

    // Seuils pour deux-roues
    private const val TWO_WHEELER_BRACKET_1_MAX = 3000.0
    private const val TWO_WHEELER_BRACKET_2_MAX = 6000.0

    private val motorcycleRates2025 = TwoWheelerRateBrackets(
        bracket1Rate = 0.395,
        bracket2Rate = 0.099,
        bracket3Rate = 0.248
    )

    private val scooterRates2025 = TwoWheelerRateBrackets(
        bracket1Rate = 0.315,
        bracket2Rate = 0.079,
        bracket3Rate = 0.198
    )

    // Vélo : taux fixe de 0.25 €/km (pas de tranches)
    private const val BIKE_RATE = 0.25

    /**
     * Calcule le coût d'un trajet en fonction du kilométrage annuel déjà parcouru.
     * Applique la majoration de 20% pour les véhicules électriques.
     *
     * @param vehicleType Type de véhicule (CAR, MOTORCYCLE, SCOOTER, BIKE)
     * @param power Puissance fiscale (pour les voitures uniquement)
     * @param previousAnnualKm Kilomètres déjà parcourus cette année pour ce véhicule
     * @param tripDistanceKm Distance du nouveau trajet
     * @param fuelType Type de carburant (pour appliquer la majoration électrique)
     * @return Coût du trajet selon le barème progressif cumulatif
     */
    fun calculateTripCost(
        vehicleType: VehicleType,
        power: VehiclePower?,
        previousAnnualKm: Double,
        tripDistanceKm: Double,
        fuelType: FuelType? = null
    ): Double {
        val isElectric = fuelType == FuelType.ELECTRIC
        val multiplier = if (isElectric) ELECTRIC_BONUS else 1.0

        val baseCost = when (vehicleType) {
            VehicleType.CAR -> calculateCarTripCost(power, previousAnnualKm, tripDistanceKm)
            VehicleType.MOTORCYCLE -> calculateTwoWheelerTripCost(motorcycleRates2025, previousAnnualKm, tripDistanceKm)
            VehicleType.SCOOTER -> calculateTwoWheelerTripCost(scooterRates2025, previousAnnualKm, tripDistanceKm)
            VehicleType.BIKE -> tripDistanceKm * BIKE_RATE
        }

        return baseCost * multiplier
    }

    /**
     * Calcule le coût pour une voiture avec le système de tranches CUMULATIF.
     *
     * Exemple pour 8360 km (4CV, partant de 0 km) :
     * - Tranche 1 : 5000 km × 0.606 = 3030 €
     * - Tranche 2 : 3360 km × 0.340 = 1142.40 €
     * - Total : 4172.40 €
     *
     * Exemple pour 30000 km (4CV, partant de 0 km) :
     * - Tranche 1 : 5000 km × 0.606 = 3030 €
     * - Tranche 2 : 15000 km × 0.340 = 5100 €
     * - Tranche 3 : 10000 km × 0.407 = 4070 €
     * - Total : 12200 €
     */
    private fun calculateCarTripCost(
        power: VehiclePower?,
        previousAnnualKm: Double,
        tripDistanceKm: Double
    ): Double {
        val rates = carRates2025[power] ?: carRates2025[VehiclePower.CV_5]!!

        var cost = 0.0
        var remainingDistance = tripDistanceKm
        var currentPosition = previousAnnualKm

        // Tranche 1: 0 - 5000 km
        if (currentPosition < BRACKET_1_MAX && remainingDistance > 0) {
            val kmInBracket1 = minOf(remainingDistance, BRACKET_1_MAX - currentPosition)
            cost += kmInBracket1 * rates.bracket1Rate
            remainingDistance -= kmInBracket1
            currentPosition += kmInBracket1
        }

        // Tranche 2: 5001 - 20000 km
        if (currentPosition < BRACKET_2_MAX && remainingDistance > 0) {
            val kmInBracket2 = minOf(remainingDistance, BRACKET_2_MAX - currentPosition)
            cost += kmInBracket2 * rates.bracket2Rate
            remainingDistance -= kmInBracket2
            currentPosition += kmInBracket2
        }

        // Tranche 3: > 20000 km
        if (remainingDistance > 0) {
            cost += remainingDistance * rates.bracket3Rate
        }

        return cost
    }

    /**
     * Calcule le coût pour un deux-roues avec le système de tranches.
     */
    private fun calculateTwoWheelerTripCost(
        rates: TwoWheelerRateBrackets,
        previousAnnualKm: Double,
        tripDistanceKm: Double
    ): Double {
        var cost = 0.0
        var remainingDistance = tripDistanceKm
        var currentPosition = previousAnnualKm

        // Tranche 1: 0 - 3000 km
        if (currentPosition < TWO_WHEELER_BRACKET_1_MAX && remainingDistance > 0) {
            val kmInBracket1 = minOf(remainingDistance, TWO_WHEELER_BRACKET_1_MAX - currentPosition)
            cost += kmInBracket1 * rates.bracket1Rate
            remainingDistance -= kmInBracket1
            currentPosition += kmInBracket1
        }

        // Tranche 2: 3001 - 6000 km
        if (currentPosition < TWO_WHEELER_BRACKET_2_MAX && remainingDistance > 0) {
            val kmInBracket2 = minOf(remainingDistance, TWO_WHEELER_BRACKET_2_MAX - currentPosition)
            cost += kmInBracket2 * rates.bracket2Rate
            remainingDistance -= kmInBracket2
            currentPosition += kmInBracket2
        }

        // Tranche 3: > 6000 km
        if (remainingDistance > 0) {
            cost += remainingDistance * rates.bracket3Rate
        }

        return cost
    }

    /**
     * Calcule l'indemnité totale annuelle pour un véhicule donné.
     * Utilise le calcul CUMULATIF par tranches.
     *
     * @param vehicleType Type de véhicule
     * @param power Puissance fiscale (pour voitures)
     * @param totalAnnualKm Total des kilomètres annuels
     * @param fuelType Type de carburant (pour majoration électrique)
     * @return Indemnité totale selon le barème cumulatif
     */
    fun calculateAnnualAllowance(
        vehicleType: VehicleType,
        power: VehiclePower?,
        totalAnnualKm: Double,
        fuelType: FuelType? = null
    ): Double {
        val isElectric = fuelType == FuelType.ELECTRIC
        val multiplier = if (isElectric) ELECTRIC_BONUS else 1.0

        val baseCost = when (vehicleType) {
            VehicleType.CAR -> calculateCarAnnualAllowance(power, totalAnnualKm)
            VehicleType.MOTORCYCLE -> calculateTwoWheelerAnnualAllowance(motorcycleRates2025, totalAnnualKm, TWO_WHEELER_BRACKET_1_MAX, TWO_WHEELER_BRACKET_2_MAX)
            VehicleType.SCOOTER -> calculateTwoWheelerAnnualAllowance(scooterRates2025, totalAnnualKm, TWO_WHEELER_BRACKET_1_MAX, TWO_WHEELER_BRACKET_2_MAX)
            VehicleType.BIKE -> totalAnnualKm * BIKE_RATE
        }

        return baseCost * multiplier
    }

    /**
     * Calcul CUMULATIF pour voitures.
     * Cumule les indemnités de chaque tranche traversée.
     */
    private fun calculateCarAnnualAllowance(power: VehiclePower?, totalAnnualKm: Double): Double {
        val rates = carRates2025[power] ?: carRates2025[VehiclePower.CV_5]!!

        var cost = 0.0
        var remainingKm = totalAnnualKm

        // Tranche 1: 0 - 5000 km
        if (remainingKm > 0) {
            val kmInBracket1 = minOf(remainingKm, BRACKET_1_MAX)
            cost += kmInBracket1 * rates.bracket1Rate
            remainingKm -= kmInBracket1
        }

        // Tranche 2: 5001 - 20000 km
        if (remainingKm > 0) {
            val kmInBracket2 = minOf(remainingKm, BRACKET_2_MAX - BRACKET_1_MAX)
            cost += kmInBracket2 * rates.bracket2Rate
            remainingKm -= kmInBracket2
        }

        // Tranche 3: > 20000 km
        if (remainingKm > 0) {
            cost += remainingKm * rates.bracket3Rate
        }

        return cost
    }

    /**
     * Calcul CUMULATIF pour deux-roues.
     */
    private fun calculateTwoWheelerAnnualAllowance(
        rates: TwoWheelerRateBrackets,
        totalAnnualKm: Double,
        bracket1Max: Double,
        bracket2Max: Double
    ): Double {
        var cost = 0.0
        var remainingKm = totalAnnualKm

        // Tranche 1
        if (remainingKm > 0) {
            val kmInBracket1 = minOf(remainingKm, bracket1Max)
            cost += kmInBracket1 * rates.bracket1Rate
            remainingKm -= kmInBracket1
        }

        // Tranche 2
        if (remainingKm > 0) {
            val kmInBracket2 = minOf(remainingKm, bracket2Max - bracket1Max)
            cost += kmInBracket2 * rates.bracket2Rate
            remainingKm -= kmInBracket2
        }

        // Tranche 3
        if (remainingKm > 0) {
            cost += remainingKm * rates.bracket3Rate
        }

        return cost
    }

    /**
     * Retourne le taux effectif moyen pour une distance donnée.
     * Utile pour l'affichage.
     */
    fun getEffectiveRate(
        vehicleType: VehicleType,
        power: VehiclePower?,
        totalAnnualKm: Double
    ): Double {
        if (totalAnnualKm <= 0) return 0.0
        val allowance = calculateAnnualAllowance(vehicleType, power, totalAnnualKm)
        return allowance / totalAnnualKm
    }

    /**
     * Retourne les informations de tranche actuelle.
     */
    fun getCurrentBracketInfo(
        vehicleType: VehicleType,
        power: VehiclePower?,
        currentAnnualKm: Double
    ): BracketInfo {
        return when (vehicleType) {
            VehicleType.CAR -> getCarBracketInfo(power, currentAnnualKm)
            VehicleType.MOTORCYCLE, VehicleType.SCOOTER -> getTwoWheelerBracketInfo(vehicleType, currentAnnualKm)
            VehicleType.BIKE -> BracketInfo(
                bracketNumber = 1,
                bracketName = "Taux unique",
                currentRate = BIKE_RATE,
                kmUntilNextBracket = null,
                nextBracketRate = null
            )
        }
    }

    private fun getCarBracketInfo(power: VehiclePower?, currentAnnualKm: Double): BracketInfo {
        val rates = carRates2025[power] ?: carRates2025[VehiclePower.CV_5]!!

        return when {
            currentAnnualKm <= BRACKET_1_MAX -> BracketInfo(
                bracketNumber = 1,
                bracketName = "0 - 5 000 km",
                currentRate = rates.bracket1Rate,
                kmUntilNextBracket = BRACKET_1_MAX - currentAnnualKm,
                nextBracketRate = rates.bracket2Rate
            )
            currentAnnualKm <= BRACKET_2_MAX -> BracketInfo(
                bracketNumber = 2,
                bracketName = "5 001 - 20 000 km",
                currentRate = rates.bracket2Rate,
                kmUntilNextBracket = BRACKET_2_MAX - currentAnnualKm,
                nextBracketRate = rates.bracket3Rate
            )
            else -> BracketInfo(
                bracketNumber = 3,
                bracketName = "> 20 000 km",
                currentRate = rates.bracket3Rate,
                kmUntilNextBracket = null,
                nextBracketRate = null
            )
        }
    }

    private fun getTwoWheelerBracketInfo(vehicleType: VehicleType, currentAnnualKm: Double): BracketInfo {
        val rates = if (vehicleType == VehicleType.MOTORCYCLE) motorcycleRates2025 else scooterRates2025

        return when {
            currentAnnualKm <= TWO_WHEELER_BRACKET_1_MAX -> BracketInfo(
                bracketNumber = 1,
                bracketName = "0 - 3 000 km",
                currentRate = rates.bracket1Rate,
                kmUntilNextBracket = TWO_WHEELER_BRACKET_1_MAX - currentAnnualKm,
                nextBracketRate = rates.bracket2Rate
            )
            currentAnnualKm <= TWO_WHEELER_BRACKET_2_MAX -> BracketInfo(
                bracketNumber = 2,
                bracketName = "3 001 - 6 000 km",
                currentRate = rates.bracket2Rate,
                kmUntilNextBracket = TWO_WHEELER_BRACKET_2_MAX - currentAnnualKm,
                nextBracketRate = rates.bracket3Rate
            )
            else -> BracketInfo(
                bracketNumber = 3,
                bracketName = "> 6 000 km",
                currentRate = rates.bracket3Rate,
                kmUntilNextBracket = null,
                nextBracketRate = null
            )
        }
    }

    /**
     * Retourne les taux du barème pour une puissance donnée.
     */
    fun getRatesForPower(power: VehiclePower): CarRateBrackets {
        return carRates2025[power] ?: carRates2025[VehiclePower.CV_5]!!
    }
}

/**
 * Information sur la tranche actuelle du barème.
 */
data class BracketInfo(
    val bracketNumber: Int,
    val bracketName: String,
    val currentRate: Double,
    val kmUntilNextBracket: Double?,
    val nextBracketRate: Double?
)
