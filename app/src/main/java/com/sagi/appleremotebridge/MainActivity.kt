package com.sagi.appleremotebridge

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var errorBox: TextView
    private lateinit var traceBox: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(this)
        CompanionPythonBridge.initialize(applicationContext)
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        status = TextView(this).apply {
            text = "Companion discovery + Android TV accessibility bridge"
            textSize = 16f
            gravity = Gravity.CENTER
        }

        errorBox = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
        }

        traceBox = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.START
            setPadding(8, 8, 8, 8)
        }

        refreshDiagnostics()

        val start = Button(this).apply {
            text = "Start Apple TV emulation"
            setOnClickListener {
                try {
                    CrashReporter.clear(this@MainActivity)
                    CompanionPythonBridge.clearTrace()
                    refreshDiagnostics()
                    startService(Intent(this@MainActivity, CompanionBridgeService::class.java))
                    status.text = "Starting Companion server…"
                } catch (e: Throwable) {
                    CrashReporter.save(this@MainActivity, "START SERVICE", e)
                    status.text = "Start failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                    refreshDiagnostics()
                }
            }
        }

        val showDiagnostics = Button(this).apply {
            text = "Show pairing trace"
            setOnClickListener { refreshDiagnostics() }
        }

        val clearDiagnostics = Button(this).apply {
            text = "Clear diagnostics"
            setOnClickListener {
                CrashReporter.clear(this@MainActivity)
                CompanionPythonBridge.clearTrace()
                refreshDiagnostics()
            }
        }

        val accessibility = Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener {
                val direct = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                try {
                    if (direct.resolveActivity(packageManager) != null) {
                        startActivity(direct)
                        status.text = "Enable Apple Remote Bridge under Accessibility."
                    } else {
                        openGeneralSettings()
                    }
                } catch (_: ActivityNotFoundException) {
                    openGeneralSettings()
                } catch (e: Exception) {
                    CrashReporter.save(this@MainActivity, "ACCESSIBILITY SETTINGS", e)
                    openGeneralSettings()
                }
            }
        }

        fun remoteButton(label: String, command: RemoteCommand) = Button(this).apply {
            text = label
            setOnClickListener {
                val ok = RemoteAccessibilityService.dispatch(command)
                status.text = if (ok) "Sent $label" else "Accessibility not enabled or action unsupported"
            }
        }

        val up = remoteButton("▲ UP", RemoteCommand.UP)
        val left = remoteButton("◀ LEFT", RemoteCommand.LEFT)
        val ok = remoteButton("OK", RemoteCommand.OK)
        val right = remoteButton("RIGHT ▶", RemoteCommand.RIGHT)
        val down = remoteButton("▼ DOWN", RemoteCommand.DOWN)
        val back = remoteButton("BACK", RemoteCommand.BACK)
        val home = remoteButton("HOME", RemoteCommand.HOME)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ok, LinearLayout.LayoutParams(0, -2, 1f))
            addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        }

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(back, LinearLayout.LayoutParams(0, -2, 1f))
            addView(home, LinearLayout.LayoutParams(0, -2, 1f))
        }

        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CompanionBridgeService::class.java))
                status.text = "Stopped"
            }
        }

        listOf(
            TextView(this).apply { text = "Apple Remote Bridge"; textSize = 28f; gravity = Gravity.CENTER },
            status,
            errorBox,
            traceBox,
            start,
            showDiagnostics,
            clearDiagnostics,
            accessibility,
            TextView(this).apply { text = "Accessibility remote test"; textSize = 18f; gravity = Gravity.CENTER },
            up, row1, down, row2, stop
        ).forEach {
            layout.addView(it, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 10, 0, 10) })
        }

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        if (::errorBox.isInitialized) refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
        val last = CrashReporter.read(this)
        errorBox.text = if (last.isNullOrBlank()) "Last error: none" else "LAST ERROR:\n$last"
        val trace = CompanionPythonBridge.getTrace()
        traceBox.text = if (trace.isBlank()) "PAIRING TRACE: none" else "PAIRING TRACE:\n$trace"
    }

    private fun openGeneralSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            status.text = "Open Accessibility manually in TV settings."
        } catch (e: Exception) {
            CrashReporter.save(this, "GENERAL SETTINGS", e)
            status.text = "Could not open TV settings."
            refreshDiagnostics()
        }
    }
}
