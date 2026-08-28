package dev.uni.glassbar.util

/**
 * Xposed 日志桥。
 *
 * 注意: 不得直接 import de.robv.android.xposed.* —— libxposed 102 新入口的进程里
 * 不存在这些类 (会被混淆重命名且不做引用重映射), 硬引用会 NoClassDefFoundError。
 * 这里用反射调用 XposedBridge.log, 拿不到就静默降级 (文件日志不受影响)。
 */
object XLog {

    private val logString = runCatching {
        Class.forName("de.robv.android.xposed.XposedBridge")
            .getMethod("log", String::class.java)
    }.getOrNull()

    private val logThrowable = runCatching {
        Class.forName("de.robv.android.xposed.XposedBridge")
            .getMethod("log", Throwable::class.java)
    }.getOrNull()

    private const val TAG = "UniGlassBar"

    fun i(message: String) {
        runCatching { logString?.invoke(null, "[$TAG] $message") }
    }

    fun w(message: String, t: Throwable? = null) {
        runCatching {
            logString?.invoke(null, "[$TAG] $message ${t?.let { "- ${it.javaClass.simpleName}: ${it.message}" } ?: ""}")
            t?.let { logThrowable?.invoke(null, it) }
        }
    }
}
