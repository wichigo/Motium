package com.application.motium.data.geocoding

import com.application.motium.utils.Constants
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

@Serializable
data class NominatimResult(
    val display_name: String,
    val lat: String,
    val lon: String,
    val type: String? = null,
    val importance: Double? = null,
    val address: NominatimAddress? = null
)

@Serializable
data class NominatimAddress(
    val house_number: String? = null,
    val road: String? = null,
    val pedestrian: String? = null,
    val footway: String? = null,
    val cycleway: String? = null,
    val path: String? = null,
    val neighbourhood: String? = null,
    val suburb: String? = null,
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val municipality: String? = null,
    val postcode: String? = null,
    val country: String? = null
) {
    /**
     * Formate l'adresse selon le format souhaité:
     * numéro rue, code postal ville, pays
     */
    fun formatAddress(): String {
        val parts = mutableListOf<String>()

        // Numéro et rue
        val streetName = road ?: pedestrian ?: footway ?: cycleway ?: path
        if (house_number != null && streetName != null) {
            parts.add("$house_number $streetName")
        } else if (streetName != null) {
            parts.add(streetName)
        } else if (neighbourhood != null) {
            parts.add(neighbourhood)
        } else if (suburb != null) {
            parts.add(suburb)
        }

        // Code postal et ville
        val cityName = city ?: town ?: village ?: municipality
        if (postcode != null && cityName != null) {
            parts.add("$postcode $cityName")
        } else if (cityName != null) {
            parts.add(cityName)
        } else if (postcode != null) {
            parts.add(postcode)
        }

        // Pays
        if (country != null) {
            parts.add(country)
        }

        return parts.joinToString(", ")
    }

    companion object {
        /**
         * Regex pour détecter un code postal français (5 chiffres)
         */
        private val FRENCH_POSTCODE_REGEX = Regex("\\b(\\d{5})\\b")

        /**
         * Regex pour détecter un numéro de rue seul (1-4 chiffres, éventuellement avec bis/ter)
         */
        private val HOUSE_NUMBER_REGEX = Regex("^\\d{1,4}(\\s*(bis|ter|quater))?$", RegexOption.IGNORE_CASE)

        /**
         * Reformate une adresse au format long Nominatim vers un format court.
         * Exemple: "Voie Rapide Urbaine de Chambéry, Gonrat, Chambéry, Savoie, Auvergne-Rhône-Alpes, France métropolitaine, 73000, France"
         * Devient: "Voie Rapide Urbaine de Chambéry, 73000 Chambéry, France"
         *
         * Exemple avec numéro: "25, Rue de Warens, La Garatte, Chambéry, ..., 73000, France"
         * Devient: "25 Rue de Warens, 73000 Chambéry, France"
         */
        fun simplifyLegacyAddress(fullAddress: String?): String {
            if (fullAddress.isNullOrBlank()) return "Adresse inconnue"

            val parts = fullAddress.split(",").map { it.trim() }
            if (parts.size <= 3) return fullAddress // Déjà court

            val result = mutableListOf<String>()

            // 1. Construire l'adresse de rue
            val firstPart = parts.firstOrNull() ?: ""
            val street: String

            // Si le premier élément est juste un numéro, combiner avec le deuxième (nom de rue)
            if (HOUSE_NUMBER_REGEX.matches(firstPart) && parts.size > 1) {
                val streetName = parts[1]
                street = "$firstPart $streetName"
            } else {
                street = firstPart
            }

            if (street.isNotBlank()) {
                result.add(street)
            }

            // 2. Chercher le code postal (5 chiffres)
            var postcode: String? = null
            var city: String? = null

            for (i in parts.indices) {
                val part = parts[i]
                val postcodeMatch = FRENCH_POSTCODE_REGEX.find(part)
                if (postcodeMatch != null) {
                    postcode = postcodeMatch.value
                    // La ville est souvent juste avant le code postal
                    // ou c'est le même élément sans le code postal
                    val partWithoutPostcode = part.replace(postcode, "").trim()
                    if (partWithoutPostcode.isNotBlank()) {
                        city = partWithoutPostcode
                    } else if (i > 0) {
                        // Chercher une ville dans les éléments précédents
                        // Éviter les régions, départements, etc.
                        for (j in (i - 1) downTo 1) {
                            val candidate = parts[j]
                            // Exclure les régions et "France métropolitaine"
                            if (!candidate.contains("Alpes", ignoreCase = true) &&
                                !candidate.contains("Rhône", ignoreCase = true) &&
                                !candidate.contains("métropolitaine", ignoreCase = true) &&
                                !candidate.contains("Savoie", ignoreCase = true) &&
                                candidate.length < 30
                            ) {
                                city = candidate
                                break
                            }
                        }
                    }
                    break
                }
            }

            // 3. Ajouter code postal + ville
            if (postcode != null && city != null) {
                result.add("$postcode $city")
            } else if (city != null) {
                result.add(city)
            } else if (postcode != null) {
                result.add(postcode)
            }

            // 4. Pays = dernier élément (généralement "France")
            val country = parts.lastOrNull()
            if (!country.isNullOrBlank() && country != firstPart && country != street) {
                result.add(country)
            }

            return if (result.isNotEmpty()) result.joinToString(", ") else fullAddress
        }
    }
}

