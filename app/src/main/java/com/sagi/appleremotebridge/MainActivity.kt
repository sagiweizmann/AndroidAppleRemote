package com.sagi.appleremotebridge

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private enum class Screen { HOME, SETTINGS, DEBUG }

    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView
    private var screen = Screen.HOME
    private var traceMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(this)
        CompanionPythonBridge.initialize(applicationContext)
        super.onCreate(savedInstanceState)

        scroll = ScrollView(this).apply {
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(56), dp(44), dp(56), dp(32))
        }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        renderHome()
    }

    private fun renderHome() {
        screen = Screen.HOME
        traceMode = false
        content.removeAllViews()

        title("Apple Remote Bridge")
        subtitle("Use the built-in iPhone Apple TV Remote with Android TV")

        val identity = CompanionIdentity(this)
        val card = section("YOUR TV")
        card.addView(TextView(this).apply {
            text = identity.deviceName
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(20), dp(18), dp(8))
        }, matchWrap())
        statusText = TextView(this).apply {
            text = if (RemoteAccessibilityService.isConnected()) "Accessibility ready" else "Accessibility permission required"
            textSize = 16f
            gravity = Gravity.CENTER
            alpha = 0.75f
            setPadding(dp(12), dp(4), dp(12), dp(20))
        }
        card.addView(statusText, matchWrap())

        val start = tvButton("Start Remote Bridge") { startBridge() }
        val accessibility = tvButton("Enable Accessibility") { openAccessibility() }
        val settings = tvButton("Settings") { renderSettings() }
        val debug = tvButton("Debug & Advanced") { renderDebug() }
        content.addView(start, matchWrap(dp(10)))
        content.addView(accessibility, matchWrap(dp(10)))

        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottomRow.addView(settings, LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(0, dp(10), dp(6), dp(10)) })
        bottomRow.addView(debug, LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(dp(6), dp(10), 0, dp(10)) })
        content.addView(bottomRow, matchWrap())

        spacer(26)
        footer()
        start.requestFocus()
    }

    private fun renderSettings() {
        screen = Screen.SETTINGS
        traceMode = false
        content.removeAllViews()
        title("Settings")
        subtitle("Changing the TV name creates a fresh pairing identity")

        val identity = CompanionIdentity(this)
        val box = section("DEVICE NAME")
        val input = EditText(this).apply {
            setText(identity.deviceName)
            hint = "Living Room TV"
            textSize = 20f
            isSingleLine = true
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        box.addView(input, matchWrap(dp(8)))

        val save = tvButton("Save name & create new identity") {
            val name = input.text?.toString().orEmpty()
            stopService(Intent(this, CompanionBridgeService::class.java))
            val applied = CompanionIdentity(this).rotateTo(name)
            CompanionPythonBridge.clearTrace()
            CrashReporter.clear(this)
            Toast.makeText(this, "New identity: $applied", Toast.LENGTH_SHORT).show()
            android.os.Handler(mainLooper).postDelayed({ startBridge(); renderHome() }, 700)
        }
        val back = tvButton("Back") { renderHome() }
        content.addView(save, matchWrap(dp(10)))
        content.addView(back, matchWrap(dp(10)))
        spacer(26)
        footer()
        input.requestFocus()
    }

    private fun renderDebug() {
        screen = Screen.DEBUG
        traceMode = false
        content.removeAllViews()
        title("Debug & Advanced")
        subtitle("Pairing diagnostics and manual remote tests")

        statusText = TextView(this).apply {
            text = "Advanced tools"
            textSize = 16f
            gravity = Gravity.CENTER
            alpha = 0.78f
        }
        content.addView(statusText, matchWrap(dp(8)))

        val identity = CompanionIdentity(this)
        val identityBox = section("IDENTITY")
        identityBox.addView(TextView(this).apply {
            text = "${identity.deviceName}\nGeneration ${identity.generation}"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
        }, matchWrap())

        val randomIdentity = tvButton("New random identity") {
            stopService(Intent(this, CompanionBridgeService::class.java))
            val name = CompanionIdentity(this).rotate()
            CompanionPythonBridge.clearTrace(); CrashReporter.clear(this)
            statusText.text = "New identity: $name"
            android.os.Handler(mainLooper).postDelayed({ startBridge(); renderDebug() }, 700)
        }
        content.addView(randomIdentity, matchWrap(dp(8)))

        sectionLabel("REMOTE TEST")
        content.addView(remoteButton("▲ UP", RemoteCommand.UP), matchWrap(dp(5)))
        val dpad = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dpad.addView(remoteButton("◀ LEFT", RemoteCommand.LEFT), weightedButton())
        dpad.addView(remoteButton("OK", RemoteCommand.OK), weightedButton())
        dpad.addView(remoteButton("RIGHT ▶", RemoteCommand.RIGHT), weightedButton())
        content.addView(dpad, matchWrap())
        content.addView(remoteButton("▼ DOWN", RemoteCommand.DOWN), matchWrap(dp(5)))

        val media = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        media.addView(remoteButton("VOL +", RemoteCommand.VOLUME_UP), weightedButton())
        media.addView(remoteButton("PLAY", RemoteCommand.PLAY), weightedButton())
        media.addView(remoteButton("VOL -", RemoteCommand.VOLUME_DOWN), weightedButton())
        content.addView(media, matchWrap())
        content.addView(remoteButton("MUTE", RemoteCommand.MUTE), matchWrap(dp(5)))

        sectionLabel("DIAGNOSTICS")
        val navDiag = tvButton("Show navigation diagnostics") { showNavigationDiagnostics() }
        val trace = tvButton("Open pairing trace") { showTrace() }
        val clear = tvButton("Clear diagnostics") {
            CrashReporter.clear(this); CompanionPythonBridge.clearTrace(); statusText.text = "Diagnostics cleared"
        }
        val accessibility = tvButton("Accessibility settings") { openAccessibility() }
        val stop = tvButton("Stop bridge") { stopService(Intent(this, CompanionBridgeService::class.java)); statusText.text = "Bridge stopped" }
        val back = tvButton("Back to app") { renderHome() }
        listOf(navDiag, trace, clear, accessibility, stop, back).forEach { content.addView(it, matchWrap(dp(7))) }
        spacer(22)
        footer()
        randomIdentity.requestFocus()
    }

    private fun showNavigationDiagnostics() {
        traceMode = true
        content.removeAllViews()
        title("Navigation Diagnostics")
        subtitle("Shows exactly what Android did with the last navigation command")
        content.addView(TextView(this).apply {
            text = RemoteAccessibilityService.navigationDiagnostic()
            textSize = 18f
            setPadding(dp(18), dp(18), dp(18), dp(18))
            typeface = android.graphics.Typeface.MONOSPACE
        }, matchWrap(dp(8)))
        content.addView(TextView(this).apply {
            text = "Run LEFT / RIGHT / UP / DOWN / OK from the Debug screen or from the iPhone remote, then reopen this page."
            textSize = 15f
            gravity = Gravity.CENTER
            alpha = 0.7f
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }, matchWrap())
        val back = tvButton("Back to Debug") { renderDebug() }
        content.addView(back, matchWrap(dp(10)))
        footer()
        back.requestFocus()
    }

    private fun showTrace() {
        traceMode = true
        content.removeAllViews()
        title("Pairing Trace")
        subtitle("UP / DOWN scroll • OK or BACK returns")
        val last = CrashReporter.read(this)
        val trace = CompanionPythonBridge.getTrace()
        content.addView(TextView(this).apply {
            text = buildString {
                if (!last.isNullOrBlank()) append("LAST ERROR\n$last\n\n")
                append(if (trace.isBlank()) "No trace yet." else trace)
            }
            textSize = 15f
            setPadding(dp(16), dp(16), dp(16), dp(16))
            typeface = android.graphics.Typeface.MONOSPACE
        }, matchWrap())
        footer()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun remoteButton(label: String, command: RemoteCommand) = tvButton(label) {
        statusText.text = if (RemoteAccessibilityService.dispatch(command)) "Sent $label" else "Accessibility service not connected"
    }

    private fun tvButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 17f
        isAllCaps = false
        isFocusable = true
        isFocusableInTouchMode = true
        minHeight = dp(62)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        setOnClickListener { action() }
        setOnFocusChangeListener { view, focused ->
            view.animate().scaleX(if (focused) 1.045f else 1f).scaleY(if (focused) 1.045f else 1f).setDuration(110).start()
            if (focused && !traceMode) scroll.post { scroll.requestChildRectangleOnScreen(view, Rect(0, 0, view.width, view.height), true) }
        }
    }

    private fun section(label: String): LinearLayout {
        sectionLabel(label)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            content.addView(this, matchWrap(dp(8)))
        }
    }

    private fun title(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 32f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(4))
        }, matchWrap())
    }

    private fun subtitle(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 17f
            gravity = Gravity.CENTER
            alpha = 0.72f
            setPadding(dp(12), 0, dp(12), dp(22))
        }, matchWrap())
    }

    private fun sectionLabel(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            letterSpacing = .12f
            alpha = 0.72f
            setPadding(dp(4), dp(16), dp(4), dp(6))
        }, matchWrap())
    }

    private fun footer() {
        content.addView(TextView(this).apply {
            text = "Built by Weizmann.ai"
            textSize = 14f
            gravity = Gravity.CENTER
            alpha = 0.5f
            setPadding(0, dp(12), 0, dp(12))
        }, matchWrap())
    }

    private fun spacer(height: Int) = content.addView(Space(this), ViewGroup.LayoutParams(1, dp(height)))
    private fun matchWrap(margin: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, margin, 0, margin) }
    private fun weightedButton() = LinearLayout.LayoutParams(0, dp(62), 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun openAccessibility() {
        val component = ComponentName(this, RemoteAccessibilityService::class.java)
        val candidates = listOf(
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
            },
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in candidates) {
            try {
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    CompanionPythonBridge.onStatus("A11Y SETTINGS • opened ${intent.action}")
                    return
                }
            } catch (e: Throwable) {
                CompanionPythonBridge.onStatus("A11Y SETTINGS • ${intent.action} failed: ${e.javaClass.simpleName}")
            }
        }

        Toast.makeText(this, "This Android TV firmware does not expose Accessibility settings to apps.", Toast.LENGTH_LONG).show()
        CompanionPythonBridge.onStatus("A11Y SETTINGS • no supported settings activity")
    }

    private fun startBridge() {
        try {
            val intent = Intent(this, CompanionBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            if (::statusText.isInitialized) statusText.text = "Bridge running as ${CompanionIdentity(this).deviceName}"
        } catch (e: Throwable) {
            CrashReporter.save(this, "START SERVICE", e)
            if (::statusText.isInitialized) statusText.text = "Start failed: ${e.message}"
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (traceMode && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { scroll.smoothScrollBy(0, dp(280)); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { scroll.smoothScrollBy(0, -dp(280)); return true }
                KeyEvent.KEYCODE_BACK -> { renderDebug(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            traceMode -> renderDebug()
            screen != Screen.HOME -> renderHome()
            else -> super.onBackPressed()
        }
    }
}
