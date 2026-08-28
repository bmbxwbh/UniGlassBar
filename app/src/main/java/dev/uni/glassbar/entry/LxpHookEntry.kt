package dev.uni.glassbar.entry

import androidx.annotation.Keep
import dev.uni.glassbar.BarInjector
import dev.uni.glassbar.HookInstaller
import dev.uni.glassbar.util.XLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * libxposed API 102 入口 (META-INF/xposed/java_init.list 注册), 与 WeKit 的 LxpHookEntry 对齐。
 * hook 本体复用 HookInstaller (de.robv 兼容层, LSPosed 运行时始终提供)。
 */
@Keep
class LxpHookEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        runCatching {
            XLog.i("libxposed entry loaded, apiVersion=$apiVersion framework=$frameworkName $frameworkVersion")
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != BarInjector.TARGET_PACKAGE) return
        runCatching {
            HookInstaller.installOnce()
            XLog.i("libxposed entry active, target=${param.packageName}")
        }.onFailure {
            XLog.w("libxposed entry install failed", it)
        }
    }
}
