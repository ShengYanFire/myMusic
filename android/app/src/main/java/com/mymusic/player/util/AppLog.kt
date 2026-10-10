package com.mymusic.player.util

import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Daily-use error collector.
 *
 * Appends formatted log lines to a size-capped file under the app's internal
 * storage — no permissions, no backend — while still mirroring every entry to
 * logcat via [android.util.Log], so `adb logcat` keeps working unchanged. The
 * backing file lives in `filesDir/logs/mymusic.log` (rotating to `mymusic.log.1`
 * once it grows past [MAX_BYTES]).
 *
 * Read it back for troubleshooting:
 *  - in-app: 设置 → 错误日志 (view / copy / share / clear);
 *  - `adb` on a debug build:
 *      `adb shell run-as com.mymusic.player cat files/logs/mymusic.log`.
 *
 * Every method is guarded by one lock and is safe to call from any thread
 * (including the uncaught-exception handler).
 */
object AppLog {

    private const val MAX_BYTES = 512 * 1024
    private const val MAIN_NAME = "mymusic.log"
    private const val MAIN_NAME_BACKUP = "mymusic.log.1"

    private val lock = Any()
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Base dir, set by [init]; null (no-op writes) until then. */
    private var dir: File? = null

    /** Must be called once from the [android.app.Application] `onCreate`. */
    fun init(context: android.content.Context) {
        synchronized(lock) {
            if (dir != null) return
            val base = File(context.filesDir, "logs").apply { mkdirs() }
            dir = base
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "未知"
            val header = buildString {
                appendLine("══════ MyMusic 启动 ══════")
                appendLine("app=$version")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("start=${Date()}")
                appendLine()
            }
            appendRaw(header)
        }
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) =
        emit(Log.ERROR, tag, msg, throwable)

    fun w(tag: String, msg: String) = emit(Log.WARN, tag, msg, null)
    fun i(tag: String, msg: String) = emit(Log.INFO, tag, msg, null)
    fun d(tag: String, msg: String) = emit(Log.DEBUG, tag, msg, null)

    /** Full collected log (rotated backup first, then the live file), oldest first. */
    fun read(): String = synchronized(lock) {
        val d = dir ?: return@synchronized ""
        val sb = StringBuilder()
        val backup = File(d, MAIN_NAME_BACKUP)
        val main = File(d, MAIN_NAME)
        if (backup.exists()) sb.append(backup.readText())
        if (main.exists()) sb.append(main.readText())
        sb.toString()
    }

    /** Wipe both the live file and its rotated backup. */
    fun clear() = synchronized(lock) {
        val d = dir ?: return@synchronized
        File(d, MAIN_NAME).delete()
        File(d, MAIN_NAME_BACKUP).delete()
    }

    private fun emit(level: Int, tag: String, msg: String, throwable: Throwable?) {
        synchronized(lock) {
            // Mirror to logcat first — the canonical Android log stays intact.
            when (level) {
                Log.ERROR -> if (throwable != null) Log.e(tag, msg, throwable) else Log.e(tag, msg)
                Log.WARN -> Log.w(tag, msg)
                Log.INFO -> Log.i(tag, msg)
                else -> Log.d(tag, msg)
            }
            val levelChar = when (level) {
                Log.ERROR -> "E"
                Log.WARN -> "W"
                Log.INFO -> "I"
                else -> "D"
            }
            val line = buildString {
                append(timestamp.format(Date()))
                    .append(' ')
                    .append(levelChar)
                    .append('[').append(Thread.currentThread().name).append("] ")
                    .append(tag)
                    .append(": ")
                    .append(msg)
                if (throwable != null) {
                    append('\n')
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    append(sw.toString())
                }
                append('\n')
            }
            appendRaw(line)
        }
    }

    /** Caller must hold [lock]. */
    private fun appendRaw(text: String) {
        val d = dir ?: return
        val main = File(d, MAIN_NAME)
        if (main.length() + text.length > MAX_BYTES) {
            // Rotate: drop the old backup, promote the live file to backup,
            // start a fresh live file.
            val backup = File(d, MAIN_NAME_BACKUP)
            backup.delete()
            if (main.exists()) main.renameTo(backup)
        }
        main.appendText(text)
    }
}