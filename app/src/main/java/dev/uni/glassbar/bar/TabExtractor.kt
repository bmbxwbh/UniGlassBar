package dev.uni.glassbar.bar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import dev.uni.glassbar.util.XLog
import java.lang.reflect.Field
import java.lang.reflect.Modifier

data class TabInfo(
    val label: String,
    val normalIcon: Bitmap?,
    val selectedIcon: Bitmap?,
    val labelColorNormal: Int,
    val labelColorSelected: Int,
)

/**
 * 运行时从原生底栏 (PageNavigationView) 抠取每个 tab 的图标与标题。
 *
 * 分层策略, 任何一层失败都能落到下一层:
 * 1. 遍历底栏顶层 item 的视图树, 收集 TextView 标题 + ImageView Drawable;
 * 2. Drawable 若为 StateListDrawable, 分别用选中/未选中状态渲染出两张位图;
 * 3. 视图树里没有 ImageView 时, 反射扫描 item 的 Drawable 字段 (库内部把两态图标存在字段里的情况);
 * 4. 完全失败时按资源名解析 (shouye/quanzi/lexiang/wode/cheKong 系列 + maintab1-5 标题);
 * 5. 最终兜底: 圆点图标 + 序号标题。
 */
object TabExtractor {

    private const val ICON_SIZE = 96 // 渲染图标的目标边长 (px), 保证清晰

    fun extract(context: Context, bar: ViewGroup, initialSelected: Int): List<TabInfo> {
        val items = topLevelItems(bar)
        if (items.isEmpty()) return resourceFallback(context, items.size)

        val infos = items.mapIndexed { index, item ->
            extractOne(item, isSelected = index == initialSelected)
        }

        // 统一配色: 选中色取自初始选中项, 普通色取自第一个未选中项
        val selectedColor = infos.getOrNull(initialSelected)?.takeIf { it.labelColorSelected != 0 }
            ?: infos.firstOrNull { it.labelColorSelected != 0 }?.labelColorSelected
            ?: 0xFF2A3344.toInt()
        val normalColor = infos.firstOrNull { it.labelColorNormal != 0 }?.labelColorNormal
            ?: 0xFF8A8A8E.toInt()

        val resolved = infos.map {
            it.copy(
                labelColorNormal = if (it.labelColorNormal != 0) it.labelColorNormal else normalColor,
                labelColorSelected = selectedColor,
            )
        }

        // 图标全空时的资源名兜底
        if (resolved.all { it.normalIcon == null && it.selectedIcon == null }) {
            val byRes = resourceFallback(context, resolved.size)
            if (byRes.size == resolved.size) {
                return resolved.mapIndexed { i, t ->
                    t.copy(
                        normalIcon = byRes[i].normalIcon ?: t.normalIcon,
                        selectedIcon = byRes[i].selectedIcon ?: t.selectedIcon,
                        label = t.label.takeIf { it.isNotBlank() } ?: byRes[i].label,
                    )
                }
            }
            return resolved.map { it.copy(normalIcon = dotIcon(selectedColor)) }
        }
        return resolved
    }

    // ------------------------------------------------------------ 顶层 item 定位

    private fun topLevelItems(bar: ViewGroup): List<View> {
        val direct = childrenOf(bar)
        val candidates = if (direct.size >= 3) direct else {
            // 库可能套了一层内部容器
            direct.filterIsInstance<ViewGroup>().flatMap { childrenOf(it) }
        }
        // PageNavigationView 自身可能带导航条按钮, 过滤掉没有内容的碎视图
        val withContent = candidates.filter { hasIconOrText(it) }
        return if (withContent.size >= 3) withContent else candidates
    }

    private fun childrenOf(group: ViewGroup): List<View> =
        (0 until group.childCount).map { group.getChildAt(it) }

    private fun hasIconOrText(view: View): Boolean {
        var found = false
        walk(view, depth = 0) { v ->
            if (v is ImageView && v.drawable != null) found = true
            if (v is TextView && !v.text.isNullOrBlank()) found = true
        }
        if (!found) found = reflectDrawables(view).isNotEmpty() || reflectText(view).isNotBlank()
        return found
    }

