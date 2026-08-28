package dev.uni.glassbar.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 内置文件日志: 写到宿主 (引力域) 私有数据目录, 不需要 adb/logcat 也能排查。
 *
 * 路径: <引力域 filesDir>/uniglassbar/log.txt
 * 开关: 同目录创建 "disable" 文件 → 注入器跳过 (自愈/手动熔断)。
 * 所有写入走单线程后台, 异常全部吞掉, 绝不影响宿主。
 */
object FileLogger {

    @Volatile
    private var dir: File? = null

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "UniGlassBar-FileLog").apply { isDaemon = true }
    }

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    const val DISABLE_FILE = "disable"
    const val CRASH_COUNT_FILE = "crash_count"

    fun init(filesDir: File) {
        if (dir != null) return
        synchronized(this) {
            if (dir != null) return
            dir = runCatching {
                File(filesDir, "uniglassbar").apply { mkdirs() }
            }.getOrNull()
        }
        i("logger initialized at ${dir?.absolutePath}")
    }

    fun isEnabled(): Boolean = runCatching { File(dir, DISABLE_FILE).exists() }.getOrDefault(false)

    fun setDisabled(disabled: Boolean) {
        runCatching {
            val f = File(dir, DISABLE_FILE)
            if (disabled) f.createNewFile() else f.delete()
        }
    }

    fun readCrashCount(): Int = runCatching {
        File(dir, CRASH_COUNT_FILE).readText().trim().toIntOrNull() ?: 0
    }.getOrDefault(0)

    fun writeCrashCount(count: Int) {
        runCatching { File(dir, CRASH_COUNT_FILE).writeText(count.toString()) }
    }

    fun i(message: String) = write("INFO", message, null)

    fun w(message: String, t: Throwable? = null) = write("WARN", message, t)

    private fun write(level: String, message: String, t: Throwable?) {
        val line = "${fmt.format(Date())} [$level] $message" +
            (t?.let { sw -> "\n    ${sw.javaClass.name}: ${sw.message}\n" + sw.stackTrace.take(24).joinToString("\n    ") { it.toString() } } ?: "") + "\n"
        // 同时镜像到 LSPosed 日志, 便于双端对照
        if (level == "WARN") XLog.w(message) else XLog.i(message)
        executor.execute {
            runCatching {
                val d = dir ?: return@execute
                val f = File(d, "log.txt")
                if (f.length() > 512 * 1024) f.writeText("") // 简单防膨胀
                f.appendText(line)
            }
        }
    }
}
