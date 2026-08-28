package dev.uni.glassbar.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 内置文件日志 (强制模式):
 * 模块入口一被加载就初始化, 无论后续是否崩溃/是否命中 hook, 都有日志落盘。
 *
 * 双目录:
 *  1. 宿主私有: <dataDir>/files/uniglassbar/log.txt
 *  2. 外部公共: /storage/emulated/0/Android/data/com.changan.uni/files/uniglassbar/log.txt
 *     (无需 root, 任意文件管理器可读)
 *
 * 开关: 目录内创建 "disable" 文件 → 注入器跳过; 连续崩溃 2 次自动生成 (自愈)。
 * 所有 IO 走单线程后台, 异常全部吞掉。
 */
object FileLogger {

    const val DISABLE_FILE = "disable"
    const val CRASH_COUNT_FILE = "crash_count"

    @Volatile
    private var dirs: List<File> = emptyList()

    @Volatile
    private var initialized = false

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "UniGlassBar-FileLog").apply { isDaemon = true }
    }

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 入口级强制初始化: 在 Xposed 入口线程就要调用。dataDir 为宿主 appInfo.dataDir, 可空。 */
    fun initForce(packageName: String, dataDir: String?) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val list = mutableListOf<File>()
            if (dataDir != null) {
                runCatching { list += File(dataDir, "files/uniglassbar") }
            }
            // 外部 app 专属目录: 同 uid 可直接写, 用户无需 root 即可读取
            runCatching {
                list += File("/storage/emulated/0/Android/data/$packageName/files/uniglassbar")
            }
            dirs = list.mapNotNull { d ->
                runCatching {
                    d.mkdirs()
                    if (d.isDirectory) d else null
                }.getOrNull()
            }
            initialized = true
        }
        i("=== UniGlassBar module loaded, forced logging active ===")
        i("log dirs: ${dirs.joinToString() { it.absolutePath }}")
        if (dirs.isEmpty()) XLog.w("no writable log dir!")
    }

    /** 兼容旧调用: 从 Activity 侧再确保一次 (带真实 filesDir)。 */
    fun init(filesDir: File) {
        if (!initialized) initForce("com.changan.uni", filesDir.parentFile?.absolutePath)
    }

    fun isEnabled(): Boolean = dirs.firstOrNull()
        ?.let { d -> runCatching { File(d, DISABLE_FILE).exists() }.getOrDefault(false) }
        ?: false

    fun setDisabled(disabled: Boolean) {
        val d = dirs.firstOrNull() ?: return
        runCatching {
            val f = File(d, DISABLE_FILE)
            if (disabled) f.createNewFile() else f.delete()
        }
    }

    fun readCrashCount(): Int = dirs.firstOrNull()?.let {
        runCatching { File(it, CRASH_COUNT_FILE).readText().trim().toIntOrNull() ?: 0 }.getOrDefault(0)
    } ?: 0

    fun writeCrashCount(count: Int) {
        val d = dirs.firstOrNull() ?: return
        runCatching { File(d, CRASH_COUNT_FILE).writeText(count.toString()) }
    }

    fun i(message: String) = write("INFO", message, null)

    fun w(message: String, t: Throwable? = null) = write("WARN", message, t)

    private fun write(level: String, message: String, t: Throwable?) {
        val line = "${fmt.format(Date())} [$level] $message" +
            (t?.let { sw ->
                "\n    ${sw.javaClass.name}: ${sw.message}\n" +
                    sw.stackTrace.take(24).joinToString("\n    ") { it.toString() }
            } ?: "") + "\n"
        if (level == "WARN") XLog.w(message) else XLog.i(message)
        executor.execute {
            for (d in dirs) {
                runCatching {
                    val f = File(d, "log.txt")
                    if (f.length() > 512 * 1024) f.writeText("")
                    f.appendText(line)
                }
            }
        }
    }
}
