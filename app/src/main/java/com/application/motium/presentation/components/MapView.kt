package com.application.motium.presentation.components

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.application.motium.domain.model.LocationPoint
import org.osmdroid.api.IMapController
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

@Composable
fun MotiumMapView(
    modifier: Modifier = Modifier,
    tracePoints: List<LocationPoint> = emptyList(),
    centerPoint: LocationPoint? = null,
    zoomLevel: Double = 15.0
) {
    val context = LocalContext.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Configure osmdroid
            Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)

                // Set initial position
                val mapController: IMapController = controller
                mapController.setZoom(zoomLevel)

                // Center on provided point or default to Paris
                val center = centerPoint?.let {
                    GeoPoint(it.latitude, it.longitude)
                } ?: GeoPoint(48.8566, 2.3522) // Paris
                mapController.setCenter(center)

                // Add trace if provided
                if (tracePoints.isNotEmpty()) {
                    val geoPoints = tracePoints.map { point ->
                        GeoPoint(point.latitude, point.longitude)
                    }

                    val polyline = Polyline().apply {
                        setPoints(geoPoints)
                        outlinePaint.color = android.graphics.Color.BLUE
                        outlinePaint.strokeWidth = 8f
                    }

                    overlays.add(polyline)

                    // Zoom to fit all points if we have trace data
                    if (geoPoints.size > 1) {
                        val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(geoPoints)
                        zoomToBoundingBox(boundingBox, true, 100)
                    }
                }
            }
        },
        update = { mapView ->
            // Update map when trace points change
            mapView.overlays.clear()

            if (tracePoints.isNotEmpty()) {
                val geoPoints = tracePoints.map { point ->
                    GeoPoint(point.latitude, point.longitude)
                }

                val polyline = Polyline().apply {
                    setPoints(geoPoints)
                    outlinePaint.color = android.graphics.Color.BLUE
                    outlinePaint.strokeWidth = 8f
                }

                mapView.overlays.add(polyline)

                // Zoom to fit all points if we have trace data
                if (geoPoints.size > 1) {
                    val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(geoPoints)
                    mapView.zoomToBoundingBox(boundingBox, true, 100)
                }
            }

            mapView.invalidate()
        }
    )
}

@Composable
fun TripMapView(
    modifier: Modifier = Modifier,
    tracePoints: List<LocationPoint>,
    startPoint: LocationPoint?,
    endPoint: LocationPoint?
) {
    val context = LocalContext.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)

                val mapController: IMapController = controller
                mapController.setZoom(15.0)

                // Add trace polyline
                if (tracePoints.isNotEmpty()) {
                    val geoPoints = tracePoints.map { point ->
                        GeoPoint(point.latitude, point.longitude)
                    }

                    val polyline = Polyline().apply {
                        setPoints(geoPoints)
                        outlinePaint.color = android.graphics.Color.parseColor("#2E7D32") // Motium green
                        outlinePaint.strokeWidth = 8f
                    }

                    overlays.add(polyline)

                    // Add start marker
                    startPoint?.let { start ->
                        val startMarker = org.osmdroid.views.overlay.Marker(this).apply {
                            position = GeoPoint(start.latitude, start.longitude)
                            title = "Départ"
                            setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                        }
                        overlays.add(startMarker)
                    }

                    // Add end marker
                    endPoint?.let { end ->
                        val endMarker = org.osmdroid.views.overlay.Marker(this).apply {
                            position = GeoPoint(end.latitude, end.longitude)
                            title = "Arrivée"
                            setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                        }
                        overlays.add(endMarker)
                    }

                    // Zoom to fit the trace
                    if (geoPoints.size > 1) {
                        val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(geoPoints)
                        zoomToBoundingBox(boundingBox, true, 100)
                    }
                }
            }
        }
    )
}