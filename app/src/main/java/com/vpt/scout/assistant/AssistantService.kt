package com.vpt.scout.assistant

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vpt.scout.SupabaseAuthManager
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ChatTurn(val role: String, val text: String)
data class AssistantStep(val tool: String = "", val summary: String = "")
data class AssistantReply(val reply: String, val steps: List<AssistantStep> = emptyList(), val sources: List<String> = emptyList())

/** Only Supabase user credentials leave the phone. The Google key stays in the edge function. */
class AssistantService(
    private val auth: SupabaseAuthManager,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(125, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
) {
    private val gson = Gson()

    suspend fun ask(turns: List<ChatTurn>, apn: String?): AssistantReply {
        for (attempt in 0..1) {
            val body = gson.toJson(mapOf("messages" to turns.takeLast(20), "apn" to apn))
            val request = requestBuilder().post(body.toRequestBody("application/json".toMediaType())).build()
            val response = await(http.newCall(request))
            response.use {
                if (it.code == 401 && attempt == 0 && auth.refreshAccessToken()) return@use
                if (!it.isSuccessful) throw IOException(assistantHttpError(it.code))
                val parsed = gson.fromJson(it.body?.string(), AssistantReply::class.java)
                if (parsed?.reply.isNullOrBlank()) throw IOException("The assistant returned an empty answer. Try again.")
                return parsed
            }
        }
        throw IOException("Your session expired. Sign in again.")
    }

    fun live(listener: WebSocketListener): WebSocket = http.newWebSocket(requestBuilder().build(), listener)

    private fun requestBuilder(): Request.Builder {
        val token = auth.getAccessToken() ?: throw IOException("Sign in first.")
        return Request.Builder().url("${auth.projectUrl.trimEnd('/')}/functions/v1/property-assistant")
            .header("apikey", auth.anonKey).header("Authorization", "Bearer $token")
    }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Could not reach the assistant. Check your connection."))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.close() }
            }
        })
    }
}

internal fun assistantHttpError(code: Int): String = when (code) {
    401 -> "Your session expired. Sign in again."
    403 -> "An active BARN subscription is required."
    404 -> "The Gemini assistant backend has not been deployed yet."
    429 -> "The assistant is busy. Wait a moment and retry."
    else -> "The assistant is unavailable ($code). Try again."
}

interface LiveEvents {
    fun ready()
    fun transcript(role: String, text: String)
    fun audio(bytes: ByteArray)
    fun interrupted()
    fun turn()
    fun activity(tool: String)
    fun sources(urls: List<String>)
    fun ended(message: String?)
}

/** App/edge relay protocol. JSON carries state/transcripts; binary frames carry mono PCM16. */
class LiveConnection(private val service: AssistantService, private val events: LiveEvents) {
    private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var ready = false

    fun connect(history: List<ChatTurn>, apn: String?) {
        closed = false
        socket = service.live(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed) { webSocket.close(1000, "Stopped"); return }
                webSocket.send(Gson().toJson(mapOf("type" to "start", "messages" to history.takeLast(20), "apn" to apn)))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!closed) events.audio(bytes.toByteArray())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed) return
                runCatching {
                    val frame = JsonParser().parse(text).asJsonObject
                    when (frame.get("type")?.asString) {
                        "ready" -> { ready = true; events.ready() }
                        "user", "assistant" -> events.transcript(frame.get("type").asString, frame.get("text")?.asString.orEmpty())
                        "interrupted" -> events.interrupted()
                        "turn" -> events.turn()
                        "activity" -> events.activity(frame.get("tool")?.asString.orEmpty())
                        "sources" -> events.sources(frame.getAsJsonArray("urls")?.map { it.asString }.orEmpty())
                        "error" -> end(frame.get("message")?.asString ?: "Voice is unavailable.")
                        "ended" -> end(frame.get("message")?.asString)
                    }
                }.onFailure { end("The voice connection returned an invalid response.") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                end(response?.code?.let(::assistantHttpError) ?: "Voice connection lost. Tap Live voice to reconnect.")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); end(null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { end(null) }
        })
    }

    fun sendAudio(bytes: ByteArray): Boolean = !closed && ready && socket?.send(ByteString.of(*bytes)) == true
    fun finishSpeaking() { if (!closed && ready) socket?.send("{\"type\":\"mic\",\"open\":false}") }
    fun text(text: String) { if (!closed && ready) socket?.send(Gson().toJson(mapOf("type" to "text", "text" to text))) }
    fun close() { closed = true; ready = false; socket?.close(1000, "Stopped"); socket = null }
    private fun end(message: String?) {
        if (closed) return
        close()
        events.ended(message)
    }
}
