package com.application.motium.service

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.Trip
import com.application.motium.data.TripLocation
import com.application.motium.data.TripRepository
import com.application.motium.data.VehicleRepository
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.geocoding.NominatimService
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.math.*
import kotlin.random.Random

/**
 * Simulateur de trajets pour tests développeur
 *
 * Simule un trajet complet avec:
 * - Détection Activity Recognition (IN_VEHICLE ENTER)
 * - Démarrage du trajet
 * - Pauses et reprises (simulation bouchons/arrêts)
 * - Fin de trajet (WALKING ENTER)
 * - Création du trajet avec route aléatoire en France
 */
class TripSimulator private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TripSimulator"

        @Volatile
        private var instance: TripSimulator? = null

        fun getInstance(context: Context): TripSimulator {
            return instance ?: synchronized(this) {
                instance ?: TripSimulator(context.applicationContext).also { instance = it }
            }
        }

        // Routes prédéfinies en France (points GPS réels)
        private val FRENCH_ROUTES = listOf(
            // Chambéry → Lyon via A43 avec sortie départementale (~100 km)
            FrenchRoute(
                name = "Chambéry → Lyon (via D1006)",
                startCity = "Chambéry",
                endCity = "Lyon",
                points = listOf(
                    // Départ Chambéry
                    GpsPoint(45.5646, 5.9178),   // Gare de Chambéry
                    GpsPoint(45.5680, 5.8900),   // Entrée A43
                    GpsPoint(45.5720, 5.8500),   // A43 - Direction Lyon
                    GpsPoint(45.5780, 5.8000),   // A43 - Km 15
                    GpsPoint(45.5820, 5.7500),   // A43 - Km 25
                    GpsPoint(45.5850, 5.7000),   // A43 - Km 35
                    GpsPoint(45.5870, 5.6500),   // A43 - Km 45
                    GpsPoint(45.5880, 5.6000),   // A43 - Approche sortie

                    // SORTIE AUTOROUTE - Bretelle vers D1006
                    GpsPoint(45.5867, 5.5800),   // Sortie 7 - Bourgoin-Jallieu
                    GpsPoint(45.5850, 5.5700),   // Rond-point sortie

                    // DÉPARTEMENTALE D1006
                    GpsPoint(45.5830, 5.5600),   // D1006 - Entrée
                    GpsPoint(45.5800, 5.5400),   // D1006 - Zone 70 km/h
                    GpsPoint(45.5780, 5.5200),   // D1006 - Traversée village
                    GpsPoint(45.5760, 5.5000),   // D1006 - Ligne droite
                    GpsPoint(45.5750, 5.4800),   // D1006 - Virage serré
                    GpsPoint(45.5760, 5.4600),   // D1006 - Approche entrée A43

                    // RETOUR SUR AUTOROUTE
                    GpsPoint(45.5780, 5.4400),   // Bretelle d'accès A43
                    GpsPoint(45.5800, 5.4200),   // Accélération
                    GpsPoint(45.5850, 5.4000),   // A43 - Retour autoroute

                    // A43/A432 vers Lyon
                    GpsPoint(45.5900, 5.3500),   // A43 - Km 60
                    GpsPoint(45.6000, 5.3000),   // A43 - Km 70
                    GpsPoint(45.6200, 5.2500),   // Échangeur A432
                    GpsPoint(45.6500, 5.2000),   // A432 - Direction Lyon
                    GpsPoint(45.6800, 5.1500),   // A432 - Km 80
                    GpsPoint(45.7100, 5.1000),   // Périphérique Lyon Est
                    GpsPoint(45.7300, 5.0500),   // Approche Lyon
                    GpsPoint(45.7500, 5.0000),   // Lyon - Périphérique
                    GpsPoint(45.7600, 4.9000),   // Lyon - Villeurbanne
                    GpsPoint(45.7640, 4.8357)    // Arrivée Lyon - Place Bellecour
                )
            ),
            // Paris → Versailles (22 km)
            FrenchRoute(
                name = "Paris → Versailles",
                startCity = "Paris",
                endCity = "Versailles",
                points = listOf(
                    GpsPoint(48.8698, 2.3075),   // Champs-Élysées
                    GpsPoint(48.8700, 2.3040),
                    GpsPoint(48.8730, 2.2980),   // Place de l'Étoile
                    GpsPoint(48.8780, 2.2830),   // Porte Maillot
                    GpsPoint(48.8820, 2.2730),
                    GpsPoint(48.8880, 2.2560),   // Pont de Neuilly
                    GpsPoint(48.8920, 2.2380),   // La Défense
                    GpsPoint(48.8930, 2.2260),
                    GpsPoint(48.8920, 2.2080),
                    GpsPoint(48.8850, 2.1940),
                    GpsPoint(48.8760, 2.1820),   // Rueil
                    GpsPoint(48.8670, 2.1700),
                    GpsPoint(48.8520, 2.1640),
                    GpsPoint(48.8370, 2.1550),
                    GpsPoint(48.8200, 2.1350),
                    GpsPoint(48.8080, 2.1220),
                    GpsPoint(48.8049, 2.1204)    // Château de Versailles
                )
            ),
            // Lyon Centre → Aéroport Saint-Exupéry (25 km)
            FrenchRoute(
                name = "Lyon → Aéroport",
                startCity = "Lyon",
                endCity = "Aéroport Lyon Saint-Exupéry",
                points = listOf(
                    GpsPoint(45.7640, 4.8357),   // Place Bellecour
                    GpsPoint(45.7680, 4.8450),
                    GpsPoint(45.7720, 4.8580),
                    GpsPoint(45.7680, 4.8750),
                    GpsPoint(45.7620, 4.8950),
                    GpsPoint(45.7580, 4.9200),
                    GpsPoint(45.7540, 4.9450),
                    GpsPoint(45.7480, 4.9700),
                    GpsPoint(45.7400, 4.9950),
                    GpsPoint(45.7320, 5.0200),
                    GpsPoint(45.7250, 5.0500),
                    GpsPoint(45.7230, 5.0780),
                    GpsPoint(45.7256, 5.0811)    // Aéroport
                )
            ),
            // Marseille → Aix-en-Provence (30 km)
            FrenchRoute(
                name = "Marseille → Aix-en-Provence",
                startCity = "Marseille",
                endCity = "Aix-en-Provence",
                points = listOf(
                    GpsPoint(43.2965, 5.3698),   // Vieux-Port
                    GpsPoint(43.3050, 5.3750),
                    GpsPoint(43.3150, 5.3800),
                    GpsPoint(43.3300, 5.3850),
                    GpsPoint(43.3450, 5.3900),
                    GpsPoint(43.3600, 5.3950),
                    GpsPoint(43.3800, 5.4000),
                    GpsPoint(43.4000, 5.4100),
                    GpsPoint(43.4200, 5.4200),
                    GpsPoint(43.4400, 5.4350),
                    GpsPoint(43.4600, 5.4450),
                    GpsPoint(43.4800, 5.4500),
                    GpsPoint(43.5000, 5.4550),
                    GpsPoint(43.5298, 5.4474)    // Cours Mirabeau
                )
            ),
            // Bordeaux → Arcachon (60 km)
            FrenchRoute(
                name = "Bordeaux → Arcachon",
                startCity = "Bordeaux",
                endCity = "Arcachon",
                points = listOf(
                    GpsPoint(44.8378, -0.5792),  // Place de la Bourse
                    GpsPoint(44.8250, -0.5900),
                    GpsPoint(44.8100, -0.6100),
                    GpsPoint(44.7900, -0.6400),
                    GpsPoint(44.7650, -0.6800),
                    GpsPoint(44.7400, -0.7200),
                    GpsPoint(44.7100, -0.7700),
                    GpsPoint(44.6800, -0.8200),
                    GpsPoint(44.6500, -0.8700),
                    GpsPoint(44.6200, -0.9200),
                    GpsPoint(44.6000, -0.9700),
                    GpsPoint(44.6610, -1.1680)   // Arcachon
                )
            ),
            // Toulouse → Albi (80 km)
            FrenchRoute(
                name = "Toulouse → Albi",
                startCity = "Toulouse",
                endCity = "Albi",
                points = listOf(
                    GpsPoint(43.6047, 1.4442),   // Place du Capitole
                    GpsPoint(43.6200, 1.4600),
                    GpsPoint(43.6400, 1.4900),
                    GpsPoint(43.6600, 1.5200),
                    GpsPoint(43.6800, 1.5600),
                    GpsPoint(43.7000, 1.6000),
                    GpsPoint(43.7250, 1.6500),
                    GpsPoint(43.7500, 1.7000),
                    GpsPoint(43.7800, 1.7500),
                    GpsPoint(43.8100, 1.8000),
                    GpsPoint(43.8400, 1.8500),
                    GpsPoint(43.8700, 1.9000),
                    GpsPoint(43.9000, 1.9500),
                    GpsPoint(43.9263, 2.1476)    // Cathédrale Sainte-Cécile
                )
            ),
            // Nantes → La Baule (80 km)
            FrenchRoute(
                name = "Nantes → La Baule",
                startCity = "Nantes",
                endCity = "La Baule",
                points = listOf(
                    GpsPoint(47.2184, -1.5536),  // Château des Ducs
                    GpsPoint(47.2100, -1.5800),
                    GpsPoint(47.1950, -1.6200),
                    GpsPoint(47.1800, -1.6700),
                    GpsPoint(47.1600, -1.7200),
                    GpsPoint(47.1400, -1.7800),
                    GpsPoint(47.1200, -1.8400),
                    GpsPoint(47.1000, -1.9000),
                    GpsPoint(47.0800, -1.9600),
                    GpsPoint(47.0600, -2.0200),
                    GpsPoint(47.0400, -2.0800),
                    GpsPoint(47.0200, -2.1400),
                    GpsPoint(47.0000, -2.2000),
                    GpsPoint(47.2867, -2.3933)   // La Baule
                )
            ),
            // Strasbourg → Colmar (70 km)
            FrenchRoute(
                name = "Strasbourg → Colmar",
                startCity = "Strasbourg",
                endCity = "Colmar",
                points = listOf(
                    GpsPoint(48.5734, 7.7521),   // Cathédrale
                    GpsPoint(48.5500, 7.7400),
                    GpsPoint(48.5200, 7.7300),
                    GpsPoint(48.4900, 7.7200),
                    GpsPoint(48.4600, 7.7100),
                    GpsPoint(48.4300, 7.7000),
                    GpsPoint(48.4000, 7.6800),
                    GpsPoint(48.3700, 7.6600),
                    GpsPoint(48.3400, 7.6400),
                    GpsPoint(48.3100, 7.6200),
                    GpsPoint(48.2800, 7.5900),
                    GpsPoint(48.2500, 7.5600),
                    GpsPoint(48.2200, 7.5300),
                    GpsPoint(48.0794, 7.3558)    // Petite Venise
                )
            ),
            // Nice → Monaco (20 km)
            FrenchRoute(
                name = "Nice → Monaco",
                startCity = "Nice",
                endCity = "Monaco",
                points = listOf(
                    GpsPoint(43.7102, 7.2620),   // Promenade des Anglais
                    GpsPoint(43.7150, 7.2800),
                    GpsPoint(43.7200, 7.3000),
                    GpsPoint(43.7220, 7.3200),
                    GpsPoint(43.7250, 7.3400),
                    GpsPoint(43.7300, 7.3600),
                    GpsPoint(43.7350, 7.3800),
                    GpsPoint(43.7400, 7.4000),
                    GpsPoint(43.7420, 7.4150),
                    GpsPoint(43.7386, 7.4246)    // Monte-Carlo
                )
            )
        )
    }

    data class GpsPoint(val lat: Double, val lng: Double)

    data class FrenchRoute(
        val name: String,
        val startCity: String,
        val endCity: String,
        val points: List<GpsPoint>
    )

    // État de la simulation
    private var isSimulating = false
    private var simulationJob: Job? = null

    private val tripRepository = TripRepository.getInstance(context)
    private val vehicleRepository = VehicleRepository.getInstance(context)
    private val localUserRepository = LocalUserRepository.getInstance(context)
    private val nominatimService = NominatimService.getInstance()

    // Callback pour le statut
    var onStatusUpdate: ((String) -> Unit)? = null
    var onSimulationComplete: ((Boolean, String?) -> Unit)? = null

    /**
     * Démarre une simulation de trajet en arrière-plan
     */
    fun startSimulation() {
        if (isSimulating) {
            MotiumApplication.logger.w("Simulation already in progress", TAG)
            onStatusUpdate?.invoke("Simulation déjà en cours...")
            return
        }

        isSimulating = true
        simulationJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                runSimulation()
            } catch (e: Exception) {
                MotiumApplication.logger.e("Simulation failed: ${e.message}", TAG, e)
                withContext(Dispatchers.Main) {
                    onSimulationComplete?.invoke(false, e.message)
                }
            } finally {
                isSimulating = false
            }
        }
    }

    /**
     * Arrête la simulation en cours
     */
    fun stopSimulation() {
        simulationJob?.cancel()
        isSimulating = false
        MotiumApplication.logger.i("Simulation cancelled by user", TAG)
    }

    /**
     * Exécute la simulation complète
     */
    private suspend fun runSimulation() {
        updateStatus("🚗 Démarrage de la simulation...")

        // 1. Choisir une route aléatoire
        val route = FRENCH_ROUTES.random()
        MotiumApplication.logger.i("Selected route: ${route.name}", TAG)
        updateStatus("📍 Route: ${route.name}")

        // 2. Simuler la détection Activity Recognition
        delay(500)
        updateStatus("📡 Activity Recognition: IN_VEHICLE détecté")
        MotiumApplication.logger.i("🚗 [SIM] Activity Recognition: IN_VEHICLE ENTER", TAG)

        // 3. Simuler le buffering GPS
        delay(500)
        updateStatus("📍 Buffering GPS en cours...")
        MotiumApplication.logger.i("📍 [SIM] GPS Buffering started", TAG)

        // 4. Générer les points GPS avec variations réalistes
        val startTime = System.currentTimeMillis()
        val gpsPoints = generateRealisticGpsPoints(route, startTime)

        // 5. Simuler la progression avec pauses
        val totalDuration = gpsPoints.last().timestamp - gpsPoints.first().timestamp
        updateStatus("🎬 Trajet confirmé - ${gpsPoints.size} points GPS")
        MotiumApplication.logger.i("🎬 [SIM] Trip confirmed with ${gpsPoints.size} GPS points", TAG)

        // 6. Simuler des pauses (bouchons, feux rouges)
        val pauseCount = Random.nextInt(1, 4)
        for (i in 1..pauseCount) {
            delay(300)
            updateStatus("⏸️ Pause $i/$pauseCount (simulation arrêt)")
            MotiumApplication.logger.i("⏸️ [SIM] Pause $i - simulating traffic stop", TAG)
            delay(200)
            updateStatus("▶️ Reprise du trajet")
            MotiumApplication.logger.i("▶️ [SIM] Resume - continuing trip", TAG)
        }

        // 7. Simuler la fin du trajet
        delay(500)
        updateStatus("🚶 Activity Recognition: WALKING détecté")
        MotiumApplication.logger.i("🚶 [SIM] Activity Recognition: WALKING ENTER", TAG)

        delay(300)
        updateStatus("⏱️ Période de grâce (debounce)...")

        delay(300)
        updateStatus("📍 Collecte points d'arrivée...")

        // 8. Calculer la distance totale
        val totalDistance = calculateTotalDistance(gpsPoints)

        // 9. Récupérer les infos utilisateur et véhicule
        val user = localUserRepository.getLoggedInUser()
        val userId = user?.id

        if (userId == null) {
            updateStatus("❌ Erreur: Utilisateur non connecté")
            withContext(Dispatchers.Main) {
                onSimulationComplete?.invoke(false, "Utilisateur non connecté")
            }
            return
        }

        val defaultVehicle = vehicleRepository.getDefaultVehicle(userId)

        // 10. Géocoder les adresses (vrai service)
        updateStatus("🌍 Geocoding des adresses...")
        val startPoint = gpsPoints.first()
        val endPoint = gpsPoints.last()

        val startAddress = try {
            nominatimService.reverseGeocode(startPoint.latitude, startPoint.longitude)
                ?: "${route.startCity}, France"
        } catch (e: Exception) {
            "${route.startCity}, France"
        }

        val endAddress = try {
            nominatimService.reverseGeocode(endPoint.latitude, endPoint.longitude)
                ?: "${route.endCity}, France"
        } catch (e: Exception) {
            "${route.endCity}, France"
        }

        // 11. Créer le trajet
        val trip = Trip(
            id = UUID.randomUUID().toString(),
            startTime = gpsPoints.first().timestamp,
            endTime = gpsPoints.last().timestamp,
            locations = gpsPoints,
            totalDistance = totalDistance,
            isValidated = false,
            vehicleId = defaultVehicle?.id,
            startAddress = startAddress,
            endAddress = endAddress,
            notes = "🤖 Trajet simulé (${route.name})",
            tripType = "PROFESSIONAL",
            userId = userId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // 12. Sauvegarder le trajet
        updateStatus("💾 Sauvegarde du trajet...")
        tripRepository.saveTrip(trip)

        val distanceKm = totalDistance / 1000.0
        val durationMin = (totalDuration / 60000).toInt()

        MotiumApplication.logger.i(
            "✅ [SIM] Trip saved: ${route.name}, ${String.format("%.1f", distanceKm)} km, $durationMin min",
            TAG
        )

        updateStatus("✅ Trajet créé: ${String.format("%.1f", distanceKm)} km")

        withContext(Dispatchers.Main) {
            onSimulationComplete?.invoke(
                true,
                "${route.name}\n${String.format("%.1f", distanceKm)} km - $durationMin min"
            )
        }
    }

    /**
     * Génère des points GPS réalistes avec variations de précision et timing
     */
    private fun generateRealisticGpsPoints(route: FrenchRoute, startTime: Long): List<TripLocation> {
        val points = mutableListOf<TripLocation>()
        var currentTime = startTime

        // Interpoler des points intermédiaires entre chaque waypoint
        for (i in 0 until route.points.size - 1) {
            val start = route.points[i]
            val end = route.points[i + 1]

            // Calculer la distance entre les deux points
            val segmentDistance = haversineDistance(start.lat, start.lng, end.lat, end.lng)

            // Nombre de points intermédiaires basé sur la distance (1 point tous les ~200m)
            val intermediatePoints = (segmentDistance / 200).toInt().coerceIn(1, 10)

            for (j in 0..intermediatePoints) {
                val ratio = j.toDouble() / intermediatePoints

                // Interpolation linéaire avec légère variation aléatoire
                val lat = start.lat + (end.lat - start.lat) * ratio + Random.nextDouble(-0.0002, 0.0002)
                val lng = start.lng + (end.lng - start.lng) * ratio + Random.nextDouble(-0.0002, 0.0002)

                // Précision GPS variable (meilleure en mouvement)
                val accuracy = Random.nextFloat() * 10f + 3f // 3-13m

                points.add(TripLocation(
                    latitude = lat,
                    longitude = lng,
                    accuracy = accuracy,
                    timestamp = currentTime
                ))

                // Intervalle variable selon la "vitesse"
                val interval = when {
                    i == 0 && j < 3 -> 4000L  // Départ lent
                    i == route.points.size - 2 && j > intermediatePoints - 3 -> 4000L  // Arrivée lente
                    else -> Random.nextLong(3000, 8000)  // Normal
                }
                currentTime += interval
            }
        }

        // Ajouter le dernier point
        val lastPoint = route.points.last()
        points.add(TripLocation(
            latitude = lastPoint.lat + Random.nextDouble(-0.0001, 0.0001),
            longitude = lastPoint.lng + Random.nextDouble(-0.0001, 0.0001),
            accuracy = Random.nextFloat() * 5f + 2f,
            timestamp = currentTime
        ))

        return points
    }

    /**
     * Calcule la distance totale du trajet en mètres
     */
    private fun calculateTotalDistance(points: List<TripLocation>): Double {
        if (points.size < 2) return 0.0

        var total = 0.0
        for (i in 1 until points.size) {
            total += haversineDistance(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude
            )
        }
        return total
    }

    /**
     * Calcul de distance Haversine en mètres
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Rayon de la Terre en mètres
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private suspend fun updateStatus(message: String) {
        withContext(Dispatchers.Main) {
            onStatusUpdate?.invoke(message)
        }
    }
}
