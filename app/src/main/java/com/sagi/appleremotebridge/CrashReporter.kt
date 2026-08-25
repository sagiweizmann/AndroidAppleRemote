package com.sagi.appleremotebridge

import android.content.Context
import android.util.Log

object CrashReporter {
    private const val PREFS = "crash_reporter"
    private const val KEY_LAST = "last_error"
    private const val TAG = "CrashReporter"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            save(appContext, "UNCAUGHT ${thread.name}: ${format(throwable)}")
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun save(context: Context, message: String) {
        Log.e(TAG, message)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST, message.take(6000))
            .apply()
    }

    fun save(context: Context, prefix: String, throwable: Throwable) {
        save(context, "$prefix: ${format(throwable)}")
    }

    fun read(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST, null)

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST)
            .apply()
    }

    private fun format(t: Throwable): String {
        val top = t.stackTrace.take(8).joinToString("\n") { "  at $it" }
        return buildString {
            append(t.javaClass.name)
            if (!t.message.isNullOrBlank()) append(": ${t.message}")
            if (top.isNotBlank()) append("\n$top")
            t.cause?.let { cause ->
                append("\nCaused by: ${cause.javaClass.name}")
                if (!cause.message.isNullOrBlank()) append(": ${cause.message}")
            }
        }
    }
}
