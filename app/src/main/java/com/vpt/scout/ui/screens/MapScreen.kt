package com.vpt.scout.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.vpt.scout.ListRepository
import com.vpt.scout.PropertyList
import com.vpt.scout.PropertyRepository
import com.vpt.scout.Property
import com.vpt.scout.data.local.PropertyEntity
import com.vpt.scout.ui.components.PropertyDetailSheet
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun MapScreen(
    propertyRepository: PropertyRepository,
    listRepository: ListRepository,
    onResearchProperty: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val properties by propertyRepository.cachedMarkers.collectAsState(initial = emptyList())
    var routes by remember { mutableStateOf<List<PropertyList>>(emptyList()) }
    var activeRouteId by remember { mutableStateOf<Long?>(null) }
    var activeRoute by remember { mutableStateOf<com.vpt.scout.ListWithProperties?>(null) }
    
    var selectedProperty by remember { mutableStateOf<PropertyEntity?>(null) }
    var showListDialog by remember { mutableStateOf(false) }
    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    val lastFetchedBounds = remember { mutableStateOf<LatLngBounds?>(null) }
    var isLoadingViewport by remember { mutableStateOf(false) }
    var mapProjectionReady by remember { mutableStateOf(false) }
    
    // FusedLocationProviderClient for getting user location
    val fusedLocationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }
    
    val hasLocationPermission = remember {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    // Default camera position (Berkeley, CA area) - used as fallback
    val defaultPosition = LatLng(37.8716, -122.2727)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(defaultPosition, 14f)
    }

    LaunchedEffect(Unit) {
        runCatching { listRepository.refreshLists() }
            .onSuccess { routes = it }
    }

    LaunchedEffect(activeRouteId) {
        activeRoute = activeRouteId?.let { listId ->
            runCatching { listRepository.getList(listId) }.getOrNull()
        }
    }
    
    // Calculate distance between two points in miles
    fun calculateDistanceMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMiles = 3958.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMiles * c
    }
    
    // Sort properties by distance from user
    val sortedProperties = remember(properties, userLocation) {
        userLocation?.let { loc ->
            properties.map { property ->
                val distance = calculateDistanceMiles(loc.latitude, loc.longitude, property.latitude, property.longitude)
                property to distance
            }.sortedBy { it.second }
        } ?: properties.map { it to 0.0 }
    }
    
    // Get user's current location; map markers load from Supabase for the visible viewport.
    LaunchedEffect(hasLocationPermission) {
        initializeMapScreen(
            hasLocationPermission = hasLocationPermission,
            fetchLastLocation = { fusedLocationClient.lastLocation.await() },
            onLocationAvailable = { latLng ->
                userLocation = latLng
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(latLng, 15f),
                    durationMs = 1000
                )
            },
            onLoadingLocationChanged = {}
        )
    }

    LaunchedEffect(mapProjectionReady) {
        if (!mapProjectionReady) return@LaunchedEffect
        // Include projection in the snapshot: `isMoving` alone can sit at false while
        // projection is still null on first layout, which would skip loading until the user pans.
        snapshotFlow { cameraPositionState.isMoving to cameraPositionState.projection }
            .distinctUntilChanged()
            .filter { (moving, projection) -> !moving && projection != null }
            .debounce(400L)
            .collectLatest { (_, projection) ->
                val proj = projection ?: return@collectLatest
                val visible = proj.visibleRegion.latLngBounds
                val fetchBounds = visible.withPaddingFraction(0.25)
                val loaded = lastFetchedBounds.value
                if (loaded != null &&
                    loaded.contains(visible.northeast) &&
                    loaded.contains(visible.southwest)
                ) {
                    return@collectLatest
                }
                isLoadingViewport = true
                selectedProperty = null
                try {
                    propertyRepository.refreshMarkersInBounds(
                        south = fetchBounds.southwest.latitude,
                        west = fetchBounds.southwest.longitude,
                        north = fetchBounds.northeast.latitude,
                        east = fetchBounds.northeast.longitude
                    )
                    lastFetchedBounds.value = fetchBounds
                } finally {
                    isLoadingViewport = false
                }
            }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Map") },
                actions = {
                    var routesExpanded by remember { mutableStateOf(false) }
                    if (routes.isNotEmpty()) {
                        ExposedDropdownMenuBox(
                            expanded = routesExpanded,
                            onExpandedChange = { routesExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = routes.find { it.id == activeRouteId }?.name ?: "Viewport only",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = routesExpanded) },
                                modifier = Modifier
                                    .width(180.dp)
                                    .menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = routesExpanded,
                                onDismissRequest = { routesExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Viewport only") },
                                    onClick = {
                                        activeRouteId = null
                                        routesExpanded = false
                                    }
                                )
                                routes.forEach { route ->
                                    DropdownMenuItem(
                                        text = { Text(route.name) },
                                        onClick = {
                                            activeRouteId = route.id
                                            routesExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                lastFetchedBounds.value = null
                                val projection = cameraPositionState.projection ?: return@launch
                                val visible = projection.visibleRegion.latLngBounds
                                val fetchBounds = visible.withPaddingFraction(0.25)
                                isLoadingViewport = true
                                selectedProperty = null
                                try {
                                    propertyRepository.refreshMarkersInBounds(
                                        south = fetchBounds.southwest.latitude,
                                        west = fetchBounds.southwest.longitude,
                                        north = fetchBounds.northeast.latitude,
                                        east = fetchBounds.northeast.longitude
                                    )
                                    lastFetchedBounds.value = fetchBounds
                                } finally {
                                    isLoadingViewport = false
                                }
                            }
                        },
                        enabled = !isLoadingViewport
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh map area")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(
                    isMyLocationEnabled = hasLocationPermission
                ),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = true,
                    myLocationButtonEnabled = hasLocationPermission
                ),
                onMapLoaded = {
                    mapProjectionReady = true
                }
            ) {
                activeRoute?.properties
                    ?.filter { it.latitude != null && it.longitude != null }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { routeProperties ->
                        Polyline(
                            points = routeProperties.map { LatLng(it.latitude!!, it.longitude!!) },
                            color = Color(0xFF1565C0),
                            width = 10f
                        )
                        routeProperties.forEachIndexed { index, property ->
                            Marker(
                                state = MarkerState(position = LatLng(property.latitude!!, property.longitude!!)),
                                title = "${index + 1}. ${property.address ?: property.apn}",
                                snippet = property.apn,
                                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                                onClick = {
                                    selectedProperty = PropertyEntity(
                                        apn = property.apn,
                                        address = property.address ?: property.apn,
                                        longitude = property.longitude,
                                        latitude = property.latitude,
                                        hasVpt = property.hasVpt,
                                        conditionScore = property.conditionScore,
                                        city = property.city,
                                        streetViewImagePath = property.streetviewImagePath,
                                        updatedAt = java.time.Instant.now()
                                    )
                                    true
                                }
                            )
                        }
                    }

                // Display markers with color coding based on distance
                sortedProperties.forEachIndexed { index, (property, distance) ->
                    // Color gradient: green for closest, yellow for medium, red for far
                    val markerColor = when {
                        index < 5 -> BitmapDescriptorFactory.HUE_GREEN  // Closest 5 properties
                        distance < 0.5 -> BitmapDescriptorFactory.HUE_GREEN  // Within 0.5 miles
                        distance < 1.0 -> BitmapDescriptorFactory.HUE_YELLOW  // Within 1 mile
                        distance < 2.0 -> BitmapDescriptorFactory.HUE_ORANGE  // Within 2 miles
                        else -> BitmapDescriptorFactory.HUE_RED  // More than 2 miles
                    }
                    
                    val distanceText = if (distance < 0.1) {
                        "${(distance * 5280).toInt()} ft"  // Show feet for very close properties
                    } else {
                        "%.2f mi".format(distance)
                    }
                    
                    Marker(
                        state = MarkerState(
                            position = LatLng(property.latitude, property.longitude)
                        ),
                        title = property.address,
                        snippet = "$distanceText • APN: ${property.apn}",
                        icon = BitmapDescriptorFactory.defaultMarker(markerColor),
                        zIndex = if (index < 5) 1f else 0f,  // Closest properties on top
                        onClick = {
                            selectedProperty = property
                            true
                        }
                    )
                }
            }

            if (isLoadingViewport) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
            
            // Tapping a marker opens the shared details sheet with directions.
            selectedProperty?.let { entity ->
                val distance = userLocation?.let { loc ->
                    calculateDistanceMiles(loc.latitude, loc.longitude, entity.latitude, entity.longitude)
                }
                val rank = sortedProperties.indexOfFirst { it.first.apn == entity.apn } + 1
                val distanceText = distance?.let { dist ->
                    val base = if (dist < 0.1) "${(dist * 5280).toInt()} ft away" else "%.2f mi away".format(dist)
                    if (rank in 1..5) "$base · #$rank closest" else base
                }
                PropertyDetailSheet(
                    property = entity.toProperty(),
                    onResearch = onResearchProperty?.let { research -> { selectedProperty = null; research(entity.apn) } },
                    distanceText = distanceText,
                    onDismiss = { selectedProperty = null },
                    onAddToList = { showListDialog = true }
                )
            }
            
            // Info cards at top
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Property count and nearest info
                Card {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "${properties.size} properties",
                            style = MaterialTheme.typography.labelMedium
                        )
                        if (sortedProperties.isNotEmpty() && userLocation != null) {
                            val closest = sortedProperties.first()
                            val nearestDist = if (closest.second < 0.1) {
                                "${(closest.second * 5280).toInt()} ft"
                            } else {
                                "%.2f mi".format(closest.second)
                            }
                            Text(
                                text = "Nearest: $nearestDist",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                
                // Center on me button
                if (hasLocationPermission) {
                    FloatingActionButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val location = fusedLocationClient.lastLocation.await()
                                    if (location != null) {
                                        val latLng = LatLng(location.latitude, location.longitude)
                                        userLocation = latLng
                                        cameraPositionState.animate(
                                            CameraUpdateFactory.newLatLngZoom(latLng, 15f),
                                            durationMs = 500
                                        )
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = "Center on my location",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
    
    // Add to List Dialog
    if (showListDialog && selectedProperty != null) {
        AddToListDialog(
            lists = routes,
            onDismiss = { showListDialog = false },
            onSelect = { listId ->
                scope.launch {
                    selectedProperty?.let { property ->
                        listRepository.addPropertiesToList(listId, listOf(property.apn))
                        routes = runCatching { listRepository.refreshLists() }.getOrDefault(routes)
                    }
                    showListDialog = false
                }
            }
        )
    }
}

/**
 * Expands a visible region so edge markers stay loaded while panning slightly.
 */
internal fun LatLngBounds.withPaddingFraction(paddingFraction: Double): LatLngBounds {
    require(paddingFraction >= 0.0)
    val latSpan = northeast.latitude - southwest.latitude
    val lngSpan = northeast.longitude - southwest.longitude
    val latPad = latSpan * paddingFraction / 2.0
    val lngPad = lngSpan * paddingFraction / 2.0
    val sw = LatLng(
        (southwest.latitude - latPad).coerceIn(-85.0, 85.0),
        southwest.longitude - lngPad
    )
    val ne = LatLng(
        (northeast.latitude + latPad).coerceIn(-85.0, 85.0),
        northeast.longitude + lngPad
    )
    return LatLngBounds(sw, ne)
}

internal suspend fun initializeMapScreen(
    hasLocationPermission: Boolean,
    fetchLastLocation: suspend () -> Location?,
    onLocationAvailable: suspend (LatLng) -> Unit,
    onLoadingLocationChanged: (Boolean) -> Unit,
    locationTimeoutMillis: Long = 3_000L
) = coroutineScope {
    try {
        if (hasLocationPermission) {
            val location = withTimeoutOrNull(locationTimeoutMillis) {
                fetchLastLocation()
            }
            if (location != null) {
                onLocationAvailable(LatLng(location.latitude, location.longitude))
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        onLoadingLocationChanged(false)
    }
}

@Composable
fun AddToListDialog(
    lists: List<PropertyList>,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to List") },
        text = {
            if (lists.isEmpty()) {
                Text("No lists available. Create one first.")
            } else {
                Column {
                    lists.forEach { list ->
                        TextButton(
                            onClick = { onSelect(list.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(list.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun PropertyEntity.toProperty() = Property(
    apn = apn,
    address = address,
    city = city,
    latitude = latitude,
    longitude = longitude,
    hasVpt = hasVpt,
    conditionScore = conditionScore,
    streetviewImagePath = streetViewImagePath
)
