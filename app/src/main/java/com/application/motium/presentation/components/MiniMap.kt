package com.application.motium.presentation.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.application.motium.utils.Constants
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.CircleManager
import org.maplibre.android.plugins.annotation.CircleOptions
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.utils.ColorUtils

@Composable
fun MiniMap(
    startLatitude: Double?,
    startLongitude: Double?,
    endLatitude: Double?,
    endLongitude: Double?,
    routeCoordinates: List<List<Double>>? = null,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    isInteractive: Boolean = true
) {
    // For compact maps in lists, use a lightweight Canvas-based route preview
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

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // MapLibre is initialized in MotiumApplication

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        if (startLatitude != null && startLongitude != null) {
            var mapView by remember { mutableStateOf<MapView?>(null) }

            AndroidView(
                factory = { ctx ->
                    MapView(ctx).also { view ->
                        mapView = view
                        view.getMapAsync { map ->
                            val styleUrl = if (Constants.PrivateServer.USE_PRIVATE_TILES) {
                                Constants.PrivateServer.MAPLIBRE_STYLE_PRIVATE
                            } else {
                                Constants.PrivateServer.MAPLIBRE_STYLE_FALLBACK
                            }

                            map.setStyle(styleUrl) { style ->
                                // Configure user interaction based on isInteractive
                                map.uiSettings.isZoomGesturesEnabled = isInteractive
                                map.uiSettings.isScrollGesturesEnabled = isInteractive
                                map.uiSettings.isRotateGesturesEnabled = false
                                map.uiSettings.isTiltGesturesEnabled = false

                                // Add route polyline if available
                                if (!routeCoordinates.isNullOrEmpty()) {
                                    addRoutePolyline(view, map, style, routeCoordinates)
                                }

                                // Add markers
                                addMarkers(view, map, style, startLatitude, startLongitude, endLatitude, endLongitude)

                                // Zoom to fit all points
                                zoomToFitRoute(map, routeCoordinates, startLatitude, startLongitude, endLatitude, endLongitude)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Lifecycle management
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> mapView?.onStart()
                        Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                        Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                        Lifecycle.Event.ON_STOP -> mapView?.onStop()
                        Lifecycle.Event.ON_DESTROY -> mapView?.onDestroy()
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    mapView?.onDestroy()
                }
            }
        } else {
            // Placeholder when no coordinates
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
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

private fun addRoutePolyline(
    mapView: MapView,
    map: MapLibreMap,
    style: Style,
    routeCoordinates: List<List<Double>>
) {
    try {
        val lineManager = LineManager(mapView, map, style)
        val latLngs = routeCoordinates.mapNotNull { coord ->
            if (coord.size >= 2) {
                LatLng(coord[1], coord[0]) // lat, lon
            } else null
        }

        if (latLngs.isNotEmpty()) {
            lineManager.create(
                LineOptions()
                    .withLatLngs(latLngs)
                    .withLineColor(ColorUtils.colorToRgbaString(AndroidColor.BLUE))
                    .withLineWidth(4f)
            )
        }
    } catch (e: Exception) {
        // Fallback: add as GeoJSON source
        android.util.Log.w("MiniMap", "LineManager not available, route not displayed: ${e.message}")
    }
}

private fun addMarkers(
    mapView: MapView,
    map: MapLibreMap,
    style: Style,
    startLat: Double,
    startLon: Double,
    endLat: Double?,
    endLon: Double?
) {
    try {
        val circleManager = CircleManager(mapView, map, style)

        // Start marker (green)
        circleManager.create(
            CircleOptions()
                .withLatLng(LatLng(startLat, startLon))
                .withCircleColor(ColorUtils.colorToRgbaString(AndroidColor.GREEN))
                .withCircleRadius(8f)
                .withCircleStrokeColor(ColorUtils.colorToRgbaString(AndroidColor.WHITE))
                .withCircleStrokeWidth(2f)
        )

        // End marker (red)
        if (endLat != null && endLon != null) {
            circleManager.create(
                CircleOptions()
                    .withLatLng(LatLng(endLat, endLon))
                    .withCircleColor(ColorUtils.colorToRgbaString(AndroidColor.RED))
                    .withCircleRadius(8f)
                    .withCircleStrokeColor(ColorUtils.colorToRgbaString(AndroidColor.WHITE))
                    .withCircleStrokeWidth(2f)
            )
        }
    } catch (e: Exception) {
        android.util.Log.w("MiniMap", "CircleManager not available: ${e.message}")
    }
}

private fun zoomToFitRoute(
    map: MapLibreMap,
    routeCoordinates: List<List<Double>>?,
    startLat: Double,
    startLon: Double,
    endLat: Double?,
    endLon: Double?
) {
    val boundsBuilder = LatLngBounds.Builder()

    // Add route points
    routeCoordinates?.forEach { coord ->
        if (coord.size >= 2) {
            boundsBuilder.include(LatLng(coord[1], coord[0]))
        }
    }

    // Add start/end points
    boundsBuilder.include(LatLng(startLat, startLon))
    if (endLat != null && endLon != null) {
        boundsBuilder.include(LatLng(endLat, endLon))
    }

    try {
        val bounds = boundsBuilder.build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 50))
    } catch (e: Exception) {
        // Fallback to center on start point
        map.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(startLat, startLon))
                    .zoom(14.0)
                    .build()
            )
        )
    }
}