    // ------------------------------------------------------------ 单个 item

    private fun extractOne(item: View, isSelected: Boolean): TabInfo {
        var label = ""
        var labelColor = 0
        val drawables = LinkedHashMap<Any, Drawable>() // key 去重

        walk(item, depth = 0) { v ->
            if (v is TextView && label.isBlank() && !v.text.isNullOrBlank()) {
                label = v.text.toString().trim()
                labelColor = v.currentTextColor
            }
            if (v is ImageView) {
                v.drawable?.let { drawables.put(it.constantState ?: it, it) }
            }
        }

        if (label.isBlank()) label = reflectText(item)
        if (labelColor == 0) labelColor = reflectTextColor(item)
        if (drawables.isEmpty()) {
            reflectDrawables(item).forEach { drawables.put(it.constantState ?: it, it) }
        }

        val (normal, selected) = splitIconStates(drawables.values.toList())
        return TabInfo(
            label = label,
            normalIcon = normal,
            selectedIcon = selected ?: normal,
            labelColorNormal = if (isSelected) 0 else labelColor,
            labelColorSelected = if (isSelected) labelColor else 0,
        )
    }

    private fun walk(root: View, depth: Int, block: (View) -> Unit) {
        if (depth > 4) return
        block(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) walk(root.getChildAt(i), depth + 1, block)
        }
    }

    // ------------------------------------------------------------ 两态图标拆分

    private fun splitIconStates(drawables: List<Drawable>): Pair<Bitmap?, Bitmap?> {
        if (drawables.isEmpty()) return null to null
        val d = drawables.first()
        if (d is StateListDrawable) {
            val normal = firstNonEmpty(
                renderState(d, intArrayOf(-android.R.attr.state_checked)),
                renderState(d, intArrayOf(-android.R.attr.state_selected)),
                renderState(d, intArrayOf()),
            )
            val selected = listOf(
                intArrayOf(android.R.attr.state_selected),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_activated),
            ).mapNotNull { renderState(d, it) }
                .firstOrNull { normal == null || !bitmapEquals(it, normal) }
            return normal to (selected ?: normal)
        }
        // 非 selector: 依次尝试第二个 drawable 作为选中态 (字段扫描常按 [normal, selected] 排列)
        if (drawables.size >= 2) {
            val second = renderDrawable(drawables[1])
            val first = renderDrawable(d)
            if (second != null && (first == null || !bitmapEquals(second, first))) {
                return first to second
            }
            return first to first
        }
        val only = renderDrawable(d)
        return only to only
    }

    private fun renderState(d: Drawable, states: IntArray): Bitmap? = runCatching {
        val mutated = d.mutate()
        mutated.state = states
        renderDrawable(mutated)
    }.getOrNull()

    private fun renderDrawable(d: Drawable): Bitmap? = runCatching {
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else ICON_SIZE
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else ICON_SIZE
        if (w <= 0 || h <= 0 || w > 2048 || h > 2048) return@runCatching null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        bmp
    }.getOrNull()

    private fun firstNonEmpty(vararg bitmaps: Bitmap?): Bitmap? {
        bitmaps.forEach { if (it != null && hasVisiblePixels(it)) return it }
        return bitmaps.firstOrNull { it != null }
    }

    private fun hasVisiblePixels(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        val step = maxOf(1, (w * h / 512)) // 稀疏采样, 足够判断是否画了东西
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var i = 0
        while (i < pixels.size) {
            if (pixels[i] != 0) return true
            i += step
        }
        return false
    }

    private fun bitmapEquals(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val pa = IntArray(a.width * a.height)
        val pb = IntArray(b.width * b.height)
        a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
        return pa.contentEquals(pb)
    }

    // ------------------------------------------------------------ 反射兜底

    private fun reflectDrawables(view: View): List<Drawable> {
        val out = mutableListOf<Drawable>()
        try {
            var clazz: Class<*>? = view.javaClass
            var level = 0
            while (clazz != null && clazz != Any::class.java && level < 4) {
                for (f in clazz.declaredFields) collectDrawableField(f, view, out) // NOSONAR
                clazz = clazz.superclass
                level++
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun collectDrawableField(field: Field, owner: Any, out: MutableList<Drawable>) {
        if (Modifier.isStatic(field.modifiers)) return
        runCatching {
            field.isAccessible = true
            when (val value = field.get(owner)) {
                is Drawable -> out.add(value)
                is Array<*> -> value.filterIsInstance<Drawable>().forEach { out.add(it) }
            }
        }
    }

    private fun reflectText(view: View): String {
        try {
            var clazz: Class<*>? = view.javaClass
            var level = 0
            while (clazz != null && clazz != Any::class.java && level < 4) {
                for (f in clazz.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(view)
                        if (v is CharSequence) {
                            val s = v.toString().trim()
                            if (s.isNotEmpty() && s.length <= 8) return s
                        }
                    }
                }
                clazz = clazz.superclass
                level++
            }
        } catch (_: Throwable) {
        }
        return ""
    }

    private fun reflectTextColor(view: View): Int {
        try {
            var clazz: Class<*>? = view.javaClass
            var level = 0
            while (clazz != null && clazz != Any::class.java && level < 4) {
                for (f in clazz.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(view)
                        if (v is Int && v != 0 && (v and 0xFF000000.toInt()) != 0) return v
                    }
                }
                clazz = clazz.superclass
                level++
            }
        } catch (_: Throwable) {
        }
        return 0
    }

    // ------------------------------------------------------------ 资源名兜底

    // 经 MT-MCP 资源表验证: shouye/shouye_、quanzi/quanzi_、lexiang/lexiang_、wode/wode_ 均为
    // 独立两态 mipmap PNG; 爱车 tab 的图标名未找到带下划线后缀的对, 候选 car (mipmap, -xhdpi)
    private val FALLBACK_ICON_NAMES = listOf(
        "shouye" to "shouye_",   // 发现/首页
        "quanzi" to "quanzi_",   // 圈子/服务
        "car" to "car_",         // 爱车/车控
        "lexiang" to "lexiang_", // 乐享/商城
        "wode" to "wode_",       // 我的
    )
    private val FALLBACK_LABELS = listOf("首页", "圈子", "车控", "乐享", "我的")

    private fun resourceFallback(context: Context, count: Int): List<TabInfo> {
        val res = context.resources
        val pkg = context.packageName
        val labels = (0 until 5).map { i ->
            val id = res.getIdentifier("maintab${i + 1}", "string", pkg)
            if (id != 0) runCatching { res.getString(id) }.getOrNull().orEmpty()
        }.ifEmpty { FALLBACK_LABELS.map { it } }

        return (0 until count.coerceAtLeast(1)).map { i ->
            val idx = i % 5
            var normal: Bitmap? = null
            var selected: Bitmap? = null
            runCatching {
                val (nName, sName) = FALLBACK_ICON_NAMES[idx]
                loadBitmap(res.getIdentifier(nName, "mipmap", pkg), res)?.let { normal = it }
                loadBitmap(res.getIdentifier(sName, "mipmap", pkg), res)?.let { selected = it }
            }.onFailure { XLog.w("resource fallback icon $idx failed", it) }
            TabInfo(
                label = labels.getOrElse(idx) { idx.toString() },
                normalIcon = normal,
                selectedIcon = selected ?: normal,
                labelColorNormal = 0,
                labelColorSelected = 0,
            )
        }
    }

    private fun loadBitmap(resId: Int, res: android.content.res.Resources): Bitmap? {
        if (resId == 0) return null
        return runCatching {
            val d = res.getDrawable(resId, null)
            renderDrawable(d)
        }.getOrNull()
    }

    private fun dotIcon(color: Int): Bitmap {
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawCircle(size / 2f, size / 2f, size / 3f, p)
        return bmp
    }
}
