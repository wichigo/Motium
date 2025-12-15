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
import android.util.Log
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
    val importance: Double? = null
)

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

            Log.d("NominatimService", "Search URL: $url")

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", Constants.NOMINATIM_USER_AGENT)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "[]"
                Log.d("NominatimService", "Search response: ${responseBody.take(200)}...")
                json.decodeFromString<List<NominatimResult>>(responseBody)
            } else {
                Log.e("NominatimService", "Private Nominatim Error: ${response.code}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("NominatimService", "Search error: ${e.message}", e)
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
        repeat(maxAttempts - 1) { attempt ->
            try {
                return block()
            } catch (e: SocketTimeoutException) {
                Log.w("NominatimService", "Attempt ${attempt + 1} timed out, retrying in ${currentDelay}ms")
                delay(currentDelay)
                currentDelay *= 2
            } catch (e: IOException) {
                Log.w("NominatimService", "Network error on attempt ${attempt + 1}, retrying in ${currentDelay}ms")
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
        if (gpsPoints.size < 2) {
            Log.w("NominatimService", "Map matching requires at least 2 points")
            return@withContext null
        }

        // Check cache first
        val cacheKey = generateCacheKey(gpsPoints)
        mapMatchCache[cacheKey]?.let { cached ->
            Log.d("NominatimService", "✅ Map matching cache hit for ${gpsPoints.size} points")
            return@withContext cached
        }

        // Clear failed cache periodically (every 30 min) to allow retry
        val now = System.currentTimeMillis()
        if (now - lastFailureClearTime > FAILURE_CACHE_TTL_MS) {
            failedMapMatchKeys.clear()
            lastFailureClearTime = now
            Log.d("NominatimService", "Cleared failed map-match cache")
        }

        // Skip if this key previously failed (avoid retry loop)
        if (cacheKey in failedMapMatchKeys) {
            Log.d("NominatimService", "⏭️ Skipping map-match (previously failed): ${gpsPoints.size} points")
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

            Log.d("NominatimService", "Map matching ${sampledPoints.size} points on private OSRM...")

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", Constants.NOMINATIM_USER_AGENT)
                .build()

            // Single attempt only - public OSRM server is unreliable, don't spam retries
            val response = retryWithBackoff(maxAttempts = 1) {
                client.newCall(request).execute()
            }

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return@withContext null
                val matchData = json.parseToJsonElement(responseBody).jsonObject

                // Vérifier le code de réponse OSRM
                val code = matchData["code"]?.jsonPrimitive?.content
                if (code != "Ok") {
                    Log.w("NominatimService", "OSRM match returned: $code")
                    return@withContext null
                }

                // Extraire la géométrie du premier matching
                val matchings = matchData["matchings"]?.jsonArray?.firstOrNull()?.jsonObject
                val geometry = matchings?.get("geometry")?.jsonObject
                val coordinates = geometry?.get("coordinates")?.jsonArray?.map { coord ->
                    coord.jsonArray.map { it.jsonPrimitive.double }
                }

                if (coordinates != null && coordinates.isNotEmpty()) {
                    mapMatchCache[cacheKey] = coordinates
                    Log.d("NominatimService", "✅ Map matching success: ${gpsPoints.size} GPS points → ${coordinates.size} road points")
                    coordinates
                } else {
                    Log.w("NominatimService", "No matched geometry returned")
                    null
                }
            } else {
                Log.e("NominatimService", "Map matching error: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e("NominatimService", "Map matching error: ${e.message}", e)
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
            Log.d("NominatimService", "Calcul route: ($startLat,$startLon) -> ($endLat,$endLon)")

            // Utiliser le serveur OSRM privé
            val url = "${Constants.PrivateServer.OSRM_ROUTE_URL}/$startLon,$startLat;$endLon,$endLat".toHttpUrl()
                .newBuilder()
                .addQueryParameter("overview", "full")
                .addQueryParameter("geometries", "geojson")
                .build()

            Log.d("NominatimService", "URL route (private OSRM): $url")

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", Constants.NOMINATIM_USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            Log.d("NominatimService", "Response code route: ${response.code}")

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return@withContext null
                Log.d("NominatimService", "Response body route (${responseBody.length} chars): ${responseBody.take(200)}...")

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

                    Log.d("NominatimService", "Route trouvée: ${distance}m, ${duration}s, ${coordinates.size} points")

                    RouteResult(
                        coordinates = coordinates,
                        distance = distance,
                        duration = duration
                    )
                } else {
                    Log.e("NominatimService", "Pas de routes dans la réponse OSRM")
                    null
                }
            } else {
                val errorBody = response.body?.string() ?: "No error body"
                Log.e("NominatimService", "Route error ${response.code}: $errorBody")
                null
            }
        } catch (e: Exception) {
            Log.e("NominatimService", "Route error: ${e.message}", e)
            null
        }
    }

    /**
     * Reverse geocoding: convertit des coordonnées GPS en adresse lisible
     * Utilise le serveur Nominatim privé
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

            Log.d("NominatimService", "Reverse geocode URL: $url")

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", Constants.NOMINATIM_USER_AGENT)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "{}"
                Log.d("NominatimService", "Reverse response: ${responseBody.take(200)}...")
                val result = json.decodeFromString<NominatimResult>(responseBody)
                Log.d("NominatimService", "✅ Reverse geocoded: ${result.display_name}")
                result.display_name
            } else {
                Log.e("NominatimService", "Reverse geocode error: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e("NominatimService", "Reverse geocoding error: ${e.message}", e)
            null
        }
    }

}