package com.vpt.scout.assistant

import android.content.Context
import com.google.gson.JsonParser
import com.vpt.scout.SupabaseAuthManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class AssistantServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: AssistantService
    @Before fun setup() {
        server = MockWebServer(); server.start()
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("assistant-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("access_token", "user-token").putString("refresh_token", "refresh-token").apply()
        val auth = SupabaseAuthManager(prefs, OkHttpClient(), server.url("/").toString().trimEnd('/'), "public-anon")
        service = AssistantService(auth)
    }
    @After fun teardown() { server.shutdown() }

    @Test fun `sends authenticated history and property context and reads grounded answer`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"reply":"Example LLC owns it.","steps":[{"tool":"get_property","summary":"Property 123"}],"sources":["https://county.example"]}"""))
        val reply = service.ask(listOf(ChatTurn("user", "Who owns it?")), "123")
        assertEquals("Example LLC owns it.", reply.reply)
        assertEquals(listOf("https://county.example"), reply.sources)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/functions/v1/property-assistant", request.path)
        assertEquals("Bearer user-token", request.getHeader("Authorization"))
        assertEquals("public-anon", request.getHeader("apikey"))
        assertEquals("123", JsonParser.parseString(request.body.readUtf8()).asJsonObject.get("apn").asString)
    }

    @Test fun `refreshes expired user token once before retrying`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"access_token":"new-token","refresh_token":"next","user":{"id":"user"}}"""))
        server.enqueue(MockResponse().setBody("""{"reply":"Found it.","steps":[],"sources":[]}"""))
        assertEquals("Found it.", service.ask(listOf(ChatTurn("user", "Find it")), null).reply)
        server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        assertEquals("Bearer new-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `subscription denial is shown without exposing backend error contents`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("secret internal error"))
        val error = runCatching { service.ask(listOf(ChatTurn("user", "Find it")), null) }.exceptionOrNull()
        assertEquals("An active BARN subscription is required.", error?.message)
    }

    @Test fun `empty answer is a retryable error`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"reply":"","steps":[],"sources":[]}"""))
        assertTrue(runCatching { service.ask(listOf(ChatTurn("user", "Find it")), null) }.isFailure)
    }
}
