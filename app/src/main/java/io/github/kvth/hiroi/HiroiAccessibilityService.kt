package io.github.kvth.hiroi

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
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
        capture {
            CaptureStore.onClosed = null
            startActivity(LookupActivity.intent(this))
        }
    }

    /**
     * Captures the screen's text and a screenshot (for OCR), then calls [then] on the main thread.
     * Both must happen before the overlay opens, or they show the overlay instead of the app.
     */
    fun capture(then: () -> Unit) {
        CaptureStore.update(AccessibilityCapture.capture(this))
        CaptureStore.sourcePackage = rootInActiveWindow?.packageName?.toString()
        CaptureStore.screenshot = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                result.hardwareBuffer.use { buffer ->
                    // A software copy: hardware bitmaps can't be read by ML Kit or kept after the buffer closes.
                    CaptureStore.screenshot = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                }
                then()
            }

            override fun onFailure(errorCode: Int) {
                // e.g. a second trigger within a second (Android's limit) or a window that forbids screenshots
                Log.w(TAG, "No screenshot (error $errorCode)")
                then()
            }
        })
    }

    companion object {
        private const val TAG = "HiroiCapture"

        /** Set while the user has the service enabled; the assistant session uses it for a full-screen capture. */
        @Volatile
        var instance: HiroiAccessibilityService? = null
            private set
    }
}
