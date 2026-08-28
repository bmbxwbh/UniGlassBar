package dev.uni.glassbar.util

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃自守卫:
 * - 包装宿主进程的默认 UncaughtExceptionHandler, 任何崩溃都先落一份堆栈到我们的日志文件再交还原 handler;
 * - 若崩溃发生时我们的底栏 overlay 已注入 (大概率与模块相关), 计数 +1;
 *   连续 2 次这样的崩溃后自动写入 disable 开关, 下次启动不再注入 (自愈), 用户删掉开关文件即可重新启用。
 */
object CrashGuard {

    private const val AUTO_DISABLE_THRESHOLD = 2

    @Volatile
    private var installed = false

    @Volatile
    private var overlayActive = false

    fun setOverlayActive(active: Boolean) {
        overlayActive = active
    }

    fun install(filesDir: File) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    FileLogger.init(filesDir)
                    if (overlayActive) {
                        val count = FileLogger.readCrashCount() + 1
                        FileLogger.writeCrashCount(count)
                        if (count >= AUTO_DISABLE_THRESHOLD) {
                            FileLogger.setDisabled(true)
                            FileLogger.w("auto-disabled after $count consecutive crashes with overlay active; delete <filesDir>/uniglassbar/disable to re-enable")
                        }
                    }
                    FileLogger.w(
                        "UNCAUGHT on thread=${thread.name} overlayActive=$overlayActive\n" +
                            StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString(),
                        throwable
                    )
                }
                previous?.uncaughtException(thread, throwable)
            }
            installed = true
        }
    }
}
