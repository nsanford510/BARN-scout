package com.vpt.scout.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.Closeable

/** Foreground-only audio. No recording or background service, no microphone use before permission. */
class LiveAudio(private val scope: CoroutineScope) : Closeable {
    private var recorder: AudioRecord? = null
    private var recordJob: Job? = null
    private var echo: AcousticEchoCanceler? = null
    private var track: AudioTrack? = null
    private var playback: Job? = null
    private val queue = Channel<ByteArray>(100)

    fun startPlayback(onError: (String) -> Unit) {
        playback = scope.launch(Dispatchers.IO) {
            try {
                val min = AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(min > 0)
                val output = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(min, 24_000)).setTransferMode(AudioTrack.MODE_STREAM).build()
                track = output
                output.play()
                for (bytes in queue) {
                    ensureActive()
                    var offset = 0
                    while (offset < bytes.size && isActive) {
                        val written = output.write(bytes, offset, bytes.size - offset)
                        check(written > 0)
                        offset += written
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { withContext(Dispatchers.Main) { onError("Could not play voice audio. Reconnect or use typed chat.") } }
            finally { track?.let { runCatching { it.stop() }; it.release() }; track = null }
        }
    }

    fun play(bytes: ByteArray): Boolean = bytes.isNotEmpty() && queue.trySend(bytes).isSuccess

    fun interrupt() {
        while (queue.tryReceive().isSuccess) { /* discard queued answer */ }
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
    }

    @SuppressLint("MissingPermission") // The screen checks RECORD_AUDIO before calling.
    fun startRecording(send: (ByteArray) -> Boolean, onError: (String) -> Unit) {
        if (recordJob?.isActive == true) return
        recordJob = scope.launch(Dispatchers.IO) {
            var input: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(min > 0)
                input = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6_400))
                check(input.state == AudioRecord.STATE_INITIALIZED)
                recorder = input
                if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(input.audioSessionId)?.also { it.enabled = true }
                input.startRecording()
                val buffer = ByteArray(640) // 20 ms at 16 kHz mono PCM16.
                while (isActive) {
                    val count = input.read(buffer, 0, buffer.size)
                    if (count <= 0) { ensureActive(); error("Microphone read failed") }
                    if (!send(buffer.copyOf(count))) { ensureActive(); error("Voice connection is full or closed") }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { withContext(Dispatchers.Main) { onError("Microphone unavailable. Check permission and reconnect.") } }
            finally {
                runCatching { input?.stop() }
                echo?.release(); echo = null
                input?.release()
                if (recorder === input) recorder = null
            }
        }
    }

    suspend fun stopRecording() {
        val job = recordJob
        recordJob = null
        job?.cancel()
        runCatching { recorder?.stop() }
        job?.join() // Do not send audioStreamEnd until the last microphone frame has left.
    }

    override fun close() {
        recordJob?.cancel(); recordJob = null
        runCatching { recorder?.stop() }
        playback?.cancel(); playback = null
        queue.close()
        runCatching { track?.pause(); track?.flush() }
    }
}
