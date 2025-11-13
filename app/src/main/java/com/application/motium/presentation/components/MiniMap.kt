package com.application.motium.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Marker
import android.util.Log

@Composable
fun MiniMap(
    startLatitude: Double?,
    startLongitude: Double?,
    endLatitude: Double?,
    endLongitude: Double?,
    routeCoordinates: List<List<Double>>? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Configure OSMDroid
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = "Motium/1.0"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        if (startLatitude != null && startLongitude != null) {
            AndroidView(
                factory = { context ->
                    MapView(context).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)

                        // MAP FIX: Contraintes de zoom élargies pour supporter les gros trajets
                        // Zoom 8 = ~160km, Zoom 10 = ~40km, Zoom 13 = ~5km, Zoom 18 = ~150m
                        minZoomLevel = 8.0  // Permet de voir des trajets jusqu'à ~100km
                        maxZoomLevel = 18.0

                        // Centre la carte sur le point de départ (temporaire)
                        val startPoint = GeoPoint(startLatitude, startLongitude)
                        controller.setCenter(startPoint)

                        // Utiliser post() pour s'assurer que la vue est prête avant d'appliquer le zoom
                        post {
                            // Ajuster la vue selon les données disponibles
                            if (endLatitude != null && endLongitude != null) {
                                // Si on a des coordonnées de route, utiliser celles-ci pour le bounding box
                                if (!routeCoordinates.isNullOrEmpty()) {
                                    Log.d("MiniMap", "Factory: Centrage sur route avec ${routeCoordinates.size} points")

                                    val allLats = routeCoordinates.mapNotNull { coord ->
                                        if (coord.size >= 2) coord[1] else null
                                    }
                                    val allLons = routeCoordinates.mapNotNull { coord ->
                                        if (coord.size >= 2) coord[0] else null
                                    }

                                    if (allLats.isNotEmpty() && allLons.isNotEmpty()) {
                                        // MAP FIX: Marges dynamiques proportionnelles pour afficher tout l'itinéraire
                                        val rawMinLat = allLats.minOrNull()!!
                                        val rawMaxLat = allLats.maxOrNull()!!
                                        val rawMinLon = allLons.minOrNull()!!
                                        val rawMaxLon = allLons.maxOrNull()!!

                                        // Calculer l'étendue du trajet
                                        val latSpan = rawMaxLat - rawMinLat
                                        val lonSpan = rawMaxLon - rawMinLon

                                        // Marge = 15% de l'étendue, min 0.002, max 0.05
                                        val latMargin = (latSpan * 0.15).coerceIn(0.002, 0.05)
                                        val lonMargin = (lonSpan * 0.15).coerceIn(0.002, 0.05)

                                        val minLat = rawMinLat - latMargin
                                        val maxLat = rawMaxLat + latMargin
                                        val minLon = rawMinLon - lonMargin
                                        val maxLon = rawMaxLon + lonMargin

                                        Log.d("MiniMap", "Factory: Bounding box route: [$minLat, $minLon] -> [$maxLat, $maxLon] (margins: lat=$latMargin, lon=$lonMargin)")

                                        zoomToBoundingBox(
                                            org.osmdroid.util.BoundingBox(maxLat, maxLon, minLat, minLon),
                                            false
                                        )
                                    } else {
                                        Log.d("MiniMap", "Factory: Coordonnées route invalides, utilisation start/end")
                                        // Fallback sur start/end si les coordonnées route sont invalides
                                        val minLat = minOf(startLatitude, endLatitude) - 0.005
                                        val maxLat = maxOf(startLatitude, endLatitude) + 0.005
                                        val minLon = minOf(startLongitude, endLongitude) - 0.005
                                        val maxLon = maxOf(startLongitude, endLongitude) + 0.005

                                        zoomToBoundingBox(
                                            org.osmdroid.util.BoundingBox(maxLat, maxLon, minLat, minLon),
                                            false
                                        )
                                    }
                                } else {
                                    Log.d("MiniMap", "Factory: Pas de route, centrage sur start/end")
                                    // Pas de route, utiliser start/end
                                    val minLat = minOf(startLatitude, endLatitude) - 0.005
                                    val maxLat = maxOf(startLatitude, endLatitude) + 0.005
                                    val minLon = minOf(startLongitude, endLongitude) - 0.005
                                    val maxLon = maxOf(startLongitude, endLongitude) + 0.005

                                    zoomToBoundingBox(
                                        org.osmdroid.util.BoundingBox(maxLat, maxLon, minLat, minLon),
                                        false
                                    )
                                }
                            } else {
                                Log.d("MiniMap", "Factory: Pas de destination, zoom par défaut sur départ")
                                controller.setZoom(15.0)
                            }
                        }

                        // Ajouter des markers pour départ et arrivée
                        if (endLatitude != null && endLongitude != null) {
                            // Marker de départ (vert)
                            val startMarker = Marker(this).apply {
                                position = GeoPoint(startLatitude, startLongitude)
                                title = "Départ"
                                icon = resources.getDrawable(android.R.drawable.presence_online, null)
                            }
                            overlays.add(startMarker)

                            // Marker d'arrivée (rouge)
                            val endMarker = Marker(this).apply {
                                position = GeoPoint(endLatitude, endLongitude)
                                title = "Arrivée"
                                icon = resources.getDrawable(android.R.drawable.presence_busy, null)
                            }
                            overlays.add(endMarker)
                        }

                        // Afficher l'itinéraire si disponible
                        if (!routeCoordinates.isNullOrEmpty()) {
                            Log.d("MiniMap", "Affichage route avec ${routeCoordinates.size} points")

                            val polyline = Polyline().apply {
                                outlinePaint.color = Color.Blue.toArgb()
                                outlinePaint.strokeWidth = 8f
                            }

                            routeCoordinates.forEach { coord ->
                                if (coord.size >= 2) {
                                    val point = GeoPoint(coord[1], coord[0]) // lat, lon
                                    polyline.addPoint(point)
                                    Log.d("MiniMap", "Point route: ${coord[1]}, ${coord[0]}")
                                }
                            }

                            overlays.add(polyline)
                            Log.d("MiniMap", "Route ajoutée avec ${routeCoordinates.size} points")
                        } else {
                            Log.d("MiniMap", "Pas de coordonnées de route disponibles")
                        }
                    }
                },
                update = { mapView ->
                    Log.d("MiniMap", "Map update called - routeCoordinates: ${routeCoordinates?.size} points")

                    // Effacer les anciens overlays
                    mapView.overlays.clear()

                    // Recentrer la carte selon les données disponibles
                    if (startLatitude != null && startLongitude != null && endLatitude != null && endLongitude != null) {
                        // Si on a des coordonnées de route, utiliser celles-ci pour le bounding box
                        if (!routeCoordinates.isNullOrEmpty()) {
                            Log.d("MiniMap", "Update: Centrage sur route avec ${routeCoordinates.size} points")

                            val allLats = routeCoordinates.mapNotNull { coord ->
                                if (coord.size >= 2) coord[1] else null
                            }
                            val allLons = routeCoordinates.mapNotNull { coord ->
                                if (coord.size >= 2) coord[0] else null
                            }

                            if (allLats.isNotEmpty() && allLons.isNotEmpty()) {
                                // MAP FIX: Marges dynamiques proportionnelles pour afficher tout l'itinéraire
                                val rawMinLat = allLats.minOrNull()!!
                                val rawMaxLat = allLats.maxOrNull()!!
                                val rawMinLon = allLons.minOrNull()!!
                                val rawMaxLon = allLons.maxOrNull()!!

                                // Calculer l'étendue du trajet
                                val latSpan = rawMaxLat - rawMinLat
                                val lonSpan = rawMaxLon - rawMinLon

                                // Marge = 15% de l'étendue, min 0.002, max 0.05
                                val latMargin = (latSpan * 0.15).coerceIn(0.002, 0.05)
                                val lonMargin = (lonSpan * 0.15).coerceIn(0.002, 0.05)

                                val minLat = rawMinLat - latMargin
                                val maxLat = rawMaxLat + latMargin
                                val minLon = rawMinLon - lonMargin
                                val maxLon = rawMaxLon + lonMargin

                                Log.d("MiniMap", "Update: Bounding box route: [$minLat, $minLon] -> [$maxLat, $maxLon] (margins: lat=$latMargin, lon=$lonMargin)")

                                mapView.zoomToBoundingBox(
                                    org.osmdroid.util.BoundingBox(maxLat, maxLon, minLat, minLon),
                                    false
                                )
                            } else {
                                Log.d("MiniMap", "Update: Coordonnées route invalides, utilisation start/end")
                                // Fallback sur start/end si les coordonnées route sont invalides
                                val minLat = minOf(startLatitude, endLatitude) - 0.005
                                val maxLat = maxOf(startLatitude, endLatitude) + 0.005
                                val minLon = minOf(startLongitude, endLongitude) - 0.005
                                val maxLon = maxOf(startLongitude, endLongitude) + 0.005

                                mapView.zoomToBoundingBox(
                                    org.osmdroid.util.BoundingBox(maxLat, maxLon, minLat, minLon),
                                    false
                                )
                            }
                        } else {
                            Log.d("MiniMap", "Update: Pas de route, centrage sur start/end")
                            // Pas de route, utiliser start/end
                            val minLat = minOf(startLatitude, endLatitude) - 0.005
                            val maxLat = maxOf(startLatitude, endLatitude) + 0.005
                            val minLon = minOf(startLongitude, endLongitude) - 0.005
                            val maxLon = maxOf(startLongitude, endLongitude) + 0.005

                            mapView.zoomToBoundingBox(
                                org.osmdroid.util.BoundingBox(maxLat, maxLon, minLat, minLon),
                                false
                            )
                        }
                    }

                    // Ajouter les markers si on a les coordonnées
                    if (startLatitude != null && startLongitude != null) {
                        if (endLatitude != null && endLongitude != null) {
                            // Marker de départ
                            val startMarker = Marker(mapView).apply {
                                position = GeoPoint(startLatitude, startLongitude)
                                title = "Départ"
                                icon = mapView.context.resources.getDrawable(android.R.drawable.presence_online, null)
                            }
                            mapView.overlays.add(startMarker)

                            // Marker d'arrivée
                            val endMarker = Marker(mapView).apply {
                                position = GeoPoint(endLatitude, endLongitude)
                                title = "Arrivée"
                                icon = mapView.context.resources.getDrawable(android.R.drawable.presence_busy, null)
                            }
                            mapView.overlays.add(endMarker)
                        }
                    }

                    // Ajouter la route si disponible
                    if (routeCoordinates != null && routeCoordinates.isNotEmpty()) {
                        Log.d("MiniMap", "Update: Ajout route avec ${routeCoordinates.size} points")

                        val polyline = Polyline().apply {
                            outlinePaint.color = Color.Blue.toArgb()
                            outlinePaint.strokeWidth = 8f
                        }

                        routeCoordinates.forEach { coord ->
                            if (coord.size >= 2) {
                                val point = GeoPoint(coord[1], coord[0]) // lat, lon
                                polyline.addPoint(point)
                            }
                        }

                        mapView.overlays.add(polyline)
                        Log.d("MiniMap", "Update: Route ajoutée avec ${routeCoordinates.size} points")
                    }

                    mapView.invalidate()
                }
            )
        } else {
            // Placeholder quand pas de coordonnées
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Carte",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Sélectionnez les adresses pour voir l'itinéraire",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}