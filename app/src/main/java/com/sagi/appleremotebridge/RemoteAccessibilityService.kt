package com.sagi.appleremotebridge

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

class RemoteAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: RemoteAccessibilityService? = null
        private val keyExecutor = Executors.newSingleThreadExecutor()
        fun dispatch(c: RemoteCommand): Boolean {
            val service = instance ?: return false
            return service.handle(c)
        }
        fun isConnected(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun handle(c: RemoteCommand): Boolean = when (c) {
        RemoteCommand.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        RemoteCommand.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        RemoteCommand.OK -> tvKey(KeyEvent.KEYCODE_DPAD_CENTER) || clickFocused()
        RemoteCommand.UP -> tvKey(KeyEvent.KEYCODE_DPAD_UP)
        RemoteCommand.DOWN -> tvKey(KeyEvent.KEYCODE_DPAD_DOWN)
        RemoteCommand.LEFT -> tvKey(KeyEvent.KEYCODE_DPAD_LEFT)
        RemoteCommand.RIGHT -> tvKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        RemoteCommand.PLAY_PAUSE -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        RemoteCommand.MEDIA_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        RemoteCommand.MEDIA_PREVIOUS -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        RemoteCommand.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
        RemoteCommand.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
        RemoteCommand.MUTE -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
        RemoteCommand.POWER -> if (android.os.Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
    }

    /* Android TV navigation is DPAD/key based. Accessibility focus walking does not move the
       framework's real Leanback/Compose focus, so inject the same key events as the physical remote. */
    private fun tvKey(code: Int): Boolean = try {
        keyExecutor.execute {
            try { Instrumentation().sendKeyDownUpSync(code) }
            catch (_: Throwable) { Handler(Looper.getMainLooper()).post { fallbackFocusKey(code) } }
        }
        true
    } catch (_: Throwable) { fallbackFocusKey(code) }

    private fun fallbackFocusKey(code: Int): Boolean {
        // Best-effort fallback for firmware which blocks Instrumentation key injection.
        val root = rootInActiveWindow ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return false
        return when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER -> focus.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else -> false
        }
    }

    private fun clickFocused(): Boolean {
        val root = rootInActiveWindow ?: return false
        var n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return false
        while (true) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent ?: break
        }
        return false
    }

    private fun audio(): AudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun volume(direction: Int): Boolean = try {
        val am = audio()
        // STREAM_MUSIC is the normal Android TV media stream. FLAG_SHOW_UI mirrors a real volume key.
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        true
    } catch (_: Throwable) {
        try { audio().adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI); true }
        catch (_: Throwable) { false }
    }
    private fun mediaKey(code: Int): Boolean = try {
        val am = audio(); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); true
    } catch (_: Throwable) { false }
}

enum class RemoteCommand {
    UP, DOWN, LEFT, RIGHT, OK, BACK, HOME,
    PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS,
    VOLUME_UP, VOLUME_DOWN, MUTE, POWER
}
