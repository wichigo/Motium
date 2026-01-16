package com.application.motium.testutils

import android.location.Location
import android.os.SystemClock

/**
 * Factory pour créer des données de localisation pour les tests
 * Fournit des routes réalistes avec vraies coordonnées GPS
 */
object TestLocationFactory {

    /**
     * Crée un point GPS avec les paramètres donnés
     */
    fun createGpsPoint(
        lat: Double,
        lng: Double,
        accuracy: Float = 5f,
        timestamp: Long = System.currentTimeMillis(),
        speed: Float = 0f,
        provider: String = "gps"
    ): Location {
        return Location(provider).apply {
            latitude = lat
            longitude = lng
            this.accuracy = accuracy
            time = timestamp
            this.speed = speed
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
    }

    /**
     * Classe helper pour les points GPS avec timing
     */
    data class GpsPoint(
        val lat: Double,
        val lng: Double,
        val accuracy: Float,
        val timestamp: Long,
        val speed: Float = 0f
    ) {
        fun toLocation(): Location = createGpsPoint(lat, lng, accuracy, timestamp, speed)
    }

    /**
     * Route Paris Champs-Élysées → Versailles (53 points, ~22 km)
     * Route réaliste via A13/A14 avec variations de précision GPS
     */
    fun createParisToVersaillesRoute(): List<GpsPoint> {
        val startTime = System.currentTimeMillis()
        return listOf(
            // ═══════════════════════════════════════════════════════════
            // PHASE 1: DÉPART - Champs-Élysées (stabilisation GPS)
            // ═══════════════════════════════════════════════════════════
            GpsPoint(48.8698, 2.3075, 15f, startTime),           // GPS froid
            GpsPoint(48.8697, 2.3074, 10f, startTime + 4000),    // Stabilisation
            GpsPoint(48.8696, 2.3072, 6f, startTime + 8000),     // GPS stable
            GpsPoint(48.8693, 2.3068, 5f, startTime + 12000),
            GpsPoint(48.8690, 2.3060, 4f, startTime + 16000),

            // ═══════════════════════════════════════════════════════════
            // PHASE 2: Place de l'Étoile → Porte Maillot
            // ═══════════════════════════════════════════════════════════
            GpsPoint(48.8700, 2.3040, 5f, startTime + 24000),
            GpsPoint(48.8715, 2.3010, 6f, startTime + 32000),
            GpsPoint(48.8730, 2.2980, 5f, startTime + 40000),    // Étoile
            GpsPoint(48.8745, 2.2950, 7f, startTime + 48000),
            GpsPoint(48.8755, 2.2920, 6f, startTime + 56000),
            GpsPoint(48.8765, 2.2890, 5f, startTime + 64000),
            GpsPoint(48.8775, 2.2860, 8f, startTime + 72000),
            GpsPoint(48.8780, 2.2830, 5f, startTime + 80000),    // Porte Maillot

            // ═══════════════════════════════════════════════════════════
            // PHASE 3: Neuilly-sur-Seine → La Défense
            // ═══════════════════════════════════════════════════════════
            GpsPoint(48.8800, 2.2780, 6f, startTime + 88000),
            GpsPoint(48.8820, 2.2730, 5f, startTime + 96000),
            GpsPoint(48.8840, 2.2680, 7f, startTime + 104000),
            GpsPoint(48.8860, 2.2620, 6f, startTime + 112000),
            GpsPoint(48.8880, 2.2560, 5f, startTime + 120000),   // Pont de Neuilly
            GpsPoint(48.8900, 2.2500, 8f, startTime + 128000),
            GpsPoint(48.8910, 2.2440, 6f, startTime + 136000),
            GpsPoint(48.8920, 2.2380, 5f, startTime + 144000),   // La Défense

            // ═══════════════════════════════════════════════════════════
            // PHASE 4: A14 → Nanterre-Préfecture
            // ═══════════════════════════════════════════════════════════
            GpsPoint(48.8925, 2.2320, 7f, startTime + 156000),
            GpsPoint(48.8930, 2.2260, 6f, startTime + 168000),
            GpsPoint(48.8928, 2.2200, 5f, startTime + 180000),
            GpsPoint(48.8925, 2.2140, 8f, startTime + 192000),
            GpsPoint(48.8920, 2.2080, 6f, startTime + 204000),
            GpsPoint(48.8915, 2.2020, 5f, startTime + 216000),   // Nanterre

            // ═══════════════════════════════════════════════════════════
            // PHASE 5: Rueil-Malmaison → Bougival
            // ═══════════════════════════════════════════════════════════
            GpsPoint(48.8880, 2.1980, 7f, startTime + 232000),
            GpsPoint(48.8850, 2.1940, 6f, startTime + 248000),
            GpsPoint(48.8820, 2.1900, 5f, startTime + 264000),
            GpsPoint(48.8790, 2.1860, 8f, startTime + 280000),
            GpsPoint(48.8760, 2.1820, 6f, startTime + 296000),   // Rueil
            GpsPoint(48.8730, 2.1780, 5f, startTime + 312000),
            GpsPoint(48.8700, 2.1740, 7f, startTime + 328000),
            GpsPoint(48.8670, 2.1700, 6f, startTime + 344000),

            // ═══════════════════════════════════════════════════════════
            // PHASE 6: Bougival → Saint-Cloud → Sèvres
            // ═══════════════════════════════════════════════════════════
            GpsPoint(48.8620, 2.1680, 5f, startTime + 364000),
            GpsPoint(48.8570, 2.1660, 8f, startTime + 384000),
            GpsPoint(48.8520, 2.1640, 6f, startTime + 404000),
            GpsPoint(48.8470, 2.1620, 5f, startTime + 424000),   // Saint-Cloud
            GpsPoint(48.8420, 2.1600, 7f, startTime + 444000),
            GpsPoint(48.8370, 2.1550, 6f, startTime + 464000),
            GpsPoint(48.8320, 2.1500, 5f, startTime + 484000),   // Sèvres

            // ═══════════════════════════════════════════════════════════
            // PHASE 7: Chaville → Viroflay → Versailles
            // ═══════════════════════════════════════════════════════════
            GpsPoint(48.8280, 2.1450, 8f, startTime + 504000),
            GpsPoint(48.8240, 2.1400, 6f, startTime + 524000),
            GpsPoint(48.8200, 2.1350, 5f, startTime + 544000),
            GpsPoint(48.8160, 2.1300, 7f, startTime + 564000),   // Viroflay
            GpsPoint(48.8120, 2.1260, 6f, startTime + 584000),
            GpsPoint(48.8080, 2.1220, 5f, startTime + 604000),

            // ═══════════════════════════════════════════════════════════
            // PHASE 8: ARRIVÉE - Château de Versailles
            // ═══════════════════════════════════════════════════════════
            GpsPoint(48.8060, 2.1200, 8f, startTime + 624000),
            GpsPoint(48.8055, 2.1205, 6f, startTime + 628000),
            GpsPoint(48.8052, 2.1204, 5f, startTime + 632000),
            GpsPoint(48.8050, 2.1203, 4f, startTime + 636000),   // Ralentissement
            GpsPoint(48.8049, 2.1204, 3f, startTime + 640000),   // Point final précis
        )
    }

    /**
     * Trajet très court (< 10m) - Devrait être rejeté
     * Simule un déplacement dans un parking
     */
    fun createShortTrip(): List<GpsPoint> {
        val startTime = System.currentTimeMillis()
        return listOf(
            GpsPoint(48.8698, 2.3075, 5f, startTime),
            GpsPoint(48.8698, 2.3076, 5f, startTime + 4000),  // ~8m de déplacement
            GpsPoint(48.8698, 2.3076, 4f, startTime + 8000),
        )
    }

    /**
     * Trajet moyen valide (~500m en 2 min)
     * Pour tester les critères de validation basiques
     */
    fun createMediumTrip(): List<GpsPoint> {
        val startTime = System.currentTimeMillis()
        return listOf(
            GpsPoint(48.8698, 2.3075, 5f, startTime),
            GpsPoint(48.8700, 2.3080, 6f, startTime + 20000),
            GpsPoint(48.8705, 2.3090, 5f, startTime + 40000),
            GpsPoint(48.8710, 2.3100, 7f, startTime + 60000),
            GpsPoint(48.8720, 2.3110, 5f, startTime + 80000),
            GpsPoint(48.8730, 2.3120, 6f, startTime + 100000),
            GpsPoint(48.8740, 2.3130, 5f, startTime + 120000),
        )
    }

    /**
     * Trajet Paris → Lyon (longue distance, ~465 km)
     * Pour tester les trajets longs et l'adaptation de fréquence GPS
     */
    fun createLongHighwayTrip(): List<GpsPoint> {
        val startTime = System.currentTimeMillis()
        // Points espacés de ~30 km chacun sur l'A6
        return listOf(
            // Départ Paris
            GpsPoint(48.8566, 2.3522, 8f, startTime),
            GpsPoint(48.8200, 2.3800, 6f, startTime + 300000),      // 5 min

            // Fontainebleau
            GpsPoint(48.4050, 2.6980, 7f, startTime + 1800000),     // 30 min
            GpsPoint(48.2000, 2.9500, 6f, startTime + 2700000),     // 45 min

            // Sens
            GpsPoint(48.1970, 3.2890, 8f, startTime + 3600000),     // 1h
            GpsPoint(47.9500, 3.4500, 6f, startTime + 4500000),     // 1h15

            // Auxerre
            GpsPoint(47.7986, 3.5670, 7f, startTime + 5400000),     // 1h30
            GpsPoint(47.5000, 3.7000, 6f, startTime + 6300000),     // 1h45

            // Avallon
            GpsPoint(47.4900, 3.9080, 8f, startTime + 7200000),     // 2h
            GpsPoint(47.2000, 4.1500, 6f, startTime + 8100000),     // 2h15

            // Beaune
            GpsPoint(47.0242, 4.8406, 7f, startTime + 9000000),     // 2h30
            GpsPoint(46.7500, 4.8500, 6f, startTime + 9900000),     // 2h45

            // Mâcon
            GpsPoint(46.3069, 4.8343, 8f, startTime + 10800000),    // 3h
            GpsPoint(46.0000, 4.7500, 6f, startTime + 11700000),    // 3h15

            // Villefranche
            GpsPoint(45.9847, 4.7180, 7f, startTime + 12600000),    // 3h30
            GpsPoint(45.8500, 4.7800, 6f, startTime + 13500000),    // 3h45

            // Arrivée Lyon
            GpsPoint(45.7640, 4.8357, 5f, startTime + 14400000),    // 4h
            GpsPoint(45.7578, 4.8320, 4f, startTime + 14460000),    // 4h01
        )
    }

    /**
     * Trajet avec tunnel (perte GPS de 3 min)
     * Pour tester la récupération après perte de signal
     */
    fun createTripWithTunnel(): List<GpsPoint> {
        val startTime = System.currentTimeMillis()
        return listOf(
            // Avant tunnel
            GpsPoint(48.8698, 2.3075, 5f, startTime),
            GpsPoint(48.8700, 2.3080, 6f, startTime + 10000),
            GpsPoint(48.8705, 2.3090, 5f, startTime + 20000),

            // Entrée tunnel - dernier point avant perte
            GpsPoint(48.8710, 2.3100, 7f, startTime + 30000),

            // GAP DE 3 MINUTES (180000 ms) - Pas de points GPS

            // Sortie tunnel - reprise GPS
            GpsPoint(48.8750, 2.3150, 15f, startTime + 210000),  // GPS imprécis à la sortie
            GpsPoint(48.8755, 2.3155, 10f, startTime + 215000),  // Stabilisation
            GpsPoint(48.8760, 2.3160, 6f, startTime + 220000),   // GPS stable
            GpsPoint(48.8770, 2.3170, 5f, startTime + 230000),
            GpsPoint(48.8780, 2.3180, 5f, startTime + 240000),
        )
    }

    /**
     * Trajet avec arrêts brefs (feux rouges, bouchons)
     * Pour tester le debounce de 2 minutes
     */
    fun createTripWithBriefStops(): List<GpsPoint> {
        val startTime = System.currentTimeMillis()
        return listOf(
            // Départ
            GpsPoint(48.8698, 2.3075, 5f, startTime),
            GpsPoint(48.8700, 2.3080, 6f, startTime + 10000),

            // Premier arrêt (feu rouge) - 30 secondes au même endroit
            GpsPoint(48.8705, 2.3090, 5f, startTime + 20000),
            GpsPoint(48.8705, 2.3090, 5f, startTime + 50000),   // +30s au même endroit

            // Reprise
            GpsPoint(48.8710, 2.3100, 6f, startTime + 60000),
            GpsPoint(48.8720, 2.3110, 5f, startTime + 70000),

            // Deuxième arrêt (bouchon) - 90 secondes
            GpsPoint(48.8730, 2.3120, 7f, startTime + 80000),
            GpsPoint(48.8731, 2.3121, 6f, startTime + 110000),  // Micro-mouvement
            GpsPoint(48.8732, 2.3122, 5f, startTime + 140000),  // Micro-mouvement
            GpsPoint(48.8733, 2.3123, 6f, startTime + 170000),  // Fin bouchon

            // Reprise fluide
            GpsPoint(48.8750, 2.3140, 5f, startTime + 190000),
            GpsPoint(48.8770, 2.3160, 6f, startTime + 210000),
            GpsPoint(48.8790, 2.3180, 5f, startTime + 230000),
        )
    }

    /**
     * Points avec précisions variées pour tester la sélection de point
     * Mélange de haute précision (<12m), moyenne (<25m), et basse (>25m)
     */
    fun createMixedPrecisionPoints(): List<GpsPoint> {
        val startTime = System.currentTimeMillis()
        return listOf(
            GpsPoint(48.8698, 2.3075, 30f, startTime),        // Basse précision
            GpsPoint(48.8697, 2.3074, 18f, startTime + 4000), // Moyenne
            GpsPoint(48.8696, 2.3073, 8f, startTime + 8000),  // Haute
            GpsPoint(48.8695, 2.3072, 5f, startTime + 12000), // Très haute
            GpsPoint(48.8694, 2.3071, 45f, startTime + 16000),// Basse (outlier)
            GpsPoint(48.8693, 2.3070, 10f, startTime + 20000),// Haute
            GpsPoint(48.8692, 2.3069, 22f, startTime + 24000),// Moyenne
            GpsPoint(48.8691, 2.3068, 4f, startTime + 28000), // Très haute
        )
    }

    /**
     * Calcule la distance totale d'une liste de points GPS (en mètres)
     */
    fun calculateTotalDistance(points: List<GpsPoint>): Double {
        if (points.size < 2) return 0.0

        var totalDistance = 0.0
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            totalDistance += calculateHaversineDistance(
                prev.lat, prev.lng,
                curr.lat, curr.lng
            )
        }
        return totalDistance
    }

    /**
     * Calcule la distance entre deux points GPS (formule Haversine)
     */
    fun calculateHaversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val earthRadius = 6371000.0 // Rayon de la Terre en mètres

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)

        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return earthRadius * c
    }

    /**
     * Calcule la durée totale d'un trajet (en ms)
     */
    fun calculateDuration(points: List<GpsPoint>): Long {
        if (points.size < 2) return 0L
        return points.last().timestamp - points.first().timestamp
    }

    /**
     * Calcule la vitesse moyenne (en m/s)
     */
    fun calculateAverageSpeed(points: List<GpsPoint>): Double {
        val distance = calculateTotalDistance(points)
        val duration = calculateDuration(points) / 1000.0 // Convertir en secondes
        return if (duration > 0) distance / duration else 0.0
    }
}
