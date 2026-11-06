package com.vpt.scout.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable

data class AssistantMessage(val role: String, val text: String, val sources: List<String> = emptyList(), val steps: List<AssistantStep> = emptyList())
data class AssistantState(
    val messages: List<AssistantMessage> = emptyList(), val apn: String = "",
    val busy: Boolean = false, val connecting: Boolean = false, val live: Boolean = false,
    val recording: Boolean = false, val activity: String? = null, val error: String? = null,
    val userTranscript: String = "", val assistantTranscript: String = ""
)

/** Conversation memory lasts for this signed-in app session; it is never persisted to disk. */
class AssistantController(private val service: AssistantService) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(AssistantState())
    val state = _state.asStateFlow()
    private var request: Job? = null
    private var voice: LiveConnection? = null
    private var audio: LiveAudio? = null
    private var generation = 0
    private var liveSources = emptyList<String>()

    fun setApn(apn: String) {
        if (_state.value.busy || _state.value.live || _state.value.connecting) return
        _state.update { it.copy(apn = apn.take(64)) }
    }
    fun newConversation() {
        stop()
        _state.update { AssistantState(apn = it.apn) }
    }
    fun error(message: String) { _state.update { it.copy(error = message) } }
    fun send(raw: String) {
        val text = raw.trim().take(4_000)
        if (text.isBlank() || _state.value.busy || _state.value.connecting) return
        if (_state.value.live) { voice?.text(text); return }
        _state.update { it.copy(messages = it.messages + AssistantMessage("user", text), error = null) }
        ask()
    }
    fun retry() {
        if (_state.value.messages.lastOrNull()?.role == "user" && !_state.value.busy) ask()
    }
    private fun ask() {
        val snapshot = _state.value
        _state.update { it.copy(busy = true, error = null) }
        request = scope.launch {
            try {
                val reply = service.ask(snapshot.messages.map { ChatTurn(it.role, it.text) }, snapshot.apn.trim().ifEmpty { null })
                _state.update { it.copy(messages = it.messages + AssistantMessage("assistant", reply.reply, reply.sources, reply.steps)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e.message ?: "The assistant could not answer. Try again.") }
            finally { _state.update { it.copy(busy = false) } }
        }
    }

    fun startLive() {
        if (_state.value.live || _state.value.connecting || _state.value.busy) return
        val session = ++generation
        _state.update { it.copy(connecting = true, error = null) }
        val deviceAudio = LiveAudio(scope)
        audio = deviceAudio
        fun current(action: () -> Unit) { scope.launch { if (generation == session) action() } }
        val connection = LiveConnection(service, object : LiveEvents {
            override fun ready() = current { _state.update { it.copy(connecting = false, live = true) } }
            override fun transcript(role: String, text: String) = current {
                _state.update { if (role == "user") it.copy(userTranscript = text) else it.copy(assistantTranscript = text, activity = null) }
            }
            override fun audio(bytes: ByteArray) {
                if (generation == session && !deviceAudio.play(bytes)) current { error("Voice playback fell behind. Reconnect to continue."); stop() }
            }
            override fun interrupted() = current { deviceAudio.interrupt(); flushTranscripts() }
            override fun turn() = current { flushTranscripts(); _state.update { it.copy(activity = null) } }
            override fun activity(tool: String) = current { _state.update { it.copy(activity = "Researching: ${tool.replace('_', ' ')}") } }
            override fun sources(urls: List<String>) = current { liveSources = (liveSources + urls).distinct() }
            override fun ended(message: String?) = current { stop(); message?.let(::error) }
        })
        voice = connection
        deviceAudio.startPlayback { message -> if (generation == session) { stop(); error(message) } }
        try { connection.connect(_state.value.messages.map { ChatTurn(it.role, it.text) }, _state.value.apn.trim().ifEmpty { null }) }
        catch (_: Exception) { stop(); error("Could not open live voice. Check your connection and sign-in.") }
    }

    fun toggleMicrophone() {
        if (!_state.value.live) return
        if (_state.value.recording) {
            _state.update { it.copy(recording = false) }
            val session = generation
            val currentAudio = audio
            scope.launch { currentAudio?.stopRecording(); if (generation == session) voice?.finishSpeaking() }
        } else {
            audio?.interrupt()
            _state.update { it.copy(recording = true, error = null) }
            audio?.startRecording({ voice?.sendAudio(it) == true }) { message -> stop(); error(message) }
        }
    }

    private fun flushTranscripts() {
        _state.update { state ->
            val added = buildList {
                if (state.userTranscript.isNotBlank()) add(AssistantMessage("user", state.userTranscript.trim()))
                if (state.assistantTranscript.isNotBlank()) add(AssistantMessage("assistant", state.assistantTranscript.trim(), liveSources))
            }
            state.copy(messages = state.messages + added, userTranscript = "", assistantTranscript = "")
        }
        liveSources = emptyList()
    }
    fun stop() {
        generation++
        request?.cancel(); request = null
        voice?.close(); voice = null
        audio?.close(); audio = null
        flushTranscripts()
        _state.update { it.copy(busy = false, live = false, connecting = false, recording = false, activity = null) }
    }
    override fun close() { stop(); scope.cancel() }
}