/**
 * Lightweight Canvas-based route preview for compact maps in scrolling lists.
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
    val startColor = Color(0xFF4CAF50)
    val endColor = Color(0xFFF44336)

    val allPoints = remember(routeCoordinates, startLat, startLon, endLat, endLon) {
        val points = mutableListOf<Pair<Double, Double>>()
        routeCoordinates?.forEach { coord ->
            if (coord.size >= 2) {
                points.add(Pair(coord[0], coord[1]))
            }
        }
        if (points.isEmpty()) {
            points.add(Pair(startLon, startLat))
            if (endLat != null && endLon != null) {
                points.add(Pair(endLon, endLat))
            }
        }
        points
    }

    if (allPoints.size < 2) {
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
        Canvas(modifier = modifier.padding(6.dp)) {
            val minLon = allPoints.minOf { it.first }
            val maxLon = allPoints.maxOf { it.first }
            val minLat = allPoints.minOf { it.second }
            val maxLat = allPoints.maxOf { it.second }

            val lonRange = maxLon - minLon
            val latRange = maxLat - minLat

            val paddingFactor = 0.15
            val adjustedMinLon = minLon - lonRange * paddingFactor
            val adjustedMaxLon = maxLon + lonRange * paddingFactor
            val adjustedMinLat = minLat - latRange * paddingFactor
            val adjustedMaxLat = maxLat + latRange * paddingFactor
            val adjustedLonRange = adjustedMaxLon - adjustedMinLon
            val adjustedLatRange = adjustedMaxLat - adjustedMinLat

            fun geoToCanvas(lon: Double, lat: Double): Offset {
                val x = if (adjustedLonRange > 0) {
                    ((lon - adjustedMinLon) / adjustedLonRange * size.width).toFloat()
                } else {
                    size.width / 2
                }
                val y = if (adjustedLatRange > 0) {
                    ((adjustedMaxLat - lat) / adjustedLatRange * size.height).toFloat()
                } else {
                    size.height / 2
                }
                return Offset(x, y)
            }

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

            val startPoint = geoToCanvas(allPoints.first().first, allPoints.first().second)
            drawCircle(color = startColor, radius = 4.dp.toPx(), center = startPoint)

            val endPoint = geoToCanvas(allPoints.last().first, allPoints.last().second)
            drawCircle(color = endColor, radius = 4.dp.toPx(), center = endPoint)
        }
    }
}
