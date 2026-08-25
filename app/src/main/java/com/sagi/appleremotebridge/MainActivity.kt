package com.sagi.appleremotebridge

import android.content.*
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

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(this)
        CompanionPythonBridge.initialize(applicationContext)
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
            isFocusable = false
        }
        scroll = ScrollView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        status = TextView(this).apply {
            text = "Companion discovery + Android TV accessibility bridge"
            textSize = 16f
            gravity = Gravity.CENTER
        }
        errorBox = TextView(this)
        traceBox = TextView(this).apply {
            textSize = 15f
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val start = Button(this).apply { text = "Start Apple TV emulation"; setOnClickListener { startBridge() } }
        val newIdentity = Button(this).apply {
            text = "NEW IDENTITY (random name)"
            setOnClickListener {
                try {
                    stopService(Intent(this@MainActivity, CompanionBridgeService::class.java))
                    val name = CompanionIdentity(this@MainActivity).rotate()
                    CompanionPythonBridge.clearTrace(); CrashReporter.clear(this@MainActivity)
                    status.text = "New identity: $name — restarting…"
                    android.os.Handler(mainLooper).postDelayed({ startBridge() }, 900)
                } catch (e: Throwable) {
                    CrashReporter.save(this@MainActivity, "ROTATE IDENTITY", e)
                    status.text = "Identity reset failed: ${e.message}"
                }
            }
        }
        val show = Button(this).apply {
            text = "Show pairing trace"
            setOnClickListener { refreshDiagnostics(); scroll.post { scroll.fullScroll(View.FOCUS_DOWN); traceBox.requestFocus() } }
        }
        val clear = Button(this).apply { text = "Clear diagnostics"; setOnClickListener { CrashReporter.clear(this@MainActivity); CompanionPythonBridge.clearTrace(); refreshDiagnostics() } }
        val accessibility = Button(this).apply { text = "Open Accessibility settings"; setOnClickListener { try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) { startActivity(Intent(Settings.ACTION_SETTINGS)) } } }
        fun rb(l: String, c: RemoteCommand) = Button(this).apply { text = l; setOnClickListener { status.text = if (RemoteAccessibilityService.dispatch(c)) "Sent $l" else "Accessibility not enabled" } }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(rb("◀ LEFT", RemoteCommand.LEFT), LinearLayout.LayoutParams(0, -2, 1f))
            addView(rb("OK", RemoteCommand.OK), LinearLayout.LayoutParams(0, -2, 1f))
            addView(rb("RIGHT ▶", RemoteCommand.RIGHT), LinearLayout.LayoutParams(0, -2, 1f))
        }
        val stop = Button(this).apply { text = "Stop"; setOnClickListener { stopService(Intent(this@MainActivity, CompanionBridgeService::class.java)); status.text = "Stopped" } }
        listOf(
            TextView(this).apply { text = "Apple Remote Bridge"; textSize = 28f; gravity = Gravity.CENTER },
            status, errorBox, traceBox, start, newIdentity, show, clear, accessibility,
            rb("▲ UP", RemoteCommand.UP), row, rb("▼ DOWN", RemoteCommand.DOWN),
            rb("BACK", RemoteCommand.BACK), rb("HOME", RemoteCommand.HOME), stop
        ).forEach { layout.addView(it, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 8, 0, 8) }) }
        scroll.addView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        refreshDiagnostics()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { scroll.smoothScrollBy(0, 260); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { scroll.smoothScrollBy(0, -260); return true }
                KeyEvent.KEYCODE_BACK -> { scroll.smoothScrollTo(0, 0); findViewById<View>(android.R.id.content)?.requestFocus(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (scroll.scrollY > 0) scroll.smoothScrollTo(0, 0) else super.onBackPressed()
    }

    private fun startBridge() {
        try {
            startService(Intent(this, CompanionBridgeService::class.java))
            val i = CompanionIdentity(this); status.text = "Starting ${i.deviceName}…"
        } catch (e: Throwable) { CrashReporter.save(this, "START SERVICE", e); status.text = "Start failed: ${e.message}" }
    }
    override fun onResume() { super.onResume(); if (::errorBox.isInitialized) refreshDiagnostics() }
    private fun refreshDiagnostics() {
        val last = CrashReporter.read(this)
        errorBox.text = if (last.isNullOrBlank()) "Last error: none" else "LAST ERROR:\n$last"
        val t = CompanionPythonBridge.getTrace()
        traceBox.text = if (t.isBlank()) "PAIRING TRACE: none" else "PAIRING TRACE:\n$t"
    }
}
