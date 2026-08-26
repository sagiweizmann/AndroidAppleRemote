package com.sagi.appleremotebridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.reflect.InvocationTargetException
import kotlin.math.abs

class RemoteAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: RemoteAccessibilityService? = null
        @Volatile private var lastNavigationDiagnostic: String = "No navigation test has run yet."
        @Volatile private var lastMediaDiagnostic: String = "No media test has run yet."

        fun dispatch(c: RemoteCommand): Boolean = instance?.handle(c) ?: run {
            if (c == RemoteCommand.PLAY) {
                lastMediaDiagnostic = "Command: PLAY/PAUSE\nResult: Accessibility service is not connected"
            } else {
                lastNavigationDiagnostic = "Command: $c\nResult: Accessibility service is not connected"
            }
            false
        }

        fun isConnected(): Boolean = instance != null
        fun navigationDiagnostic(): String =
            "Accessibility connected: ${if (instance != null) "YES" else "NO"}\nAndroid SDK: ${Build.VERSION.SDK_INT}\n\n$lastNavigationDiagnostic"
        fun mediaDiagnostic(): String =
            "Accessibility connected: ${if (instance != null) "YES" else "NO"}\nAndroid SDK: ${Build.VERSION.SDK_INT}\n\n$lastMediaDiagnostic"

        private fun setNavDiagnostic(text: String) { lastNavigationDiagnostic = text }
        private fun setMediaDiagnostic(text: String) { lastMediaDiagnostic = text }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        setNavDiagnostic("Accessibility service connected.\nWaiting for a navigation command.")
        setMediaDiagnostic("Accessibility service connected.\nWaiting for a media command.")
        CompanionPythonBridge.onStatus("A11Y • connected • sdk=${Build.VERSION.SDK_INT}")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        setNavDiagnostic("Accessibility service disconnected.")
        setMediaDiagnostic("Accessibility service disconnected.")
        CompanionPythonBridge.onStatus("A11Y • disconnected")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun handle(c: RemoteCommand): Boolean = when (c) {
        RemoteCommand.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        RemoteCommand.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        RemoteCommand.OK -> stableOk()
        RemoteCommand.UP -> navigateDpad(c, KeyEvent.KEYCODE_DPAD_UP, View.FOCUS_UP)
        RemoteCommand.DOWN -> navigateDpad(c, KeyEvent.KEYCODE_DPAD_DOWN, View.FOCUS_DOWN)
        RemoteCommand.LEFT -> navigateDpad(c, KeyEvent.KEYCODE_DPAD_LEFT, View.FOCUS_LEFT)
        RemoteCommand.RIGHT -> navigateDpad(c, KeyEvent.KEYCODE_DPAD_RIGHT, View.FOCUS_RIGHT)
        RemoteCommand.PLAY -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "PLAY_PAUSE")
        RemoteCommand.MEDIA_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "NEXT")
        RemoteCommand.MEDIA_PREVIOUS -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "PREVIOUS")
        RemoteCommand.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
        RemoteCommand.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
        RemoteCommand.MUTE -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
        RemoteCommand.POWER -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
    }

    private fun stableOk(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)
            setNavDiagnostic("Command: OK\nMethod: Android system DPAD center\nResult: $ok")
            CompanionPythonBridge.onStatus("NAV • OK • system-dpad=$ok")
            if (ok) return true
        }

        // Keep OK on the proven Accessibility click path. Do not run smart focus/gesture code here.
        val clicked = clickFocused()
        setNavDiagnostic("Command: OK\nMethod: Accessibility focused-node click\nResult: $clicked")
        CompanionPythonBridge.onStatus("NAV • OK • focused-click=$clicked")
        return clicked
    }

    private fun navigateDpad(command: RemoteCommand, keyCode: Int, focusDirection: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val action = when (command) {
                RemoteCommand.UP -> GLOBAL_ACTION_DPAD_UP
                RemoteCommand.DOWN -> GLOBAL_ACTION_DPAD_DOWN
                RemoteCommand.LEFT -> GLOBAL_ACTION_DPAD_LEFT
                RemoteCommand.RIGHT -> GLOBAL_ACTION_DPAD_RIGHT
                else -> -1
            }
            if (action != -1) {
                val ok = performGlobalAction(action)
                setNavDiagnostic("Command: $command\nMethod: Android system DPAD global action\nResult: $ok")
                CompanionPythonBridge.onStatus("NAV • $command • system-dpad=$ok")
                if (ok) return true
            }
        }

        // Android 10-12 experiment: call InputManager.injectInputEvent by reflection.
        val injected = injectKeyWithInputManager(command, keyCode)
        if (injected) return true

        // Non-destructive fallback, useful only on Views exposing real focus through Accessibility.
        val fallback = moveFocus(focusDirection)
        CompanionPythonBridge.onStatus("NAV • $command • node-fallback=$fallback")
        if (!fallback) {
            // Preserve the InputManager failure reason in the diagnostic; only append fallback result.
            setNavDiagnostic(lastNavigationDiagnostic + "\nNode focus fallback: $fallback")
        }
        return fallback
    }

    private fun injectKeyWithInputManager(command: RemoteCommand, keyCode: Int): Boolean {
        return try {
            val clazz = Class.forName("android.hardware.input.InputManager")
            val getInstance = clazz.getDeclaredMethod("getInstance")
            getInstance.isAccessible = true
            val manager = getInstance.invoke(null)

            val inputEventClass = Class.forName("android.view.InputEvent")
            val inject = clazz.getDeclaredMethod("injectInputEvent", inputEventClass, Int::class.javaPrimitiveType)
            inject.isAccessible = true

            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)

            // INJECT_INPUT_EVENT_MODE_ASYNC = 0
            val downResult = inject.invoke(manager, down, 0) as? Boolean ?: false
            val upResult = inject.invoke(manager, up, 0) as? Boolean ?: false
            val ok = downResult && upResult

            setNavDiagnostic(
                "Command: $command\nMethod: InputManager.injectInputEvent reflection\n" +
                    "KeyCode: $keyCode\nDOWN result: $downResult\nUP result: $upResult\nOverall: $ok"
            )
            CompanionPythonBridge.onStatus("NAV • $command • inputmanager down=$downResult up=$upResult")
            ok
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            val type = cause.javaClass.simpleName
            val message = cause.message ?: "no message"
            setNavDiagnostic(
                "Command: $command\nMethod: InputManager.injectInputEvent reflection\n" +
                    "Result: BLOCKED/FAILED\n$type: $message\n" +
                    if (cause is SecurityException) "Conclusion: firmware requires INJECT_EVENTS/system or shell privilege." else ""
            )
            CompanionPythonBridge.onStatus("NAV • $command • inputmanager exception=$type • $message")
            false
        } catch (e: Throwable) {
            val type = e.javaClass.simpleName
            val message = e.message ?: "no message"
            setNavDiagnostic(
                "Command: $command\nMethod: InputManager.injectInputEvent reflection\n" +
                    "Result: FAILED\n$type: $message"
            )
            CompanionPythonBridge.onStatus("NAV • $command • inputmanager exception=$type • $message")
            false
        }
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
        val input = try { next.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Throwable) { false }
        val a11y = try { next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) } catch (_: Throwable) { false }
        return input || a11y
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
        var depth = 0
        while (depth < 8) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent ?: break
            depth++
        }
        return false
    }

    private fun audio(): AudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun volume(direction: Int): Boolean = try {
        audio().adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
        true
    } catch (_: Throwable) {
        false
    }

    private fun mediaKey(code: Int, label: String): Boolean = try {
        val am = audio()
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        setMediaDiagnostic("Command: $label\nMethod: AudioManager.dispatchMediaKeyEvent\nKeyCode: $code\nDispatched: YES")
        CompanionPythonBridge.onStatus("MEDIA • $label • keyCode=$code • dispatched=true")
        true
    } catch (e: Throwable) {
        setMediaDiagnostic("Command: $label\nMethod: AudioManager.dispatchMediaKeyEvent\nResult: FAILED\n${e.javaClass.simpleName}: ${e.message}")
        CompanionPythonBridge.onStatus("MEDIA • $label • dispatched=false • ${e.javaClass.simpleName}")
        false
    }
}

enum class RemoteCommand {
    UP, DOWN, LEFT, RIGHT, OK, BACK, HOME,
    PLAY, MEDIA_NEXT, MEDIA_PREVIOUS,
    VOLUME_UP, VOLUME_DOWN, MUTE, POWER
}
