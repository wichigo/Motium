package com.application.motium.presentation.components

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.application.motium.domain.model.LocationPoint
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

/**
 * Get the MapLibre style URL based on configuration
 */
private fun getStyleUrl() = if (Constants.PrivateServer.USE_PRIVATE_TILES) {
    Constants.PrivateServer.MAPLIBRE_STYLE_PRIVATE
} else {
    Constants.PrivateServer.MAPLIBRE_STYLE_FALLBACK
}

@Composable
fun MotiumMapView(
    modifier: Modifier = Modifier,
    tracePoints: List<LocationPoint> = emptyList(),
    centerPoint: LocationPoint? = null,
    zoomLevel: Double = 15.0
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // MapLibre is initialized in MotiumApplication

    var mapView by remember { mutableStateOf<MapView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).also { view ->
                mapView = view
                view.getMapAsync { map ->
                    map.setStyle(getStyleUrl()) { style ->
                        // Set initial position
                        val center = centerPoint?.let {
                            LatLng(it.latitude, it.longitude)
                        } ?: LatLng(48.8566, 2.3522) // Paris default

                        map.moveCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(center)
                                    .zoom(zoomLevel)
                                    .build()
                            )
                        )

                        // Add trace if provided
                        if (tracePoints.isNotEmpty()) {
                            addTracePolyline(view, map, style, tracePoints)

                            // Zoom to fit all points
                            if (tracePoints.size > 1) {
                                zoomToFitTrace(map, tracePoints)
                            }
                        }
                    }
                }
            }
        },
        update = { _ ->
            // MapLibre handles updates internally
        }
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
}

@Composable
fun TripMapView(
    modifier: Modifier = Modifier,
    tracePoints: List<LocationPoint>,
    startPoint: LocationPoint?,
    endPoint: LocationPoint?
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // MapLibre is initialized in MotiumApplication

    var mapView by remember { mutableStateOf<MapView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).also { view ->
                mapView = view
                view.getMapAsync { map ->
                    map.setStyle(getStyleUrl()) { style ->
                        map.moveCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(48.8566, 2.3522))
                                    .zoom(15.0)
                                    .build()
                            )
                        )

                        // Add trace polyline
                        if (tracePoints.isNotEmpty()) {
                            addTracePolyline(view, map, style, tracePoints)

                            // Add start marker
                            startPoint?.let { start ->
                                addMarker(view, map, style, start.latitude, start.longitude, AndroidColor.GREEN)
                            }

                            // Add end marker
                            endPoint?.let { end ->
                                addMarker(view, map, style, end.latitude, end.longitude, AndroidColor.RED)
                            }

                            // Zoom to fit the trace
                            if (tracePoints.size > 1) {
                                zoomToFitTrace(map, tracePoints)
                            }
                        }
                    }
                }
            }
        }
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
}

private fun addTracePolyline(
    mapView: MapView,
    map: MapLibreMap,
    style: Style,
    tracePoints: List<LocationPoint>
) {
    try {
        val lineManager = LineManager(mapView, map, style)
        val latLngs = tracePoints.map { point ->
            LatLng(point.latitude, point.longitude)
        }

        lineManager.create(
            LineOptions()
                .withLatLngs(latLngs)
                .withLineColor(ColorUtils.colorToRgbaString(AndroidColor.parseColor("#2E7D32"))) // Motium green
                .withLineWidth(6f)
        )
    } catch (e: Exception) {
        android.util.Log.w("MapView", "LineManager not available: ${e.message}")
    }
}

private fun addMarker(
    mapView: MapView,
    map: MapLibreMap,
    style: Style,
    latitude: Double,
    longitude: Double,
    color: Int
) {
    try {
        val circleManager = CircleManager(mapView, map, style)
        circleManager.create(
            CircleOptions()
                .withLatLng(LatLng(latitude, longitude))
                .withCircleColor(ColorUtils.colorToRgbaString(color))
                .withCircleRadius(10f)
                .withCircleStrokeColor(ColorUtils.colorToRgbaString(AndroidColor.WHITE))
                .withCircleStrokeWidth(2f)
        )
    } catch (e: Exception) {
        android.util.Log.w("MapView", "CircleManager not available: ${e.message}")
    }
}

private fun zoomToFitTrace(
    map: MapLibreMap,
    tracePoints: List<LocationPoint>
) {
    val boundsBuilder = LatLngBounds.Builder()
    tracePoints.forEach { point ->
        boundsBuilder.include(LatLng(point.latitude, point.longitude))
    }

    try {
        val bounds = boundsBuilder.build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
    } catch (e: Exception) {
        android.util.Log.w("MapView", "Could not zoom to fit trace: ${e.message}")
    }
}
