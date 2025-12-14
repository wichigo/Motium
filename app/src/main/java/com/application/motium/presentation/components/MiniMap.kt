package com.application.motium.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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

@Composable
fun MiniMap(
    startLatitude: Double?,
    startLongitude: Double?,
    endLatitude: Double?,
    endLongitude: Double?,
    routeCoordinates: List<List<Double>>? = null,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false  // For small maps (e.g., 83dp on Home screen)
) {
    // For compact maps in lists, use a lightweight Canvas-based route preview
    // This avoids creating heavy MapView instances during scrolling
    if (isCompact && startLatitude != null && startLongitude != null) {
        CompactRoutePreview(
            routeCoordinates = routeCoordinates,
            startLat = startLatitude,
            startLon = startLongitude,
            endLat = endLatitude,
            endLon = endLongitude,
            modifier = modifier
        )
        return
    }

    // Full MapView for detail screens
    val strokeWidth = 8f
    val boundingBoxPadding = 30
    val context = LocalContext.current

    // Configure OSMDroid
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = "Motium/1.0"
    }

    // Helper function to calculate bounding box from coordinates
    fun calculateBoundingBox(
        coords: List<List<Double>>?,
        fallbackStartLat: Double,
        fallbackStartLon: Double,
        fallbackEndLat: Double?,
        fallbackEndLon: Double?
    ): org.osmdroid.util.BoundingBox {
        val allLats = mutableListOf<Double>()
        val allLons = mutableListOf<Double>()

        coords?.forEach { coord ->
            if (coord.size >= 2) {
                allLats.add(coord[1])
                allLons.add(coord[0])
            }
        }

        allLats.add(fallbackStartLat)
        allLons.add(fallbackStartLon)
        if (fallbackEndLat != null && fallbackEndLon != null) {
            allLats.add(fallbackEndLat)
            allLons.add(fallbackEndLon)
        }

        val minLat = allLats.minOrNull()!!
        val maxLat = allLats.maxOrNull()!!
        val minLon = allLons.minOrNull()!!
        val maxLon = allLons.maxOrNull()!!

        return org.osmdroid.util.BoundingBox(maxLat, maxLon, minLat, minLon)
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
                        setMultiTouchControls(false)
                        minZoomLevel = 5.0
                        maxZoomLevel = 19.0

                        val startPoint = GeoPoint(startLatitude, startLongitude)
                        controller.setCenter(startPoint)
                        controller.setZoom(14.0)

                        if (endLatitude != null && endLongitude != null) {
                            val startMarker = Marker(this).apply {
                                position = GeoPoint(startLatitude, startLongitude)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                icon = resources.getDrawable(android.R.drawable.presence_online, null)
                            }
                            overlays.add(startMarker)

                            val endMarker = Marker(this).apply {
                                position = GeoPoint(endLatitude, endLongitude)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                icon = resources.getDrawable(android.R.drawable.presence_busy, null)
                            }
                            overlays.add(endMarker)
                        }

                        if (!routeCoordinates.isNullOrEmpty()) {
                            val polyline = Polyline().apply {
                                outlinePaint.color = Color.Blue.toArgb()
                                outlinePaint.strokeWidth = strokeWidth
                            }
                            routeCoordinates.forEach { coord ->
                                if (coord.size >= 2) {
                                    polyline.addPoint(GeoPoint(coord[1], coord[0]))
                                }
                            }
                            overlays.add(polyline)
                        }

                        post {
                            val boundingBox = calculateBoundingBox(
                                routeCoordinates,
                                startLatitude,
                                startLongitude,
                                endLatitude,
                                endLongitude
                            )
                            zoomToBoundingBox(boundingBox, false, boundingBoxPadding)
                        }
                    }
                },
                update = { mapView ->
                    mapView.overlays.clear()

                    if (endLatitude != null && endLongitude != null) {
                        val startMarker = Marker(mapView).apply {
                            position = GeoPoint(startLatitude, startLongitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            icon = mapView.context.resources.getDrawable(android.R.drawable.presence_online, null)
                        }
                        mapView.overlays.add(startMarker)

                        val endMarker = Marker(mapView).apply {
                            position = GeoPoint(endLatitude, endLongitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            icon = mapView.context.resources.getDrawable(android.R.drawable.presence_busy, null)
                        }
                        mapView.overlays.add(endMarker)
                    }

                    if (!routeCoordinates.isNullOrEmpty()) {
                        val polyline = Polyline().apply {
                            outlinePaint.color = Color.Blue.toArgb()
                            outlinePaint.strokeWidth = strokeWidth
                        }
                        routeCoordinates.forEach { coord ->
                            if (coord.size >= 2) {
                                polyline.addPoint(GeoPoint(coord[1], coord[0]))
                            }
                        }
                        mapView.overlays.add(polyline)
                    }

                    mapView.post {
                        val boundingBox = calculateBoundingBox(
                            routeCoordinates,
                            startLatitude,
                            startLongitude,
                            endLatitude,
                            endLongitude
                        )
                        mapView.zoomToBoundingBox(boundingBox, false, boundingBoxPadding)
                        mapView.invalidate()
                    }
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

/**
 * Lightweight Canvas-based route preview for compact maps in scrolling lists.
 * Much faster than MapView as it doesn't load map tiles or create heavy Android views.
 */
@Composable
private fun CompactRoutePreview(
    routeCoordinates: List<List<Double>>?,
    startLat: Double,
    startLon: Double,
    endLat: Double?,
    endLon: Double?,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val startColor = Color(0xFF4CAF50) // Green for start
    val endColor = Color(0xFFF44336) // Red for end

    // Collect all points for bounds calculation
    val allPoints = remember(routeCoordinates, startLat, startLon, endLat, endLon) {
        val points = mutableListOf<Pair<Double, Double>>()

        // Add route coordinates if available
        routeCoordinates?.forEach { coord ->
            if (coord.size >= 2) {
                points.add(Pair(coord[0], coord[1])) // lon, lat
            }
        }

        // If no route, use start/end points
        if (points.isEmpty()) {
            points.add(Pair(startLon, startLat))
            if (endLat != null && endLon != null) {
                points.add(Pair(endLon, endLat))
            }
        }

        points
    }

    if (allPoints.size < 2) {
        // Single point - show a marker icon
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(24.dp)
            )
        }
    } else {
        // Draw the route path directly on Canvas
        Canvas(modifier = modifier.padding(6.dp)) {
            // Calculate bounds
            val minLon = allPoints.minOf { it.first }
            val maxLon = allPoints.maxOf { it.first }
            val minLat = allPoints.minOf { it.second }
            val maxLat = allPoints.maxOf { it.second }

            val lonRange = maxLon - minLon
            val latRange = maxLat - minLat

            // Add padding to bounds (15%)
            val paddingFactor = 0.15
            val adjustedMinLon = minLon - lonRange * paddingFactor
            val adjustedMaxLon = maxLon + lonRange * paddingFactor
            val adjustedMinLat = minLat - latRange * paddingFactor
            val adjustedMaxLat = maxLat + latRange * paddingFactor
            val adjustedLonRange = adjustedMaxLon - adjustedMinLon
            val adjustedLatRange = adjustedMaxLat - adjustedMinLat

            // Function to convert geo coords to canvas coords
            fun geoToCanvas(lon: Double, lat: Double): Offset {
                val x = if (adjustedLonRange > 0) {
                    ((lon - adjustedMinLon) / adjustedLonRange * size.width).toFloat()
                } else {
                    size.width / 2
                }
                // Invert Y because canvas Y increases downward, but latitude increases upward
                val y = if (adjustedLatRange > 0) {
                    ((adjustedMaxLat - lat) / adjustedLatRange * size.height).toFloat()
                } else {
                    size.height / 2
                }
                return Offset(x, y)
            }

            // Draw the route path
            val path = Path()
            allPoints.forEachIndexed { index, point ->
                val canvasPoint = geoToCanvas(point.first, point.second)
                if (index == 0) {
                    path.moveTo(canvasPoint.x, canvasPoint.y)
                } else {
                    path.lineTo(canvasPoint.x, canvasPoint.y)
                }
            }

            drawPath(
                path = path,
                color = primaryColor,
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Draw start marker (green circle)
            val startPoint = geoToCanvas(allPoints.first().first, allPoints.first().second)
            drawCircle(
                color = startColor,
                radius = 4.dp.toPx(),
                center = startPoint
            )

            // Draw end marker (red circle)
            val endPoint = geoToCanvas(allPoints.last().first, allPoints.last().second)
            drawCircle(
                color = endColor,
                radius = 4.dp.toPx(),
                center = endPoint
            )
        }
    }
}