package dev.uni.glassbar

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.uni.glassbar.util.XLog

/**
 * legacy 入口 (assets/xposed_init): 供旧框架 / libxposed 兼容性差的框架回退使用 (WeKit 同款双入口策略)。
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != BarInjector.TARGET_PACKAGE) return
        runCatching {
            HookInstaller.installOnce()
            XLog.i("legacy entry active, target=${lpparam.packageName} process=${lpparam.processName}")
        }.onFailure {
            XLog.w("legacy entry install failed", it)
            XposedBridge.log(it)
        }
    }
}
