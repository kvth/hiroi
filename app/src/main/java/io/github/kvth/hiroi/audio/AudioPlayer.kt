package io.github.kvth.hiroi.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Plays word audio: downloaded recordings, the phone's Japanese text-to-speech, and Yomitan's
 * fallback sounds. One thing at a time, as Yomitan stops the playing audio for the next.
 * Call from the main thread; [release] when done.
 */
class AudioPlayer(context: Context) {
    private val context = context.applicationContext
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<String, Int>? = null
    private var onSpeechFailed: (() -> Unit)? = null

    fun play(bytes: ByteArray, volume: Int) {
        stop()
        // MediaPlayer plays files; the cache keeps the last recording.
        val file = File(context.cacheDir, "word-audio").apply { writeBytes(bytes) }
        start(volume) { setDataSource(file.path) }
    }

    /** Yomitan's fallback sound ("click" or "bloop"), for a word no source has audio for. */
    fun playFallback(sound: String, volume: Int) {
        if (sound !in listOf("click", "bloop")) return
        stop()
        start(volume) { context.assets.openFd("audio/fallback-$sound.mp3").use { setDataSource(it.fileDescriptor, it.startOffset, it.length) } }
    }

    /**
     * Speaks [text] with the phone's Japanese voice; the engine starts on first use. [onFailed]
     * runs if there's no text-to-speech engine or it has no Japanese voice.
     */
    fun speak(text: String, volume: Int, onFailed: () -> Unit = {}) {
        stop()
        onSpeechFailed = onFailed
        if (ttsReady) {
            say(text, volume)
            return
        }
        pendingSpeech = text to volume
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                val language = if (status == TextToSpeech.SUCCESS) tts?.setLanguage(Locale.JAPANESE) else null
                ttsReady = language != null && language >= TextToSpeech.LANG_AVAILABLE
                val pending = pendingSpeech
                pendingSpeech = null
                if (!ttsReady) {
                    Log.w(TAG, "No Japanese text-to-speech (status $status, language $language)")
                    tts?.shutdown()
                    tts = null
                    onSpeechFailed?.invoke()
                } else if (pending != null) {
                    say(pending.first, pending.second)
                }
            }
        }
    }

    private fun say(text: String, volume: Int) {
        val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume / 100f) }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "hiroi")
    }

    private fun start(volume: Int, source: MediaPlayer.() -> Unit) {
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                source()
                setVolume(volume / 100f, volume / 100f)
                setOnCompletionListener {
                    Log.d(TAG, "Played ${it.duration} ms")
                    it.release()
                    if (player === it) player = null
                }
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "Couldn't play audio", it) }.getOrNull()
    }

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
        tts?.stop()
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private companion object {
        const val TAG = "HiroiAudio"
    }
}
