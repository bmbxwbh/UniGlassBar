package dev.uni.glassbar

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 模块状态页: 让本模块成为一个"正常"的应用 (有图标/有界面),
 * 避免 LSPosed 包监视器对无界面空壳应用解析失败 (Failed to find package info)。
 * 纯代码构建 UI, 不引入额外资源。
 */
class ModuleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()

        fun text(content: String, size: Float, bold: Boolean = false, color: Int = Color.DKGRAY): TextView =
            TextView(this).apply {
                this.text = content
                textSize = size
                setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(color)
                setPadding(pad, pad / 2, pad, pad / 2)
            }

        val root = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }

        column.addView(text("引力域玻璃底栏", 22f, bold = true, color = Color.BLACK))
        column.addView(text("版本 ${packageManager.getPackageInfo(packageName, 0).versionName}", 13f))
        column.addView(text(
            "使用步骤:\n" +
                "1. 打开 LSPosed 管理器 → 模块 → 勾选启用本模块;\n" +
                "2. 作用域勾选「引力域 (com.changan.uni)」;\n" +
                "3. 重启系统 (首次启用/更新模块后建议重启);\n" +
                "4. 打开引力域查看效果。",
            15f
        ))
        column.addView(text(
            "日志与开关 (需 root):\n" +
                "日志: /data/data/com.changan.uni/files/uniglassbar/log.txt\n" +
                "熔断开关: 同目录创建 disable 文件 = 停用注入, 删除 = 恢复;\n" +
                "注入后连续崩溃 2 次会自动生成该开关 (自愈)。",
            14f
        ))
        column.addView(text(
            "异常排查:\n" +
                "• LSPosed 提示找不到模块包信息 → 卸载后重装本模块并重启;\n" +
                "• 引力域无变化 → 确认 LSPosed 模块页本模块已勾选且作用域正确;\n" +
                "• 引力域闪退 → 查看 log.txt 末尾的 UNCAUGHT 堆栈。",
            14f, color = Color.GRAY
        ))

        root.addView(column)
        setContentView(root)
    }
}
