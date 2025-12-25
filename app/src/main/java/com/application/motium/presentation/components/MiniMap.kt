package com.application.motium.presentation.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.application.motium.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.utils.ColorUtils

// LRU cache for map snapshot bitmaps (max 10MB for better retention)
private val mapSnapshotCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(10 * 1024 * 1024) {
    override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
}

// Track which snapshots are currently being generated to prevent duplicates
private val pendingSnapshots = mutableSetOf<String>()

/**
 * Process bitmap with route overlay on background thread.
 * This function is CPU-intensive and should NOT run on main thread.
 */
private fun processBitmapWithRoute(
    baseBitmap: Bitmap,
    allPoints: List<Pair<Double, Double>>
): Bitmap {
    val resultBitmap = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = AndroidCanvas(resultBitmap)

    if (allPoints.size >= 2) {
        // Draw route line
        val linePaint = Paint().apply {
            color = AndroidColor.parseColor("#2196F3") // Blue route
            strokeWidth = 6f
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Calculate pixel positions manually based on bounds and image size
        val imgWidth = resultBitmap.width.toFloat()
        val imgHeight = resultBitmap.height.toFloat()

        val minLat = allPoints.minOf { it.first }
        val maxLat = allPoints.maxOf { it.first }
        val minLon = allPoints.minOf { it.second }
        val maxLon = allPoints.maxOf { it.second }
        val latRange = maxLat - minLat
        val lonRange = maxLon - minLon
        val padding = 0.2f // 20% padding

        fun latLngToPixel(lat: Double, lon: Double): android.graphics.PointF {
            val x = if (lonRange > 0) {
                ((lon - minLon) / lonRange * (1 - 2 * padding) + padding) * imgWidth
            } else imgWidth / 2
            val y = if (latRange > 0) {
                ((maxLat - lat) / latRange * (1 - 2 * padding) + padding) * imgHeight
            } else imgHeight / 2

            return android.graphics.PointF(x.toFloat(), y.toFloat())
        }

        val path = android.graphics.Path()
        allPoints.forEachIndexed { index, (lat, lon) ->
            val point = latLngToPixel(lat, lon)
            if (index == 0) {
                path.moveTo(point.x, point.y)
            } else {
                path.lineTo(point.x, point.y)
            }
        }
        canvas.drawPath(path, linePaint)

        // Draw start marker (green)
        val startPoint = latLngToPixel(allPoints.first().first, allPoints.first().second)
        val startPaint = Paint().apply {
            color = AndroidColor.parseColor("#4CAF50")
            isAntiAlias = true
        }
        val startStroke = Paint().apply {
            color = AndroidColor.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        canvas.drawCircle(startPoint.x, startPoint.y, 12f, startPaint)
        canvas.drawCircle(startPoint.x, startPoint.y, 12f, startStroke)

        // Draw end marker (red)
        val endPoint = latLngToPixel(allPoints.last().first, allPoints.last().second)
        val endPaint = Paint().apply {
            color = AndroidColor.parseColor("#F44336")
            isAntiAlias = true
        }
        canvas.drawCircle(endPoint.x, endPoint.y, 12f, endPaint)
        canvas.drawCircle(endPoint.x, endPoint.y, 12f, startStroke)
    }

    return resultBitmap
}

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
                                map.uiSettings.isLogoEnabled = false
                                map.uiSettings.isAttributionEnabled = false

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
                    // Properly stop GL rendering before destroying
                    // Must follow lifecycle order: pause -> stop -> destroy
                    mapView?.onPause()
                    mapView?.onStop()
                    mapView?.onDestroy()
                    mapView = null
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
        // Fallback: LineManager not available, route not displayed
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
        // CircleManager not available
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
 * Compact map snapshot for scrolling lists using MapSnapshotter.
 * Features:
 * - Aggressive caching: snapshots are cached and reused across recompositions
 * - Deduplication: prevents multiple snapshotters for the same route
 * - Background processing: bitmap operations run off main thread
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
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // Calculate all points for the route
    val allPoints = remember(routeCoordinates, startLat, startLon, endLat, endLon) {
        val points = mutableListOf<Pair<Double, Double>>()
        routeCoordinates?.forEach { coord ->
            if (coord.size >= 2) {
                points.add(Pair(coord[1], coord[0])) // lat, lon
            }
        }
        if (points.isEmpty()) {
            points.add(Pair(startLat, startLon))
            if (endLat != null && endLon != null) {
                points.add(Pair(endLat, endLon))
            }
        }
        points
    }

    // Generate stable cache key based on route
    val cacheKey = remember(allPoints) {
        if (allPoints.isEmpty()) "" else {
            val first = allPoints.first()
            val last = allPoints.last()
            "map_${first.first.hashCode()}_${first.second.hashCode()}_${last.first.hashCode()}_${last.second.hashCode()}_${allPoints.size}"
        }
    }

    // State for the bitmap - check cache immediately
    var bitmap by remember(cacheKey) {
        mutableStateOf(if (cacheKey.isNotEmpty()) mapSnapshotCache.get(cacheKey) else null)
    }
    var isLoading by remember(cacheKey) { mutableStateOf(bitmap == null) }

    // Track snapshotter for cleanup
    var snapshotterRef by remember { mutableStateOf<MapSnapshotter?>(null) }

    // Cleanup on dispose
    DisposableEffect(cacheKey) {
        onDispose {
            snapshotterRef?.cancel()
            snapshotterRef = null
        }
    }

    // Generate snapshot only if not in cache AND not already pending
    LaunchedEffect(cacheKey) {
        if (cacheKey.isEmpty() || bitmap != null) return@LaunchedEffect

        // Check cache again (might have been populated by another composable)
        val cachedBitmap = mapSnapshotCache.get(cacheKey)
        if (cachedBitmap != null) {
            bitmap = cachedBitmap
            isLoading = false
            return@LaunchedEffect
        }

        // Check if already being generated
        synchronized(pendingSnapshots) {
            if (pendingSnapshots.contains(cacheKey)) {
                // Wait for the other one to finish, then check cache
                return@LaunchedEffect
            }
            pendingSnapshots.add(cacheKey)
        }

        // Debounce to avoid rapid creation during fast scroll
        delay(100)

        // Double-check cache after debounce
        val cachedAfterDelay = mapSnapshotCache.get(cacheKey)
        if (cachedAfterDelay != null) {
            bitmap = cachedAfterDelay
            isLoading = false
            synchronized(pendingSnapshots) { pendingSnapshots.remove(cacheKey) }
            return@LaunchedEffect
        }

        try {
            val sizePx = with(density) { 166.dp.toPx().toInt() }

            // Calculate bounds and zoom
            val boundsBuilder = LatLngBounds.Builder()
            allPoints.forEach { (lat, lon) -> boundsBuilder.include(LatLng(lat, lon)) }
            val bounds = boundsBuilder.build()
            val maxSpan = maxOf(bounds.latitudeSpan, bounds.longitudeSpan)
            val zoom = when {
                maxSpan > 0.5 -> 8.0
                maxSpan > 0.1 -> 10.0
                maxSpan > 0.05 -> 12.0
                maxSpan > 0.01 -> 14.0
                else -> 15.0
            }

            val styleUrl = if (Constants.PrivateServer.USE_PRIVATE_TILES) {
                Constants.PrivateServer.MAPLIBRE_STYLE_PRIVATE
            } else {
                Constants.PrivateServer.MAPLIBRE_STYLE_FALLBACK
            }

            val options = MapSnapshotter.Options(sizePx, sizePx)
                .withStyle(styleUrl)
                .withCameraPosition(
                    CameraPosition.Builder()
                        .target(bounds.center)
                        .zoom(zoom - 0.5)
                        .build()
                )

            val snapshotter = MapSnapshotter(context, options)
            snapshotterRef = snapshotter

            val pointsCopy = allPoints.toList()
            val keyCopy = cacheKey

            snapshotter.start(object : MapSnapshotter.SnapshotReadyCallback {
                override fun onSnapshotReady(snapshot: MapSnapshot) {
                    coroutineScope.launch(Dispatchers.Default) {
                        try {
                            val resultBitmap = processBitmapWithRoute(snapshot.bitmap, pointsCopy)

                            // Store in cache
                            mapSnapshotCache.put(keyCopy, resultBitmap)

                            withContext(Dispatchers.Main) {
                                bitmap = resultBitmap
                                isLoading = false
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { isLoading = false }
                        } finally {
                            synchronized(pendingSnapshots) { pendingSnapshots.remove(keyCopy) }
                        }
                    }
                }
            }, object : MapSnapshotter.ErrorHandler {
                override fun onError(error: String) {
                    synchronized(pendingSnapshots) { pendingSnapshots.remove(keyCopy) }
                    isLoading = false
                }
            })
        } catch (e: Exception) {
            synchronized(pendingSnapshots) { pendingSnapshots.remove(cacheKey) }
            isLoading = false
        }
    }

    // Poll for cache updates if we're waiting for another snapshotter
    LaunchedEffect(cacheKey, bitmap) {
        if (bitmap != null || cacheKey.isEmpty()) return@LaunchedEffect

        // If pending by another composable, poll cache
        while (bitmap == null && pendingSnapshots.contains(cacheKey)) {
            delay(50)
            val cached = mapSnapshotCache.get(cacheKey)
            if (cached != null) {
                bitmap = cached
                isLoading = false
                break
            }
        }
    }

    // UI
    Box(
        modifier = modifier.background(Color(0xFFF5F5F5)),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Carte du trajet",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            isLoading -> {
                // Show route preview while loading tiles
                SimpleRoutePreview(allPoints, Modifier.fillMaxSize())
            }
            else -> {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Simple route preview shown while map tiles are loading.
 */
@Composable
private fun SimpleRoutePreview(
    allPoints: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.background(Color(0xFFF0F4F8))) {
        if (allPoints.size < 2) return@Canvas

        val minLat = allPoints.minOf { it.first }
        val maxLat = allPoints.maxOf { it.first }
        val minLon = allPoints.minOf { it.second }
        val maxLon = allPoints.maxOf { it.second }
        val latRange = maxLat - minLat
        val lonRange = maxLon - minLon
        val padding = 0.15f

        fun toScreen(lat: Double, lon: Double): Offset {
            val x = if (lonRange > 0) ((lon - minLon) / lonRange * (1 - 2 * padding) + padding) * size.width else size.width / 2
            val y = if (latRange > 0) ((maxLat - lat) / latRange * (1 - 2 * padding) + padding) * size.height else size.height / 2
            return Offset(x.toFloat(), y.toFloat())
        }

        val routePath = Path().apply {
            allPoints.forEachIndexed { index, (lat, lon) ->
                val point = toScreen(lat, lon)
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }

        drawPath(routePath, Color(0xFF2196F3), style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        val startPoint = toScreen(allPoints.first().first, allPoints.first().second)
        drawCircle(Color.White, 8f, startPoint)
        drawCircle(Color(0xFF4CAF50), 5f, startPoint)

        val endPoint = toScreen(allPoints.last().first, allPoints.last().second)
        drawCircle(Color.White, 8f, endPoint)
        drawCircle(Color(0xFFF44336), 5f, endPoint)
    }
}
