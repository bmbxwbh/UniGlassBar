package dev.uni.glassbar.entry

import androidx.annotation.Keep
import dev.uni.glassbar.BarInjector
import dev.uni.glassbar.util.FileLogger
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * libxposed API 102 入口 (META-INF/xposed/java_init.list 注册)。
 * hook 走 LxpHookBridge (XposedInterface.Hooker 原生 API, 不引用 de.robv 类)。
 */
@Keep
class LxpHookEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        runCatching {
            FileLogger.i(
                "libxposed entry module loaded: apiVersion=$apiVersion " +
                    "framework=$frameworkName $frameworkVersion " +
                    "applicationInfo=${runCatching { moduleApplicationInfo.sourceDir }.getOrNull()}"
            )
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // 强制日志: 模块一进目标进程就落盘
        runCatching {
            FileLogger.initForce(param.packageName, param.applicationInfo?.dataDir)
        }
        FileLogger.i("libxposed entry: onPackageReady pkg=${param.packageName} firstPackage=${param.isFirstPackage}")

        if (param.packageName != BarInjector.TARGET_PACKAGE) {
            FileLogger.i("not target process, skip")
            return
        }
        runCatching {
            LxpHookBridge.installOnce(this)
            FileLogger.i("libxposed entry active")
        }.onFailure {
            FileLogger.w("libxposed entry install failed", it)
        }
    }
}