@Serializable
data class FrenchAddressResponse(
    val features: List<FrenchAddressFeature>
)

@Serializable
data class FrenchAddressFeature(
    val properties: FrenchAddressProperties,
    val geometry: FrenchAddressGeometry
)

@Serializable
data class FrenchAddressProperties(
    val label: String,
    val score: Double,
    val housenumber: String? = null,
    val name: String? = null,
    val postcode: String? = null,
    val city: String? = null,
    val context: String? = null,
    val type: String? = null
)

@Serializable
data class FrenchAddressGeometry(
    val coordinates: List<Double>
)

@Serializable
data class RouteResult(
    val coordinates: List<List<Double>>,
    val distance: Double,
    val duration: Double
)

class NominatimService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    // LRU cache for map matching results (max 50 entries)
    private val mapMatchCache = object : LinkedHashMap<String, List<List<Double>>>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<List<Double>>>): Boolean {
            return size > 50
        }
    }

    // Cache of failed map-match keys to avoid retrying indefinitely
    // Keys are cleared after 30 minutes to allow retry when server might be back
    private val failedMapMatchKeys = mutableSetOf<String>()
    private var lastFailureClearTime = System.currentTimeMillis()
    private val FAILURE_CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes

    private fun generateCacheKey(gpsPoints: List<Pair<Double, Double>>): String {
        val first = gpsPoints.first()
        val last = gpsPoints.last()
        val middle = gpsPoints[gpsPoints.size / 2]
        return "${first.first},${first.second}|${middle.first},${middle.second}|${last.first},${last.second}|${gpsPoints.size}"
    }

    companion object {
        // Seuils de validation pour le geocoding
        private const val MIN_GEOCODING_SCORE = 0.5 // Score minimum de confiance (0-1)
        private const val MAX_GEOCODING_DISTANCE_METERS = 100f // Distance max entre GPS et adresse retournée

        @Volatile
        private var instance: NominatimService? = null

        fun getInstance(): NominatimService {
            return instance ?: synchronized(this) {
                instance ?: NominatimService().also { instance = it }
            }
        }
    }

    suspend fun searchAddress(query: String): List<NominatimResult> = withContext(Dispatchers.IO) {
        try {
            if (query.length < 3) return@withContext emptyList()

            // Utiliser le serveur Nominatim privé
            val url = Constants.PrivateServer.NOMINATIM_SEARCH_URL.toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("limit", "8")
                .addQueryParameter("countrycodes", Constants.PrivateServer.NOMINATIM_COUNTRY_CODES)
                .addQueryParameter("addressdetails", "1")
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", Constants.NOMINATIM_USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "[]"
                    json.decodeFromString<List<NominatimResult>>(responseBody)
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }


    /**
     * Retry helper with exponential backoff for network operations
     */
    private suspend fun <T> retryWithBackoff(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 1000,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        repeat(maxAttempts - 1) { _ ->
            try {
                return block()
            } catch (e: SocketTimeoutException) {
                delay(currentDelay)
                currentDelay *= 2
            } catch (e: IOException) {
                delay(currentDelay)
                currentDelay *= 2
            }
        }
        return block() // Final attempt - let exception propagate
    }

    /**
     * Map Matching: "Snap to Road" - Aligne les points GPS sur les routes réelles
     * Utilise l'API OSRM Match pour créer un tracé qui suit les vraies routes
     * au lieu de tracer des lignes droites entre les points GPS.
     *
     * @param gpsPoints Liste de paires [latitude, longitude]
     * @return Liste de coordonnées snappées [longitude, latitude] pour affichage
     */
    suspend fun matchRoute(gpsPoints: List<Pair<Double, Double>>): List<List<Double>>? = withContext(Dispatchers.IO) {
        // OSRM map-matching requires at least 3 points to work properly
        // With only 2 points, it returns HTTP 400
        if (gpsPoints.size < 3) {
            return@withContext null
        }

        // Check cache first
        val cacheKey = generateCacheKey(gpsPoints)
        mapMatchCache[cacheKey]?.let { cached ->
            return@withContext cached
        }

        // Clear failed cache periodically (every 30 min) to allow retry
        val now = System.currentTimeMillis()
        if (now - lastFailureClearTime > FAILURE_CACHE_TTL_MS) {
            failedMapMatchKeys.clear()
            lastFailureClearTime = now
        }

        // Skip if this key previously failed (avoid retry loop)
        if (cacheKey in failedMapMatchKeys) {
            return@withContext null
        }

        try {
            // OSRM Match API - limite de 100 points par requête
            // Si plus de points, on échantillonne
            val sampledPoints = if (gpsPoints.size > 100) {
                val step = gpsPoints.size / 100
                gpsPoints.filterIndexed { index, _ -> index % step == 0 || index == gpsPoints.size - 1 }
            } else {
                gpsPoints
            }

            // Format OSRM: lon,lat;lon,lat;...
            val coordinatesString = sampledPoints.joinToString(";") { (lat, lon) ->
                "$lon,$lat"
            }

            val url = "${Constants.PrivateServer.OSRM_MATCH_URL}/$coordinatesString".toHttpUrl()
                .newBuilder()
                .addQueryParameter("overview", "full")
                .addQueryParameter("geometries", "geojson")
                .addQueryParameter("radiuses", sampledPoints.joinToString(";") { "25" }) // 25m de tolérance
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", Constants.NOMINATIM_USER_AGENT)
                .build()

            // Single attempt only - private OSRM server
            retryWithBackoff(maxAttempts = 1) {
                client.newCall(request).execute()
            }.use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: return@use null
                    val matchData = json.parseToJsonElement(responseBody).jsonObject

                    // Vérifier le code de réponse OSRM
                    val code = matchData["code"]?.jsonPrimitive?.content
                    if (code != "Ok") {
                        failedMapMatchKeys.add(cacheKey)
                        return@use null
                    }

                    // Extraire la géométrie du premier matching
                    val matchings = matchData["matchings"]?.jsonArray?.firstOrNull()?.jsonObject
                    val geometry = matchings?.get("geometry")?.jsonObject
                    val coordinates = geometry?.get("coordinates")?.jsonArray?.map { coord ->
                        coord.jsonArray.map { it.jsonPrimitive.double }
                    }

                    if (coordinates != null && coordinates.isNotEmpty()) {
                        mapMatchCache[cacheKey] = coordinates
                        coordinates
                    } else {
                        failedMapMatchKeys.add(cacheKey)
                        null
                    }
                } else {
                    // HTTP error (400, 500, etc.) - mark as failed to prevent retry loop
                    failedMapMatchKeys.add(cacheKey)
                    null
                }
            }
        } catch (e: Exception) {
            // Mark as failed to avoid retry loop
            failedMapMatchKeys.add(cacheKey)
            null
        }
    }

    suspend fun getRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): RouteResult? = withContext(Dispatchers.IO) {
        try {
            // Utiliser le serveur OSRM privé
            val url = "${Constants.PrivateServer.OSRM_ROUTE_URL}/$startLon,$startLat;$endLon,$endLat".toHttpUrl()
                .newBuilder()
                .addQueryParameter("overview", "full")
                .addQueryParameter("geometries", "geojson")
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", Constants.NOMINATIM_USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: return@use null

                    // Parse la réponse OSRM
                    val routeData = json.parseToJsonElement(responseBody).jsonObject
                    val routes = routeData["routes"]?.jsonArray?.firstOrNull()?.jsonObject

                    if (routes != null) {
                        val geometry = routes["geometry"]?.jsonObject
                        val coordinates = geometry?.get("coordinates")?.jsonArray?.map { coord ->
                            coord.jsonArray.map { it.jsonPrimitive.double }
                        } ?: emptyList()

                        val distance = routes["distance"]?.jsonPrimitive?.double ?: 0.0
                        val duration = routes["duration"]?.jsonPrimitive?.double ?: 0.0

                        RouteResult(
                            coordinates = coordinates,
                            distance = distance,
                            duration = duration
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reverse geocoding: convertit des coordonnées GPS en adresse lisible
     * Utilise le serveur Nominatim privé
     * Format: numéro rue, code postal ville, pays
     */
    suspend fun reverseGeocode(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        try {
            val url = Constants.PrivateServer.NOMINATIM_REVERSE_URL.toHttpUrl()
                .newBuilder()
                .addQueryParameter("lat", latitude.toString())
                .addQueryParameter("lon", longitude.toString())
                .addQueryParameter("format", "json")
                .addQueryParameter("addressdetails", "1")
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", Constants.NOMINATIM_USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "{}"
                    val result = json.decodeFromString<NominatimResult>(responseBody)
                    // Utiliser le format personnalisé si les détails sont disponibles
                    result.address?.formatAddress()?.takeIf { it.isNotBlank() }
                        ?: result.display_name
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

}