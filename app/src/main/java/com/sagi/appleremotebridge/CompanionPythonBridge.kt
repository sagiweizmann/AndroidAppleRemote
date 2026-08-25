package com.sagi.appleremotebridge

import android.util.Log

/** Static entry points called from the embedded Python Companion server. */
object CompanionPythonBridge {
    private const val TAG = "CompanionPythonBridge"

    @Volatile
    var onReady: ((Int) -> Unit)? = null

    @Volatile
    var onStatusChanged: ((String) -> Unit)? = null

    @JvmStatic
    fun onServerReady(port: Int) {
        Log.i(TAG, "Companion Python server ready on $port")
        onReady?.invoke(port)
    }

    @JvmStatic
    fun onStatus(message: String) {
        Log.i(TAG, message)
        onStatusChanged?.invoke(message)
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
            Log.d(TAG, "Ignoring unsupported command: $command")
            return false
        }

        val handled = RemoteAccessibilityService.dispatch(remote)
        Log.d(TAG, "Dispatch $command -> $handled")
        return handled
    }
}
