package com.culustech.klicka.log

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger that writes to both Android's LogCat and a file in the user's Documents/klicka/logs directory.
 * Creates daily log files with timestamps for each entry.
 */
class FileLogger private constructor() {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logDir: File? = null
    private var currentLogFile: File? = null

    companion object {
        private const val TAG = "KlickaLogger"
        private var instance: FileLogger? = null

        fun getInstance(): FileLogger {
            if (instance == null) {
                instance = FileLogger()
            }
            return instance!!
        }

        // Convenience methods for static access
        fun d(tag: String, message: String) = getInstance().debug(tag, message)
        fun i(tag: String, message: String) = getInstance().info(tag, message)
        fun w(tag: String, message: String) = getInstance().warn(tag, message)
        fun e(tag: String, message: String) = getInstance().error(tag, message)
        fun e(tag: String, message: String, throwable: Throwable) = getInstance().error(tag, message, throwable)
    }

    /**
     * Initialize the logger.
     * This must be called before using the logger.
     *
     * Note: Context parameter was removed as it's not needed for public storage access.
     */
    fun init() {
        try {
            // Create the logs directory in Documents/klicka/logs
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val klickaDir = File(docsDir, "klicka")
            logDir = File(klickaDir, "logs")

            if (!logDir!!.exists()) {
                if (!logDir!!.mkdirs()) {
                    Log.e(TAG, "Failed to create log directory: ${logDir!!.absolutePath}")
                    return
                }
            }

            // Create or get today's log file
            updateLogFile()

            info(TAG, "FileLogger initialized. Logs will be saved to: ${currentLogFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing FileLogger", e)
        }
    }

    /**
     * Update the log file to use today's date
     */
    private fun updateLogFile() {
        val today = dateFormat.format(Date())
        currentLogFile = File(logDir, "klicka-$today.log")

        // Create the file if it doesn't exist
        if (!currentLogFile!!.exists()) {
            try {
                currentLogFile!!.createNewFile()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create log file: ${currentLogFile!!.absolutePath}", e)
            }
        }
    }

    /**
     * Write a log entry to the file
     */
    private fun writeToFile(level: String, tag: String, message: String) {
        if (currentLogFile == null) {
            Log.w(TAG, "Logger not initialized or failed to create log file")
            return
        }

        // Check if we need to create a new log file for today
        val today = dateFormat.format(Date())
        val currentFileName = "klicka-$today.log"
        if (currentLogFile?.name != currentFileName) {
            updateLogFile()
        }

        try {
            val timestamp = timeFormat.format(Date())
            val logEntry = "[$timestamp] $level/$tag: $message\n"

            PrintWriter(FileWriter(currentLogFile, true)).use { writer ->
                writer.write(logEntry)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }

    /**
     * Log a debug message
     */
    fun debug(tag: String, message: String) {
        Log.d(tag, message)
        writeToFile("DEBUG", tag, message)
    }

    /**
     * Log an info message
     */
    fun info(tag: String, message: String) {
        Log.i(tag, message)
        writeToFile("INFO", tag, message)
    }

    /**
     * Log a warning message
     */
    fun warn(tag: String, message: String) {
        Log.w(tag, message)
        writeToFile("WARN", tag, message)
    }

    /**
     * Log an error message
     */
    fun error(tag: String, message: String) {
        Log.e(tag, message)
        writeToFile("ERROR", tag, message)
    }

    /**
     * Log an error message with exception
     */
    fun error(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
        writeToFile("ERROR", tag, "$message\n${throwable.stackTraceToString()}")
    }
}