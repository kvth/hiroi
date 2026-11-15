package io.github.kvth.hiroi

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class HiroiAccessibilityService : AccessibilityService() {

    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) = captureAndShow()
    }

    override fun onServiceConnected() {
        instance = this
        accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun captureAndShow() {
        CaptureStore.update(AccessibilityCapture.capture(this))
        CaptureStore.onClosed = null
        startActivity(LookupActivity.intent(this))
    }

    companion object {
        /** Set while the user has the service enabled; the assistant session uses it for a full-screen capture. */
        @Volatile
        var instance: HiroiAccessibilityService? = null
            private set
    }
}
