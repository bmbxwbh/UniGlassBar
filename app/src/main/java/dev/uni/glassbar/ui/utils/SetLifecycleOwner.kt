package dev.uni.glassbar.ui.utils

import android.view.View
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

// 摘自 WeKit ui/utils/ComposeUtils.kt: 给被注入的 ComposeView 挂上 Compose 必需的三棵 owner 树
fun View.setLifecycleOwner(lifecycleOwner: XposedLifecycleOwner) {
    setViewTreeLifecycleOwner(lifecycleOwner)
    setViewTreeViewModelStoreOwner(lifecycleOwner)
    setViewTreeSavedStateRegistryOwner(lifecycleOwner)
}
