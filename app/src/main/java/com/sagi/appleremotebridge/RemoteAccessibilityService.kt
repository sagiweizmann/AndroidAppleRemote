package com.sagi.appleremotebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

class RemoteAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: RemoteAccessibilityService? = null
        @Volatile private var lastNavigationDiagnostic: String = "No navigation test has run yet."

        fun dispatch(c: RemoteCommand): Boolean = instance?.handle(c) ?: run {
            setDiagnostic("Command: $c\nResult: Accessibility service is not connected")
            false
        }
        fun isConnected(): Boolean = instance != null
        fun navigationDiagnostic(): String = buildString {
            append("Accessibility connected: ").append(if (instance != null) "YES" else "NO")
            append("\nAndroid SDK: ").append(Build.VERSION.SDK_INT)
            append("\n\n").append(lastNavigationDiagnostic)
        }
        private fun setDiagnostic(text: String) { lastNavigationDiagnostic = text }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        setDiagnostic("Accessibility service connected.\nWaiting for a navigation command.")
        CompanionPythonBridge.onStatus("A11Y • connected • sdk=${Build.VERSION.SDK_INT} • gestures=enabled")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        setDiagnostic("Accessibility service disconnected.")
        CompanionPythonBridge.onStatus("A11Y • disconnected")
        super.onDestroy()
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun handle(c: RemoteCommand): Boolean = when (c) {
        RemoteCommand.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        RemoteCommand.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        RemoteCommand.OK -> navigateOrTap(RemoteCommand.OK)
        RemoteCommand.UP -> navigateOrTap(RemoteCommand.UP)
        RemoteCommand.DOWN -> navigateOrTap(RemoteCommand.DOWN)
        RemoteCommand.LEFT -> navigateOrTap(RemoteCommand.LEFT)
        RemoteCommand.RIGHT -> navigateOrTap(RemoteCommand.RIGHT)
        RemoteCommand.PLAY -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        RemoteCommand.MEDIA_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        RemoteCommand.MEDIA_PREVIOUS -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        RemoteCommand.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
        RemoteCommand.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
        RemoteCommand.MUTE -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
        RemoteCommand.POWER -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
    }

    private fun navigateOrTap(command: RemoteCommand): Boolean {
        setDiagnostic("Command: $command\nStarting navigation injection…")

        if (Build.VERSION.SDK_INT >= 33) {
            val action = when (command) {
                RemoteCommand.UP -> GLOBAL_ACTION_DPAD_UP
                RemoteCommand.DOWN -> GLOBAL_ACTION_DPAD_DOWN
                RemoteCommand.LEFT -> GLOBAL_ACTION_DPAD_LEFT
                RemoteCommand.RIGHT -> GLOBAL_ACTION_DPAD_RIGHT
                RemoteCommand.OK -> GLOBAL_ACTION_DPAD_CENTER
                else -> -1
            }
            if (action != -1) {
                val ok = performGlobalAction(action)
                setDiagnostic("Command: $command\nMethod: Android system DPAD global action\nAccepted: $ok")
                CompanionPythonBridge.onStatus("NAV • $command • system-dpad=$ok")
                if (ok) return true
            }
        }

        val gestureAccepted = performNavigationGesture(command)
        setDiagnostic("Command: $command\nMethod: Accessibility dispatchGesture\nAccepted by Android: $gestureAccepted\nWaiting for completion callback…")
        CompanionPythonBridge.onStatus("NAV • $command • gesture-accepted=$gestureAccepted")
        if (gestureAccepted) return true

        val fallback = if (command == RemoteCommand.OK) clickFocused() else moveFocus(
            when (command) {
                RemoteCommand.UP -> View.FOCUS_UP
                RemoteCommand.DOWN -> View.FOCUS_DOWN
                RemoteCommand.LEFT -> View.FOCUS_LEFT
                RemoteCommand.RIGHT -> View.FOCUS_RIGHT
                else -> View.FOCUS_FORWARD
            }
        )
        setDiagnostic("Command: $command\nMethod: Accessibility node focus fallback\nResult: $fallback\nGesture was rejected before fallback.")
        CompanionPythonBridge.onStatus("NAV • $command • node-fallback=$fallback")
        return fallback
    }

    private fun performNavigationGesture(command: RemoteCommand): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        if (w <= 0f || h <= 0f) {
            setDiagnostic("Command: $command\nMethod: Accessibility gesture\nResult: FAILED\nReason: invalid display size ${w.toInt()}x${h.toInt()}")
            return false
        }

        val cx = w * 0.5f
        val cy = h * 0.5f
        val dx = w * 0.20f
        val dy = h * 0.20f
        val path = Path()

        if (command == RemoteCommand.OK) {
            path.moveTo(cx, cy)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
                .build()
            return dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    setDiagnostic("Command: OK\nMethod: Accessibility dispatchGesture (center tap)\nAccepted: YES\nCallback: COMPLETED")
                    CompanionPythonBridge.onStatus("NAV • OK • gesture-completed")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    setDiagnostic("Command: OK\nMethod: Accessibility dispatchGesture (center tap)\nAccepted: YES\nCallback: CANCELLED")
                    CompanionPythonBridge.onStatus("NAV • OK • gesture-cancelled")
                }
            }, null)
        }

        val endX = when (command) {
            RemoteCommand.LEFT -> cx - dx
            RemoteCommand.RIGHT -> cx + dx
            else -> cx
        }
        val endY = when (command) {
            RemoteCommand.UP -> cy - dy
            RemoteCommand.DOWN -> cy + dy
            else -> cy
        }
        path.moveTo(cx, cy)
        path.lineTo(endX, endY)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 150))
            .build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                setDiagnostic("Command: $command\nMethod: Accessibility dispatchGesture (swipe)\nDisplay: ${w.toInt()}x${h.toInt()}\nFrom: ${cx.toInt()},${cy.toInt()}\nTo: ${endX.toInt()},${endY.toInt()}\nAccepted: YES\nCallback: COMPLETED")
                CompanionPythonBridge.onStatus("NAV • $command • gesture-completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                setDiagnostic("Command: $command\nMethod: Accessibility dispatchGesture (swipe)\nDisplay: ${w.toInt()}x${h.toInt()}\nAccepted: YES\nCallback: CANCELLED")
                CompanionPythonBridge.onStatus("NAV • $command • gesture-cancelled")
            }
        }, null)
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
