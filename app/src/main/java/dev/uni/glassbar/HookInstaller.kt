package dev.uni.glassbar

import android.app.Activity

/**
 * 共用 hook 安装逻辑: legacy (assets/xposed_init) 与 libxposed 102 (META-INF/xposed) 两个入口都调这里。
 * 运行时 LSPosed 会同时向宿主提供 de.robv 兼容层, 因此新入口也可以直接使用 XposedHelpers。
 */
object HookInstaller {

    @Volatile
    private var installed = false

    fun installOnce() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        try {
                            BarInjector.tryInject(activity)
                        } catch (t: Throwable) {
                            dev.uni.glassbar.util.XLog.w("tryInject crashed", t)
                        }
                    }
                }
            )
            installed = true
            dev.uni.glassbar.util.XLog.i("Activity.onResume hook installed")
        }
    }
}
