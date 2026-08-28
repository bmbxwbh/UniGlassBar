package dev.uni.glassbar.entry

import android.app.Activity
import dev.uni.glassbar.BarInjector
import dev.uni.glassbar.util.FileLogger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * libxposed 入口专用桥: 用 XposedInterface.Hooker 原生 API 实现 hook。
 *
 * 关键教训 (来自真机日志): libxposed 102 新入口的进程里, LSPosed 不提供 de.robv 兼容层
 * (其 API 类被混淆重命名, 且仅对 legacy 入口的模块做引用重映射),
 * 因此本入口的任何类都不得引用 de.robv.* —— 否则 NoClassDefFoundError。
 */
object LxpHookBridge {

    @Volatile
    private var installed = false

    fun installOnce(self: XposedModule) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            self.hook(onResume)
                .setPriority(50)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        // after 语义: 先放行原方法, 再执行注入 (与 XC_MethodHook.afterHookedMethod 等价)
                        val result = chain.proceed()
                        val activity = chain.thisObject as? Activity
                        if (activity != null) {
                            try {
                                BarInjector.tryInject(activity)
                            } catch (t: Throwable) {
                                FileLogger.w("tryInject crashed", t)
                            }
                        }
                        return result
                    }
                })
            installed = true
            FileLogger.i("Activity.onResume hook installed (libxposed Hooker)")
        }
    }
}
