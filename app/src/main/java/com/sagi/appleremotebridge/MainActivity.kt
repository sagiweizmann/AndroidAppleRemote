package com.sagi.appleremotebridge

import android.content.*
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
        scroll = ScrollView(this).apply { isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        status = TextView(this).apply { text = "Companion discovery + Android TV accessibility bridge"; textSize = 16f; gravity = Gravity.CENTER; isFocusable = false }
        errorBox = TextView(this).apply { isFocusable = false }
        traceBox = TextView(this).apply { textSize = 15f; isFocusable = false }

        startButton = tvButton("Start Apple TV emulation") { startBridge() }
        val newIdentity = tvButton("NEW IDENTITY (random name)") {
            try {
                stopService(Intent(this, CompanionBridgeService::class.java))
                val name = CompanionIdentity(this).rotate()
                CompanionPythonBridge.clearTrace(); CrashReporter.clear(this)
                status.text = "New identity: $name — restarting…"
                android.os.Handler(mainLooper).postDelayed({ startBridge() }, 900)
            } catch (e: Throwable) { CrashReporter.save(this, "ROTATE IDENTITY", e); status.text = "Identity reset failed: ${e.message}" }
        }
        val show = tvButton("Show pairing trace") {
            refreshDiagnostics(); traceMode = true
            status.text = "TRACE MODE: UP/DOWN scroll • OK or BACK returns to buttons"
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        val clear = tvButton("Clear diagnostics") { CrashReporter.clear(this); CompanionPythonBridge.clearTrace(); refreshDiagnostics() }
        val accessibility = tvButton("Open Accessibility settings") {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        fun remoteButton(label: String, command: RemoteCommand) = tvButton(label) {
            status.text = if (RemoteAccessibilityService.dispatch(command)) "Sent $label" else "Accessibility service not connected"
        }
        val left = remoteButton("◀ LEFT", RemoteCommand.LEFT)
        val ok = remoteButton("OK", RemoteCommand.OK)
        val right = remoteButton("RIGHT ▶", RemoteCommand.RIGHT)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, -2, 1f)); addView(ok, LinearLayout.LayoutParams(0, -2, 1f)); addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val stop = tvButton("Stop") { stopService(Intent(this, CompanionBridgeService::class.java)); status.text = "Stopped" }

        listOf(
            TextView(this).apply { text = "Apple Remote Bridge"; textSize = 28f; gravity = Gravity.CENTER; isFocusable = false },
            status, errorBox, traceBox, startButton, newIdentity, show, clear, accessibility,
            remoteButton("▲ UP", RemoteCommand.UP), row, remoteButton("▼ DOWN", RemoteCommand.DOWN),
            remoteButton("VOLUME +", RemoteCommand.VOLUME_UP), remoteButton("VOLUME -", RemoteCommand.VOLUME_DOWN),
            remoteButton("MUTE", RemoteCommand.MUTE), remoteButton("PLAY", RemoteCommand.PLAY),
            remoteButton("BACK", RemoteCommand.BACK), remoteButton("HOME", RemoteCommand.HOME), stop
        ).forEach { layout.addView(it, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 8, 0, 8) }) }

        scroll.addView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll); refreshDiagnostics(); startButton.requestFocus()
    }

    private fun tvButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isFocusable = true; isFocusableInTouchMode = true; setOnClickListener { action() }
        setOnFocusChangeListener { _, hasFocus -> if (hasFocus && !traceMode) scroll.post { scroll.requestChildRectangleOnScreen(this, android.graphics.Rect(0, 0, width, height), true) } }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && traceMode) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { scroll.smoothScrollBy(0, 260); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { scroll.smoothScrollBy(0, -260); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK -> { exitTraceMode(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun exitTraceMode() { traceMode = false; scroll.smoothScrollTo(0, 0); status.text = "Use DPAD to select a button • OK to press"; startButton.requestFocus() }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { if (traceMode || scroll.scrollY > 0) exitTraceMode() else super.onBackPressed() }

    private fun startBridge() {
        try {
            val intent = Intent(this, CompanionBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            val i = CompanionIdentity(this); status.text = "Starting ${i.deviceName}…"
        } catch (e: Throwable) { CrashReporter.save(this, "START SERVICE", e); status.text = "Start failed: ${e.message}" }
    }

    override fun onResume() { super.onResume(); if (::errorBox.isInitialized) refreshDiagnostics() }
    private fun refreshDiagnostics() {
        val last = CrashReporter.read(this); errorBox.text = if (last.isNullOrBlank()) "Last error: none" else "LAST ERROR:\n$last"
        val t = CompanionPythonBridge.getTrace(); traceBox.text = if (t.isBlank()) "PAIRING TRACE: none" else "PAIRING TRACE:\n$t"
    }
}
