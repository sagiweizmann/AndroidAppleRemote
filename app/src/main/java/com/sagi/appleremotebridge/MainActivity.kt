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
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val status = TextView(this).apply {
            text = "Companion discovery + Android TV accessibility bridge"
            textSize = 16f
            gravity = Gravity.CENTER
        }

        val start = Button(this).apply {
            text = "Start Apple TV emulation"
            setOnClickListener {
                try {
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, CompanionBridgeService::class.java)
                    )
                    status.text = "Advertising as Android TV. Open Apple TV Remote on iPhone."
                } catch (e: Exception) {
                    status.text = "Could not start service: ${e.javaClass.simpleName}"
                }
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
                        openGeneralSettings(status)
                    }
                } catch (_: ActivityNotFoundException) {
                    openGeneralSettings(status)
                } catch (_: Exception) {
                    openGeneralSettings(status)
                }
            }
        }

        fun remoteButton(label: String, command: RemoteCommand) = Button(this).apply {
            text = label
            setOnClickListener {
                val ok = RemoteAccessibilityService.dispatch(command)
                status.text = if (ok) {
                    "Sent $label through Accessibility"
                } else {
                    "Accessibility not enabled, or this screen cannot handle $label"
                }
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
            TextView(this).apply {
                text = "Apple Remote Bridge"
                textSize = 28f
                gravity = Gravity.CENTER
            },
            status,
            start,
            accessibility,
            TextView(this).apply {
                text = "Accessibility remote test"
                textSize = 18f
                gravity = Gravity.CENTER
            },
            up,
            row1,
            down,
            row2,
            stop
        ).forEach {
            layout.addView(
                it,
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 12, 0, 12) }
            )
        }

        setContentView(layout)
    }

    private fun openGeneralSettings(status: TextView) {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            status.text = "This TV has no direct Accessibility screen intent. Open Accessibility manually in Settings."
        } catch (e: Exception) {
            status.text = "TV Settings could not be opened automatically. Open Settings > Accessibility manually."
        }
    }
}
