package dev.uni.glassbar.bar

import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import dev.uni.glassbar.ui.content.FloatingBottomBar
import dev.uni.glassbar.ui.content.FloatingBottomBarDefaults
import dev.uni.glassbar.ui.content.FloatingBottomBarMode
import dev.uni.glassbar.ui.content.rememberViewBackdrop
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/** pager → Compose 的状态桥, 由 PagerBarBridge 的反射回调写入。 */
class BarSync(initialIndex: Int) {
    val selected = mutableIntStateOf(initialIndex) // 已落定页 (onPageScrolled position)
    val target = mutableIntStateOf(initialIndex)   // 目标页 (onPageSelected), 驱动药丸动画
    val offset = mutableFloatStateOf(0f)
}

/**
 * 液态玻璃悬浮底栏 (Compose + miuix-blur), 与 WeKit 悬浮模式同构:
 * - FloatingBottomBar: WeKit 的药丸底栏组件 (vibrancy + blur + lens 玻璃效果);
 * - rememberViewBackdrop: 把宿主原生 ViewPager 的像素采进玻璃 (WeKit ViewBackdrop 原样搬运);
 * - 图标/标题来自 TabExtractor 的运行时提取, 两态位图随选中切换。
 */
@Composable
fun GlassBarOverlay(
    tabs: List<TabInfo>,
    pager: View?,
    sync: BarSync,
    owner: LifecycleOwner,
    onNavigate: (Int) -> Unit,
) {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxWidth()) {
            val bottomCenter = Modifier.align(Alignment.BottomCenter)

            // 采样宿主原生内容进玻璃; pager 缺失时退化为空层 (只剩玻璃底色)
            val backdrop: Backdrop = if (pager != null) {
                rememberViewBackdrop(pager, owner)
            } else {
                rememberLayerBackdrop()
            }

            val dark = isSystemInDarkTheme()
            val containerColor = if (dark) Color(0xE6151517) else Color(0xE6F7F7F8)
            val activeColor = tabs.firstOrNull { it.labelColorSelected != 0 }
                ?.labelColorSelected?.let(::Color) ?: Color(0xFF2A3344)
            val normalColor = tabs.firstOrNull { it.labelColorNormal != 0 }
                ?.labelColorNormal?.let(::Color) ?: Color(0xFF8A8A8E)

            val target = sync.target.intValue

            FloatingBottomBar(
                items = tabs,
                selectedIndex = { sync.target.intValue },
                onSelected = { index -> onNavigate(index) },
                modifier = bottomCenter.padding(
                    bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                ),
                backdrop = backdrop,
                mode = FloatingBottomBarMode.LiquidGlass,
                colors = FloatingBottomBarDefaults.colors(
                    containerColor = containerColor,
                    indicatorColor = activeColor,
                    contentColor = normalColor,
                    activeContentColor = activeColor,
                ),
                liquidGlassBlurRadius = 12.dp,
                dynamicGravityHighlight = true,
                iconContent = { tab, index ->
                    val isSelected = index == target
                    val bmp = when {
                        isSelected -> tab.selectedIcon ?: tab.normalIcon
                        else -> tab.normalIcon ?: tab.selectedIcon
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = tab.label,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
                labelContent = { tab, _ ->
                    Text(text = tab.label, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1)
                },
            )
        }
    }
}
