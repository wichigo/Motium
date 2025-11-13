package com.application.motium.data.geocoding

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import android.util.Log

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

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun searchAddress(query: String): List<NominatimResult> = withContext(Dispatchers.IO) {
        try {
            if (query.length < 3) return@withContext emptyList()

            // Utiliser l'API française officielle qui est bien meilleure pour l'autocomplétion
            val url = "https://api-adresse.data.gouv.fr/search/".toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("limit", "8")
                .addQueryParameter("autocomplete", "1") // Mode autocomplétion
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Motium/1.0")
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "{\"features\":[]}"
                val frenchResponse = json.decodeFromString<FrenchAddressResponse>(responseBody)

                // Convertir les résultats français vers le format NominatimResult
                frenchResponse.features.map { feature ->
                    val props = feature.properties
                    val coords = feature.geometry.coordinates

                    NominatimResult(
                        display_name = props.label,
                        lat = coords[1].toString(), // latitude
                        lon = coords[0].toString(), // longitude
                        type = props.type,
                        importance = props.score
                    )
                }
            } else {
                Log.e("NominatimService", "French API Error: ${response.code}")

                // Fallback vers Nominatim si l'API française échoue
                fallbackToNominatim(query)
            }
        } catch (e: Exception) {
            Log.e("NominatimService", "French API error: ${e.message}", e)

            // Fallback vers Nominatim en cas d'erreur
            fallbackToNominatim(query)
        }
    }

    private suspend fun fallbackToNominatim(query: String): List<NominatimResult> {
        try {
            val url = "https://nominatim.openstreetmap.org/search".toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("limit", "8")
                .addQueryParameter("countrycodes", "fr")
                .addQueryParameter("addressdetails", "1")
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Motium/1.0")
                .build()

            val response = client.newCall(request).execute()

            return if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "[]"
                json.decodeFromString<List<NominatimResult>>(responseBody)
            } else {
                Log.e("NominatimService", "Nominatim fallback error: ${response.code}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("NominatimService", "Nominatim fallback error: ${e.message}", e)
            return emptyList()
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

            // Utiliser OSRM (Open Source Routing Machine) - totalement gratuit, pas de clé API
            val url = "https://router.project-osrm.org/route/v1/driving/$startLon,$startLat;$endLon,$endLat".toHttpUrl()
                .newBuilder()
                .addQueryParameter("overview", "full")
                .addQueryParameter("geometries", "geojson")
                .build()

            Log.d("NominatimService", "URL route: $url")

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Motium/1.0")
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
     */
    suspend fun reverseGeocode(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        try {
            // Utiliser l'API française en priorité
            val url = "https://api-adresse.data.gouv.fr/reverse/".toHttpUrl()
                .newBuilder()
                .addQueryParameter("lon", longitude.toString())
                .addQueryParameter("lat", latitude.toString())
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Motium/1.0")
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "{\"features\":[]}"
                val frenchResponse = json.decodeFromString<FrenchAddressResponse>(responseBody)

                // Retourner la première adresse trouvée
                frenchResponse.features.firstOrNull()?.properties?.label
            } else {
                Log.e("NominatimService", "French reverse API Error: ${response.code}")
                // Fallback vers Nominatim
                reverseGeocodeNominatim(latitude, longitude)
            }
        } catch (e: Exception) {
            Log.e("NominatimService", "French reverse geocoding error: ${e.message}", e)
            // Fallback vers Nominatim
            reverseGeocodeNominatim(latitude, longitude)
        }
    }

    private suspend fun reverseGeocodeNominatim(latitude: Double, longitude: Double): String? {
        try {
            val url = "https://nominatim.openstreetmap.org/reverse".toHttpUrl()
                .newBuilder()
                .addQueryParameter("lat", latitude.toString())
                .addQueryParameter("lon", longitude.toString())
                .addQueryParameter("format", "json")
                .addQueryParameter("addressdetails", "1")
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Motium/1.0")
                .build()

            val response = client.newCall(request).execute()

            return if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "{}"
                val result = json.decodeFromString<NominatimResult>(responseBody)
                result.display_name
            } else {
                Log.e("NominatimService", "Nominatim reverse error: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e("NominatimService", "Nominatim reverse error: ${e.message}", e)
            return null
        }
    }

    companion object {
        @Volatile
        private var instance: NominatimService? = null

        fun getInstance(): NominatimService {
            return instance ?: synchronized(this) {
                instance ?: NominatimService().also { instance = it }
            }
        }
    }
}