package com.dramafactory.app

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * TD-4：从 AppGraph 上帝对象抽出（原 AppGraph.CrashLog 嵌套对象）。
 * 全局未捕获异常 / 显式 record 写入 files/crash/last_crash.txt，并多写一份到应用专属外部目录，
 * 便于用文件管理器/adb 取出（无需 root，规避 Environment.getExternalStoragePublicDirectory 在 API29+ 不可写）。
 */
object CrashLog {
    private fun crashFile(app: Context): File =
        File(File(app.filesDir, "crash"), "last_crash.txt")

    private fun writeCrash(app: Context, header: String, throwable: Throwable) {
        try {
            val stack = Log.getStackTraceString(throwable)
            crashFile(app).apply { parentFile?.mkdirs() }.writeText(
                buildString {
                    appendLine("time=${System.currentTimeMillis()}")
                    appendLine(header)
                    appendLine(stack)
                }
            )
            // 同时写一份到应用专属外部目录，便于用文件管理器/adb 取出（无需 root）。
            // 原实现用 Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)：
            // 该 API 自 API 29 废弃，且在分区存储（targetSdk≥30）下不可写，外面还套了 runCatching
            // —— 于是在 Android 11+ 上「静默写不出去」，崩溃日志永远拿不到。改用应用专属目录。
            runCatching {
                val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                val ext = File(dir, "ai-drama-crash.log")
                ext.writeText("time=${System.currentTimeMillis()}\n$header\n$stack\n\n")
            }
        } catch (_: Throwable) {}
    }

    fun installCrashLogger(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrash(app, "thread=${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun record(context: Context, tag: String, throwable: Throwable) {
        writeCrash(context.applicationContext, "tag=$tag", throwable)
        Log.e(tag, throwable.message ?: throwable.javaClass.simpleName, throwable)
    }

    fun lastCrashLog(context: Context): String? =
        runCatching { crashFile(context.applicationContext) }
            .getOrNull()?.takeIf { it.exists() }?.readText()
}
