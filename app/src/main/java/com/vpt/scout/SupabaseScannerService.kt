package com.vpt.scout

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

internal const val MAP_MARKER_COLUMNS =
    "apn,location,city,situs_zip,zip_code,lat,lng,has_vpt,delinquent,power_status,condition_score," +
        "streetview_image_path,research_status,owner_name,deceased_count,last_sale_date,tax_year," +
        "last_payment,mailing_address,is_out_of_state,is_favorite,is_scouted," +
        "ai_vacancy_verdict,ai_vacancy_confidence,site_vacant"

/** App sort keys (bills column names) -> map_markers columns. */
private val SORT_COLUMNS = mapOf(
    "location_of_property" to "location",
    "apn" to "apn",
    "city" to "city",
    "added_at" to "added_at",
    "condition_score" to "condition_score",
    "has_vpt" to "has_vpt",
    "delinquent" to "delinquent",
    "power_status" to "power_status",
    "last_payment" to "last_payment",
    "tax_year" to "tax_year",
    "situs_zip" to "situs_zip",
    "deceased_count" to "deceased_count",
    "outreach_score" to "outreach_score",
    "ai_vacancy_confidence" to "ai_vacancy_confidence"
)

private val AI_VERDICTS = setOf("likely_vacant", "likely_occupied", "unknown")

/** Minimum AI vacancy confidence as a 0-1 fraction; accepts 0-1 or a 0-100 percent. */
internal fun parseConfidenceMin(value: String?): Double? {
    var n = value?.trim()?.toDoubleOrNull() ?: return null
    if (n > 1) n /= 100
    return n.takeIf { it > 0 && it <= 1 }
}

interface ScannerDataService {
    suspend fun getProperties(filters: PropertyFilters, page: Int = 1, perPage: Int = 50): PropertiesResponse
    suspend fun getMapPropertiesInBounds(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        limit: Int = 1000
    ): List<Property>
    suspend fun getNextProperty(
        latitude: Double,
        longitude: Double,
        city: String? = null,
        vptOnly: Boolean = false,
        listId: Long? = null,
        conditionMin: Float? = null,
        conditionMax: Float? = null
    ): NextPropertyResponse
    suspend fun getLists(): List<PropertyList>
    suspend fun createList(request: CreateListRequest): PropertyList
    suspend fun getList(listId: Long): ListWithProperties
    suspend fun deleteList(listId: Long)
    suspend fun addPropertiesToList(listId: Long, request: AddPropertiesRequest)
    suspend fun reorderListProperties(listId: Long, apns: List<String>)
    suspend fun removePropertyFromList(listId: Long, apn: String)
    suspend fun getListRoute(listId: Long): RouteResponse
    suspend fun submitScoutResult(request: ScoutResultRequest)
    suspend fun getScoutResults(collectionId: Long? = null): List<ScoutResult>
    suspend fun getScoutStats(): ScoutStats
}

