package com.sagi.appleremotebridge

import android.content.Context
import android.util.Log

/** Static entry points called from the embedded Python Companion server. */
object CompanionPythonBridge {
    private const val TAG = "CompanionPythonBridge"
    private const val PREFS = "companion_trace"
    private const val KEY_TRACE = "trace"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var onReady: ((Int) -> Unit)? = null

    @Volatile
    var onStatusChanged: ((String) -> Unit)? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun getStateDir(): String {
        val context = appContext ?: error("CompanionPythonBridge not initialized")
        return context.filesDir.absolutePath
    }

    @JvmStatic
    fun onServerReady(port: Int) {
        Log.i(TAG, "Companion Python server ready on $port")
        appendTrace("SERVER READY • port $port")
        onReady?.invoke(port)
    }

    @JvmStatic
    fun onStatus(message: String) {
        Log.i(TAG, message)
        appendTrace(message)
        onStatusChanged?.invoke(message)
    }

    @JvmStatic
    fun clearTrace() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove(KEY_TRACE)?.apply()
    }

    @JvmStatic
    fun getTrace(): String {
        return appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_TRACE, "") ?: ""
    }

    private fun appendTrace(message: String) {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY_TRACE, "") ?: ""
        val line = "${System.currentTimeMillis()} • $message"
        val combined = if (previous.isBlank()) line else "$previous\n$line"
        val trimmed = combined.takeLast(12000)
        prefs.edit().putString(KEY_TRACE, trimmed).apply()
    }

    @JvmStatic
    fun dispatch(command: String): Boolean {
        val remote = when (command.uppercase()) {
            "UP" -> RemoteCommand.UP
            "DOWN" -> RemoteCommand.DOWN
            "LEFT" -> RemoteCommand.LEFT
            "RIGHT" -> RemoteCommand.RIGHT
            "OK", "SELECT" -> RemoteCommand.OK
            "BACK", "MENU" -> RemoteCommand.BACK
            "HOME" -> RemoteCommand.HOME
            "PLAY_PAUSE", "PLAY", "PAUSE" -> RemoteCommand.PLAY_PAUSE
            else -> null
        }
        if (remote == null) {
            appendTrace("REMOTE unsupported • $command")
            Log.d(TAG, "Ignoring unsupported command: $command")
            return false
        }
        val handled = RemoteAccessibilityService.dispatch(remote)
        appendTrace("REMOTE $command -> $handled")
        Log.d(TAG, "Dispatch $command -> $handled")
        return handled
    }
}
