package io.github.kvth.hiroi

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityEvent

class HiroiAccessibilityService : AccessibilityService() {

    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) = captureAndShow()
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Whether Hiroi hid the keyboard for the overlay; it's given back when the overlay closes. */
    private var keyboardHidden = false

    override fun onServiceConnected() {
        instance = this
        accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
        // In case Hiroi stopped while it had the keyboard hidden
        softKeyboardController.setShowMode(SHOW_MODE_AUTO)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
        restoreKeyboard()
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
     *
     * An open keyboard is hidden first: the overlay would close it anyway (it takes the focus),
     * and the app below would then lay itself out again, its text no longer where its boxes are.
     * So the capture waits until the keyboard is gone and the app has moved its text; the
     * keyboard may come back when the overlay closes ([restoreKeyboard]).
     */
    fun capture(then: () -> Unit) {
        // Triggered again with the overlay open on a frozen screen: it keeps the moment it shows.
        if (LookupActivity.keepsFrozen(this)) {
            then()
            return
        }
        if (keyboardShowing() && softKeyboardController.setShowMode(SHOW_MODE_HIDDEN)) {
            keyboardHidden = true
            afterKeyboardGone(SystemClock.uptimeMillis()) { captureNow(then) }
        } else {
            captureNow(then)
        }
    }

    private fun keyboardShowing() = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    /** Calls [then] once the keyboard's window is gone and the app below has had time to lay itself out again. */
    private fun afterKeyboardGone(since: Long, then: () -> Unit) {
        if (!keyboardShowing() || SystemClock.uptimeMillis() - since > KEYBOARD_TIMEOUT_MS) {
            handler.postDelayed(then, RELAYOUT_MS)
        } else {
            handler.postDelayed({ afterKeyboardGone(since, then) }, POLL_MS)
        }
    }

    /**
     * Lets the keyboard show again after the overlay that hid it has closed, and opens it again
     * for the field that had it, as a tap on that field would, so typing can go on.
     */
    fun restoreKeyboard() {
        if (!keyboardHidden) return
        keyboardHidden = false
        softKeyboardController.setShowMode(SHOW_MODE_AUTO)
        // Once the app has its focus back
        handler.postDelayed({
            if (!LookupActivity.showing) {
                rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?.takeIf { it.isEditable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }, REFOCUS_MS)
    }

    private fun captureNow(then: () -> Unit) {
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
        private const val POLL_MS = 30L
        private const val KEYBOARD_TIMEOUT_MS = 1000L

        /** How long apps take to lay themselves out again once the keyboard is gone (its animation is over by then). */
        private const val RELAYOUT_MS = 250L

        /** How long the app below takes to get the focus back once the overlay has closed. */
        private const val REFOCUS_MS = 300L

        /** Set while the user has the service enabled; the assistant session uses it for a full-screen capture. */
        @Volatile
        var instance: HiroiAccessibilityService? = null
            private set
    }
}
