package com.vpt.scout

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

class SupabaseScannerServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun service() = SupabaseScannerService(
        baseUrl = server.url("/").toString(),
        anonKey = "anon-key",
        accessTokenProvider = { "jwt-token" },
        authManager = null
    )

    @Test
    fun `getProperties reads map_markers with auth headers and exact count`() = runBlocking {
        server.enqueueJson("""[]""", contentRange = "*/0")

        val response = service().getProperties(
            filters = PropertyFilters(city = "oakland", query = "elm", vptOnly = true),
            page = 1,
            perPage = 50
        )

        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("/rest/v1/map_markers", url.encodedPath)
        assertEquals("Bearer jwt-token", request.getHeader("Authorization"))
        assertEquals("anon-key", request.getHeader("apikey"))
        assertEquals("count=exact", request.getHeader("Prefer"))
        assertEquals("eq.OAKLAND", url.queryParameter("city"))
        assertEquals("(apn.ilike.*elm*,location.ilike.*elm*)", url.queryParameter("or"))
        assertEquals("eq.1", url.queryParameter("has_vpt"))
        assertEquals(0, response.total)
        assertTrue(response.properties.isEmpty())
    }

    @Test
    fun `getProperties maps the full filter contract onto postgrest params`() = runBlocking {
        server.enqueueJson("""[]""", contentRange = "*/0")

        service().getProperties(
            filters = PropertyFilters(
                city = "Oakland",
                query = "elm",
                zip = "94601, 94602",
                power = "off",
                favoritesOnly = true,
                vptOnly = true,
                delinquentOnly = true,
                condition = "fair",
                outOfStateOnly = true,
                research = "complete",
                ownerName = "smith",
                sort = "condition_score",
                order = "desc",
                scouted = false
            ),
            page = 3,
            perPage = 25
        )

        val url = server.takeRequest().requestUrl!!
        assertEquals("in.(\"94601\",\"94602\")", url.queryParameter("situs_zip"))
        assertEquals("eq.off", url.queryParameter("power_status"))
        assertEquals("is.true", url.queryParameter("is_favorite"))
        assertEquals("eq.1", url.queryParameter("delinquent"))
        assertEquals("(condition_score.gte.4,condition_score.lt.7)", url.queryParameter("and"))
        assertEquals("is.true", url.queryParameter("is_out_of_state"))
        assertEquals("eq.complete", url.queryParameter("research_status"))
        assertEquals("ilike.*smith*", url.queryParameter("owner_name"))
        assertEquals("is.false", url.queryParameter("is_scouted"))
        assertEquals("condition_score.desc.nullslast,apn.asc", url.queryParameter("order"))
        assertEquals("50", url.queryParameter("offset"))
        assertEquals("25", url.queryParameter("limit"))
    }

    @Test
    fun `getProperties maps ai vacancy filters onto postgrest params`() = runBlocking {
        server.enqueueJson("""[]""", contentRange = "*/0")

        service().getProperties(
            PropertyFilters(
                aiVerdict = "likely_vacant",
                aiConfidenceMin = "70",
                siteVacant = "yes",
                sort = "ai_vacancy_confidence",
                order = "desc"
            ),
            page = 1,
            perPage = 10
        )

        val url = server.takeRequest().requestUrl!!
        assertEquals("eq.likely_vacant", url.queryParameter("ai_vacancy_verdict"))
        assertEquals("gte.0.7", url.queryParameter("ai_vacancy_confidence"))
        assertEquals("is.true", url.queryParameter("site_vacant"))
        assertEquals("ai_vacancy_confidence.desc.nullslast,apn.asc", url.queryParameter("order"))
    }

    @Test
    fun `getProperties maps not-assessed verdict and ignores bogus vacancy values`() = runBlocking {
        server.enqueueJson("""[]""", contentRange = "*/0")
        service().getProperties(PropertyFilters(aiVerdict = "none", siteVacant = "unknown"), page = 1, perPage = 10)
        var url = server.takeRequest().requestUrl!!
        assertEquals("is.null", url.queryParameter("ai_vacancy_verdict"))
        assertEquals("is.null", url.queryParameter("site_vacant"))

        server.enqueueJson("""[]""", contentRange = "*/0")
        service().getProperties(
            PropertyFilters(aiVerdict = "bogus", aiConfidenceMin = "500", siteVacant = "maybe"),
            page = 1,
            perPage = 10
        )
        url = server.takeRequest().requestUrl!!
        assertEquals(null, url.queryParameter("ai_vacancy_verdict"))
        assertEquals(null, url.queryParameter("ai_vacancy_confidence"))
        assertEquals(null, url.queryParameter("site_vacant"))
    }

    @Test
    fun `parseConfidenceMin accepts fractions and percents`() {
        assertEquals(0.7, parseConfidenceMin("0.7")!!, 1e-9)
        assertEquals(0.7, parseConfidenceMin("70")!!, 1e-9)
        listOf(null, "", "abc", "0", "-5", "500").forEach { assertEquals(it, null, parseConfidenceMin(it)) }
    }

    @Test
    fun `getProperties strips postgrest syntax characters from search terms`() = runBlocking {
        server.enqueueJson("""[]""", contentRange = "*/0")

        service().getProperties(PropertyFilters(query = "elm, (st)*"), page = 1, perPage = 10)

        val or = server.takeRequest().requestUrl!!.queryParameter("or")!!
        assertTrue(or, or.startsWith("(apn.ilike.*elm") && !or.contains("(st)"))
    }

    @Test
    fun `getProperties parses view rows and content-range total`() = runBlocking {
        server.enqueueJson(
            """
            [
              {
                "apn": "63-2969-19",
                "location": "1002 GRIZZLY PEAK BLVD, BERKELEY",
                "city": "BERKELEY",
                "situs_zip": null,
                "zip_code": "94708",
                "lat": 37.88,
                "lng": -122.24,
                "has_vpt": 1,
                "delinquent": 0,
                "power_status": "off",
                "condition_score": 4.0,
                "streetview_image_path": null,
                "research_status": "failed",
                "owner_name": null,
                "last_payment": "Dec 8, 2025",
                "tax_year": "2025-2026",
                "is_out_of_state": false,
                "is_favorite": false,
                "is_scouted": true,
                "ai_vacancy_verdict": "likely_vacant",
                "ai_vacancy_confidence": 0.85,
                "site_vacant": true
              }
            ]
            """.trimIndent(),
            contentRange = "0-0/519"
        )

        val response = service().getProperties(PropertyFilters(), page = 1, perPage = 25)

        assertEquals(519, response.total)
        assertEquals(21, response.totalPages)
        val property = response.properties.single()
        assertEquals("1002 GRIZZLY PEAK BLVD, BERKELEY", property.address)
        assertEquals(37.88, property.latitude!!, 0.0)
        assertEquals(-122.24, property.longitude!!, 0.0)
        assertTrue(property.hasVpt)
        assertTrue(property.isScouted)
        assertEquals("94708", property.zipCode)
        assertEquals("Dec 8, 2025", property.lastPayment)
        assertEquals("failed", property.researchStatus)
        assertEquals("likely_vacant", property.aiVacancyVerdict)
        assertEquals(0.85f, property.aiVacancyConfidence!!, 0.0001f)
        assertEquals(true, property.siteVacant)
    }

    @Test
    fun `getProperties restricts to list apns server side`() = runBlocking {
        server.enqueueJson("""[{"apn":"002-002"},{"apn":"001-001"}]""")
        server.enqueueJson("""[]""", contentRange = "*/0")

        service().getProperties(PropertyFilters(listId = 7L), page = 1, perPage = 50)

        assertEquals("/rest/v1/list_properties", server.takeRequest().requestUrl!!.encodedPath)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/v1/map_markers", url.encodedPath)
        assertEquals("in.(\"002-002\",\"001-001\")", url.queryParameter("apn"))
    }

    @Test
    fun `getProperties returns empty without querying markers for an empty list`() = runBlocking {
        server.enqueueJson("""[]""")

        val response = service().getProperties(PropertyFilters(listId = 7L), page = 1, perPage = 50)

        assertEquals(0, response.total)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `getMapPropertiesInBounds filters the viewport server side`() = runBlocking {
        server.enqueueJson("""[{"apn":"inside","location":"101 First St","city":"OAKLAND","lat":37.8,"lng":-122.2,"has_vpt":1}]""")

        val properties = service().getMapPropertiesInBounds(
            south = 37.7,
            west = -122.3,
            north = 37.9,
            east = -122.1
        )

        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/v1/map_markers", url.encodedPath)
        assertEquals(listOf("gte.37.7", "lte.37.9"), url.queryParameterValues("lat"))
        assertEquals(listOf("gte.-122.3", "lte.-122.1"), url.queryParameterValues("lng"))
        assertEquals(listOf("inside"), properties.map { it.apn })
        assertEquals(37.8, properties.single().latitude!!, 0.0)
    }

    @Test
    fun `addPropertiesToList appends through route queue rpc`() = runBlocking {
        server.enqueueJson("""{"added":2}""")

        val service = service()

        service.addPropertiesToList(7L, AddPropertiesRequest(listOf("002", "003")))

        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/append_properties_to_list", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"p_list_id\":7"))
        assertTrue(body.contains("\"002\""))
        assertTrue(body.contains("\"003\""))
    }

    @Test
    fun `reorderListProperties posts ordered apns to route queue rpc`() = runBlocking {
        server.enqueueJson("""{"updated":3}""")

        val service = service()

        service.reorderListProperties(7L, listOf("003", "001", "002"))

        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/reorder_list_properties", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"p_list_id\":7"))
        assertTrue(body.indexOf("003") < body.indexOf("001"))
        assertTrue(body.indexOf("001") < body.indexOf("002"))
    }

    @Test
    fun `getNextProperty calls scout_next and reads remaining from the row`() = runBlocking {
        server.enqueueJson(
            """
            [
              {
                "apn": "001-100-100",
                "address": "123 Test St",
                "city": "OAKLAND",
                "latitude": 37.8,
                "longitude": -122.2,
                "has_vpt": 1,
                "condition_score": 5.5,
                "streetview_image_path": "https://example.com/1.jpg",
                "distance_km": 0.4,
                "remaining": 4
              }
            ]
            """.trimIndent()
        )

        val response = service().getNextProperty(
            latitude = 37.8,
            longitude = -122.2,
            city = "oakland",
            vptOnly = true,
            listId = 7L
        )

        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/scout_next", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"p_lat\":37.8"))
        assertTrue(body.contains("\"p_city\":\"OAKLAND\""))
        assertTrue(body.contains("\"p_vpt_only\":true"))
        assertTrue(body.contains("\"p_list_id\":7"))
        assertEquals("001-100-100", response.property?.apn)
        assertTrue(response.property!!.hasVpt)
        assertEquals(4, response.remaining)
    }

    @Test
    fun `getNextProperty returns no property when scout_next is empty`() = runBlocking {
        server.enqueueJson("""[]""")

        val response = service().getNextProperty(latitude = 37.8, longitude = -122.2)

        assertEquals(null, response.property)
        assertEquals(0, response.remaining)
    }

    @Test
    fun `getList hydrates properties from map_markers in list order`() = runBlocking {
        server.enqueueJson("""[{"id":7,"name":"East Bay","description":"targets","created_at":"2026-04-05T00:00:00Z"}]""")
        server.enqueueJson("""[{"apn":"002-002"},{"apn":"001-001"},{"apn":"no-coords"}]""")
        server.enqueueJson(
            """
            [
              {"apn":"001-001","location":"101 First St","city":"OAKLAND","lat":37.80,"lng":-122.20,"has_vpt":1,"is_scouted":false},
              {"apn":"002-002","location":"202 Second St","city":"OAKLAND","lat":37.81,"lng":-122.21,"has_vpt":0,"is_scouted":true}
            ]
            """.trimIndent()
        )

        val list = service().getList(7L)

        server.takeRequest()
        server.takeRequest()
        val markers = server.takeRequest().requestUrl!!
        assertEquals("/rest/v1/map_markers", markers.encodedPath)
        assertEquals(listOf("002-002", "001-001"), list.properties.map { it.apn })
        assertTrue(list.properties.first().isScouted)
        assertTrue(!list.properties.last().isScouted)
        assertTrue(list.properties.all { it.latitude != null })
    }

    @Test
    fun `getProperties executes blocking http work off the caller thread`() = runBlocking {
        val requestThreads = Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestThreads += Thread.currentThread().name
                val body = "[]"
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = SupabaseScannerService(
            baseUrl = "https://example.supabase.co",
            anonKey = "anon-key",
            accessTokenProvider = { "jwt-token" },
            authManager = null,
            client = client
        )

        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "caller-thread")
        }.asCoroutineDispatcher().use { dispatcher ->
            withContext(dispatcher) {
                service.getProperties(
                    filters = PropertyFilters(city = "OAKLAND"),
                    page = 1,
                    perPage = 50
                )
            }
        }

        assertTrue(requestThreads.isNotEmpty())
        assertTrue(requestThreads.none { it.contains("caller-thread") })
    }

    private fun MockWebServer.enqueueJson(body: String, contentRange: String? = null) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .apply { contentRange?.let { addHeader("Content-Range", it) } }
                .setBody(body)
        )
    }
}
