package com.hriyaan.photostorage.data

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileLogger private constructor(context: Context) {

    private val prefs = PrefsStore(context.applicationContext)
    private val logFile = File(context.applicationContext.filesDir, "logs/app.log")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, message: String) = maybeWrite("D", tag, message)
    fun i(tag: String, message: String) = maybeWrite("I", tag, message)
    fun w(tag: String, message: String) = maybeWrite("W", tag, message)
    fun e(tag: String, message: String) = maybeWrite("E", tag, message)
    fun e(tag: String, message: String, throwable: Throwable) = maybeWrite("E", tag, "$message\n${throwable.stackTraceToString()}")

    fun start() {
        prefs.setDiagnosticsEnabled(true)
        prefs.setDiagnosticsStartedAt(System.currentTimeMillis())
    }

    fun stop() {
        prefs.setDiagnosticsEnabled(false)
        prefs.setDiagnosticsStartedAt(null)
    }

    fun isRunning(): Boolean = prefs.isDiagnosticsEnabled()

    fun getLogFile(): File? = logFile.takeIf { it.exists() }

    fun getLogSizeBytes(): Long = logFile.length()

    fun clear() {
        synchronized(lock) {
            logFile.delete()
        }
    }

    private fun maybeWrite(level: String, tag: String, message: String) {
        if (!prefs.isDiagnosticsEnabled()) return
        synchronized(lock) {
            FileWriter(logFile, true).use { writer ->
                val timestamp = dateFormat.format(Date())
                writer.appendLine("$timestamp [$level] $tag: $message")
            }
        }
    }

    companion object {
        private val lock = Any()

        @Volatile
        private var instance: FileLogger? = null

        fun getInstance(context: Context): FileLogger {
            return instance ?: synchronized(lock) {
                instance ?: FileLogger(context.applicationContext).also { instance = it }
            }
        }
    }
}
