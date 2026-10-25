package com.lumatv.app

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Store a minimal diagnostic for the next launch without ever persisting
 * exception messages, IPTV URLs, credentials, HTTP headers or playlist data.
 */
object CrashReporter {
    @Volatile private var installed = false

    @Synchronized fun install(context: Context) {
        if (installed) return
        val application = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                val frames = failure.stackTrace
                    .filter { it.className.startsWith("com.lumatv.app.") }
                    .take(24)
                    .joinToString("\n") {
                        "${it.className.substringAfterLast('.') }.${it.methodName} (${it.fileName}:${it.lineNumber})"
                    }
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val report = "Time: $stamp\nAndroid SDK: ${Build.VERSION.SDK_INT}\n" +
                    "Crash type: ${failure.javaClass.simpleName}\n" +
                    "Application stack:\n${frames.ifEmpty { "No application frames available" }}"
                application.getSharedPreferences("lumatv_crash_report", Context.MODE_PRIVATE)
                    .edit().putString("last_report", report.take(6000)).commit()
            } catch (_: Throwable) {
                // Never block Android's ordinary crash handling.
            }
            previous?.uncaughtException(thread, failure)
        }
        installed = true
    }

    fun lastReport(context: Context): String =
        context.getSharedPreferences("lumatv_crash_report", Context.MODE_PRIVATE)
            .getString("last_report", "").orEmpty()
}
