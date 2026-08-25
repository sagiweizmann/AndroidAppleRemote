package com.sagi.appleremotebridge
import android.content.*
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
class MainActivity:AppCompatActivity(){
 private lateinit var status:TextView;private lateinit var errorBox:TextView;private lateinit var traceBox:TextView
 override fun onCreate(savedInstanceState:Bundle?){CrashReporter.install(this);CompanionPythonBridge.initialize(applicationContext);super.onCreate(savedInstanceState);val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(48,48,48,48)};status=TextView(this).apply{text="Companion discovery + Android TV accessibility bridge";textSize=16f;gravity=Gravity.CENTER};errorBox=TextView(this);traceBox=TextView(this);refreshDiagnostics()
 val start=Button(this).apply{text="Start Apple TV emulation";setOnClickListener{startBridge()}}
 val newIdentity=Button(this).apply{text="NEW IDENTITY (random name)";setOnClickListener{try{stopService(Intent(this@MainActivity,CompanionBridgeService::class.java));val name=CompanionIdentity(this@MainActivity).rotate();CompanionPythonBridge.clearTrace();CrashReporter.clear(this@MainActivity);status.text="New identity: $name — restarting…";android.os.Handler(mainLooper).postDelayed({startBridge()},900)}catch(e:Throwable){CrashReporter.save(this@MainActivity,"ROTATE IDENTITY",e);status.text="Identity reset failed: ${e.message}"}}}
 val show=Button(this).apply{text="Show pairing trace";setOnClickListener{refreshDiagnostics()}};val clear=Button(this).apply{text="Clear diagnostics";setOnClickListener{CrashReporter.clear(this@MainActivity);CompanionPythonBridge.clearTrace();refreshDiagnostics()}};val accessibility=Button(this).apply{text="Open Accessibility settings";setOnClickListener{try{startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}catch(_:Throwable){startActivity(Intent(Settings.ACTION_SETTINGS))}}}
 fun rb(l:String,c:RemoteCommand)=Button(this).apply{text=l;setOnClickListener{status.text=if(RemoteAccessibilityService.dispatch(c))"Sent $l"else"Accessibility not enabled"}}
 val left=rb("◀ LEFT",RemoteCommand.LEFT);val ok=rb("OK",RemoteCommand.OK);val right=rb("RIGHT ▶",RemoteCommand.RIGHT);val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;addView(left,LinearLayout.LayoutParams(0,-2,1f));addView(ok,LinearLayout.LayoutParams(0,-2,1f));addView(right,LinearLayout.LayoutParams(0,-2,1f))}
 val stop=Button(this).apply{text="Stop";setOnClickListener{stopService(Intent(this@MainActivity,CompanionBridgeService::class.java));status.text="Stopped"}}
 listOf(TextView(this).apply{text="Apple Remote Bridge";textSize=28f;gravity=Gravity.CENTER},status,errorBox,traceBox,start,newIdentity,show,clear,accessibility,rb("▲ UP",RemoteCommand.UP),row,rb("▼ DOWN",RemoteCommand.DOWN),rb("BACK",RemoteCommand.BACK),rb("HOME",RemoteCommand.HOME),stop).forEach{layout.addView(it,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,8,0,8)})};setContentView(layout)}
 private fun startBridge(){try{startService(Intent(this,CompanionBridgeService::class.java));val i=CompanionIdentity(this);status.text="Starting ${i.deviceName}…"}catch(e:Throwable){CrashReporter.save(this,"START SERVICE",e);status.text="Start failed: ${e.message}"}}
 override fun onResume(){super.onResume();if(::errorBox.isInitialized)refreshDiagnostics()}
 private fun refreshDiagnostics(){val last=CrashReporter.read(this);errorBox.text=if(last.isNullOrBlank())"Last error: none"else"LAST ERROR:\n$last";val t=CompanionPythonBridge.getTrace();traceBox.text=if(t.isBlank())"PAIRING TRACE: none"else"PAIRING TRACE:\n$t"}
}
