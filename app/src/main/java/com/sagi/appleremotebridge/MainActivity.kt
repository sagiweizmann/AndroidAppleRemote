package com.sagi.appleremotebridge

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
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48,48,48,48) }
        val status = TextView(this).apply { text = "Stage 1: Companion discovery + Android TV accessibility bridge"; textSize = 16f; gravity = Gravity.CENTER }
        val start = Button(this).apply { text = "Start Apple TV emulation"; setOnClickListener { ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, CompanionBridgeService::class.java)); status.text = "Advertising as Android TV. Open Apple TV Remote on iPhone." } }
        val accessibility = Button(this).apply { text = "Enable Accessibility"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        val stop = Button(this).apply { text = "Stop"; setOnClickListener { stopService(Intent(this@MainActivity, CompanionBridgeService::class.java)); status.text = "Stopped" } }
        listOf(TextView(this).apply { text="Apple Remote Bridge"; textSize=28f; gravity=Gravity.CENTER }, status, start, accessibility, stop).forEach { layout.addView(it, LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,12,0,12) }) }
        setContentView(layout)
    }
}
