package dev.uni.glassbar

import android.app.Activity
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.uni.glassbar.util.FileLogger
import dev.uni.glassbar.util.XLog

/**
 * legacy 入口 (assets/xposed_init): 供旧框架 / libxposed 兼容性差的框架回退使用 (WeKit 同款双入口策略)。
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 强制日志: 模块一进进程就落盘, 用于回答"模块到底加载没有"
        runCatching {
            FileLogger.initForce(
                lpparam.packageName,
                lpparam.appInfo?.dataDir,
            )
        }
        FileLogger.i("legacy entry: handleLoadPackage pkg=${lpparam.packageName} proc=${lpparam.processName}")

        if (lpparam.packageName != BarInjector.TARGET_PACKAGE) {
            FileLogger.i("not target process, skip")
            return
        }
        runCatching {
            HookInstaller.installOnce()
            FileLogger.i("legacy entry active")
        }.onFailure {
            FileLogger.w("legacy entry install failed", it)
            XposedBridge.log(it)
        }
    }
}
