package dev.uni.glassbar.bar

import android.view.View
import dev.uni.glassbar.util.XLog
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * 与宿主 ViewPager 的桥接。
 *
 * 关键点: 全部通过宿主 classloader 反射 + 动态代理, 模块自身完全不引用 androidx.viewpager 类,
 * 避免模块/宿主两个 classloader 的类身份不一致 (isInstance 失败 / NCDFE)。
 */
object PagerBarBridge {

    fun currentItem(pager: View?): Int {
        if (pager == null) return 0
        return runCatching {
            pager.javaClass.getMethod("getCurrentItem").invoke(pager) as Int
        }.getOrDefault(0)
    }

    fun setCurrentItem(pager: View?, index: Int, smooth: Boolean): Boolean {
        if (pager == null) return false
        return runCatching {
            pager.javaClass.getMethod(
                "setCurrentItem",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).invoke(pager, index, smooth)
            true
        }.getOrElse {
            XLog.w("setCurrentItem failed", it)
            false
        }
    }

    /**
     * 给 pager 追加页面切换监听, 回调写入 [sync]。
     * 先按 ViewPager1 (addOnPageChangeListener) 试, 失败再按 ViewPager2
     * (registerOnPageChangeCallback + OnPageChangeCallback) 试 —— 包内两种都有使用。
     * 全程动态代理宿主类加载器里的接口/抽象类, 模块不引用 androidx 类。
     */
    fun attachPageListener(pager: View?, sync: BarSync): Boolean {
        if (pager == null) {
            XLog.w("pager is null, page sync disabled")
            return false
        }
        if (attachV1Listener(pager, sync)) return true
        if (attachV2Callback(pager, sync)) return true
        XLog.w("pager is neither ViewPager1 nor ViewPager2: ${pager.javaClass.name}, page sync disabled")
        return false
    }

    private fun attachV1Listener(pager: View, sync: BarSync): Boolean = runCatching {
        val loader = pager.javaClass.classLoader ?: return false
        val vpClass = Class.forName("androidx.viewpager.widget.ViewPager", false, loader)
        if (!vpClass.isInstance(pager)) return false
        val listenerClass = vpClass.classes.firstOrNull {
            it.name == "androidx.viewpager.widget.ViewPager\$OnPageChangeListener"
        } ?: return false

        val proxy = Proxy.newProxyInstance(loader, arrayOf(listenerClass), listenerProxy(sync))
        vpClass.getMethod("addOnPageChangeListener", listenerClass).invoke(pager, proxy)
        XLog.i("page listener attached (viewpager1)")
        true
    }.getOrElse {
        XLog.w("attachV1Listener failed", it)
        false
    }

    private fun attachV2Callback(pager: View, sync: BarSync): Boolean = runCatching {
        val loader = pager.javaClass.classLoader ?: return false
        val vp2Class = Class.forName("androidx.viewpager2.widget.ViewPager2", false, loader)
        if (!vp2Class.isInstance(pager)) return false
        val callbackClass = Class.forName(
            "androidx.viewpager2.widget.ViewPager2\$OnPageChangeCallback", false, loader
        )
        val proxy = Proxy.newProxyInstance(loader, arrayOf(callbackClass), listenerProxy(sync))
        vp2Class.getMethod("registerOnPageChangeCallback", callbackClass).invoke(pager, proxy)
        XLog.i("page callback attached (viewpager2)")
        true
    }.getOrElse {
        XLog.w("attachV2Callback failed", it)
        false
    }

    private fun listenerProxy(sync: BarSync): InvocationHandler =
        InvocationHandler { _, method, args ->
            runCatching {
                when (method.name) {
                    // ViewPager1: onPageScrolled(position, offset, pixels) / onPageSelected / onPageScrollStateChanged
                    // ViewPager2: onPageScrolled(position, offset, pixels) 同名; 状态常量一致
                    "onPageScrolled" -> {
                        sync.selected.intValue = args[0] as Int
                        sync.offset.floatValue = args[1] as Float
                    }
                    "onPageSelected" -> sync.target.intValue = args[0] as Int
                }
            }.onFailure { XLog.w("page listener callback failed", it) }
            null
        }
}
