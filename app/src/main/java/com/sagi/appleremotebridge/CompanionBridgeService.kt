package com.sagi.appleremotebridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class CompanionBridgeService : Service() {
    companion object {
        private const val TAG = "CompanionBridge"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1001
    }

    private val io = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
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

        startForeground(NOTIFICATION_ID, note("Starting…"))
        startServer()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        io.execute {
            try {
                val localServer = ServerSocket(0)
                server = localServer
                register(localServer.localPort)
                updateNotification("Advertising as Android TV")

                // Important: accept on ONE server thread. The previous implementation queued
                // unlimited blocking accept() calls into a cached thread pool and could exhaust
                // the process almost immediately.
                while (!localServer.isClosed) {
                    try {
                        val socket = localServer.accept()
                        Log.i(TAG, "Connection from ${socket.inetAddress?.hostAddress}")
                        io.execute { probe(socket) }
                    } catch (e: Exception) {
                        if (!localServer.isClosed) {
                            Log.e(TAG, "accept failed", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "server failed", e)
                updateNotification("Server error: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun register(port: Int) {
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
                    Log.i(TAG, "mDNS registered as ${serviceInfo.serviceName}")
                    updateNotification("Visible as ${serviceInfo.serviceName}")
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

    private fun probe(socket: Socket) {
        socket.use {
            try {
                it.soTimeout = 8000
                val buffer = ByteArray(4096)
                val count = it.getInputStream().read(buffer)
                if (count > 0) {
                    Log.i(TAG, "Received $count Companion bytes")
                    updateNotification("iPhone reached Companion server ✓")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Connection probe ended: ${e.message}")
            }
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
        listener?.let {
            try {
                nsd.unregisterService(it)
            } catch (_: Exception) {
            }
        }
        try {
            server?.close()
        } catch (_: Exception) {
        }
        io.shutdownNow()
        super.onDestroy()
    }
}
