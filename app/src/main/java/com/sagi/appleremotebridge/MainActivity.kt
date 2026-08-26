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
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(24))
        }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        renderHome()
    }

    private fun renderHome() {
        screen = Screen.HOME
        traceMode = false
        content.removeAllViews()

        val identity = CompanionIdentity(this)
        val ready = RemoteAccessibilityService.isConnected()

        // Header: glyph beside the wordmark, left aligned so the eye lands in one place.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_remote)
        }, LinearLayout.LayoutParams(dp(34), dp(51)).apply { setMargins(0, 0, dp(18), 0) })
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(TextView(this).apply {
            text = "Apple Remote Bridge"
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, matchWrap())
        heading.addView(TextView(this).apply {
            text = "Use the built-in iPhone Apple TV Remote with this TV"
            textSize = 15f
            alpha = 0.65f
        }, matchWrap())
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(header, matchWrap())

        // Body: status card on the left, a 2x2 action grid on the right. Fixed height so the
        // whole screen fits a 720p TV without ever scrolling.
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)).apply {
            setMargins(0, dp(22), 0, dp(16))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.tv_card)
            setPadding(dp(26), dp(20), dp(26), dp(20))
        }
        body.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f).apply {
            setMargins(0, 0, dp(16), 0)
        })
        card.addView(TextView(this).apply {
            text = "THIS TV"
            textSize = 13f
            letterSpacing = .14f
            alpha = 0.55f
        }, matchWrap())
        card.addView(TextView(this).apply {
            text = identity.deviceName
            textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(14))
        }, matchWrap())
        statusText = TextView(this).apply {
            text = if (ready) "● Ready to pair" else "● Accessibility permission required"
            textSize = 17f
            setTextColor(getColor(if (ready) R.color.ok else R.color.warn))
        }
        card.addView(statusText, matchWrap())
        card.addView(TextView(this).apply {
            text = "Pairing PIN 1337"
            textSize = 16f
            alpha = 0.6f
            setPadding(0, dp(10), 0, dp(18))
        }, matchWrap())
        card.addView(TextView(this).apply {
            text = "On your iPhone:  Control Center  →  Apple TV Remote"
            textSize = 15f
            alpha = 0.5f
        }, matchWrap())

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(grid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val start = tvButton("Start bridge") { startBridge() }
        val accessibility = tvButton("Accessibility") { openAccessibility() }
        val settings = tvButton("Settings") { renderSettings() }
        val debug = tvButton("Debug") { renderDebug() }
        grid.addView(tileRow(start, accessibility), tileRowParams(dp(8)))
        grid.addView(tileRow(settings, debug), tileRowParams(0))

        content.addView(TextView(this).apply {
            text = "Built by Weizmann.ai"
            textSize = 13f
            alpha = 0.4f
        }, matchWrap())

        (if (ready) start else accessibility).requestFocus()
    }

    private fun tileRow(left: View, right: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(0, 0, dp(8), 0)
        })
        addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun tileRowParams(bottom: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(0, 0, 0, bottom)
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
        background = getDrawable(R.drawable.tv_button)
        setTextColor(getColorStateList(R.color.tv_button_text))
        stateListAnimator = null
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
            background = getDrawable(R.drawable.tv_card)
            setPadding(dp(18), dp(14), dp(18), dp(14))
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

    override fun onResume() {
        super.onResume()
        // The accessibility service connects asynchronously and after a trip to system
        // Settings, so the home status would otherwise sit on a stale value until the
        // user navigated away and back.
        if (screen == Screen.HOME && !traceMode) renderHome()
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
