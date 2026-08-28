package dev.uni.glassbar.util

import de.robv.android.xposed.XposedBridge

object XLog {
    private const val TAG = "UniGlassBar"

    fun i(message: String) {
        runCatching { XposedBridge.log("[$TAG] $message") }
    }

    fun w(message: String, t: Throwable? = null) {
        runCatching {
            XposedBridge.log("[$TAG] $message ${t?.let { "- ${it.javaClass.simpleName}: ${it.message}" } ?: ""}")
            t?.let { XposedBridge.log(it) }
        }
    }
}
