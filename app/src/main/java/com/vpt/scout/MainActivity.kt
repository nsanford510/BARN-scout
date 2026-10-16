package com.vpt.scout

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vpt.scout.proximity.ProximityNotificationManager
import com.vpt.scout.ui.screens.*
import com.vpt.scout.ui.theme.ScoutAppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var pendingRoute by mutableStateOf<String?>(null)
    private var onRuntimePermissionsResult: ((Boolean) -> Unit)? = null

    private val runtimePermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = areInitialProximityPermissionsGranted(
            permissions = permissions,
            sdkInt = Build.VERSION.SDK_INT
        )
        onRuntimePermissionsResult?.invoke(granted)
        onRuntimePermissionsResult = null
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRoute = extractRequestedRoute(intent)

        val container = (application as ScoutApplication).container
        
        setContent {
            ScoutAppTheme {
                ScoutApp(
                    container = container,
                    pendingRoute = pendingRoute,
                    onRouteConsumed = { pendingRoute = null },
                    requestProximityPermissions = ::requestProximityPermissions
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = extractRequestedRoute(intent)
    }

    private fun requestProximityPermissions(onResult: (Boolean) -> Unit) {
        onRuntimePermissionsResult = onResult
        val permissions = buildInitialProximityPermissionsRequest(Build.VERSION.SDK_INT)
        runtimePermissionRequest.launch(permissions)
    }
}

internal fun buildInitialProximityPermissionsRequest(sdkInt: Int): Array<String> {
    return buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
}

internal fun areInitialProximityPermissionsGranted(
    permissions: Map<String, Boolean>,
    sdkInt: Int
): Boolean {
    val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
    val coarseLocation = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
    val notificationsGranted = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
    } else {
        true
    }
    return (fineLocation || coarseLocation) && notificationsGranted
}

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Properties : Screen("list", "List", Icons.Default.ViewList)
    object Map : Screen("map", "Map", Icons.Default.Map)
    object Gallery : Screen("gallery", "Gallery", Icons.Default.PhotoLibrary)
    object Route : Screen("route", "Route", Icons.Default.Route)
    object Stats : Screen("stats", "Stats", Icons.Default.BarChart)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoutApp(
    container: AppContainer,
    pendingRoute: String? = null,
    onRouteConsumed: () -> Unit = {},
    requestProximityPermissions: ((Boolean) -> Unit) -> Unit = {}
) {
    val authState by container.authManager.state.collectAsState()
    val loginScope = rememberCoroutineScope()
    var loginError by remember { mutableStateOf<String?>(null) }
    var loginLoading by remember { mutableStateOf(false) }
    var loginInfo by remember { mutableStateOf<String?>(null) }
    var lastEmail by remember { mutableStateOf(authState.email ?: "") }

    // A stored session may belong to someone whose access was revoked since last launch.
    LaunchedEffect(authState.userId) {
        if (authState.isAuthenticated &&
            container.authManager.verifyAccess() == AccessCheck.NOT_APPROVED
        ) {
            loginError = SupabaseAuthManager.NOT_APPROVED_MESSAGE
        }
    }

    if (!authState.isAuthenticated) {
        LoginScreen(
            initialEmail = lastEmail,
            isLoading = loginLoading,
            errorMessage = loginError,
            infoMessage = loginInfo,
            onLogin = { email, password ->
                lastEmail = email
                loginScope.launch {
                    loginLoading = true
                    loginError = null
                    loginInfo = null
                    val result = container.authManager.signIn(email, password)
                    if (result.isFailure) {
                        loginError = result.exceptionOrNull()?.message ?: "Login failed."
                    }
                    loginLoading = false
                }
            },
            onForgotPassword = { email ->
                lastEmail = email
                loginError = null
                loginInfo = null
                if (email.isBlank()) {
                    loginError = "Enter your email first, then tap \"Forgot password?\"."
                } else {
                    loginScope.launch {
                        loginLoading = true
                        val result = container.authManager.requestPasswordReset(email)
                        if (result.isSuccess) {
                            loginInfo = "If that email has an account, a reset link is on its way."
                        } else {
                            loginError = result.exceptionOrNull()?.message ?: "Could not send reset email."
                        }
                        loginLoading = false
                    }
                }
            }
        )
        return
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    LaunchedEffect(authState.isAuthenticated, pendingRoute) {
        if (authState.isAuthenticated && !pendingRoute.isNullOrBlank()) {
            navController.navigate(pendingRoute) {
                launchSingleTop = true
            }
            onRouteConsumed()
        }
    }
    
    val bottomNavScreens = listOf(Screen.Properties, Screen.Map, Screen.Gallery, Screen.Route, Screen.Stats)
    // Shared so the List and Gallery tabs show the same search results.
    val exploreFilters = remember { ExploreFilters() }
    // Show bottom bar for main tabs only (not for detail screens)
    val showBottomBar = currentRoute in bottomNavScreens.map { it.route }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavScreens.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = currentRoute == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId)
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Properties.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            listOf(Screen.Properties to ExploreMode.LIST, Screen.Gallery to ExploreMode.GALLERY).forEach { (screen, mode) ->
                composable(screen.route) {
                    PropertiesScreen(
                        mode = mode,
                        filters = exploreFilters,
                        propertyRepository = container.propertyRepository,
                        listRepository = container.listRepository,
                        proximityAlertPreferences = container.proximityAlertPreferences,
                        requestProximityPermissions = requestProximityPermissions,
                        onNavigateToScout = { city, vptOnly, listId ->
                            navController.navigate(
                                "scout?city=${city ?: ""}&vpt=${if (vptOnly) "1" else "0"}&listId=${listId ?: ""}"
                            )
                        }
                    )
                }
            }
            
            composable(Screen.Route.route) {
                ListsScreen(
                    listRepository = container.listRepository,
                    onNavigateToList = { listId ->
                        navController.navigate("list/$listId")
                    }
                )
            }
            
            // Map Screen
            composable(Screen.Map.route) {
                MapScreen(
                    propertyRepository = container.propertyRepository,
                    listRepository = container.listRepository
                )
            }
            
            // Stats Screen
            composable(Screen.Stats.route) {
                StatsScreen(
                    scoutRepository = container.scoutRepository,
                    signedInEmail = authState.email,
                    onSignOut = {
                        loginError = null
                        loginInfo = null
                        loginScope.launch { container.authManager.logout() }
                    }
                )
            }
            
            // Live Scout Mode Screen
            composable(
                route = "scout?city={city}&vpt={vpt}&listId={listId}",
                arguments = listOf(
                    navArgument("city") { type = NavType.StringType; defaultValue = "" },
                    navArgument("vpt") { type = NavType.StringType; defaultValue = "0" },
                    navArgument("listId") { type = NavType.StringType; defaultValue = "" }
                )
            ) { backStackEntry ->
                val city = backStackEntry.arguments?.getString("city")?.takeIf { it.isNotBlank() }
                val vptOnly = backStackEntry.arguments?.getString("vpt") == "1"
                val listId = backStackEntry.arguments?.getString("listId")?.toLongOrNull()
                
                LiveScoutScreen(
                    propertyRepository = container.propertyRepository,
                    scoutRepository = container.scoutRepository,
                    city = city,
                    vptOnly = vptOnly,
                    listId = listId,
                    onBack = { navController.popBackStack() }
                )
            }
            
            // List Detail Screen
            composable(
                route = "list/{listId}",
                arguments = listOf(navArgument("listId") { type = NavType.LongType })
            ) { backStackEntry ->
                val listId = backStackEntry.arguments?.getLong("listId") ?: return@composable
                ListDetailScreen(
                    listId = listId,
                    listRepository = container.listRepository,
                    onNavigateToScout = {
                        navController.navigate("scout?city=&vpt=0&listId=$listId")
                    },
                    onNavigateToCardSwipe = {
                        navController.navigate("cardSwipe/$listId")
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            // Card Swipe Carousel Screen
            composable(
                route = "cardSwipe/{listId}",
                arguments = listOf(navArgument("listId") { type = NavType.LongType })
            ) { backStackEntry ->
                val listId = backStackEntry.arguments?.getLong("listId") ?: return@composable
                CardSwipeScreen(
                    listId = listId,
                    listRepository = container.listRepository,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = "alerted-scout/{apn}",
                arguments = listOf(navArgument("apn") { type = NavType.StringType })
            ) { backStackEntry ->
                val apn = backStackEntry.arguments?.getString("apn") ?: return@composable
                AlertedPropertyScoutScreen(
                    apn = apn,
                    proximityRepository = container.proximityAlertRepository,
                    scoutRepository = container.scoutRepository,
                    onBack = { navController.popBackStack() }
                )
            }
            
        }
    }
}

fun extractRequestedRoute(intent: Intent?): String? {
    return intent?.getStringExtra(ProximityNotificationManager.EXTRA_ROUTE)
}
