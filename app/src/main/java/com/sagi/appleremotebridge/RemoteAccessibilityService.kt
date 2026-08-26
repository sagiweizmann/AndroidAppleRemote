package com.sagi.appleremotebridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
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
        @Volatile private var lastMediaDiagnostic: String = "No media test has run yet."

        fun dispatch(c: RemoteCommand): Boolean = instance?.handle(c) ?: run {
            if (c == RemoteCommand.PLAY) lastMediaDiagnostic = "Command: PLAY/PAUSE\nResult: Accessibility service is not connected"
            else lastNavigationDiagnostic = "Command: $c\nResult: Accessibility service is not connected"
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

    // Inspired by Google's Android TV AccessibilityDemo FocusController: maintain one logical
    // current position and move it deterministically. No gestures/InputManager/backward fallbacks.
    private var logicalCurrent: NodeSignature? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        setNavDiagnostic("FocusController-only navigation enabled.\nWaiting for a command.")
        setMediaDiagnostic("Accessibility service connected.\nWaiting for a media command.")
        CompanionPythonBridge.onStatus("A11Y • connected • sdk=${Build.VERSION.SDK_INT} • focus-controller-only")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        logicalCurrent = null
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
        RemoteCommand.OK -> clickLogicalCurrent()
        RemoteCommand.UP -> moveLogical(View.FOCUS_UP, "UP")
        RemoteCommand.DOWN -> moveLogical(View.FOCUS_DOWN, "DOWN")
        RemoteCommand.LEFT -> moveLogical(View.FOCUS_LEFT, "LEFT")
        RemoteCommand.RIGHT -> moveLogical(View.FOCUS_RIGHT, "RIGHT")
        RemoteCommand.PLAY -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "PLAY_PAUSE")
        RemoteCommand.MEDIA_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "NEXT")
        RemoteCommand.MEDIA_PREVIOUS -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "PREVIOUS")
        RemoteCommand.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
        RemoteCommand.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
        RemoteCommand.MUTE -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
        RemoteCommand.POWER -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
    }

    private fun moveLogical(direction: Int, label: String): Boolean {
        val root = rootInActiveWindow ?: run {
            setNavDiagnostic("Command: $label\nResult: FAILED\nrootInActiveWindow=null")
            return false
        }

        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectFocusable(root, nodes)
        val current = resolveCurrent(root, nodes)
        val before = current?.let(::signature)

        // 1. The app's own focus machinery knows its focus order - nextFocusLeft, custom
        //    RecyclerView focus search - far better than geometry can infer it.
        val searched = current?.let { try { it.focusSearch(direction) } catch (_: Throwable) { null } }
        if (searched != null && focusNode(searched) && focusMoved(before)) {
            logicalCurrent = signature(searched)
            return report(label, "focusSearch", nodes.size, current, searched, true)
        }

        // 2. Geometric pick, for UIs that expose no usable focus order.
        val target = if (current == null) chooseInitial(nodes, direction)
                     else chooseDirectional(nodes, current, direction)
        if (target != null && focusNode(target) && focusMoved(before)) {
            logicalCurrent = signature(target)
            return report(label, "geometric", nodes.size, current, target, true)
        }

        // 3. TV rows are RecyclerViews that own their focus, so ACTION_FOCUS on an item that
        //    has been recycled or is not laid out yet is accepted and then ignored - which is
        //    why LEFT along a row almost always reported reached=false. Scrolling the
        //    container itself moves the row. This is a semantic scroll action, not the
        //    dispatchGesture synthetic-touch fallback that was removed earlier.
        if (scrollInDirection(current ?: root, direction)) {
            logicalCurrent = null
            return report(label, "scroll", nodes.size, current, null, true)
        }

        return report(label, "exhausted", nodes.size, current, target, false)
    }

    private fun focusMoved(before: NodeSignature?): Boolean {
        // performAction() reports delivery, not effect. Only a changed input focus counts.
        val root = rootInActiveWindow ?: return false
        val now = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(::signature) ?: return false
        return now != before
    }

    private fun scrollInDirection(from: AccessibilityNodeInfo, direction: Int): Boolean {
        val action = when (direction) {
            View.FOCUS_LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            View.FOCUS_RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
            View.FOCUS_UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            View.FOCUS_DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            else -> return false
        }
        val backward = direction == View.FOCUS_LEFT || direction == View.FOCUS_UP
        var node: AccessibilityNodeInfo? = from
        var depth = 0
        while (node != null && depth < 12) {
            try {
                if (node.actionList.contains(action) && node.performAction(action.id)) return true
                if (node.isScrollable) {
                    val legacy = if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                                 else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    if (node.performAction(legacy)) return true
                }
            } catch (_: Throwable) {}
            node = node.parent
            depth++
        }
        return false
    }

    private fun report(
        label: String,
        method: String,
        nodeCount: Int,
        before: AccessibilityNodeInfo?,
        target: AccessibilityNodeInfo?,
        moved: Boolean
    ): Boolean {
        setNavDiagnostic(
            "Command: $label\nMethod: $method\nFocusable nodes: $nodeCount\n" +
                "Before: ${before?.let(::describe) ?: "NONE"}\n" +
                "Target: ${target?.let(::describe) ?: "NONE"}\nFocus moved: $moved"
        )
        CompanionPythonBridge.onStatus("NAV • $label • $method • moved=$moved")
        return moved
    }

    private fun clickLogicalCurrent(): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectFocusable(root, nodes)
        val current = resolveCurrent(root, nodes) ?: nodes.firstOrNull() ?: return false
        var node: AccessibilityNodeInfo? = current
        var depth = 0
        while (node != null && depth < 8) {
            if (node.isClickable) {
                val ok = try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
                if (ok) {
                    logicalCurrent = signature(current)
                    setNavDiagnostic("Command: OK\nMethod: FocusController-only click\nTarget: ${describe(current)}\nACTION_CLICK: true")
                    CompanionPythonBridge.onStatus("NAV • OK • focus-controller click=true")
                    return true
                }
            }
            node = node.parent
            depth++
        }
        setNavDiagnostic("Command: OK\nMethod: FocusController-only click\nTarget: ${describe(current)}\nACTION_CLICK: false")
        CompanionPythonBridge.onStatus("NAV • OK • focus-controller click=false")
        return false
    }

    private fun resolveCurrent(root: AccessibilityNodeInfo, nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        // Prefer actual Android input focus. Then our logical FocusController position.
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        nodes.firstOrNull { it.isFocused }?.let { return it }
        logicalCurrent?.let { sig -> nodes.firstOrNull { signature(it) == sig }?.let { return it } }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { return it }
        nodes.firstOrNull { it.isAccessibilityFocused }?.let { return it }
        return null
    }

    private fun focusNode(node: AccessibilityNodeInfo): Boolean {
        // FocusController-only means only input focus. Deliberately do NOT use
        // ACTION_ACCESSIBILITY_FOCUS because that was the compatibility path which conflicted.
        return try { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Throwable) { false }
    }

    private fun chooseInitial(nodes: List<AccessibilityNodeInfo>, direction: Int): AccessibilityNodeInfo {
        return when (direction) {
            View.FOCUS_RIGHT, View.FOCUS_DOWN -> nodes.minByOrNull { positionScore(it) } ?: nodes.first()
            View.FOCUS_LEFT, View.FOCUS_UP -> nodes.maxByOrNull { positionScore(it) } ?: nodes.last()
            else -> nodes.first()
        }
    }

    private fun positionScore(node: AccessibilityNodeInfo): Long {
        val r = Rect().also(node::getBoundsInScreen)
        return r.top.toLong() * 100000L + r.left
    }

    private fun chooseDirectional(
        nodes: List<AccessibilityNodeInfo>,
        current: AccessibilityNodeInfo,
        direction: Int
    ): AccessibilityNodeInfo? {
        val cr = Rect().also(current::getBoundsInScreen)
        val cx = cr.exactCenterX()
        val cy = cr.exactCenterY()

        return nodes.asSequence()
            .filter { !sameNode(it, current) }
            .mapNotNull { candidate ->
                val r = Rect().also(candidate::getBoundsInScreen)
                if (r.isEmpty) return@mapNotNull null
                val dx = r.exactCenterX() - cx
                val dy = r.exactCenterY() - cy
                val valid = when (direction) {
                    View.FOCUS_UP -> dy < -1f
                    View.FOCUS_DOWN -> dy > 1f
                    View.FOCUS_LEFT -> dx < -1f
                    View.FOCUS_RIGHT -> dx > 1f
                    else -> false
                }
                if (!valid) return@mapNotNull null

                val vertical = direction == View.FOCUS_UP || direction == View.FOCUS_DOWN
                val primary = if (vertical) abs(dy) else abs(dx)
                val secondary = if (vertical) abs(dx) else abs(dy)
                // Same row/column first, then nearest target — equivalent to maintaining a
                // logical grid position like Google's FocusController.
                val sameLane = if (vertical) overlaps(cr.left, cr.right, r.left, r.right)
                               else overlaps(cr.top, cr.bottom, r.top, r.bottom)
                val lanePenalty = if (sameLane) 0.0 else 10_000_000.0
                candidate to (lanePenalty + primary * 1000.0 + secondary)
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun overlaps(a1: Int, a2: Int, b1: Int, b2: Int): Boolean =
        maxOf(a1, b1) <= minOf(a2, b2)

    private fun collectFocusable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val r = Rect().also(node::getBoundsInScreen)
        if (node.isVisibleToUser && !r.isEmpty && node.isEnabled && (node.isFocusable || node.isClickable)) {
            out += node
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectFocusable(it, out) }
    }

    private data class NodeSignature(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val cls: String, val text: String
    )

    private fun signature(node: AccessibilityNodeInfo): NodeSignature {
        val r = Rect().also(node::getBoundsInScreen)
        return NodeSignature(
            r.left, r.top, r.right, r.bottom,
            node.className?.toString().orEmpty(),
            node.text?.toString() ?: node.contentDescription?.toString().orEmpty()
        )
    }

    private fun sameNode(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean = signature(a) == signature(b)

    private fun describe(node: AccessibilityNodeInfo): String {
        val r = Rect().also(node::getBoundsInScreen)
        val text = node.text?.toString()?.take(24)
            ?: node.contentDescription?.toString()?.take(24)
            ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        return "$cls${if (text.isNotBlank()) "[$text]" else ""}@${r.left},${r.top}-${r.right},${r.bottom} f=${node.isFocused}"
    }

    private fun audio(): AudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun volume(direction: Int): Boolean = try {
        audio().adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
        true
    } catch (_: Throwable) { false }

    private fun mediaKey(code: Int, label: String): Boolean = try {
        val am = audio()
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        setMediaDiagnostic("Command: $label\nMethod: AudioManager.dispatchMediaKeyEvent\nKeyCode: $code\nDispatched: YES")
        CompanionPythonBridge.onStatus("MEDIA • $label • keyCode=$code • dispatched=true")
        true
    } catch (e: Throwable) {
        setMediaDiagnostic("Command: $label\nResult: FAILED\n${e.javaClass.simpleName}: ${e.message}")
        false
    }
}

enum class RemoteCommand {
    UP, DOWN, LEFT, RIGHT, OK, BACK, HOME,
    PLAY, MEDIA_NEXT, MEDIA_PREVIOUS,
    VOLUME_UP, VOLUME_DOWN, MUTE, POWER
}
