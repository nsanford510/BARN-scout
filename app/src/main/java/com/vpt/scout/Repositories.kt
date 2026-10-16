package com.vpt.scout

import com.vpt.scout.data.local.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import java.time.Instant

/**
 * Repository for managing properties with Supabase as source of truth.
 */
class PropertyRepository(
    private val propertyDao: PropertyDao,
    private val service: ScannerDataService
) {
    
    private val _propertiesState = MutableStateFlow<PropertiesState>(PropertiesState.Loading)
    val propertiesState: StateFlow<PropertiesState> = _propertiesState
    
    /**
     * Load properties with filters from Flask API.
     */
    suspend fun loadProperties(
        page: Int = 1,
        perPage: Int = 50,
        city: String? = null,
        query: String? = null,
        zip: String? = null,
        power: String? = null,
        favoritesOnly: Boolean = false,
        vptOnly: Boolean = false,
        delinquentOnly: Boolean = false,
        condition: String? = null,
        outOfStateOnly: Boolean = false,
        research: String? = null,
        ownerName: String? = null,
        aiVerdict: String? = null,
        aiConfidenceMin: String? = null,
        siteVacant: String? = null,
        sort: String = "location_of_property",
        order: String = "asc",
        scouted: Boolean? = null,
        listId: Long? = null
    ): PropertiesResponse {
        val response = service.getProperties(
            filters = PropertyFilters(
                city = city,
                query = query,
                zip = zip,
                power = power,
                favoritesOnly = favoritesOnly,
                vptOnly = vptOnly,
                delinquentOnly = delinquentOnly,
                condition = condition,
                outOfStateOnly = outOfStateOnly,
                research = research,
                ownerName = ownerName,
                aiVerdict = aiVerdict,
                aiConfidenceMin = aiConfidenceMin,
                siteVacant = siteVacant,
                sort = sort,
                order = order,
                scouted = scouted,
                listId = listId
            ),
            page = page,
            perPage = perPage
        )
        _propertiesState.value = PropertiesState.Success(response)
        return response
    }
    
    /**
     * Get next property for scout mode.
     */
    suspend fun getNextProperty(
        latitude: Double,
        longitude: Double,
        city: String? = null,
        vptOnly: Boolean = false,
        listId: Long? = null
    ): NextPropertyResponse {
        return service.getNextProperty(
            latitude = latitude,
            longitude = longitude,
            city = city,
            vptOnly = vptOnly,
            listId = listId
        )
    }

    suspend fun getPropertyByApn(apn: String): Property? {
        return loadProperties(
            page = 1,
            perPage = 1,
            query = apn
        ).properties.firstOrNull { it.apn == apn }
    }
    
    /**
     * Refresh map markers for the currently visible map bounds only.
     */
    suspend fun refreshMarkersInBounds(
        south: Double,
        west: Double,
        north: Double,
        east: Double
    ) {
        try {
            val boundedProperties = service.getMapPropertiesInBounds(
                south = south,
                west = west,
                north = north,
                east = east
            )
            val entities = boundedProperties.mapNotNull { property ->
                val latitude = property.latitude ?: return@mapNotNull null
                val longitude = property.longitude ?: return@mapNotNull null
                PropertyEntity(
                    apn = property.apn,
                    address = property.address ?: "Unknown",
                    longitude = longitude,
                    latitude = latitude,
                    hasVpt = property.hasVpt,
                    conditionScore = property.conditionScore,
                    city = property.city,
                    streetViewImagePath = property.streetviewImagePath,
                    updatedAt = Instant.now()
                )
            }
            propertyDao.deleteAll()
            if (entities.isNotEmpty()) {
                propertyDao.insertAll(entities)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Get cached markers as Flow for map display.
     */
    val cachedMarkers: Flow<List<PropertyEntity>> = propertyDao.getAllProperties()
    
    /**
     * Get VPT-only properties from cache.
     */
    val cachedVptProperties: Flow<List<PropertyEntity>> = propertyDao.getVptProperties()
    
    /**
     * Load all APNs matching the given filters (for select all functionality).
     * Fetches all pages and collects all APNs.
     */
    suspend fun loadAllPropertyApns(
        city: String? = null,
        query: String? = null,
        zip: String? = null,
        power: String? = null,
        favoritesOnly: Boolean = false,
        vptOnly: Boolean = false,
        delinquentOnly: Boolean = false,
        condition: String? = null,
        outOfStateOnly: Boolean = false,
        research: String? = null,
        ownerName: String? = null,
        aiVerdict: String? = null,
        aiConfidenceMin: String? = null,
        siteVacant: String? = null,
        sort: String = "location_of_property",
        order: String = "asc",
        scouted: Boolean? = null,
        listId: Long? = null
    ): Set<String> {
        val allApns = mutableSetOf<String>()
        var page = 1
        var totalPages: Int
        
        do {
            val response = loadProperties(
                page = page,
                perPage = 500, // Fetch in larger batches for efficiency
                city = city,
                query = query,
                zip = zip,
                power = power,
                favoritesOnly = favoritesOnly,
                vptOnly = vptOnly,
                delinquentOnly = delinquentOnly,
                condition = condition,
                outOfStateOnly = outOfStateOnly,
                research = research,
                ownerName = ownerName,
                aiVerdict = aiVerdict,
                aiConfidenceMin = aiConfidenceMin,
                siteVacant = siteVacant,
                sort = sort,
                order = order,
                scouted = scouted,
                listId = listId
            )
            allApns.addAll(response.properties.map { it.apn })
            totalPages = response.totalPages
            page++
        } while (page <= totalPages)
        
        return allApns
    }
}

data class PropertyFilters(
    val city: String? = null,
    val query: String? = null,
    val zip: String? = null,
    val power: String? = null,
    val favoritesOnly: Boolean = false,
    val vptOnly: Boolean = false,
    val delinquentOnly: Boolean = false,
    val condition: String? = null,
    val outOfStateOnly: Boolean = false,
    val research: String? = null,
    val ownerName: String? = null,
    val aiVerdict: String? = null,
    val aiConfidenceMin: String? = null,
    val siteVacant: String? = null,
    val sort: String = "location_of_property",
    val order: String = "asc",
    val scouted: Boolean? = null,
    val listId: Long? = null
)

/**
 * State for properties loading.
 */
sealed class PropertiesState {
    object Loading : PropertiesState()
    data class Success(val response: PropertiesResponse) : PropertiesState()
    data class Error(val message: String) : PropertiesState()
}

/**
 * Repository for managing lists (collections) via Flask API.
 */
class ListRepository(
    private val service: ScannerDataService
) {
    
    private val _listsState = MutableStateFlow<ListsState>(ListsState.Loading)
    val listsState: StateFlow<ListsState> = _listsState
    
    /**
     * Refresh lists from Flask API.
     */
    suspend fun refreshLists(): List<PropertyList> {
        val lists = service.getLists()
        _listsState.value = ListsState.Success(lists)
        return lists
    }
    
    /**
     * Create a new list.
     */
    suspend fun createList(name: String, description: String? = null): PropertyList {
        val newList = service.createList(CreateListRequest(name, description))
        refreshLists()
        return newList
    }
    
    /**
     * Delete a list.
     */
    suspend fun deleteList(listId: Long) {
        service.deleteList(listId)
        refreshLists()
    }
    
    /**
     * Get list with properties.
     */
    suspend fun getList(listId: Long): ListWithProperties {
        return service.getList(listId)
    }
    
    /**
     * Add properties to a list.
     */
    suspend fun addPropertiesToList(listId: Long, apns: List<String>) {
        service.addPropertiesToList(listId, AddPropertiesRequest(apns))
    }
    
    /**
     * Remove property from a list.
     */
    suspend fun removePropertyFromList(listId: Long, apn: String) {
        service.removePropertyFromList(listId, apn)
    }

    suspend fun reorderListProperties(listId: Long, apns: List<String>) {
        service.reorderListProperties(listId, apns)
    }
    
    /**
     * Get route URL for a list.
     */
    suspend fun getRouteUrl(listId: Long): RouteResponse {
        return service.getListRoute(listId)
    }
}

/**
 * State for lists loading.
 */
sealed class ListsState {
    object Loading : ListsState()
    data class Success(val lists: List<PropertyList>) : ListsState()
    data class Error(val message: String) : ListsState()
}

/**
 * Repository for scout mode operations.
 */
class ScoutRepository(
    private val service: ScannerDataService
) {
    
    /**
     * Submit a scout result (saves locally and syncs to server).
     */
    suspend fun submitScoutResult(
        apn: String,
        followUp: Boolean,
        flyered: Boolean,
        notes: String?,
        latitude: Double?,
        longitude: Double?
    ): Long {
        service.submitScoutResult(
            ScoutResultRequest(
                apn = apn,
                followUp = followUp,
                flyered = flyered,
                notes = notes,
                latitude = latitude,
                longitude = longitude
            )
        )
        return Instant.now().toEpochMilli()
    }
    
    /**
     * Sync pending results to server.
     */
    suspend fun syncPendingResults() {
        return
    }
    
    /**
     * Get scout statistics from server.
     */
    suspend fun getStats(): ScoutStats {
        return service.getScoutStats()
    }
    
    /**
     * Get local scout results as Flow.
     */
    fun getLocalResults(): Flow<List<ScoutResultEntity>> {
        return flow { emit(emptyList()) }
    }
}

// Keep old CollectionRepository for backward compatibility during migration
// This can be removed once fully migrated to ListRepository
class CollectionRepository(
    private val collectionDao: CollectionDao,
    private val scoutResultDao: ScoutResultDao,
    private val service: ScannerDataService
) {
    // Delegate to ListRepository for API operations
    private val listRepo = ListRepository(service)
    
    val allCollections: Flow<List<PropertyList>> = flow {
        emit(listRepo.refreshLists())
    }
    
    suspend fun createCollection(name: String, description: String? = null): Long {
        val list = listRepo.createList(name, description)
        return list.id
    }
    
    suspend fun deleteCollection(collectionId: Long) {
        listRepo.deleteList(collectionId)
    }
    
    suspend fun getPropertiesInCollection(collectionId: Long): List<Property> {
        val list = listRepo.getList(collectionId)
        return list.properties
    }
    
    suspend fun addPropertyToCollection(collectionId: Long, apn: String) {
        listRepo.addPropertiesToList(collectionId, listOf(apn))
    }
    
    suspend fun removePropertyFromCollection(collectionId: Long, apn: String) {
        listRepo.removePropertyFromList(collectionId, apn)
    }
    
    // Scout results - delegate to ScoutRepository
    private val scoutRepo = ScoutRepository(service)
    
    suspend fun saveScoutResult(
        apn: String,
        followUp: Boolean,
        flyered: Boolean,
        notes: String?,
        latitude: Double?,
        longitude: Double?
    ): Long {
        return scoutRepo.submitScoutResult(apn, followUp, flyered, notes, latitude, longitude)
    }
    
    suspend fun syncPendingResults() {
        scoutRepo.syncPendingResults()
    }
}

internal fun mapPropertiesResponseToEntities(response: PropertiesResponse): List<PropertyEntity> {
    return response.properties.mapNotNull { property ->
        val latitude = property.latitude ?: return@mapNotNull null
        val longitude = property.longitude ?: return@mapNotNull null

        PropertyEntity(
            apn = property.apn,
            address = property.address ?: "Unknown",
            longitude = longitude,
            latitude = latitude,
            hasVpt = property.hasVpt,
            conditionScore = property.conditionScore,
            city = property.city,
            streetViewImagePath = property.streetviewImagePath,
            updatedAt = Instant.now()
        )
    }
}

internal suspend fun collectAllFilteredMapMarkerEntities(
    filters: PropertyFilters,
    fetchPage: suspend (filters: PropertyFilters, page: Int, perPage: Int) -> PropertiesResponse,
    perPage: Int = 200,
    onPageCollected: suspend (List<PropertyEntity>) -> Unit = {}
): List<PropertyEntity> {
    val entities = mutableListOf<PropertyEntity>()
    var page = 1
    var totalPages: Int

    do {
        val response = fetchPage(filters, page, perPage)
        val pageEntities = mapPropertiesResponseToEntities(response)
        entities += pageEntities
        onPageCollected(pageEntities)
        totalPages = response.totalPages
        page += 1
    } while (page <= totalPages)

    return entities
}
