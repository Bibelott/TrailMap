package pl.edu.ur.jr125156.trailmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.RenderedQueryGeometry
import com.mapbox.maps.RenderedQueryOptions
import com.mapbox.maps.ScreenBox
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateBearing
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val TRAIL_SOURCE_ID = "trail-source-id"
const val TRAIL_LAYER_ID = "trail-layer-id"

public class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ActivityResultContracts.RequestPermission()
        super.onCreate(savedInstanceState)
        setContent {
            MapScreen()
        }
    }

    @Composable
    fun MapScreen() {
        RequestLocationPermission()

        val altitudeState = rememberAltitudeState()
        val headingState = rememberHeadingState()
        val userLocationState = rememberLocationState()

        val selectedTrail = remember { mutableStateOf<Trail?>(null) }
        val isNavigating = remember { mutableStateOf(false) }
        val altitudeHistory = remember { mutableStateListOf<Double>() }
        val trailGeoJsonState = remember { mutableStateOf<String?>(null) }
        val context = LocalContext.current
        val scope = rememberCoroutineScope() // Needed for download coroutine
        val sampleInterval = 1000L
        val mapboxToken = androidx.compose.ui.res.stringResource(R.string.mapbox_access_token)

        LaunchedEffect(isNavigating.value) {
            if (isNavigating.value) {

                while (true) {
                    altitudeHistory.add(altitudeState.value)

                    if (altitudeHistory.size > 100) {
                        altitudeHistory.removeAt(0)
                    }

                    delay(sampleInterval)
                }
            }
        }

        val TRAIL_SOURCE_ID = "trail-source-id"
        val TRAIL_LAYER_ID = "trail-layer-id"

        val REMOTE_SOURCE_ID = "remote-trail-source"
        val REMOTE_LAYER_ID = "remote-trail-layer"

        val myTilesetId = "mapbox://bibelot.7xx9xrlo"
        val mySourceLayer = "trails-8jyh3m"

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val data = TrailRepository.loadTrails(context)
                trailGeoJsonState.value = data
            }
        }

        val mapViewportState = rememberMapViewportState {
            setCameraOptions {
                zoom(13.0)
                center(Point.fromLngLat(21.9980, 50.0388))
                pitch(0.0)
                bearing(0.0)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            MapboxMap(
                Modifier.fillMaxSize(),
                mapViewportState = mapViewportState,
            ) {
                MapEffect(Unit) { mapView ->

                    mapView.location.updateSettings {
                        locationPuck = createDefault2DPuck(true)
                        enabled = true
                        puckBearing = PuckBearing.HEADING
                        puckBearingEnabled = true
                    }

                    mapView.mapboxMap.getStyle { style ->
                        if (!style.styleSourceExists(TRAIL_SOURCE_ID)) {
                            val geoJsonString = loadGeoJsonFromAssets(context, "trails.geojson")
                            if (geoJsonString != null) {
                                style.addSource(
                                    geoJsonSource(TRAIL_SOURCE_ID) {
                                        data(geoJsonString)
                                    }
                                )

                                val trailLayer = lineLayer(TRAIL_LAYER_ID, TRAIL_SOURCE_ID) {
                                    lineColor("#FF4500")
                                    lineWidth(6.0)
                                    lineCap(LineCap.ROUND)
                                    lineJoin(LineJoin.ROUND)
                                }

                                when {
                                    style.styleLayerExists("road-label") -> {
                                        style.addLayerBelow(trailLayer, "road-label")
                                    }
                                    style.styleLayerExists("mapbox-location-indicator-layer") -> {
                                        style.addLayerBelow(trailLayer, "mapbox-location-indicator-layer")
                                    }
                                    else -> {
                                        style.addLayer(trailLayer)
                                    }
                                }
                            }
                        }
                    }

                    mapView.gestures.addOnMapClickListener { point ->
                        if (isNavigating.value) return@addOnMapClickListener true

                        val pixel = mapView.mapboxMap.pixelForCoordinate(point)
                        val touchBuffer = 40.0
                        val queryBox = ScreenBox(
                            ScreenCoordinate(pixel.x - touchBuffer, pixel.y - touchBuffer),
                            ScreenCoordinate(pixel.x + touchBuffer, pixel.y + touchBuffer)
                        )

                        mapView.mapboxMap.queryRenderedFeatures(
                            RenderedQueryGeometry(queryBox),
                            RenderedQueryOptions(listOf(TRAIL_LAYER_ID), null)
                        ) { expected ->
                            if (expected.isValue && !expected.value.isNullOrEmpty()) {

                                val feature = expected.value!![0].queriedFeature.feature
                                val id = feature.getStringProperty("id")
                                val name = feature.getStringProperty("name")
                                val desc = if (feature.hasProperty("description")) feature.getStringProperty("description") else ""
                                val geometry = feature.geometry() as? LineString
                                val points = geometry?.coordinates() ?: emptyList()

                                selectedTrail.value = Trail(
                                    id = id,
                                    name = name,
                                    description = desc,
                                    points = points
                                )
                            } else {

                                selectedTrail.value = null
                            }
                        }
                        true
                    }
                }
                MapEffect(trailGeoJsonState.value) { mapView ->
                    if (trailGeoJsonState.value != null) {
                        mapView.mapboxMap.getStyle { style ->
                            val source = style.getSourceAs<com.mapbox.maps.extension.style.sources.generated.GeoJsonSource>(TRAIL_SOURCE_ID)
                            source?.data(trailGeoJsonState.value!!) // Updates the map instantly!
                        }
                    }
                }
            }

            HikeHud(
                altitudeMeters = altitudeState.value,
                headingDegrees = headingState.value,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )

            val isCompassMode = remember { mutableStateOf(false) }

            FloatingActionButton(
                onClick = {
                    isCompassMode.value = !isCompassMode.value

                    val bearingBehavior = if (isCompassMode.value) {
                        FollowPuckViewportStateBearing.SyncWithLocationPuck
                    } else {

                        FollowPuckViewportStateBearing.Constant(0.0)
                    }

                    mapViewportState.transitionToFollowPuckState(
                        followPuckViewportStateOptions = FollowPuckViewportStateOptions.Builder()
                            .bearing(bearingBehavior)
                            .pitch(0.0)
                            .build()
                    )
                },
                containerColor = if (isCompassMode.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = if (isCompassMode.value) Icons.Filled.Lock else Icons.Filled.LocationOn,
                    contentDescription = "Tryb Kompasu",
                    tint = if (isCompassMode.value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
            val MAPBOX_USERNAME = "bibelot"
            val DATASET_ID = "cmlqwd86z1ky11oo6kqc5kyr5"
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        val newUrl = "https://api.mapbox.com/datasets/v1/$MAPBOX_USERNAME/$DATASET_ID/features?access_token=$mapboxToken"
                        Log.w("Trail Refresh", "Downloading from $newUrl")
                        val newData = TrailRepository.downloadTrails(context, newUrl)
                        if (newData != null) {
                            trailGeoJsonState.value = newData // Triggers the MapEffect above!
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 32.dp, start = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Odśwież",
                )
            }

            if (isNavigating.value && selectedTrail.value != null) {
                NavigationHud(
                    trail = selectedTrail.value!!,
                    altitudeHistory = altitudeHistory,
                    sampleIntervalMs = sampleInterval,
                    onStopClick = {
                        isNavigating.value = false
                        selectedTrail.value = null
                        altitudeHistory.clear()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }

            else if (selectedTrail.value != null) {

                val fullTrail = getSampleTrails().find { it.id == selectedTrail.value!!.id }
                    ?: selectedTrail.value!!

                TrailSelectionCard(
                    trail = fullTrail,
                    userLocation = userLocationState.value,
                    onNavigateClick = {
                        isNavigating.value = true
                        altitudeHistory.clear()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }
    }

    @Composable
    fun TrailSelectionCard(
        trail: Trail,
        userLocation: Point?,
        onNavigateClick: () -> Unit,
        modifier: Modifier
    ) {

        val distance = if (userLocation != null && trail.points.isNotEmpty()) {
            distanceToTrail(userLocation, trail.points)
        } else {
            Double.MAX_VALUE
        }

        val maxDistanceMeters = 100.0
        val isNearby = distance <= maxDistanceMeters

        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = trail.name, style = MaterialTheme.typography.headlineSmall)

                if (userLocation == null) {
                    Text("Pobieranie lokacji GPS...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                } else if (isNearby) {
                    Text("Jesteś na szlaku!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(
                        text = "Znajdujesz się zbyt daleko (${distance.toInt()}m)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text(
                    text = trail.description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )


                androidx.compose.material3.Button(
                    onClick = onNavigateClick,
                    enabled = isNearby,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(if (isNearby) "Zacznij Nawigację" else "Musisz znajdować się bliżej, aby rozpocząć")
                }
            }
        }
    }

    @Composable
    fun NavigationHud(
        trail: Trail,
        altitudeHistory: List<Double>,
        onStopClick: () -> Unit,
        sampleIntervalMs: Long = 3000L,
        modifier: Modifier
    ) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Nawigowanie: ${trail.name}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )

                val durationSeconds = ((altitudeHistory.size - 1) * sampleIntervalMs) / 1000
                Text(
                    text = "Czas: ${formatDuration(durationSeconds)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )


                if (altitudeHistory.size > 1) {

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .padding(vertical = 8.dp)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val bottomPadding = 60f
                            val graphHeight = size.height - bottomPadding

                            val maxAlt = altitudeHistory.maxOrNull() ?: 0.0
                            val minAlt = altitudeHistory.minOrNull() ?: 0.0
                            val altRange = (maxAlt - minAlt).coerceAtLeast(5.0)


                            val strokePath = Path()
                            val fillPath = Path()

                            fillPath.moveTo(0f, graphHeight)

                            val widthPerPoint = size.width / (altitudeHistory.size - 1).coerceAtLeast(1)

                            altitudeHistory.forEachIndexed { index, alt ->
                                val x = index * widthPerPoint
                                val normalizedY = (alt - minAlt) / altRange

                                val y = graphHeight - (normalizedY * graphHeight).toFloat()

                                if (index == 0) strokePath.moveTo(x, y) else strokePath.lineTo(x, y)
                                fillPath.lineTo(x, y)
                            }


                            fillPath.lineTo(size.width, graphHeight)
                            fillPath.lineTo(0f, graphHeight)
                            fillPath.close()


                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color(0x442196F3), Color(0x112196F3)),
                                    endY = graphHeight
                                )
                            )
                            drawPath(
                                path = strokePath,
                                color = Color(0xFF2196F3),
                                style = Stroke(width = 5f)
                            )


                            drawLine(
                                color = Color.LightGray,
                                start = androidx.compose.ui.geometry.Offset(0f, graphHeight),
                                end = androidx.compose.ui.geometry.Offset(size.width, graphHeight),
                                strokeWidth = 2f
                            )


                            drawContext.canvas.nativeCanvas.apply {
                                val textPaint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.DKGRAY
                                    textSize = 32f
                                }

                                textPaint.textAlign = android.graphics.Paint.Align.LEFT
                                drawText("${maxAlt.roundToInt()}m", 10f, 40f, textPaint)
                                drawText("${minAlt.roundToInt()}m", 10f, graphHeight - 10f, textPaint)


                                val labelY = size.height - 15f

                                drawText("Start", 0f, labelY, textPaint)

                                textPaint.textAlign = android.graphics.Paint.Align.RIGHT
                                drawText("Teraz", size.width, labelY, textPaint)
                            }
                        }
                    }
                } else {
                    Text(
                        "Oczekiwanie na dane...",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }

                androidx.compose.material3.Button(
                    onClick = onStopClick,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Stop")
                }
            }
        }
    }


    fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }

    @Composable
    fun HikeHud(
        altitudeMeters: Double?,
        headingDegrees: Float,
        modifier: Modifier,
    ) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = altitudeMeters?.let {
                        "Wysokość: ${it.toInt()} m"
                    } ?: "Wysokość: —",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "Kierunek: ${headingDegrees.toInt()}°",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    @Composable
    fun RequestLocationPermission() {
        val context = LocalContext.current
        val permissionLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {}

        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun rememberAltitudeState(): State<Double> {
        val context = LocalContext.current
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        Log.w("No barometer", "No barometer was found")

        val altitude = remember { mutableStateOf(0.0) }

        if (pressureSensor == null) {
            val fusedLocationClient = remember {
                LocationServices.getFusedLocationProviderClient(context)
            }
            DisposableEffect(Unit) {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        result.lastLocation?.let { location ->
                            altitude.value = location.altitude
                        }
                    }
                }
                fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                onDispose { fusedLocationClient.removeLocationUpdates(callback) }
            }
            return altitude
        }


        DisposableEffect(Unit) {
            val listener = object : SensorEventListener {

                val alpha = 0.15f

                override fun onSensorChanged(event: SensorEvent) {
                    val pressure = event.values[0]

                    val currentRawAltitude = SensorManager.getAltitude(
                        SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                        pressure
                    ).toDouble()


                    if (altitude.value == 0.0) {
                        altitude.value = currentRawAltitude
                    } else {
                        altitude.value = (currentRawAltitude * alpha) + (altitude.value * (1.0 - alpha))
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            sensorManager.registerListener(
                listener,
                pressureSensor,
                SensorManager.SENSOR_DELAY_UI
            )

            onDispose {
                sensorManager.unregisterListener(listener)
            }
        }

        return altitude
    }

    @Composable
    fun rememberHeadingState(): State<Float> {
        val context = LocalContext.current
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val heading = remember { mutableStateOf(0f) }

        DisposableEffect(Unit) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(
                        rotationMatrix,
                        event.values
                    )

                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientation)

                    val azimuthRad = orientation[0]
                    val azimuthDeg =
                        (Math.toDegrees(azimuthRad.toDouble()) + 360) % 360

                    heading.value = azimuthDeg.toFloat()
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            sensorManager.registerListener(
                listener,
                rotationSensor,
                SensorManager.SENSOR_DELAY_GAME
            )

            onDispose {
                sensorManager.unregisterListener(listener)
            }
        }

        return heading
    }

    fun loadGeoJsonFromAssets(context: Context, fileName: String): String? {
        return try {
            context.assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun rememberLocationState(): State<Point?> {
        val context = LocalContext.current
        val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
        val locationState = remember { mutableStateOf<Point?>(null) }

        DisposableEffect(Unit) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let {
                        locationState.value = Point.fromLngLat(it.longitude, it.latitude)
                    }
                }
            }
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            onDispose { fusedLocationClient.removeLocationUpdates(callback) }
        }
        return locationState
    }



    fun distanceBetween(p1: Point, p2: Point): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(p2.latitude() - p1.latitude())
        val dLon = Math.toRadians(p2.longitude() - p1.longitude())
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(p1.latitude())) * cos(Math.toRadians(p2.latitude())) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1.0 - a))
        return R * c
    }


    fun distanceToTrail(userLoc: Point, trailPoints: List<Point>): Double {
        if (trailPoints.isEmpty()) return Double.MAX_VALUE
        return trailPoints.minOf { distanceBetween(userLoc, it) }
    }
}