package com.sagi.appleremotebridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

class RemoteAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: RemoteAccessibilityService? = null
        fun dispatch(c: RemoteCommand): Boolean = instance?.handle(c) ?: false
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
        RemoteCommand.OK -> clickFocused()
        RemoteCommand.UP -> moveFocus(View.FOCUS_UP)
        RemoteCommand.DOWN -> moveFocus(View.FOCUS_DOWN)
        RemoteCommand.LEFT -> moveFocus(View.FOCUS_LEFT)
        RemoteCommand.RIGHT -> moveFocus(View.FOCUS_RIGHT)
        RemoteCommand.PLAY -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        RemoteCommand.MEDIA_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        RemoteCommand.MEDIA_PREVIOUS -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        RemoteCommand.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
        RemoteCommand.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
        RemoteCommand.MUTE -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
        RemoteCommand.POWER -> if (android.os.Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
    }

    private fun moveFocus(direction: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val current = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: firstFocusable(root)
            ?: return false

        val next = try { current.focusSearch(direction) } catch (_: Throwable) { null }
            ?: spatialNeighbor(root, current, direction)
            ?: return false

        val inputFocused = next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val a11yFocused = next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        return inputFocused || a11yFocused
    }

    private fun spatialNeighbor(root: AccessibilityNodeInfo, current: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectFocusable(root, nodes)
        val cr = Rect().also(current::getBoundsInScreen)
        val cx = cr.centerX(); val cy = cr.centerY()
        return nodes.asSequence().filter { it != current }.mapNotNull { n ->
            val r = Rect().also(n::getBoundsInScreen)
            val dx = r.centerX() - cx; val dy = r.centerY() - cy
            val valid = when (direction) {
                View.FOCUS_UP -> dy < 0
                View.FOCUS_DOWN -> dy > 0
                View.FOCUS_LEFT -> dx < 0
                View.FOCUS_RIGHT -> dx > 0
                else -> false
            }
            if (!valid) null else {
                val primary = if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) abs(dy) else abs(dx)
                val secondary = if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) abs(dx) else abs(dy)
                n to (primary * 1000L + secondary)
            }
        }.minByOrNull { it.second }?.first
    }

    private fun collectFocusable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isVisibleToUser && (node.isFocusable || node.isClickable)) out += node
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectFocusable(it, out) }
    }

    private fun firstFocusable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && (node.isFocusable || node.isClickable)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            firstFocusable(child)?.let { return it }
        }
        return null
    }

    private fun clickFocused(): Boolean {
        val root = rootInActiveWindow ?: return false
        var node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: firstFocusable(root)
            ?: return false
        while (true) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent ?: break
        }
        return false
    }

    private fun audio(): AudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun volume(direction: Int): Boolean {
        val am = audio()
        return try {
            am.adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
            true
        } catch (_: Throwable) {
            try {
                am.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                true
            } catch (_: Throwable) {
                try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI); true }
                catch (_: Throwable) { false }
            }
        }
    }

    private fun mediaKey(code: Int): Boolean = try {
        val am = audio()
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        true
    } catch (_: Throwable) { false }
}

enum class RemoteCommand {
    UP, DOWN, LEFT, RIGHT, OK, BACK, HOME,
    PLAY, MEDIA_NEXT, MEDIA_PREVIOUS,
    VOLUME_UP, VOLUME_DOWN, MUTE, POWER
}
