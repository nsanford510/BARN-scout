package com.vpt.scout

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vpt.scout.ui.screens.ArrivedDialog
import com.vpt.scout.ui.screens.ListDetailScreen
import com.vpt.scout.ui.screens.ListsScreen
import com.vpt.scout.ui.screens.LoginScreen
import com.vpt.scout.ui.screens.ScoutVerdict
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppWorkflowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeTestActivity>()

    @Before
    fun keepTestActivityVisible() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.keepScreenVisibleForTests()
        }
    }

    @Test
    fun loginScreen_submitsTrimmedCredentials() {
        var submitted: Pair<String, String>? = null

        composeRule.setTestContent {
            LoginScreen(
                initialEmail = "",
                isLoading = false,
                errorMessage = null,
                onLogin = { email, password -> submitted = email to password }
            )
        }

        composeRule.textField("Email").performTextInput("  scout@example.com  ")
        composeRule.textField("Password").performTextInput("secret")
        composeRule.onNodeWithText("Sign In").assertIsEnabled().performClick()

        assertEquals("scout@example.com" to "secret", submitted)
    }

    @Test
    fun listsScreen_createsNewListAndShowsIt() {
        val service = WorkflowScannerDataService(
            initialLists = mutableListOf(
                PropertyList(id = 1, name = "East Bay", description = null, propertyCount = 2)
            )
        )
        val repository = ListRepository(service)

        composeRule.setTestContent {
            ListsScreen(
                listRepository = repository,
                onNavigateToList = {}
            )
        }

        composeRule.waitUntilExists("East Bay")
        composeRule.onNodeWithContentDescription("Create List").performClick()
        composeRule.textField("Name").performTextInput("South Bay")
        composeRule.onNodeWithText("Create").performClick()

        composeRule.waitUntilExists("South Bay")
        assertEquals(listOf("East Bay", "South Bay"), service.currentListNames())
    }

    @Test
    fun listsScreen_deletesListAfterConfirmation() {
        val service = WorkflowScannerDataService(
            initialLists = mutableListOf(
                PropertyList(id = 1, name = "Remove Me", description = null, propertyCount = 1)
            )
        )
        val repository = ListRepository(service)

        composeRule.setTestContent {
            ListsScreen(
                listRepository = repository,
                onNavigateToList = {}
            )
        }

        composeRule.waitUntilExists("Remove Me")
        composeRule.onNodeWithContentDescription("Delete").performClick()
        composeRule.onNodeWithText("Delete").performClick()

        composeRule.waitUntil {
            service.currentListNames().isEmpty()
        }
        assertEquals(emptyList<String>(), service.currentListNames())
    }

    @Test
    fun listDetailScreen_removesPropertyAndInvokesScout() {
        val service = WorkflowScannerDataService(
            initialLists = mutableListOf(
                PropertyList(id = 7, name = "Priority", description = "targets", propertyCount = 1)
            ),
            listProperties = mutableMapOf(
                7L to mutableListOf(
                    Property(
                        apn = "001-100-100",
                        address = "123 Test St",
                        city = "OAKLAND",
                        latitude = 37.8,
                        longitude = -122.2,
                        hasVpt = true,
                        conditionScore = 5f,
                        isScouted = false,
                        streetviewImagePath = null
                    )
                )
            )
        )
        val repository = ListRepository(service)
        var scoutClicks = 0

        composeRule.setTestContent {
            ListDetailScreen(
                listId = 7L,
                listRepository = repository,
                onNavigateToScout = { scoutClicks += 1 },
                onBack = {}
            )
        }

        composeRule.waitUntilExists("123 Test St")
        composeRule.onNodeWithText("Scout", useUnmergedTree = true).performClick()
        composeRule.onNodeWithContentDescription("Remove").performClick()
        composeRule.waitUntil {
            service.listProperties.getValue(7L).isEmpty()
        }

        assertEquals(1, scoutClicks)
        assertEquals("001-100-100", service.removedApn)
    }

    @Test
    fun arrivedDialog_yesAndNoSubmitWithoutNotes() {
        val verdicts = mutableListOf<Pair<ScoutVerdict, String>>()

        composeRule.setTestContent {
            ArrivedDialog(
                property = arrivedProperty,
                submitting = false,
                error = null,
                onVerdict = { verdict, notes -> verdicts += verdict to notes },
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("123 Test St").assertIsDisplayed()
        composeRule.onNodeWithText("Yes").performClick()
        composeRule.onNodeWithText("No").performClick()

        assertEquals(listOf(ScoutVerdict.YES to "", ScoutVerdict.NO to ""), verdicts)
    }

    @Test
    fun arrivedDialog_maybeAsksForNotesBeforeSubmitting() {
        val verdicts = mutableListOf<Pair<ScoutVerdict, String>>()

        composeRule.setTestContent {
            ArrivedDialog(
                property = arrivedProperty,
                submitting = false,
                error = null,
                onVerdict = { verdict, notes -> verdicts += verdict to notes },
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("Maybe").performClick()
        composeRule.onNodeWithText("Submit").assertIsNotEnabled()
        assertEquals(emptyList<Pair<ScoutVerdict, String>>(), verdicts)

        composeRule.textField("Notes").performTextInput("gate locked")
        composeRule.onNodeWithText("Submit").assertIsEnabled().performClick()

        assertEquals(listOf(ScoutVerdict.MAYBE to "gate locked"), verdicts)
    }
}

private val arrivedProperty = Property(
    apn = "001-100-100",
    address = "123 Test St",
    city = "OAKLAND",
    latitude = 37.8,
    longitude = -122.2,
    hasVpt = true,
    conditionScore = 5f,
    isScouted = false,
    streetviewImagePath = null
)

private fun ComposeContentTestRule.setTestContent(content: @androidx.compose.runtime.Composable () -> Unit) {
    setContent {
        MaterialTheme {
            content()
        }
    }
    waitForIdle()
}

private fun ComposeContentTestRule.textField(label: String) =
    onNode(hasText(label) and hasSetTextAction())

private fun ComposeContentTestRule.waitUntilExists(text: String) {
    waitUntil(timeoutMillis = 5_000) {
        runCatching {
            onNodeWithText(text).fetchSemanticsNode()
            true
        }.getOrDefault(false)
    }
}

private class WorkflowScannerDataService(
    private val pagedProperties: List<PropertiesResponse> = listOf(
        PropertiesResponse(emptyList(), 0, 1, 50, 1)
    ),
    initialLists: MutableList<PropertyList> = mutableListOf(),
    val listProperties: MutableMap<Long, MutableList<Property>> = mutableMapOf()
) : ScannerDataService {
    private val lists = initialLists
    private var nextListId = (lists.maxOfOrNull { it.id } ?: 0L) + 1

    var lastAddedProperties: List<String>? = null
        private set
    var lastAddedListName: String? = null
        private set
    var removedApn: String? = null
        private set

    fun currentListNames(): List<String> = lists.map { it.name }

    override suspend fun getProperties(
        filters: PropertyFilters,
        page: Int,
        perPage: Int
    ): PropertiesResponse = pagedProperties.getOrElse(page - 1) {
        PropertiesResponse(emptyList(), 0, page, perPage, 1)
    }

    override suspend fun getMapPropertiesInBounds(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        limit: Int
    ): List<Property> = emptyList()

    override suspend fun getNextProperty(
        latitude: Double,
        longitude: Double,
        city: String?,
        vptOnly: Boolean,
        listId: Long?,
        conditionMin: Float?,
        conditionMax: Float?
    ): NextPropertyResponse = NextPropertyResponse(null, 0)

    override suspend fun getLists(): List<PropertyList> = lists.toList()

    override suspend fun createList(request: CreateListRequest): PropertyList {
        val list = PropertyList(
            id = nextListId++,
            name = request.name,
            description = request.description,
            propertyCount = 0,
            createdAt = null
        )
        lists += list
        lastAddedListName = list.name
        listProperties[list.id] = mutableListOf()
        return list
    }

    override suspend fun getList(listId: Long): ListWithProperties {
        val list = lists.first { it.id == listId }
        return ListWithProperties(
            id = list.id,
            name = list.name,
            description = list.description,
            createdAt = list.createdAt,
            properties = listProperties[listId].orEmpty().toList()
        )
    }

    override suspend fun deleteList(listId: Long) {
        lists.removeAll { it.id == listId }
        listProperties.remove(listId)
    }

    override suspend fun addPropertiesToList(listId: Long, request: AddPropertiesRequest) {
        lastAddedProperties = request.apns
        val current = listProperties.getOrPut(listId) { mutableListOf() }
        request.apns.forEach { apn ->
            current += Property(
                apn = apn,
                address = apn,
                city = "OAKLAND",
                latitude = 37.8,
                longitude = -122.2,
                hasVpt = false,
                conditionScore = null,
                isScouted = false,
                streetviewImagePath = null
            )
        }
    }

    override suspend fun removePropertyFromList(listId: Long, apn: String) {
        removedApn = apn
        listProperties[listId]?.removeAll { it.apn == apn }
    }

    override suspend fun reorderListProperties(listId: Long, apns: List<String>) {
        val existing = listProperties[listId].orEmpty().associateBy { it.apn }
        listProperties[listId] = apns.mapNotNull { existing[it] }.toMutableList()
    }

    override suspend fun getListRoute(listId: Long): RouteResponse =
        RouteResponse(url = "https://example.com", propertyCount = listProperties[listId]?.size ?: 0, optimized = true)

    override suspend fun submitScoutResult(request: ScoutResultRequest) {
    }

    override suspend fun getScoutResults(collectionId: Long?): List<ScoutResult> = emptyList()

    override suspend fun getScoutStats(): ScoutStats = ScoutStats(0, 0, 0, 0)
}
