package dev.uni.glassbar.entry

import android.app.Activity
import android.app.Instrumentation
import dev.uni.glassbar.BarInjector
import dev.uni.glassbar.util.FileLogger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * libxposed 入口专用桥: 用 XposedInterface.Hooker 原生 API 实现 hook。
 *
 * 关键教训 (来自真机日志): libxposed 102 新入口的进程里, LSPosed 不提供 de.robv 兼容层
 * (其 API 类被混淆重命名, 且仅对 legacy 入口的模块做引用重映射),
 * 因此本入口的任何类都不得引用 de.robv.* —— 否则 NoClassDefFoundError。
 *
 * 双 hook 策略:
 * - Instrumentation.callActivityOnResume: 框架对每个 Activity 必经, 不依赖子类是否重写 onResume;
 * - Activity.onResume: 备份路径。
 * 并记录每个 Activity 的触发 (去重), 用于判断宿主是否走到了 UI 阶段。
 */
object LxpHookBridge {

    @Volatile
    private var installed = false

    private val seenActivities = ConcurrentHashMap<String, Boolean>()

    fun installOnce(self: XposedModule) {
        if (installed) return
        synchronized(this) {
            if (installed) return

            // 必经路径: ActivityThread 会调用 instrumentation.callActivityOnResume(activity)
            val callOnResume = Instrumentation::class.java
                .getDeclaredMethod("callActivityOnResume", Activity::class.java)
            self.hook(callOnResume)
                .setPriority(50)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        val activity = chain.args.firstOrNull() as? Activity
                        if (activity != null) {
                            logActivity(activity)
                            try {
                                BarInjector.tryInject(activity)
                            } catch (t: Throwable) {
                                FileLogger.w("tryInject crashed (callActivityOnResume)", t)
                            }
                        }
                        return result
                    }
                })

            // 备份路径
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            self.hook(onResume)
                .setPriority(50)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        val activity = chain.thisObject as? Activity
                        if (activity != null) {
                            logActivity(activity)
                            try {
                                BarInjector.tryInject(activity)
                            } catch (t: Throwable) {
                                FileLogger.w("tryInject crashed (onResume)", t)
                            }
                        }
                        return result
                    }
                })

            installed = true
            FileLogger.i("hooks installed (libxposed): Instrumentation.callActivityOnResume + Activity.onResume")
        }
    }

    private fun logActivity(activity: Activity) {
        val name = activity.javaClass.name
        if (seenActivities.putIfAbsent(name, true) == null) {
            FileLogger.i("activity resumed: $name")
        }
    }
}
