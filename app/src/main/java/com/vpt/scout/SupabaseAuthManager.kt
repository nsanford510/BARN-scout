package com.vpt.scout

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class SupabaseAuthState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val userId: String? = null,
    val email: String? = null
) {
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank()
}

/** Result of asking the backend whether the signed-in user is an approved scout or admin. */
enum class AccessCheck { APPROVED, NOT_APPROVED, UNKNOWN }

class SupabaseAuthManager(
    private val prefs: SharedPreferences,
    private val httpClient: OkHttpClient,
    private val supabaseUrl: String,
    private val supabaseAnonKey: String,
    /** Where password-reset emails send people (the web UI's /reset-password page). */
    private val passwordResetRedirectUrl: String = ""
) {
    constructor(context: Context) : this(
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        httpClient = OkHttpClient(),
        supabaseUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
        supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
        passwordResetRedirectUrl = BuildConfig.WEB_APP_URL.trimEnd('/')
            .takeIf { it.startsWith("http") }
            ?.let { "$it/reset-password" }
            .orEmpty()
    )

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<SupabaseAuthState> = _state.asStateFlow()

    val projectUrl: String
        get() = supabaseUrl

    val anonKey: String
        get() = supabaseAnonKey

    fun getAccessToken(): String? = _state.value.accessToken

    /** Clear the session on this device only. */
    fun signOut() {
        prefs.edit().clear().apply()
        _state.value = SupabaseAuthState()
    }

    /** Revoke the session on the server (best effort), then clear it locally. */
    suspend fun logout() = withContext(Dispatchers.IO) {
        val token = _state.value.accessToken
        if (!token.isNullOrBlank() && supabaseUrl.isNotBlank()) {
            runCatching {
                val request = Request.Builder()
                    .url("$supabaseUrl/auth/v1/logout")
                    .addHeader("apikey", supabaseAnonKey)
                    .addHeader("Authorization", "Bearer $token")
                    .post("".toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(request).execute().close()
            }
        }
        signOut()
    }

    suspend fun signIn(email: String, password: String): Result<SupabaseAuthState> =
        withContext(Dispatchers.IO) {
            try {
                if (supabaseUrl.isBlank() || supabaseAnonKey.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Supabase is not configured in app resources.")
                    )
                }

                val payload = JsonObject().apply {
                    addProperty("email", email.trim())
                    addProperty("password", password)
                }

                val request = Request.Builder()
                    .url("$supabaseUrl/auth/v1/token?grant_type=password")
                    .addHeader("apikey", supabaseAnonKey)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val authState = httpClient.newCall(request).execute().use { response ->
                    val rawBody = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException(errorMessage(rawBody, "Login failed (${response.code})"))
                        )
                    }

                    val json = JsonParser().parse(rawBody).asJsonObject
                    val accessToken = json.string("access_token")
                        ?: return@withContext Result.failure(
                            IllegalStateException("Supabase login did not return an access token.")
                        )
                    val user = json.objectValue("user")
                    SupabaseAuthState(
                        accessToken = accessToken,
                        refreshToken = json.string("refresh_token"),
                        userId = user?.string("id"),
                        email = user?.string("email") ?: email.trim()
                    )
                }

                // A valid password is not enough: the account must be approved.
                when (checkAccess(authState.accessToken!!)) {
                    AccessCheck.APPROVED -> Unit
                    AccessCheck.NOT_APPROVED -> return@withContext Result.failure(
                        IllegalStateException(NOT_APPROVED_MESSAGE)
                    )
                    AccessCheck.UNKNOWN -> return@withContext Result.failure(
                        IllegalStateException("Could not check account access. Try again.")
                    )
                }

                persistState(authState)
                _state.value = authState
                Result.success(authState)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Re-check that the stored session still belongs to an approved user, e.g. on app start.
     * Signs out only on an explicit "not approved"; network errors keep the session.
     */
    suspend fun verifyAccess(): AccessCheck = withContext(Dispatchers.IO) {
        val token = _state.value.accessToken ?: return@withContext AccessCheck.NOT_APPROVED
        // An expired token shows up as UNKNOWN; the data client's Authenticator refreshes it later.
        val result = checkAccess(token)
        if (result == AccessCheck.NOT_APPROVED) signOut()
        result
    }

    /** Ask Supabase to email a password-reset link. */
    suspend fun requestPasswordReset(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (supabaseUrl.isBlank() || supabaseAnonKey.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Supabase is not configured in app resources.")
                )
            }
            val url = "$supabaseUrl/auth/v1/recover".toHttpUrlOrNull()!!.newBuilder().apply {
                if (passwordResetRedirectUrl.isNotBlank()) {
                    addQueryParameter("redirect_to", passwordResetRedirectUrl)
                }
            }.build()
            val payload = JsonObject().apply { addProperty("email", email.trim()) }
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", supabaseAnonKey)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IllegalStateException(errorMessage(rawBody, "Could not send reset email (${response.code})"))
                    )
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Refresh the access token using the stored refresh token.
     * Returns true if refresh was successful, false otherwise.
     * On failure, clears the auth state so the user is prompted to log in again.
     */
    suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        refreshAccessTokenSync()
    }

    /**
     * Blocking refresh, used directly by the OkHttp Authenticator.
     */
    @Synchronized
    fun refreshAccessTokenSync(): Boolean {
        val currentRefreshToken = _state.value.refreshToken
        if (currentRefreshToken.isNullOrBlank()) {
            signOut()
            return false
        }

        return try {
            val payload = JsonObject().apply {
                addProperty("refresh_token", currentRefreshToken)
            }

            val request = Request.Builder()
                .url("$supabaseUrl/auth/v1/token?grant_type=refresh_token")
                .addHeader("apikey", supabaseAnonKey)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    signOut()
                    return false
                }

                val json = JsonParser().parse(rawBody).asJsonObject
                val newAccessToken = json.string("access_token")
                if (newAccessToken == null) {
                    signOut()
                    return false
                }
                val user = json.objectValue("user")

                val authState = SupabaseAuthState(
                    accessToken = newAccessToken,
                    refreshToken = json.string("refresh_token") ?: currentRefreshToken,
                    userId = user?.string("id") ?: _state.value.userId,
                    email = user?.string("email") ?: _state.value.email
                )

                persistState(authState)
                _state.value = authState
                true
            }
        } catch (e: Exception) {
            signOut()
            false
        }
    }

    private fun checkAccess(accessToken: String): AccessCheck = try {
        val request = Request.Builder()
            .url("$supabaseUrl/rest/v1/rpc/has_app_access")
            .addHeader("apikey", supabaseAnonKey)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty().trim()
            when {
                response.isSuccessful && body == "true" -> AccessCheck.APPROVED
                response.isSuccessful && body == "false" -> AccessCheck.NOT_APPROVED
                else -> AccessCheck.UNKNOWN
            }
        }
    } catch (e: Exception) {
        AccessCheck.UNKNOWN
    }

    private fun errorMessage(rawBody: String, fallback: String): String {
        val errorBody = runCatching { JsonParser().parse(rawBody).asJsonObject }.getOrNull()
        return errorBody?.string("msg")
            ?: errorBody?.string("error_description")
            ?: errorBody?.string("error")
            ?: fallback
    }

    private fun persistState(state: SupabaseAuthState) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, state.accessToken)
            .putString(KEY_REFRESH_TOKEN, state.refreshToken)
            .putString(KEY_USER_ID, state.userId)
            .putString(KEY_EMAIL, state.email)
            .apply()
    }

    private fun loadState(): SupabaseAuthState {
        return SupabaseAuthState(
            accessToken = prefs.getString(KEY_ACCESS_TOKEN, null),
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null),
            userId = prefs.getString(KEY_USER_ID, null),
            email = prefs.getString(KEY_EMAIL, null)
        )
    }

    companion object {
        // Excluded from backups in res/xml/backup_rules.xml and data_extraction_rules.xml.
        private const val PREFS_NAME = "supabase_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val NOT_APPROVED_MESSAGE = "Your account hasn't been approved yet. Ask an admin for access."
    }
}

private fun JsonObject.string(key: String): String? =
    get(key)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

private fun JsonObject.objectValue(key: String): JsonObject? =
    get(key)?.takeUnless { it.isJsonNull }?.asJsonObject
