package com.sagi.appleremotebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.Executors

class CompanionBridgeService : Service() {
    companion object {
        private const val TAG = "CompanionBridge"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1001
    }

    private val pythonExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: NsdManager.RegistrationListener? = null
    private lateinit var nsd: NsdManager

    override fun onCreate() {
        super.onCreate()
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Apple Remote Bridge",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        startForeground(NOTIFICATION_ID, note("Starting Companion server…"))

        CompanionPythonBridge.onReady = { port ->
            mainHandler.post {
                unregisterMdns()
                registerMdns(port)
            }
        }
        CompanionPythonBridge.onStatusChanged = { text ->
            mainHandler.post { updateNotification(text) }
        }

        startPythonServer()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPythonServer() {
        pythonExecutor.execute {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(applicationContext))
                }
                val identity = CompanionIdentity(this).identifier
                Log.i(TAG, "Starting embedded Companion Link server for $identity")
                Python.getInstance()
                    .getModule("android_companion")
                    .callAttr("run_server", identity)
            } catch (e: Throwable) {
                Log.e(TAG, "Embedded Companion server failed", e)
                mainHandler.post {
                    updateNotification("Companion error: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun registerMdns(port: Int) {
        try {
            val info = NsdServiceInfo().apply {
                serviceName = "Android TV"
                serviceType = "_companion-link._tcp"
                setPort(port)
                CompanionIdentity(this@CompanionBridgeService).txtRecords().forEach { (key, value) ->
                    setAttribute(key, value)
                }
            }

            listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "mDNS registered as ${serviceInfo.serviceName} on $port")
                    updateNotification("Ready • PIN 1337 • ${serviceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "mDNS registration failed: $errorCode")
                    updateNotification("mDNS failed: $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            }

            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "mDNS setup failed", e)
            updateNotification("mDNS error: ${e.javaClass.simpleName}")
        }
    }

    private fun unregisterMdns() {
        val current = listener ?: return
        listener = null
        try {
            nsd.unregisterService(current)
        } catch (_: Exception) {
        }
    }

    private fun note(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Apple Remote Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, note(text))
        } catch (e: Exception) {
            Log.e(TAG, "notification failed", e)
        }
    }

    override fun onDestroy() {
        CompanionPythonBridge.onReady = null
        CompanionPythonBridge.onStatusChanged = null
        unregisterMdns()

        try {
            if (Python.isStarted()) {
                Python.getInstance().getModule("android_companion").callAttr("stop_server")
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Python stop ignored: ${e.message}")
        }

        pythonExecutor.shutdownNow()
        super.onDestroy()
    }
}
