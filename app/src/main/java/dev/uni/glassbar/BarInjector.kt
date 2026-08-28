package dev.uni.glassbar

import android.app.Activity
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import dev.uni.glassbar.bar.BarSync
import dev.uni.glassbar.bar.GlassBarOverlay
import dev.uni.glassbar.bar.PagerBarBridge
import dev.uni.glassbar.bar.TabExtractor
import dev.uni.glassbar.ui.utils.LifecycleOwnerProvider
import dev.uni.glassbar.ui.utils.setLifecycleOwner
import dev.uni.glassbar.util.CrashGuard
import dev.uni.glassbar.util.FileLogger
import dev.uni.glassbar.util.XLog

/**
 * 注入器 (WeKit 悬浮模式同构):
 * 1. 在 Activity 内容里找原生底栏 PageNavigationView (类名精确匹配, 不依赖 classloader 的 Class 对象, 对加固壳免疫);
 * 2. 提取每个 tab 的图标与标题 (运行时从原 View 抠取, 保证与线上样式一致);
 * 3. 隐藏原生底栏 (保留实例, 其内部与 ViewPager 的绑定仍在, 便于回退);
 * 4. 把 ComposeView 渲染的 miuix 玻璃底栏作为 overlay 加到 android.R.id.content 底部 (不参与内容布局)。
 */
object BarInjector {

    const val TARGET_PACKAGE = "com.changan.uni"

    private const val OVERLAY_TAG = "uni_glass_bar_overlay"
    private const val BAR_CLASS = "me.majiajie.pagerbottomtabstrip.PageNavigationView"
    private const val MAX_RETRY = 4

    private val retryCounts = HashMap<Int, Int>()

    fun tryInject(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        // 尽早初始化文件日志与崩溃守卫 (目录: 引力域 filesDir/uniglassbar/)
        runCatching {
            FileLogger.init(activity.filesDir)
            CrashGuard.install(activity.filesDir)
        }
        if (FileLogger.isEnabled()) {
            XLog.i("disabled by switch file, skip injection")
            return
        }
        FileLogger.i("tryInject on ${activity.javaClass.name}")

        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
            FileLogger.w("android.R.id.content not found")
            return
        }
        if (content.findViewWithTag<View>(OVERLAY_TAG) != null) return

        val originalBar = findViewByExactClassName(content, BAR_CLASS)
        if (originalBar == null) {
            scheduleRetry(activity)
            return
        }
        FileLogger.i("native bar found: ${originalBar.javaClass.name}, children=${originalBar.childCount}")

        val pager = findSiblingPager(originalBar)
        val initialIndex = PagerBarBridge.currentItem(pager)
        FileLogger.i(
            "pager=${pager?.javaClass?.name ?: "<not found>"} initialIndex=$initialIndex"
        )

        // ---- 提取原生 tab 数据 (图标/标题/配色), 失败自动走资源/占位回退 ----
        val tabs = TabExtractor.extract(activity, originalBar, initialIndex)
        FileLogger.i(
            "extracted ${tabs.size} tabs: " + tabs.joinToString(" | ") { t ->
                "${t.label}(normal=${t.normalIcon != null}, selected=${t.selectedIcon != null})"
            }
        )
        if (tabs.isEmpty()) {
            FileLogger.w("tab extraction returned empty, skip injection")
            return
        }
        // 原底栏视图树结构 (诊断提取问题用)
        FileLogger.i("bar view tree:\n" + dumpTree(originalBar, maxDepth = 3))

        // ---- 隐藏原生底栏 (保留视图与监听器, 停用模块即恢复) ----
        originalBar.visibility = View.GONE

        // ---- pager ↔ Compose 状态桥 ----
        val sync = BarSync(initialIndex)
        val owner = LifecycleOwnerProvider.getOrCreate(activity)
        val dm = activity.resources.displayMetrics

        // ---- ComposeView: miuix 玻璃底栏 (与 WeKit 注入方式一致) ----
        val composeView = ComposeView(activity).apply {
            tag = OVERLAY_TAG
            setLifecycleOwner(owner)
            clipChildren = false
            clipToPadding = false
            setContent {
                GlassBarOverlay(
                    tabs = tabs,
                    pager = pager,
                    sync = sync,
                    owner = owner,
                    onNavigate = { index -> navigate(pager, originalBar, index) },
                )
            }
        }

