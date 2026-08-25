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
        private const val DEVICE_NAME = "Xiaomi TV 2"
    }
    private val pythonExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: NsdManager.RegistrationListener? = null
    private lateinit var nsd: NsdManager
    override fun onCreate() {
        super.onCreate(); CrashReporter.install(this); CompanionPythonBridge.initialize(applicationContext)
        try { nsd = getSystemService(Context.NSD_SERVICE) as NsdManager } catch (e: Throwable) { CrashReporter.save(this,"NSD INIT",e); stopSelf(); return }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID,"Apple Remote Bridge",NotificationManager.IMPORTANCE_LOW))
            startForeground(NOTIFICATION_ID,note("Starting Companion server…"))
        } catch (e: Throwable) { CrashReporter.save(this,"FOREGROUND INIT",e); Log.e(TAG,"Foreground init failed, continuing as normal service",e) }
        CompanionPythonBridge.onReady={port->mainHandler.post{try{unregisterMdns();registerMdns(port)}catch(e:Throwable){CrashReporter.save(this,"MDNS READY CALLBACK",e)}}}
        CompanionPythonBridge.onStatusChanged={text->mainHandler.post{CrashReporter.save(this,"STATUS: $text");updateNotification(text)}}
        mainHandler.postDelayed({startPythonServer()},700)
    }
    override fun onBind(intent: Intent?): IBinder?=null
    private fun startPythonServer(){pythonExecutor.execute{try{CrashReporter.save(this,"STATUS: starting embedded Python runtime");if(!Python.isStarted())Python.start(AndroidPlatform(applicationContext));CrashReporter.save(this,"STATUS: Python runtime started");val identity=CompanionIdentity(this).identifier;CrashReporter.save(this,"STATUS: loading android_companion module");Python.getInstance().getModule("android_companion").callAttr("run_server",identity)}catch(e:Throwable){Log.e(TAG,"Embedded Companion server failed",e);CrashReporter.save(this,"PYTHON SERVER",e);mainHandler.post{updateNotification("Companion error: ${e.javaClass.simpleName}")}}}}
    private fun registerMdns(port:Int){try{val info=NsdServiceInfo().apply{serviceName=DEVICE_NAME;serviceType="_companion-link._tcp";setPort(port);CompanionIdentity(this@CompanionBridgeService).txtRecords().forEach{(key,value)->setAttribute(key,value)}};listener=object:NsdManager.RegistrationListener{override fun onServiceRegistered(serviceInfo:NsdServiceInfo){CrashReporter.save(this@CompanionBridgeService,"STATUS: READY • PIN 1337 • ${serviceInfo.serviceName} • port $port");updateNotification("Ready • PIN 1337 • ${serviceInfo.serviceName}")}override fun onRegistrationFailed(serviceInfo:NsdServiceInfo,errorCode:Int){val msg="mDNS registration failed: $errorCode";CrashReporter.save(this@CompanionBridgeService,msg);updateNotification(msg)}override fun onServiceUnregistered(serviceInfo:NsdServiceInfo)=Unit;override fun onUnregistrationFailed(serviceInfo:NsdServiceInfo,errorCode:Int)=Unit};nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,listener)}catch(e:Throwable){CrashReporter.save(this,"MDNS SETUP",e);updateNotification("mDNS error: ${e.javaClass.simpleName}")}}
    private fun unregisterMdns(){val current=listener?:return;listener=null;try{nsd.unregisterService(current)}catch(_:Throwable){}}
    private fun note(text:String):Notification{val builder=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)Notification.Builder(this,CHANNEL_ID)else{@Suppress("DEPRECATION") Notification.Builder(this)};return builder.setContentTitle("Apple Remote Bridge").setContentText(text).setSmallIcon(android.R.drawable.ic_media_play).build()}
    private fun updateNotification(text:String){try{getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,note(text))}catch(e:Throwable){Log.e(TAG,"notification failed",e)}}
    override fun onDestroy(){CompanionPythonBridge.onReady=null;CompanionPythonBridge.onStatusChanged=null;unregisterMdns();try{if(Python.isStarted())Python.getInstance().getModule("android_companion").callAttr("stop_server")}catch(_:Throwable){};pythonExecutor.shutdownNow();super.onDestroy()}
}
