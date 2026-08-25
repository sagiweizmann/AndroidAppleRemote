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
    private val io=Executors.newCachedThreadPool(); private var server:ServerSocket?=null; private var listener:NsdManager.RegistrationListener?=null; private lateinit var nsd:NsdManager
    override fun onCreate(){ super.onCreate(); nsd=getSystemService(Context.NSD_SERVICE) as NsdManager; if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("bridge","Apple Remote Bridge",NotificationManager.IMPORTANCE_LOW)); startForeground(1001,note("Starting…")); io.execute { try { server=ServerSocket(0); register(server!!.localPort); while(server?.isClosed==false) io.execute { probe(server!!.accept()) } } catch(e:Exception){ Log.e("CompanionBridge","server",e) } } }
    override fun onBind(intent:Intent?):IBinder?=null
    private fun register(port:Int){ val info=NsdServiceInfo().apply { serviceName="Android TV"; serviceType="_companion-link._tcp"; setPort(port); CompanionIdentity(this@CompanionBridgeService).txtRecords().forEach { (k,v)->setAttribute(k,v) } }; listener=object:NsdManager.RegistrationListener { override fun onServiceRegistered(i:NsdServiceInfo){ notify("Visible as ${i.serviceName}") }; override fun onRegistrationFailed(i:NsdServiceInfo,e:Int){notify("mDNS failed: $e")}; override fun onServiceUnregistered(i:NsdServiceInfo){}; override fun onUnregistrationFailed(i:NsdServiceInfo,e:Int){} }; nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,listener) }
    private fun probe(s:Socket){ s.use { try { it.soTimeout=8000; val b=ByteArray(4096); val n=it.getInputStream().read(b); if(n>0){ Log.i("CompanionBridge","Received $n bytes"); notify("iPhone reached Companion server ✓") } }catch(_:Exception){} } }
    private fun note(t:String)=Notification.Builder(this,"bridge").setContentTitle("Apple Remote Bridge").setContentText(t).setSmallIcon(android.R.drawable.ic_media_play).build()
    private fun notify(t:String)=getSystemService(NotificationManager::class.java).notify(1001,note(t))
    override fun onDestroy(){ listener?.let { try{nsd.unregisterService(it)}catch(_:Exception){} }; try{server?.close()}catch(_:Exception){}; io.shutdownNow(); super.onDestroy() }
}
