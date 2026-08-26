package com.sagi.appleremotebridge

import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
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
    private lateinit var status: TextView
    private lateinit var errorBox: TextView
    private lateinit var traceBox: TextView
    private lateinit var scroll: ScrollView
    private lateinit var startButton: Button
    private var traceMode = false

    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val bg get() = if (dark) Color.rgb(13, 15, 20) else Color.rgb(242, 244, 248)
    private val surface get() = if (dark) Color.rgb(25, 28, 35) else Color.WHITE
    private val surfaceAlt get() = if (dark) Color.rgb(34, 38, 47) else Color.rgb(232, 235, 241)
    private val textPrimary get() = if (dark) Color.WHITE else Color.rgb(20, 22, 26)
    private val textSecondary get() = if (dark) Color.rgb(181, 188, 201) else Color.rgb(91, 98, 111)
    private val accent get() = Color.rgb(87, 143, 255)
    private val danger get() = Color.rgb(210, 73, 73)

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(this)
        CompanionPythonBridge.initialize(applicationContext)
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(bg)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(54), dp(38), dp(54), dp(54))
            isFocusable = false
        }

        scroll = ScrollView(this).apply {
            isFocusable = false
            isFillViewport = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setBackgroundColor(bg)
        }

        val hero = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }
        hero.addView(TextView(this).apply {
            text = "Apple Remote Bridge"
            textSize = 30f
            setTextColor(textPrimary)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        hero.addView(TextView(this).apply {
            text = "Use the built-in iPhone Apple TV Remote with Android TV"
            textSize = 16f
            setTextColor(textSecondary)
            setPadding(0, dp(8), 0, dp(4))
        })
        status = TextView(this).apply {
            text = "Ready to start"
            textSize = 16f
            setTextColor(accent)
            setPadding(0, dp(12), 0, 0)
        }
        hero.addView(status)
        content.addView(hero, lp(match = true, top = 0, bottom = 22))

        val controls = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(22))
        }
        controls.addView(sectionTitle("BRIDGE"))
        startButton = tvButton("▶  Start bridge") { startBridge() }
        val newIdentity = tvButton("↻  New random identity") {
            try {
                stopService(Intent(this, CompanionBridgeService::class.java))
                val name = CompanionIdentity(this).rotate()
                CompanionPythonBridge.clearTrace(); CrashReporter.clear(this)
                status.text = "New identity: $name — restarting…"
                android.os.Handler(mainLooper).postDelayed({ startBridge() }, 900)
            } catch (e: Throwable) {
                CrashReporter.save(this, "ROTATE IDENTITY", e)
                status.text = "Identity reset failed: ${e.message}"
            }
        }
        val accessibility = tvButton("⚙  Accessibility settings") {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        val stop = tvButton("■  Stop bridge", destructive = true) {
            stopService(Intent(this, CompanionBridgeService::class.java))
            status.text = "Bridge stopped"
        }
        controls.addView(startButton, lp(true, bottom = 10))
        controls.addView(newIdentity, lp(true, bottom = 10))
        controls.addView(accessibility, lp(true, bottom = 10))
        controls.addView(stop, lp(true))
        content.addView(controls, lp(true, bottom = 22))

        val remote = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(22))
        }
        remote.addView(sectionTitle("REMOTE TEST"))
        fun rb(label: String, command: RemoteCommand) = tvButton(label) {
            status.text = if (RemoteAccessibilityService.dispatch(command)) "Sent: $label" else "Accessibility service not connected"
        }
        val up = rb("▲", RemoteCommand.UP)
        val down = rb("▼", RemoteCommand.DOWN)
        val left = rb("◀", RemoteCommand.LEFT)
        val ok = rb("OK", RemoteCommand.OK)
        val right = rb("▶", RemoteCommand.RIGHT)
        remote.addView(centered(up), lp(true, bottom = 8))
        remote.addView(horizontal(left, ok, right), lp(true, bottom = 8))
        remote.addView(centered(down), lp(true, bottom = 16))
        remote.addView(horizontal(rb("VOL +", RemoteCommand.VOLUME_UP), rb("MUTE", RemoteCommand.MUTE), rb("VOL −", RemoteCommand.VOLUME_DOWN)), lp(true, bottom = 8))
        remote.addView(horizontal(rb("PLAY", RemoteCommand.PLAY), rb("BACK", RemoteCommand.BACK), rb("HOME", RemoteCommand.HOME)), lp(true))
        content.addView(remote, lp(true, bottom = 22))

        val diagnostics = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(22))
        }
        diagnostics.addView(sectionTitle("DIAGNOSTICS"))
        val show = tvButton("Show pairing trace") {
            refreshDiagnostics()
            traceMode = true
            status.text = "Trace mode • UP/DOWN scroll • OK/BACK exits"
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        val clear = tvButton("Clear diagnostics") {
            CrashReporter.clear(this)
            CompanionPythonBridge.clearTrace()
            refreshDiagnostics()
        }
        diagnostics.addView(horizontal(show, clear), lp(true, bottom = 14))

        errorBox = TextView(this).apply {
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        traceBox = TextView(this).apply {
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        diagnostics.addView(errorBox)
        diagnostics.addView(traceBox)
        content.addView(diagnostics, lp(true))

        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        refreshDiagnostics()
        startButton.requestFocus()
    }

    private fun sectionTitle(label: String) = TextView(this).apply {
        text = label
        textSize = 13f
        letterSpacing = .12f
        setTextColor(textSecondary)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(4), 0, 0, dp(14))
    }

    private fun card() = LinearLayout(this).apply {
        background = rounded(surface, 22f)
        elevation = dp(4).toFloat()
        isFocusable = false
    }

    private fun centered(view: View): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        addView(view, LinearLayout.LayoutParams(dp(180), dp(64)))
    }

    private fun horizontal(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        views.forEachIndexed { index, v ->
            addView(v, LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                if (index > 0) leftMargin = dp(8)
            })
        }
    }

    private fun tvButton(label: String, destructive: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        isFocusable = true
        isFocusableInTouchMode = true
        setTextColor(textPrimary)
        background = rounded(if (destructive) danger else surfaceAlt, 16f)
        stateListAnimator = null
        elevation = 0f
        setOnClickListener { action() }
        setOnFocusChangeListener { _, hasFocus ->
            background = rounded(
                when {
                    hasFocus -> accent
                    destructive -> danger
                    else -> surfaceAlt
                }, 16f
            )
            animate().scaleX(if (hasFocus) 1.045f else 1f).scaleY(if (hasFocus) 1.045f else 1f).setDuration(110).start()
            if (hasFocus && !traceMode) scroll.post {
                scroll.requestChildRectangleOnScreen(this, Rect(0, 0, width, height), true)
            }
        }
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setColor(color)
    }

    private fun lp(match: Boolean = true, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        if (match) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && traceMode) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { scroll.smoothScrollBy(0, dp(240)); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { scroll.smoothScrollBy(0, -dp(240)); return true }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BACK -> { exitTraceMode(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun exitTraceMode() {
        traceMode = false
        scroll.smoothScrollTo(0, 0)
        status.text = "Ready"
        startButton.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (traceMode || scroll.scrollY > 0) exitTraceMode() else super.onBackPressed()
    }

    private fun startBridge() {
        try {
            val intent = Intent(this, CompanionBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            val i = CompanionIdentity(this)
            status.text = "Starting ${i.deviceName}…"
        } catch (e: Throwable) {
            CrashReporter.save(this, "START SERVICE", e)
            status.text = "Start failed: ${e.message}"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::errorBox.isInitialized) refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
        val last = CrashReporter.read(this)
        errorBox.text = if (last.isNullOrBlank()) "Last error: none" else "LAST ERROR:\n$last"
        val t = CompanionPythonBridge.getTrace()
        traceBox.text = if (t.isBlank()) "PAIRING TRACE: none" else "PAIRING TRACE:\n$t"
    }
}
