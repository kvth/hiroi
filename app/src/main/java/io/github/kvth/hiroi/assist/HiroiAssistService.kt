package io.github.kvth.hiroi.assist

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import io.github.kvth.hiroi.AssistStructureCapture
import io.github.kvth.hiroi.CaptureStore
import io.github.kvth.hiroi.HiroiAccessibilityService
import io.github.kvth.hiroi.LookupActivity

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
        // Android hands the next trigger to a session that is still showing (e.g. when the overlay
        // was left with Home), so every show starts over.
        launched = false
        val accessibility = HiroiAccessibilityService.instance
        Log.d(TAG, "onShow flags=$showFlags accessibility=${accessibility != null}")
        CaptureStore.screenshot = null
        // The accessibility service sees every window (status bar, notifications, dialogs),
        // so prefer it over the assist structure, which only covers the foreground app.
        if (accessibility != null) {
            launched = true // keeps onHandleAssist out while the capture finishes
            accessibility.capture { launch() }
        }
    }

    /** The assistant's own screenshot, used for OCR when the accessibility service is off. */
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (CaptureStore.screenshot == null && screenshot != null) {
            CaptureStore.screenshot = screenshot.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    override fun onHide() {
        Log.d(TAG, "onHide")
        launched = false
        super.onHide()
    }

    override fun onHandleAssist(state: AssistState) {
        if (launched || !state.isFocused) return
        launched = true
        CaptureStore.update(state.assistStructure?.let(AssistStructureCapture::capture).orEmpty())
        CaptureStore.sourcePackage = state.assistStructure?.activityComponent?.packageName
        launch()
    }

    /** Opens the overlay on what CaptureStore holds. */
    private fun launch() {
        Log.d(TAG, "launch with ${CaptureStore.blocks.size} text blocks, screenshot=${CaptureStore.screenshot != null}")
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