        // 药丸按压放大 (graphicsLayer ~1.39x) 会超出 ComposeView 边界, 关闭各级裁剪 (WeKit 同款处理)
        content.clipChildren = false
        content.clipToPadding = false

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            leftMargin = dp(dm, 10f)
            rightMargin = dp(dm, 10f)
            bottomMargin = dp(dm, 12f)
        }

        // 手势条适配: 底部边距加上 navigation bar inset
        composeView.setOnApplyWindowInsetsListener { v, insets ->
            try {
                lp.bottomMargin = dp(dm, 12f) + insets.systemWindowInsetBottom
                v.layoutParams = lp
            } catch (_: Throwable) {
            }
            insets
        }

        content.addView(composeView, lp)
        CrashGuard.setOverlayActive(true)
        FileLogger.writeCrashCount(0) // 成功挂载后重置连续崩溃计数
        composeView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = CrashGuard.setOverlayActive(true)
            override fun onViewDetachedFromWindow(v: View) = CrashGuard.setOverlayActive(false)
        })

        // pager 滑动 → 药丸/选中态同步
        val listenerAttached = PagerBarBridge.attachPageListener(pager, sync)
        FileLogger.i(
            "overlay attached, listenerAttached=$listenerAttached, " +
                "overlaySize=${composeView.width}x${composeView.height} (首次布局前为 0 属正常)"
        )

        XLog.i(
            "injected: activity=${activity.javaClass.name} tabs=${tabs.size} " +
                "pager=${pager?.javaClass?.name} api=${Build.VERSION.SDK_INT}"
        )
    }

    /** 点击新底栏 tab: 优先驱动原 ViewPager (滑动动画), 失败则回退到点击原底栏 item (走原生逻辑)。 */
    private fun navigate(pager: View?, originalBar: ViewGroup, index: Int) {
        if (pager != null && PagerBarBridge.setCurrentItem(pager, index, true)) return
        fallbackClickOriginalItem(originalBar, index)
    }

    private fun fallbackClickOriginalItem(bar: ViewGroup, index: Int) {
        runCatching {
            val items = mutableListOf<View>()
            collectChildren(bar, items, depth = 0, maxDepth = 3)
            val target = items.getOrNull(index) ?: return
            target.performClick()
        }.onFailure { XLog.w("fallback click failed", it) }
    }

    private fun scheduleRetry(activity: Activity) {
        // 底栏可能在 onResume 之后才加入视图树 (Fragment 异步挂载), 有限次重试
        val key = System.identityHashCode(activity)
        val count = retryCounts[key] ?: 0
        if (count >= MAX_RETRY) return
        retryCounts[key] = count + 1
        val decor = activity.window?.decorView ?: return
        decor.postDelayed({
            try {
                tryInject(activity)
            } catch (_: Throwable) {
            }
        }, 600L)
    }

    // ------------------------------------------------------------------ 视图查找

    /** 按类全名精确匹配查找视图, 全程只用 class name 字符串, 不做 isinstance (壳/类加载器无关)。 */
    private fun findViewByExactClassName(root: View, className: String): ViewGroup? {
        if (root.javaClass.name == className && root is ViewGroup) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            val found = findViewByExactClassName(root.getChildAt(i), className)
            if (found != null) return found
        }
        return null
    }

    /** 找底栏同容器里的内容 pager (布局结构: LinearLayout[CustomViewPager, PageNavigationView])。 */
    private fun findSiblingPager(bar: ViewGroup): View? {
        val parent = bar.parent as? ViewGroup ?: return null
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child !== bar && child.javaClass.name.endsWith("ViewPager")) return child
        }
        // 兜底: 往上再找一层
        val grand = parent.parent as? ViewGroup ?: return null
        for (i in 0 until grand.childCount) {
            val child = grand.getChildAt(i)
            if (child !== parent && child !== bar && child.javaClass.name.endsWith("ViewPager")) return child
        }
        return null
    }

    private fun collectChildren(root: ViewGroup, out: MutableList<View>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            out.add(child)
            if (child is ViewGroup) collectChildren(child, out, depth + 1, maxDepth)
        }
    }

    /** 底栏视图树结构 dump (缩进文本), 用于诊断提取为什么失败。 */
    private fun dumpTree(root: ViewGroup, maxDepth: Int): String {
        val sb = StringBuilder()
        fun walk(v: View, depth: Int) {
            if (depth > maxDepth) return
            repeat(depth) { sb.append("  ") }
            sb.append(v.javaClass.simpleName.ifEmpty { v.javaClass.name })
            if (v is TextView) sb.append(" \"${v.text}\"")
            if (v is ImageView) sb.append(" drawable=${v.drawable?.javaClass?.simpleName}")
            sb.append('\n')
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    fun dp(dm: android.util.DisplayMetrics, value: Float): Int = Math.round(value * dm.density)
}