class SupabaseScannerService(
    private val baseUrl: String,
    private val anonKey: String,
    private val accessTokenProvider: () -> String?,
    private val authManager: SupabaseAuthManager? = null,
    private val client: OkHttpClient = buildClient(accessTokenProvider, authManager)
) : ScannerDataService {

    override suspend fun getMapPropertiesInBounds(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        limit: Int
    ): List<Property> = withContext(Dispatchers.IO) {
        val url = mapMarkersUrl().apply {
            addQueryParameter("select", MAP_MARKER_COLUMNS)
            addQueryParameter("lat", "gte.$south")
            addQueryParameter("lat", "lte.$north")
            if (west <= east) {
                addQueryParameter("lng", "gte.$west")
                addQueryParameter("lng", "lte.$east")
            } else {
                // Viewport straddles the antimeridian.
                addQueryParameter("or", "(lng.gte.$west,lng.lte.$east)")
            }
            addQueryParameter("limit", limit.coerceIn(1, 5000).toString())
        }.build()

        val rows = executeJsonArrayRequest(
            Request.Builder().url(url).applySupabaseHeaders().get().build()
        )
        rows.map { it.asJsonObject.toProperty() }
    }

    override suspend fun getNextProperty(
        latitude: Double,
        longitude: Double,
        city: String?,
        vptOnly: Boolean,
        listId: Long?,
        conditionMin: Float?,
        conditionMax: Float?
    ): NextPropertyResponse = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("p_lat", latitude)
            addProperty("p_lng", longitude)
            city?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { addProperty("p_city", it) }
            addProperty("p_vpt_only", vptOnly)
            listId?.let { addProperty("p_list_id", it) }
            conditionMin?.let { addProperty("p_condition_min", it) }
            conditionMax?.let { addProperty("p_condition_max", it) }
            addProperty("p_limit", 1)
        }

        val rows = executeJsonArrayRequest(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/rest/v1/rpc/scout_next")
                .applySupabaseHeaders()
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
        val first = rows.firstOrNull()?.asJsonObject
        NextPropertyResponse(
            property = first?.toProperty(),
            remaining = first?.get("remaining")?.takeUnless { it.isJsonNull }?.asInt ?: 0
        )
    }

    override suspend fun getProperties(
        filters: PropertyFilters,
        page: Int,
        perPage: Int
    ): PropertiesResponse = withContext(Dispatchers.IO) {
        val listApns = filters.listId?.let { getListApns(it) }
        if (listApns != null && listApns.isEmpty()) {
            return@withContext PropertiesResponse(emptyList(), 0, page, perPage, 1)
        }

        val safePerPage = perPage.coerceAtLeast(1)
        val url = mapMarkersUrl().apply {
            addQueryParameter("select", MAP_MARKER_COLUMNS)
            applyPropertyFilters(filters)
            listApns?.let { addQueryParameter("apn", "in.(${it.toPostgrestList()})") }
            val sortColumn = SORT_COLUMNS[filters.sort] ?: "location"
            val direction = if (filters.order.equals("desc", ignoreCase = true)) "desc" else "asc"
            addQueryParameter("order", "$sortColumn.$direction.nullslast,apn.asc")
            addQueryParameter("offset", (((page - 1).coerceAtLeast(0)) * safePerPage).toString())
            addQueryParameter("limit", safePerPage.toString())
        }.build()

        client.newCall(
            Request.Builder()
                .url(url)
                .applySupabaseHeaders()
                .addHeader("Prefer", "count=exact")
                .get()
                .build()
        ).execute().use { response ->
            response.requireSuccess()
            val rawBody = response.body?.string().orEmpty()
            val rows = if (rawBody.isBlank()) JsonArray() else JsonParser().parse(rawBody).asJsonArray
            val properties = rows.map { it.asJsonObject.toProperty() }
            val total = parseContentRangeTotal(response.header("Content-Range")) ?: properties.size
            PropertiesResponse(
                properties = properties,
                total = total,
                page = page,
                perPage = perPage,
                totalPages = ((total + safePerPage - 1) / safePerPage).coerceAtLeast(1)
            )
        }
    }

    override suspend fun getLists(): List<PropertyList> = withContext(Dispatchers.IO) {
        val listRows = executeJsonArrayRequest(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/rest/v1/lists?select=id,name,description,created_at&order=name.asc")
                .applySupabaseHeaders()
                .get()
                .build()
        )

        buildList {
            for (index in 0 until listRows.size()) {
                val row = listRows.get(index).asJsonObject
                val listId = row.get("id").asLong
                val count = getListPropertyCount(listId)
                add(
                    PropertyList(
                        id = listId,
                        name = row.get("name")?.asString.orEmpty(),
                        description = row.get("description")?.takeUnless { it.isJsonNull }?.asString,
                        propertyCount = count,
                        createdAt = row.get("created_at")?.takeUnless { it.isJsonNull }?.asString
                    )
                )
            }
        }
    }

    override suspend fun createList(request: CreateListRequest): PropertyList = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("name", request.name)
            if (!request.description.isNullOrBlank()) {
                addProperty("description", request.description)
            }
        }
        val row = executeJsonObjectRequest(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/rest/v1/lists")
                .applySupabaseHeaders(returnRepresentation = true)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
        PropertyList(
            id = row.get("id").asLong,
            name = row.get("name").asString,
            description = row.get("description")?.takeUnless { it.isJsonNull }?.asString,
            propertyCount = 0,
            createdAt = row.get("created_at")?.takeUnless { it.isJsonNull }?.asString
        )
    }

    override suspend fun getList(listId: Long): ListWithProperties = withContext(Dispatchers.IO) {
        val listRow = executeJsonObjectRequest(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/rest/v1/lists?select=id,name,description,created_at&id=eq.$listId")
                .applySupabaseHeaders()
                .get()
                .build()
        )

        val apns = getListApns(listId)
        val properties = if (apns.isEmpty()) {
            emptyList()
        } else {
            val url = mapMarkersUrl().apply {
                addQueryParameter("select", MAP_MARKER_COLUMNS)
                addQueryParameter("apn", "in.(${apns.toPostgrestList()})")
            }.build()
            val byApn = executeJsonArrayRequest(
                Request.Builder().url(url).applySupabaseHeaders().get().build()
            ).map { it.asJsonObject.toProperty() }.associateBy { it.apn }
            // map_markers only holds rows with coordinates; keep list order and
            // drop APNs the view does not return.
            apns.mapNotNull { byApn[it] }
        }

        ListWithProperties(
            id = listRow.get("id").asLong,
            name = listRow.get("name").asString,
            description = listRow.get("description")?.takeUnless { it.isJsonNull }?.asString,
            createdAt = listRow.get("created_at")?.takeUnless { it.isJsonNull }?.asString,
            properties = properties
        )
    }

    override suspend fun deleteList(listId: Long) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/rest/v1/lists?id=eq.$listId")
            .applySupabaseHeaders()
            .delete()
            .build()

        client.newCall(request).execute().use { response ->
            response.requireSuccess()
        }
    }

    override suspend fun addPropertiesToList(listId: Long, request: AddPropertiesRequest) = withContext(Dispatchers.IO) {
        if (request.apns.isEmpty()) {
            return@withContext
        }
        val body = JsonObject().apply {
            addProperty("p_list_id", listId)
            add(
                "p_apns",
                JsonArray().apply {
                    request.apns.forEach { add(it) }
                }
            )
        }

        client.newCall(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/rest/v1/rpc/append_properties_to_list")
                .applySupabaseHeaders()
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        ).execute().use { response ->
            response.requireSuccess()
        }
    }

    override suspend fun reorderListProperties(listId: Long, apns: List<String>) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("p_list_id", listId)
            add(
                "p_apns",
                JsonArray().apply {
                    apns.forEach { add(it) }
                }
            )
        }

        client.newCall(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/rest/v1/rpc/reorder_list_properties")
                .applySupabaseHeaders()
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        ).execute().use { response ->
            response.requireSuccess()
        }
    }

    override suspend fun removePropertyFromList(listId: Long, apn: String) = withContext(Dispatchers.IO) {
        val encodedApn = apn.encodeUrlComponent()
        client.newCall(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/rest/v1/list_properties?list_id=eq.$listId&apn=eq.$encodedApn")
                .applySupabaseHeaders()
                .delete()
                .build()
        ).execute().use { response ->
            response.requireSuccess()
        }
    }

    override suspend fun getListRoute(listId: Long): RouteResponse = withContext(Dispatchers.IO) {
        val list = getList(listId)
        val destinations = list.properties
            .mapNotNull { it.address }
            .filter { it.isNotBlank() }
        val url = if (destinations.isEmpty()) {
            "https://www.google.com/maps"
        } else {
            val joined = destinations.joinToString("/")
            "https://www.google.com/maps/dir/${joined.encodeRoutePath()}"
        }
        RouteResponse(
            url = url,
            propertyCount = destinations.size,
            optimized = false
        )
    }

    override suspend fun submitScoutResult(request: ScoutResultRequest) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("apn", request.apn)
            addProperty("follow_up", request.followUp)
            addProperty("flyered", request.flyered)
            if (!request.notes.isNullOrBlank()) {
                addProperty("notes", request.notes)
            } else {
                add("notes", null)
            }
            request.latitude?.let { addProperty("latitude", it) }
            request.longitude?.let { addProperty("longitude", it) }
        }
        client.newCall(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/rest/v1/scout_results")
                .applySupabaseHeaders()
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        ).execute().use { response ->
            response.requireSuccess()
        }
    }

    override suspend fun getScoutResults(collectionId: Long?): List<ScoutResult> = withContext(Dispatchers.IO) {
        val url = buildString {
            append("${baseUrl.trimEnd('/')}/rest/v1/scout_results?select=id,apn,collection_id,follow_up,flyered,notes,scouted_at,latitude,longitude&order=scouted_at.desc")
            if (collectionId != null) {
                append("&collection_id=eq.$collectionId")
            }
        }
        val rows = executeJsonArrayRequest(
            Request.Builder()
                .url(url)
                .applySupabaseHeaders()
                .get()
                .build()
        )
        buildList {
            for (index in 0 until rows.size()) {
                val row = rows.get(index).asJsonObject
                add(
                    ScoutResult(
                        id = row.get("id").asLong,
                        apn = row.get("apn").asString,
                        followUp = row.get("follow_up").asBoolean,
                        flyered = row.get("flyered").asBoolean,
                        notes = row.get("notes")?.takeUnless { it.isJsonNull }?.asString,
                        scoutedAt = row.get("scouted_at")?.takeUnless { it.isJsonNull }?.asString,
                        latitude = row.get("latitude")?.takeUnless { it.isJsonNull }?.asDouble,
                        longitude = row.get("longitude")?.takeUnless { it.isJsonNull }?.asDouble
                    )
                )
            }
        }
    }

    override suspend fun getScoutStats(): ScoutStats = withContext(Dispatchers.IO) {
        val results = getScoutResults(collectionId = null)
        val uniqueProperties = results.map { it.apn }.toSet().size
        ScoutStats(
            totalVisits = results.size,
            followUps = results.count { it.followUp },
            flyered = results.count { it.flyered },
            uniqueProperties = uniqueProperties
        )
    }









    private fun JsonObject.toProperty(): Property {
        fun string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            get(key)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        }
        fun double(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
            get(key)?.takeUnless { it.isJsonNull }?.asDouble
        }
        fun flag(key: String): Boolean {
            val element = get(key)?.takeUnless { it.isJsonNull } ?: return false
            val primitive = element.asJsonPrimitive
            return if (primitive.isBoolean) primitive.asBoolean else primitive.asInt == 1
        }

        return Property(
            apn = get("apn")?.asString.orEmpty(),
            address = string("location", "location_of_property", "address"),
            city = string("city"),
            latitude = double("lat", "latitude"),
            longitude = double("lng", "longitude"),
            hasVpt = flag("has_vpt"),
            conditionScore = get("condition_score")?.takeUnless { it.isJsonNull }?.asFloat,
            isScouted = flag("is_scouted"),
            streetviewImagePath = string("streetview_image_path"),
            powerStatus = string("power_status"),
            mailingAddress = string("mailing_address"),
            lastSaleDate = string("last_sale_date"),
            isOutOfState = flag("is_out_of_state"),
            deceasedCount = get("deceased_count")?.takeUnless { it.isJsonNull }?.asInt,
            isDelinquent = flag("delinquent"),
            zipCode = string("situs_zip", "zip_code"),
            taxYear = string("tax_year"),
            lastPayment = string("last_payment"),
            ownerName = string("owner_name"),
            researchStatus = string("research_status"),
            aiVacancyVerdict = string("ai_vacancy_verdict"),
            aiVacancyConfidence = get("ai_vacancy_confidence")?.takeUnless { it.isJsonNull }?.asFloat,
            siteVacant = get("site_vacant")?.takeUnless { it.isJsonNull }?.asBoolean
        )
    }

    private fun mapMarkersUrl(): HttpUrl.Builder =
        "${baseUrl.trimEnd('/')}/rest/v1/map_markers".toHttpUrl().newBuilder()

    /** Mirrors get_bills_with_parcels_filtered in scan/db.py so web and phone agree. */
    private fun HttpUrl.Builder.applyPropertyFilters(filters: PropertyFilters) {
        filters.query?.toPostgrestSearchTerm()?.let { term ->
            addQueryParameter("or", "(apn.ilike.*$term*,location.ilike.*$term*)")
        }
        filters.city?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let {
            addQueryParameter("city", "eq.$it")
        }
        filters.zip?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let {
            addQueryParameter("situs_zip", "in.(${it.toPostgrestList()})")
        }
        filters.power?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("power_status", "eq.$it") }
        filters.research?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("research_status", "eq.$it") }
        filters.ownerName?.toPostgrestSearchTerm()?.let { addQueryParameter("owner_name", "ilike.*$it*") }
        if (filters.vptOnly) addQueryParameter("has_vpt", "eq.1")
        if (filters.delinquentOnly) addQueryParameter("delinquent", "eq.1")
        if (filters.favoritesOnly) addQueryParameter("is_favorite", "is.true")
        if (filters.outOfStateOnly) addQueryParameter("is_out_of_state", "is.true")
        filters.scouted?.let { addQueryParameter("is_scouted", "is.$it") }
        when (filters.condition?.trim()?.lowercase()) {
            "good" -> addQueryParameter("condition_score", "gte.7")
            "fair" -> addQueryParameter("and", "(condition_score.gte.4,condition_score.lt.7)")
            "poor" -> addQueryParameter("condition_score", "lt.4")
            "unscored" -> addQueryParameter("condition_score", "is.null")
        }
        when (val verdict = filters.aiVerdict?.trim()?.lowercase()) {
            "none" -> addQueryParameter("ai_vacancy_verdict", "is.null")
            in AI_VERDICTS -> addQueryParameter("ai_vacancy_verdict", "eq.$verdict")
        }
        parseConfidenceMin(filters.aiConfidenceMin)?.let { addQueryParameter("ai_vacancy_confidence", "gte.$it") }
        when (filters.siteVacant?.trim()?.lowercase()) {
            "yes" -> addQueryParameter("site_vacant", "is.true")
            "no" -> addQueryParameter("site_vacant", "is.false")
            "unknown" -> addQueryParameter("site_vacant", "is.null")
        }
    }

    private fun getListApns(listId: Long): List<String> {
        val apnRows = executeJsonArrayRequest(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/rest/v1/list_properties?select=apn&list_id=eq.$listId&order=sort_order.asc")
                .applySupabaseHeaders()
                .get()
                .build()
        )
        return buildList {
            for (index in 0 until apnRows.size()) {
                add(apnRows.get(index).asJsonObject.get("apn").asString)
            }
        }
    }



    private fun getListPropertyCount(listId: Long): Int {
        return executeJsonArrayRequest(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/rest/v1/list_properties?select=apn&list_id=eq.$listId")
                .applySupabaseHeaders()
                .get()
                .build()
        ).size()
    }



    private fun executeJsonObjectRequest(request: Request): JsonObject {
        return client.newCall(request).execute().use { response ->
            response.requireSuccess()
            val rawBody = response.body?.string().orEmpty()
            val root = if (rawBody.isBlank()) JsonArray() else JsonParser().parse(rawBody)
            when {
                root.isJsonArray && root.asJsonArray.size() > 0 -> root.asJsonArray.get(0).asJsonObject
                root.isJsonObject -> root.asJsonObject
                else -> JsonObject()
            }
        }
    }

    private fun executeJsonArrayRequest(request: Request): JsonArray {
        return client.newCall(request).execute().use { response ->
            response.requireSuccess()
            val rawBody = response.body?.string().orEmpty()
            if (rawBody.isBlank()) {
                JsonArray()
            } else {
                JsonParser().parse(rawBody).asJsonArray
            }
        }
    }

    private fun Request.Builder.applySupabaseHeaders(
        returnRepresentation: Boolean = false
    ): Request.Builder {
        addHeader("apikey", anonKey)
        addHeader("Authorization", "Bearer ${accessTokenProvider().orEmpty()}")
        addHeader("Content-Type", "application/json")
        if (returnRepresentation) {
            addHeader("Prefer", "return=representation")
        }
        return this
    }

    companion object {
        private fun buildClient(
            accessTokenProvider: () -> String?,
            authManager: SupabaseAuthManager?
        ): OkHttpClient {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            val tokenAuthenticator = Authenticator { _, response ->
                if (response.request.header("X-Retry-After-Refresh") != null) {
                    return@Authenticator null
                }
                val refreshed = authManager?.refreshAccessTokenSync() ?: false
                if (!refreshed) {
                    return@Authenticator null
                }
                val newToken = accessTokenProvider().orEmpty()
                if (newToken.isBlank()) {
                    return@Authenticator null
                }
                response.request.newBuilder()
                    .removeHeader("Authorization")
                    .addHeader("Authorization", "Bearer $newToken")
                    .addHeader("X-Retry-After-Refresh", "true")
                    .build()
            }

            return OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain -> chain.proceed(chain.request()) })
                .addInterceptor(logging)
                .authenticator(tokenAuthenticator)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}

private fun Response.requireSuccess() {
    if (isSuccessful) return
    val bodyText = body?.string().orEmpty()
    throw IllegalStateException("Supabase scanner request failed (${code}): $bodyText")
}

private fun String.encodeUrlComponent(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

private fun String.encodeRoutePath(): String =
    split("/")
        .joinToString("/") { it.encodeUrlComponent() }

/** Total from a PostgREST Content-Range header, e.g. "0-24/519" gives 519. */
internal fun parseContentRangeTotal(header: String?): Int? =
    header?.substringAfterLast('/', "")?.toIntOrNull()

/** Quote each value for a PostgREST in.(...) list. */
private fun Collection<String>.toPostgrestList(): String =
    joinToString(",") { "\"" + it.replace("\"", "") + "\"" }

/** Strip characters that would break PostgREST or/ilike syntax. */
private fun String.toPostgrestSearchTerm(): String? =
    replace(Regex("[,()*\"\\\\]"), " ").trim().takeIf { it.isNotEmpty() }
