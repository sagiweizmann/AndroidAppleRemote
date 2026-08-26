package com.sagi.appleremotebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.max

class RemoteAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: RemoteAccessibilityService? = null
        @Volatile private var lastNavigationDiagnostic: String = "No navigation test has run yet."
        @Volatile private var lastMediaDiagnostic: String = "No media test has run yet."

        fun dispatch(c: RemoteCommand): Boolean = instance?.handle(c) ?: run {
            if (c == RemoteCommand.PLAY) lastMediaDiagnostic = "Command: PLAY/PAUSE\nResult: Accessibility service is not connected"
            else lastNavigationDiagnostic = "Command: $c\nResult: Accessibility service is not connected"
            false
        }
        fun isConnected(): Boolean = instance != null
        fun navigationDiagnostic(): String = "Accessibility connected: ${if (instance != null) "YES" else "NO"}\nAndroid SDK: ${Build.VERSION.SDK_INT}\n\n$lastNavigationDiagnostic"
        fun mediaDiagnostic(): String = "Accessibility connected: ${if (instance != null) "YES" else "NO"}\nAndroid SDK: ${Build.VERSION.SDK_INT}\n\n$lastMediaDiagnostic"
        private fun setNavDiagnostic(text: String) { lastNavigationDiagnostic = text }
        private fun setMediaDiagnostic(text: String) { lastMediaDiagnostic = text }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        setNavDiagnostic("Accessibility service connected.\nWaiting for a navigation command.")
        setMediaDiagnostic("Accessibility service connected.\nWaiting for a media command.")
        CompanionPythonBridge.onStatus("A11Y • connected • sdk=${Build.VERSION.SDK_INT} • gestures=enabled")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        setNavDiagnostic("Accessibility service disconnected.")
        setMediaDiagnostic("Accessibility service disconnected.")
        CompanionPythonBridge.onStatus("A11Y • disconnected")
        super.onDestroy()
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun handle(c: RemoteCommand): Boolean = when (c) {
        RemoteCommand.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        RemoteCommand.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        RemoteCommand.OK, RemoteCommand.UP, RemoteCommand.DOWN, RemoteCommand.LEFT, RemoteCommand.RIGHT -> navigateOrTap(c)
        RemoteCommand.PLAY -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "PLAY_PAUSE")
        RemoteCommand.MEDIA_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "NEXT")
        RemoteCommand.MEDIA_PREVIOUS -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "PREVIOUS")
        RemoteCommand.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
        RemoteCommand.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
        RemoteCommand.MUTE -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
        RemoteCommand.POWER -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
    }

    private fun navigateOrTap(command: RemoteCommand): Boolean {
        setNavDiagnostic("Command: $command\nStarting navigation injection…")

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
                setNavDiagnostic("Command: $command\nMethod: Android system DPAD global action\nAccepted: $ok")
                CompanionPythonBridge.onStatus("NAV • $command • system-dpad=$ok")
                if (ok) return true
            }
        }

        // On Android TV 10-12, try manipulating the actual accessibility/view focus first.
        // A completed touchscreen swipe does not imply DPAD navigation on TV launchers.
        val smart = smartNodeNavigation(command)
        if (smart) return true

        // Last-resort experiment for touch-aware TV apps.
        val gestureAccepted = performNavigationGesture(command)
        setNavDiagnostic("Command: $command\nMethod: gesture fallback\nAccepted by Android: $gestureAccepted\nSmart node navigation did not find/apply a target.")
        CompanionPythonBridge.onStatus("NAV • $command • gesture-fallback=$gestureAccepted")
        return gestureAccepted
    }

    private fun smartNodeNavigation(command: RemoteCommand): Boolean {
        val root = rootInActiveWindow ?: run {
            setNavDiagnostic("Command: $command\nMethod: smart Accessibility nodes\nResult: FAILED\nReason: rootInActiveWindow is null")
            CompanionPythonBridge.onStatus("NAV • $command • smart-root=null")
            return false
        }

        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectFocusable(root, nodes)
        val current = findCurrentFocus(root, nodes)

        if (command == RemoteCommand.OK) {
            val target = current ?: nodes.firstOrNull()
            if (target == null) {
                setNavDiagnostic("Command: OK\nMethod: smart Accessibility nodes\nResult: FAILED\nFocusable nodes: ${nodes.size}\nReason: no current/target node")
                return false
            }
            val clicked = clickNodeOrParent(target)
            setNavDiagnostic("Command: OK\nMethod: smart Accessibility nodes\nFocusable nodes: ${nodes.size}\nTarget: ${describe(target)}\nACTION_CLICK: $clicked")
            CompanionPythonBridge.onStatus("NAV • OK • smart-click=$clicked • nodes=${nodes.size}")
            return clicked
        }

        if (current == null) {
            val first = chooseInitialNode(nodes, command)
            if (first == null) {
                setNavDiagnostic("Command: $command\nMethod: smart Accessibility nodes\nResult: FAILED\nFocusable nodes: ${nodes.size}\nReason: no focused or focusable node")
                return false
            }
            val applied = applyFocus(first, null)
            scheduleFocusVerification(command, null, first, nodes.size, applied)
            return applied
        }

        val direction = when (command) {
            RemoteCommand.UP -> View.FOCUS_UP
            RemoteCommand.DOWN -> View.FOCUS_DOWN
            RemoteCommand.LEFT -> View.FOCUS_LEFT
            RemoteCommand.RIGHT -> View.FOCUS_RIGHT
            else -> View.FOCUS_FORWARD
        }

        val frameworkTarget = try { current.focusSearch(direction) } catch (_: Throwable) { null }
        val target = frameworkTarget?.takeIf { isUsableTarget(it) && !sameNode(it, current) }
            ?: spatialNeighbor(nodes, current, direction)

        if (target == null) {
            setNavDiagnostic(
                "Command: $command\nMethod: smart Accessibility nodes\nFocusable nodes: ${nodes.size}\n" +
                    "Before: ${describe(current)}\nTarget: NONE\nResult: no directional candidate"
            )
            CompanionPythonBridge.onStatus("NAV • $command • smart-target=none • nodes=${nodes.size}")
            return false
        }

        val applied = applyFocus(target, current)
        setNavDiagnostic(
            "Command: $command\nMethod: smart Accessibility nodes\nFocusable nodes: ${nodes.size}\n" +
                "Before: ${describe(current)}\nTarget: ${describe(target)}\n" +
                "Focus action accepted: $applied\nWaiting to verify resulting focus…"
        )
        CompanionPythonBridge.onStatus("NAV • $command • smart-focus=$applied • nodes=${nodes.size}")
        scheduleFocusVerification(command, current, target, nodes.size, applied)
        return applied
    }

    private fun findCurrentFocus(root: AccessibilityNodeInfo, nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { return it }
        nodes.firstOrNull { it.isFocused }?.let { return it }
        nodes.firstOrNull { it.isAccessibilityFocused }?.let { return it }
        return null
    }

    private fun chooseInitialNode(nodes: List<AccessibilityNodeInfo>, command: RemoteCommand): AccessibilityNodeInfo? {
        if (nodes.isEmpty()) return null
        return when (command) {
            RemoteCommand.RIGHT, RemoteCommand.DOWN -> nodes.minByOrNull { n -> Rect().also(n::getBoundsInScreen).let { it.top.toLong() * 10000L + it.left } }
            RemoteCommand.LEFT, RemoteCommand.UP -> nodes.maxByOrNull { n -> Rect().also(n::getBoundsInScreen).let { it.bottom.toLong() * 10000L + it.right } }
            else -> nodes.firstOrNull()
        }
    }

    private fun applyFocus(target: AccessibilityNodeInfo, current: AccessibilityNodeInfo?): Boolean {
        try { current?.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) } catch (_: Throwable) {}
        val input = try { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Throwable) { false }
        val a11y = try { target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) } catch (_: Throwable) { false }
        return input || a11y
    }

    private fun scheduleFocusVerification(
        command: RemoteCommand,
        before: AccessibilityNodeInfo?,
        target: AccessibilityNodeInfo,
        nodeCount: Int,
        accepted: Boolean
    ) {
        val beforeDesc = before?.let(::describe) ?: "NONE"
        val targetDesc = describe(target)
        mainHandler.postDelayed({
            val freshRoot = rootInActiveWindow
            if (freshRoot == null) {
                setNavDiagnostic("Command: $command\nMethod: smart Accessibility nodes\nBefore: $beforeDesc\nTarget: $targetDesc\nFocus action accepted: $accepted\nAfter: ROOT NULL")
                return@postDelayed
            }
            val freshNodes = mutableListOf<AccessibilityNodeInfo>()
            collectFocusable(freshRoot, freshNodes)
            val after = findCurrentFocus(freshRoot, freshNodes)
            val afterDesc = after?.let(::describe) ?: "NONE"
            val changed = after != null && (before == null || !sameNode(after, before))
            val reachedTarget = after != null && sameNode(after, target)
            setNavDiagnostic(
                "Command: $command\nMethod: smart Accessibility nodes\nFocusable nodes: $nodeCount -> ${freshNodes.size}\n" +
                    "Before: $beforeDesc\nTarget: $targetDesc\nAfter: $afterDesc\n" +
                    "Focus action accepted: $accepted\nFocus changed: $changed\nReached target: $reachedTarget"
            )
            CompanionPythonBridge.onStatus("NAV • $command • verify changed=$changed target=$reachedTarget")
        }, 120)
    }

    private fun spatialNeighbor(nodes: List<AccessibilityNodeInfo>, current: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        val cr = Rect().also(current::getBoundsInScreen)
        val cx = cr.exactCenterX(); val cy = cr.exactCenterY()
        return nodes.asSequence()
            .filter { !sameNode(it, current) && isUsableTarget(it) }
            .mapNotNull { n ->
                val r = Rect().also(n::getBoundsInScreen)
                if (r.isEmpty) return@mapNotNull null
                val tx = r.exactCenterX(); val ty = r.exactCenterY()
                val dx = tx - cx; val dy = ty - cy
                val valid = when (direction) {
                    View.FOCUS_UP -> dy < -1
                    View.FOCUS_DOWN -> dy > 1
                    View.FOCUS_LEFT -> dx < -1
                    View.FOCUS_RIGHT -> dx > 1
                    else -> false
                }
                if (!valid) return@mapNotNull null

                val vertical = direction == View.FOCUS_UP || direction == View.FOCUS_DOWN
                val primary = if (vertical) abs(dy) else abs(dx)
                val secondary = if (vertical) abs(dx) else abs(dy)
                val beamOverlap = if (vertical) rangesOverlap(cr.left, cr.right, r.left, r.right)
                                  else rangesOverlap(cr.top, cr.bottom, r.top, r.bottom)
                // Strongly prefer candidates in the same row/column (Android TV style focus beam),
                // then nearest primary-axis distance, then perpendicular distance.
                val beamPenalty = if (beamOverlap) 0.0 else 1_000_000.0
                val score = beamPenalty + primary * 1000.0 + secondary
                n to score
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun rangesOverlap(a1: Int, a2: Int, b1: Int, b2: Int): Boolean = max(a1, b1) <= kotlin.math.min(a2, b2)

    private fun isUsableTarget(node: AccessibilityNodeInfo): Boolean {
        val r = Rect().also(node::getBoundsInScreen)
        return node.isVisibleToUser && !r.isEmpty && (node.isFocusable || node.isClickable || node.isEnabled)
    }

    private fun collectFocusable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (isUsableTarget(node) && (node.isFocusable || node.isClickable)) out += node
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectFocusable(it, out) }
    }

    private fun sameNode(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean {
        if (a == b) return true
        val ar = Rect().also(a::getBoundsInScreen)
        val br = Rect().also(b::getBoundsInScreen)
        return ar == br && a.className?.toString() == b.className?.toString() && a.text?.toString() == b.text?.toString()
    }

    private fun describe(node: AccessibilityNodeInfo): String {
        val r = Rect().also(node::getBoundsInScreen)
        val text = node.text?.toString()?.take(28)
            ?: node.contentDescription?.toString()?.take(28)
            ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        return "$cls${if (id.isNotBlank()) "#$id" else ""}${if (text.isNotBlank()) "[$text]" else ""}@${r.left},${r.top}-${r.right},${r.bottom} f=${node.isFocused} af=${node.isAccessibilityFocused}"
    }

    private fun clickNodeOrParent(start: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = start
        var depth = 0
        while (node != null && depth < 8) {
            if (node.isClickable) {
                val ok = try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
                if (ok) return true
            }
            node = node.parent
            depth++
        }
        return false
    }

    private fun performNavigationGesture(command: RemoteCommand): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        if (w <= 0f || h <= 0f) return false
        val cx = w * .5f; val cy = h * .5f; val dx = w * .20f; val dy = h * .20f
        val path = Path()
        if (command == RemoteCommand.OK) {
            path.moveTo(cx, cy)
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build()
            return dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { CompanionPythonBridge.onStatus("NAV • OK • gesture-fallback-completed") }
                override fun onCancelled(g: GestureDescription?) { CompanionPythonBridge.onStatus("NAV • OK • gesture-fallback-cancelled") }
            }, null)
        }
        val endX = when (command) { RemoteCommand.LEFT -> cx - dx; RemoteCommand.RIGHT -> cx + dx; else -> cx }
        val endY = when (command) { RemoteCommand.UP -> cy - dy; RemoteCommand.DOWN -> cy + dy; else -> cy }
        path.moveTo(cx, cy); path.lineTo(endX, endY)
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 150)).build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { CompanionPythonBridge.onStatus("NAV • $command • gesture-fallback-completed") }
            override fun onCancelled(g: GestureDescription?) { CompanionPythonBridge.onStatus("NAV • $command • gesture-fallback-cancelled") }
        }, null)
    }

    private fun audio(): AudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun volume(direction: Int): Boolean = try { audio().adjustVolume(direction, AudioManager.FLAG_SHOW_UI); true } catch (_: Throwable) { false }

    private fun mediaKey(code: Int, label: String): Boolean = try {
        val am = audio()
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        setMediaDiagnostic("Command: $label\nMethod: AudioManager.dispatchMediaKeyEvent\nKeyCode: $code\nDOWN sent: YES\nUP sent: YES\nNote: Android does not return whether the active media app consumed the key.")
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
