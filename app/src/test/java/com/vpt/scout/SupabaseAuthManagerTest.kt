package com.vpt.scout

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SupabaseAuthManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: InMemorySharedPreferences

    @Before
    fun setUp() {
        server = MockWebServer()
        prefs = InMemorySharedPreferences()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `signIn persists auth state from supabase response`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "access_token": "access-123",
                      "refresh_token": "refresh-456",
                      "user": {
                        "id": "user-1",
                        "email": "agent@example.com"
                      }
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(approved(true))

        val authManager = SupabaseAuthManager(
            prefs = prefs,
            httpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        val result = authManager.signIn("  agent@example.com  ", "secret")

        assertTrue(result.isSuccess)
        assertEquals("access-123", authManager.state.value.accessToken)
        assertEquals("refresh-456", authManager.state.value.refreshToken)
        assertEquals("user-1", authManager.state.value.userId)
        assertEquals("agent@example.com", authManager.state.value.email)
        assertTrue(authManager.state.value.isAuthenticated)
        assertEquals("access-123", prefs.getString("access_token", null))
        assertEquals("refresh-456", prefs.getString("refresh_token", null))
        assertEquals("user-1", prefs.getString("user_id", null))
        assertEquals("agent@example.com", prefs.getString("email", null))
    }

    @Test
    fun `signIn returns server error and does not persist session`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"msg":"Invalid login credentials"}""")
        )

        val authManager = SupabaseAuthManager(
            prefs = prefs,
            httpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        val result = authManager.signIn("agent@example.com", "wrong")

        assertTrue(result.isFailure)
        assertEquals("Invalid login credentials", result.exceptionOrNull()?.message)
        assertFalse(authManager.state.value.isAuthenticated)
        assertNull(prefs.getString("access_token", null))
    }

    @Test
    fun `refreshAccessToken updates tokens and preserves user metadata`() = runBlocking {
        prefs.edit()
            .putString("access_token", "old-access")
            .putString("refresh_token", "old-refresh")
            .putString("user_id", "user-1")
            .putString("email", "agent@example.com")
            .apply()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "access_token": "new-access",
                      "refresh_token": "new-refresh"
                    }
                    """.trimIndent()
                )
        )

        val authManager = SupabaseAuthManager(
            prefs = prefs,
            httpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        val refreshed = authManager.refreshAccessToken()

        assertTrue(refreshed)
        assertEquals("new-access", authManager.state.value.accessToken)
        assertEquals("new-refresh", authManager.state.value.refreshToken)
        assertEquals("user-1", authManager.state.value.userId)
        assertEquals("agent@example.com", authManager.state.value.email)
        assertEquals("new-access", prefs.getString("access_token", null))
        assertEquals("new-refresh", prefs.getString("refresh_token", null))
        assertEquals("user-1", prefs.getString("user_id", null))
        assertEquals("agent@example.com", prefs.getString("email", null))
    }

    @Test
    fun `refreshAccessToken signs out when refresh token is missing`() = runBlocking {
        prefs.edit()
            .putString("access_token", "old-access")
            .putString("email", "agent@example.com")
            .apply()

        val authManager = SupabaseAuthManager(
            prefs = prefs,
            httpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        val refreshed = authManager.refreshAccessToken()

        assertFalse(refreshed)
        assertFalse(authManager.state.value.isAuthenticated)
        assertNull(prefs.getString("email", null))
    }

    @Test
    fun `refreshAccessTokenSync updates persisted session`() {
        prefs.edit()
            .putString("access_token", "old-access")
            .putString("refresh_token", "old-refresh")
            .putString("user_id", "user-1")
            .putString("email", "agent@example.com")
            .apply()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "access_token": "sync-access",
                      "refresh_token": "sync-refresh"
                    }
                    """.trimIndent()
                )
        )

        val authManager = SupabaseAuthManager(
            prefs = prefs,
            httpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        val refreshed = authManager.refreshAccessTokenSync()

        assertTrue(refreshed)
        assertEquals("sync-access", authManager.state.value.accessToken)
        assertEquals("sync-refresh", prefs.getString("refresh_token", null))
    }

    @Test
    fun `signOut clears in memory and persisted auth state`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "access_token": "access-123",
                      "refresh_token": "refresh-456",
                      "user": {
                        "id": "user-1",
                        "email": "agent@example.com"
                      }
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(approved(true))

        val authManager = SupabaseAuthManager(
            prefs = prefs,
            httpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        authManager.signIn("agent@example.com", "secret")
        authManager.signOut()

        assertFalse(authManager.state.value.isAuthenticated)
        assertNull(prefs.getString("access_token", null))
        assertNull(prefs.getString("refresh_token", null))
        assertNull(prefs.getString("user_id", null))
        assertNull(prefs.getString("email", null))
    }

    @Test
    fun `signIn checks approval with the new access token`() = runBlocking {
        server.enqueue(tokenResponse())
        server.enqueue(approved(true))
        val authManager = manager()

        authManager.signIn("agent@example.com", "secret")

        server.takeRequest()
        val check = server.takeRequest()
        assertEquals("/rest/v1/rpc/has_app_access", check.path)
        assertEquals("Bearer access-123", check.getHeader("Authorization"))
        assertEquals("anon-key", check.getHeader("apikey"))
    }

    @Test
    fun `signIn rejects unapproved accounts and does not persist session`() = runBlocking {
        server.enqueue(tokenResponse())
        server.enqueue(approved(false))
        val authManager = manager()

        val result = authManager.signIn("agent@example.com", "secret")

        assertTrue(result.isFailure)
        assertEquals(SupabaseAuthManager.NOT_APPROVED_MESSAGE, result.exceptionOrNull()?.message)
        assertFalse(authManager.state.value.isAuthenticated)
        assertNull(prefs.getString("access_token", null))
    }

    @Test
    fun `signIn fails closed when approval cannot be checked`() = runBlocking {
        server.enqueue(tokenResponse())
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"not found"}"""))
        val authManager = manager()

        val result = authManager.signIn("agent@example.com", "secret")

        assertTrue(result.isFailure)
        assertFalse(authManager.state.value.isAuthenticated)
        assertNull(prefs.getString("access_token", null))
    }

    @Test
    fun `verifyAccess signs out revoked users but keeps session on network errors`() = runBlocking {
        prefs.edit().putString("access_token", "stored").putString("refresh_token", "r").apply()
        val authManager = manager()

        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(AccessCheck.UNKNOWN, authManager.verifyAccess())
        assertTrue(authManager.state.value.isAuthenticated)

        server.enqueue(approved(false))
        assertEquals(AccessCheck.NOT_APPROVED, authManager.verifyAccess())
        assertFalse(authManager.state.value.isAuthenticated)
        assertNull(prefs.getString("access_token", null))
    }

    @Test
    fun `requestPasswordReset posts email with redirect to the web reset page`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val authManager = manager(resetUrl = "https://barn.example/reset-password")

        val result = authManager.requestPasswordReset("  agent@example.com ")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("/auth/v1/recover", request.requestUrl?.encodedPath)
        assertEquals("https://barn.example/reset-password", request.requestUrl?.queryParameter("redirect_to"))
        assertEquals("""{"email":"agent@example.com"}""", request.body.readUtf8())
    }

    @Test
    fun `requestPasswordReset surfaces server errors`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"msg":"Too many requests"}"""))

        val result = manager().requestPasswordReset("agent@example.com")

        assertTrue(result.isFailure)
        assertEquals("Too many requests", result.exceptionOrNull()?.message)
    }

    @Test
    fun `logout revokes the session on the server and clears it locally`() = runBlocking {
        prefs.edit().putString("access_token", "stored").putString("refresh_token", "r").apply()
        server.enqueue(MockResponse().setResponseCode(204))
        val authManager = manager()

        authManager.logout()

        val request = server.takeRequest()
        assertEquals("/auth/v1/logout", request.path)
        assertEquals("Bearer stored", request.getHeader("Authorization"))
        assertFalse(authManager.state.value.isAuthenticated)
        assertNull(prefs.getString("refresh_token", null))
    }

    private fun manager(resetUrl: String = "") = SupabaseAuthManager(
        prefs = prefs,
        httpClient = OkHttpClient(),
        supabaseUrl = server.url("/").toString().trimEnd('/'),
        supabaseAnonKey = "anon-key",
        passwordResetRedirectUrl = resetUrl
    )

    private fun tokenResponse() = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody("""{"access_token":"access-123","refresh_token":"refresh-456","user":{"id":"user-1","email":"agent@example.com"}}""")

    private fun approved(value: Boolean) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(value.toString())
}

private class InMemorySharedPreferences : SharedPreferences {
    private val data = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        data[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (data[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int =
        data[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        data[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        data[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        data[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor(data)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
    }

    private class Editor(
        private val data: MutableMap<String, Any?>
    ) : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = values?.toSet()
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = null
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearRequested = true
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearRequested) {
                data.clear()
            }
            pending.forEach { (key, value) ->
                if (value == null) {
                    data.remove(key)
                } else {
                    data[key] = value
                }
            }
        }
    }
}
