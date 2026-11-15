package io.github.kvth.hiroi.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import io.github.kvth.hiroi.AccessibilityCapture
import io.github.kvth.hiroi.AssistStructureCapture
import io.github.kvth.hiroi.CaptureStore
import io.github.kvth.hiroi.HiroiAccessibilityService
import io.github.kvth.hiroi.LookupActivity
import io.github.kvth.hiroi.TextBlock

class HiroiAssistService : VoiceInteractionService()

class HiroiSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = HiroiSession(this)
}

/**
 * Runs when Hiroi is invoked as the digital assistant. It has no UI of its own: it captures the
 * screen text and opens [LookupActivity], staying alive until that activity closes.
 */
class HiroiSession(context: Context) : VoiceInteractionSession(context) {
    private var launched = false

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // The accessibility service sees every window (status bar, notifications, dialogs),
        // so prefer it over the assist structure, which only covers the foreground app.
        HiroiAccessibilityService.instance?.let { launch(AccessibilityCapture.capture(it)) }
    }

    override fun onHandleAssist(state: AssistState) {
        if (launched || !state.isFocused) return
        launch(state.assistStructure?.let(AssistStructureCapture::capture).orEmpty())
    }

    private fun launch(blocks: List<TextBlock>) {
        if (launched) return
        launched = true
        CaptureStore.update(blocks)
        try {
            CaptureStore.onClosed = { hide() }
            startAssistantActivity(LookupActivity.intent(context))
        } catch (e: RuntimeException) {
            Log.w(TAG, "startAssistantActivity failed, starting a normal activity", e)
            CaptureStore.onClosed = null
            context.startActivity(LookupActivity.intent(context))
            hide()
        }
    }

    private companion object {
        const val TAG = "HiroiSession"
    }
}

/** Assistant apps must declare a recognition service. Hiroi doesn't do speech, so it always errors. */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
