package com.sagi.appleremotebridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

class RemoteAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: RemoteAccessibilityService? = null
        fun dispatch(c: RemoteCommand) = instance?.handle(c) ?: false
    }

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun handle(c: RemoteCommand): Boolean = when (c) {
        RemoteCommand.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        RemoteCommand.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        RemoteCommand.OK -> click()
        RemoteCommand.UP -> move(D.UP)
        RemoteCommand.DOWN -> move(D.DOWN)
        RemoteCommand.LEFT -> move(D.LEFT)
        RemoteCommand.RIGHT -> move(D.RIGHT)
        RemoteCommand.PLAY_PAUSE -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        RemoteCommand.MEDIA_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        RemoteCommand.MEDIA_PREVIOUS -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        RemoteCommand.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
        RemoteCommand.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
        RemoteCommand.MUTE -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
        RemoteCommand.POWER -> {
            if (android.os.Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
        }
    }

    private fun audio(): AudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun volume(direction: Int): Boolean = try {
        audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        true
    } catch (_: Throwable) { false }

    private fun mediaKey(code: Int): Boolean = try {
        val am = audio()
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        true
    } catch (_: Throwable) { false }

    private fun click(): Boolean {
        val r = rootInActiveWindow ?: return false
        var n = r.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: findFocused(r) ?: return false
        while (true) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent ?: break
        }
        return false
    }

    private fun move(d: D): Boolean {
        val r = rootInActiveWindow ?: return false
        val a = mutableListOf<AccessibilityNodeInfo>()
        collect(r, a)
        if (a.isEmpty()) return false
        val c = r.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: findFocused(r) ?: a.first()
        val cr = Rect().also(c::getBoundsInScreen)
        val x = cr.centerX(); val y = cr.centerY()
        val best = a.filter { it != c }.mapNotNull { n ->
            val q = Rect().also(n::getBoundsInScreen)
            val dx = q.centerX() - x; val dy = q.centerY() - y
            val ok = when (d) { D.UP -> dy < 0; D.DOWN -> dy > 0; D.LEFT -> dx < 0; D.RIGHT -> dx > 0 }
            if (!ok) null else {
                val p = if (d == D.UP || d == D.DOWN) abs(dy) else abs(dx)
                val s = if (d == D.UP || d == D.DOWN) abs(dx) else abs(dy)
                n to p * 1000L + s * 3L
            }
        }.minByOrNull { it.second }?.first ?: return false
        return best.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) || best.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun collect(n: AccessibilityNodeInfo, o: MutableList<AccessibilityNodeInfo>) {
        if (n.isVisibleToUser && (n.isFocusable || n.isClickable)) o += n
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, o) }
    }

    private fun findFocused(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isFocused || n.isAccessibilityFocused) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { findFocused(it)?.let { f -> return f } }
        return null
    }

    private enum class D { UP, DOWN, LEFT, RIGHT }
}

enum class RemoteCommand {
    UP, DOWN, LEFT, RIGHT, OK, BACK, HOME,
    PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS,
    VOLUME_UP, VOLUME_DOWN, MUTE, POWER
}
