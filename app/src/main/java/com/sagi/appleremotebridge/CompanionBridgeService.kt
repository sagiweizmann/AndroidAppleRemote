package com.sagi.appleremotebridge

import android.app.*
import android.content.*
import android.net.nsd.*
import android.os.*
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
    @Volatile private var serverStarted = false

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        CompanionPythonBridge.initialize(applicationContext)
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Apple Remote Bridge", NotificationManager.IMPORTANCE_LOW)
            )
        }
        // Foreground immediately so Android TV does not treat the bridge like an activity-owned process.
        startForeground(NOTIFICATION_ID, note("Apple Remote Bridge is running"))

        CompanionPythonBridge.onReady = { port ->
            mainHandler.post {
                try { unregisterMdns(); registerMdns(port) }
                catch (e: Throwable) { CrashReporter.save(this, "MDNS READY CALLBACK", e) }
            }
        }
        CompanionPythonBridge.onStatusChanged = { text ->
            mainHandler.post { CrashReporter.save(this, "STATUS: $text"); updateNotification(text) }
        }
        startPythonServerOnce()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPythonServerOnce()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPythonServerOnce() {
        if (serverStarted) return
        serverStarted = true
        pythonExecutor.execute {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                val i = CompanionIdentity(this)
                Python.getInstance().getModule("android_companion")
                    .callAttr("run_server", i.identifier, i.deviceName, i.generation)
            } catch (e: Throwable) {
                serverStarted = false
                Log.e(TAG, "server failed", e)
                CrashReporter.save(this, "PYTHON SERVER", e)
                mainHandler.postDelayed({ startPythonServerOnce() }, 2000)
            }
        }
    }

    private fun registerMdns(port: Int) {
        val identity = CompanionIdentity(this)
        val info = NsdServiceInfo().apply {
            serviceName = identity.deviceName
            serviceType = "_companion-link._tcp"
            setPort(port)
            identity.txtRecords().forEach { (k, v) -> setAttribute(k, v) }
        }
        listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {
                CrashReporter.save(this@CompanionBridgeService, "STATUS: READY • PIN 1337 • ${s.serviceName}")
                updateNotification("Ready • ${s.serviceName}")
            }
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {
                CrashReporter.save(this@CompanionBridgeService, "mDNS failed: $e")
            }
            override fun onServiceUnregistered(s: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) = Unit
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun unregisterMdns() {
        val l = listener ?: return
        listener = null
        try { nsd.unregisterService(l) } catch (_: Throwable) {}
    }

    private fun note(t: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Apple Remote Bridge")
            .setContentText(t)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    private fun updateNotification(t: String) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, note(t)) }
        catch (_: Throwable) {}
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do not stop when the launcher closes the activity task. START_STICKY asks Android to
        // recreate the service if the TV firmware later reclaims the process.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        CompanionPythonBridge.onReady = null
        CompanionPythonBridge.onStatusChanged = null
        unregisterMdns()
        try {
            if (Python.isStarted()) Python.getInstance().getModule("android_companion").callAttr("stop_server")
        } catch (_: Throwable) {}
        pythonExecutor.shutdownNow()
        super.onDestroy()
    }
}
