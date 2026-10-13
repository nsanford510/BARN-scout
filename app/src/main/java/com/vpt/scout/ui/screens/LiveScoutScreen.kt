package com.vpt.scout.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.vpt.scout.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * State for live scout mode.
 */
enum class ScoutState {
    LOADING_LOCATION,
    FINDING_PROPERTY,
    READY_TO_NAVIGATE,
    NAVIGATING,
    QUESTIONNAIRE,
    SUBMITTING,
    COMPLETE
}

/**
 * Answer picked in the "I'm Here" dialog.
 */
enum class ScoutVerdict {
    YES,
    NO,
    MAYBE
}

/**
 * scout_results has no verdict column, so the answer rides on follow_up:
 * a "maybe" is a follow-up that carries notes.
 */
internal fun verdictFollowUp(verdict: ScoutVerdict): Boolean = verdict != ScoutVerdict.NO

internal fun verdictNotes(verdict: ScoutVerdict, notes: String): String? =
    if (verdict == ScoutVerdict.MAYBE) notes.trim().takeIf { it.isNotEmpty() } else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScoutScreen(
    propertyRepository: PropertyRepository,
    scoutRepository: ScoutRepository,
    city: String? = null,
    vptOnly: Boolean = false,
    listId: Long? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // State
    var scoutState by remember { mutableStateOf(ScoutState.LOADING_LOCATION) }
    var currentProperty by remember { mutableStateOf<Property?>(null) }
    var remainingCount by remember { mutableStateOf(0) }
    var userLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var scoutedCount by remember { mutableStateOf(0) }
    
    // Location permission
    val fusedLocationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }
    
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            // Re-trigger location fetch
            scoutState = ScoutState.LOADING_LOCATION
        } else {
            error = "Location permission required for scout mode"
        }
    }
    
    // Get current location
    suspend fun getCurrentLocation(): Pair<Double, Double>? {
        return try {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            if (!hasPermission) {
                locationPermissionLauncher.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
                return null
            }
            
            val location = fusedLocationClient.lastLocation.await()
            if (location != null) {
                Pair(location.latitude, location.longitude)
            } else {
                // Fallback to Oakland if no location
                Pair(37.8044, -122.2712)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to Oakland
            Pair(37.8044, -122.2712)
        }
    }
    
    // Find next property; returns true when a new one was loaded
    suspend fun findNextProperty(): Boolean {
        scoutState = ScoutState.FINDING_PROPERTY
        error = null
        
        val location = userLocation ?: getCurrentLocation()
        if (location == null) {
            error = "Could not get your location"
            return false
        }
        userLocation = location
        
        return try {
            val response = propertyRepository.getNextProperty(
                latitude = location.first,
                longitude = location.second,
                city = city,
                vptOnly = vptOnly,
                listId = listId
            )
            
            if (response.property != null) {
                currentProperty = response.property
                remainingCount = response.remaining
                scoutState = ScoutState.READY_TO_NAVIGATE
                true
            } else {
                scoutState = ScoutState.COMPLETE
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            error = e.message ?: "Failed to find property"
            scoutState = if (currentProperty != null) {
                ScoutState.READY_TO_NAVIGATE
            } else {
                ScoutState.FINDING_PROPERTY
            }
            false
        }
    }
    
    // Navigate to property
    fun navigateToProperty() {
        currentProperty?.let { property ->
            property.latitude?.let { lat ->
                property.longitude?.let { lng ->
                    val uri = Uri.parse("google.navigation:q=$lat,$lng")
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    intent.setPackage("com.google.android.apps.maps")
                    try {
                        context.startActivity(intent)
                        scoutState = ScoutState.NAVIGATING
                    } catch (e: ActivityNotFoundException) {
                        error = "Google Maps is not installed"
                    }
                }
            }
        }
    }
    
    // Mark arrived (show yes/no/maybe dialog)
    fun onArrived() {
        submitError = null
        scoutState = ScoutState.QUESTIONNAIRE
    }
    
    // Save the verdict, then head straight to the next property
    fun submitVerdict(verdict: ScoutVerdict, notes: String) {
        scope.launch {
            scoutState = ScoutState.SUBMITTING
            submitError = null
            try {
                currentProperty?.let { property ->
                    scoutRepository.submitScoutResult(
                        apn = property.apn,
                        followUp = verdictFollowUp(verdict),
                        flyered = false,
                        notes = verdictNotes(verdict, notes),
                        latitude = userLocation?.first,
                        longitude = userLocation?.second
                    )
                    scoutedCount++
                }
            } catch (e: Exception) {
                e.printStackTrace()
                submitError = e.message ?: "Failed to submit"
                scoutState = ScoutState.QUESTIONNAIRE
                return@launch
            }
            // Get fresh location and find next
            userLocation = getCurrentLocation()
            if (findNextProperty()) {
                navigateToProperty()
            }
        }
    }
    
    // Initial load
    LaunchedEffect(Unit) {
        userLocation = getCurrentLocation()
        if (userLocation != null) {
            findNextProperty()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scout Mode") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Stats chip
                    if (scoutedCount > 0) {
                        AssistChip(
                            onClick = {},
                            label = { Text("$scoutedCount scouted") },
                            leadingIcon = { Icon(Icons.Default.CheckCircle, null) }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Error display
            error?.let { errMsg ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        errMsg,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                if (currentProperty == null && scoutState != ScoutState.SUBMITTING) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch { findNextProperty() }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Retry")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Filters display
            if (city != null || vptOnly || listId != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Active filters:", fontWeight = FontWeight.Medium)
                        city?.let { Text(it) }
                        if (vptOnly) Text("Favorites")
                        listId?.let { Text("List #$it") }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Main content based on state
            when (scoutState) {
                ScoutState.LOADING_LOCATION -> {
                    LoadingContent("Getting your location...")
                }
                
                ScoutState.FINDING_PROPERTY -> {
                    LoadingContent("Finding nearest property...")
                }
                
                ScoutState.READY_TO_NAVIGATE, ScoutState.NAVIGATING,
                ScoutState.QUESTIONNAIRE, ScoutState.SUBMITTING -> {
                    currentProperty?.let { property ->
                        PropertyCard(
                            property = property,
                            remaining = remainingCount,
                            onNavigate = { navigateToProperty() },
                            onArrived = { onArrived() },
                            onSkip = {
                                scope.launch { findNextProperty() }
                            }
                        )
                        
                        if (scoutState == ScoutState.QUESTIONNAIRE ||
                            scoutState == ScoutState.SUBMITTING
                        ) {
                            ArrivedDialog(
                                property = property,
                                submitting = scoutState == ScoutState.SUBMITTING,
                                error = submitError,
                                onVerdict = { verdict, notes -> submitVerdict(verdict, notes) },
                                onDismiss = { scoutState = ScoutState.READY_TO_NAVIGATE }
                            )
                        }
                    }
                }
                
                ScoutState.COMPLETE -> {
                    CompleteCard(
                        scoutedCount = scoutedCount,
                        onDone = onBack
                    )
                }
            }
        }
    }
}

@Composable
internal fun LoadingContent(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(message)
    }
}

@Composable
private fun PropertyCard(
    property: Property,
    remaining: Int,
    onNavigate: () -> Unit,
    onArrived: () -> Unit,
    onSkip: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Property info
            Text(
                property.address ?: property.apn,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                property.city?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                if (property.hasVpt) {
                    Badge(containerColor = Color(0xFFE53935)) {
                        Text("VPT")
                    }
                }
                property.conditionScore?.let { score ->
                    val color = when {
                        score >= 7 -> Color(0xFF4CAF50)
                        score >= 4 -> Color(0xFFFFC107)
                        else -> Color(0xFFE53935)
                    }
                    Badge(containerColor = color) {
                        Text("${"%.0f".format(score)}/10")
                    }
                }
            }
            
            // Distance
            property.latitude?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Distance: nearby",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Remaining count
            Text(
                "$remaining more properties after this",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSkip,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.SkipNext, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Skip")
                }
                
                Button(
                    onClick = onNavigate,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Navigation, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Navigate")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onArrived,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(Icons.Default.CheckCircle, null)
                Spacer(Modifier.width(4.dp))
                Text("I'm Here")
            }
        }
    }
}

@Composable
internal fun ArrivedDialog(
    property: Property,
    submitting: Boolean,
    error: String?,
    onVerdict: (verdict: ScoutVerdict, notes: String) -> Unit,
    onDismiss: () -> Unit
) {
    var askingNotes by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    val notesFocus = remember { FocusRequester() }
    
    AlertDialog(
        onDismissRequest = {
            when {
                submitting -> Unit
                askingNotes -> askingNotes = false
                else -> onDismiss()
            }
        },
        // Don't lose typed notes to a stray tap outside the dialog
        properties = DialogProperties(dismissOnClickOutside = !askingNotes),
        title = { Text(property.address ?: property.apn) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (askingNotes) {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(notesFocus),
                        minLines = 3,
                        enabled = !submitting
                    )
                    LaunchedEffect(Unit) {
                        notesFocus.requestFocus()
                    }
                } else {
                    Text(
                        "Follow up on this property?",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    VerdictButton("Yes", Icons.Default.Check, !submitting) {
                        onVerdict(ScoutVerdict.YES, "")
                    }
                    VerdictButton("No", Icons.Default.Close, !submitting) {
                        onVerdict(ScoutVerdict.NO, "")
                    }
                    VerdictButton("Maybe", Icons.Default.QuestionMark, !submitting) {
                        askingNotes = true
                    }
                }
                
                if (submitting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (askingNotes) {
                Button(
                    onClick = { onVerdict(ScoutVerdict.MAYBE, notes) },
                    enabled = !submitting && notes.isNotBlank()
                ) {
                    Text("Submit")
                }
            }
        },
        dismissButton = {
            if (askingNotes) {
                TextButton(onClick = { askingNotes = false }, enabled = !submitting) {
                    Text("Back")
                }
            } else {
                TextButton(onClick = onDismiss, enabled = !submitting) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun VerdictButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = enabled
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScoutResultForm(
    property: Property?,
    submitButtonText: String,
    enabled: Boolean,
    onSubmit: (followUp: Boolean, flyered: Boolean, notes: String) -> Unit
) {
    var followUp by remember { mutableStateOf(false) }
    var flyered by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Property Report",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            property?.let {
                Text(
                    it.address ?: it.apn,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Follow Up question
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Needs follow-up?",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = followUp,
                    onCheckedChange = { followUp = it },
                    enabled = enabled
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Flyered question
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Left flyer?",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = flyered,
                    onCheckedChange = { flyered = it },
                    enabled = enabled
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Notes
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                enabled = enabled
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = { onSubmit(followUp, flyered, notes) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled
            ) {
                Icon(Icons.Default.Send, null)
                Spacer(Modifier.width(8.dp))
                Text(submitButtonText)
            }
        }
    }
}

@Composable
private fun CompleteCard(
    scoutedCount: Int,
    onDone: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CheckCircle,
                null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                "All Done!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                "You've scouted $scoutedCount properties",
                style = MaterialTheme.typography.bodyLarge
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(onClick = onDone) {
                Text("Done")
            }
        }
    }
}
