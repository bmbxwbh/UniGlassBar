# UniGlassBar — 引力域液态玻璃底栏 (WeKit 同构版)

一个 LSPosed (Xposed) 模块，把长安引力域 App (`com.changan.uni`) 的原生底部导航栏
(`me.majiajie.pagerbottomtabstrip.PageNavigationView`) 替换为 **miuix 液态玻璃悬浮底栏**，
图标与标题在运行时从原生底栏提取，与线上样式保持一致。

实现思路与 WeKit 一致：运行时 hook、隐藏原生底栏、注入 ComposeView 悬浮底栏；
UI 组件 (`FloatingBottomBar` / `ViewBackdrop` / 动画与玻璃效果层) 直接取自 WeKit 源码。

## 与 WeKit 的对齐点

| 项 | 版本 |
|---|---|
| libxposed API | `io.github.libxposed:api:102.0.0`（`META-INF/xposed` 注册，targetApiVersion=102） |
| legacy API | 已移除，仅保留 libxposed 102 单入口|
| miuix | `top.yukonga.miuix.kmp:miuix-blur-android:0.9.4-rc01` (+ miuix-shader) |
| Compose | BOM `2026.08.00`，material3 `1.5.0-alpha26` |
| 工具链 | AGP 9.3.1 / Kotlin 2.4.10 / Gradle 9.7.0 / JDK 21 |

## 原理

1. **入口**：libxposed 102 单入口 (`LxpHookBridge` 原生 Hooker)——只 hook 框架类 `android.app.Activity.onResume`
   （与加固壳的类加载解耦），在每个 Activity 里探测类名
   `me.majiajie.pagerbottomtabstrip.PageNavigationView`（按类名字符串精确匹配，不做
   `isInstance`，对爱加密壳/双 classloader 免疫）。
2. **提取**：遍历底栏顶层 item，抠出标题 TextView、图标 Drawable；selector 图标按
   选中/未选中状态分别渲染位图。回退链：视图树 → 反射 Drawable 字段 → 资源名 → 圆点占位。
3. **替换**：原生底栏 `GONE`（保留实例，停用模块即恢复）；`ComposeView` 以
   `Gravity.BOTTOM` 加到 `android.R.id.content`，`clipChildren` 全链路关闭（药丸放大溢出）。
   Compose 生命周期用 WeKit 的 `XposedLifecycleOwner` + `LifecycleOwnerProvider` 方案。
4. **玻璃**：`rememberViewBackdrop(viewPager)`（WeKit `ViewBackdrop` 原样搬运）把宿主
   原生 pager 的像素采进 `GraphicsLayer`（补 `-scrollX/-scrollY`，`isDirty` 门控），
   `drawBackdrop { vibrancy(); blur(); lens() }` 出 miuix 液态玻璃质感。
5. **联动**：反射 + 动态代理给宿主 `CustomViewPager` 追加 `OnPageChangeListener`，
   写入 `BarSync` 状态——`FloatingBottomBar` 内部 `snapshotFlow` 驱动药丸动画与选中态；
   点击 tab 调 `setCurrentItem(index, true)`，失败回退原底栏 item `performClick()`。

## 安装使用

1. GitHub Actions 产物 `UniGlassBar-apk` 下载安装（debug 签名可直接安装）；
2. LSPosed 启用模块，作用域勾选 **引力域 (com.changan.uni)**；
3. 杀掉引力域进程重开。日志过滤 `UniGlassBar`。

## 内置文件日志 / 自愈开关

- 日志文件：`/data/data/com.changan.uni/files/uniglassbar/log.txt`
  （root 或"文件管理器 + Android/data 授权"均可查看；每一步注入过程、提取结果、原底栏视图树、崩溃堆栈都会写入）
- **自愈**：注入后连续 2 次崩溃会自动在 `uniglassbar/disable` 生成熔断开关，下次启动不再注入（App 恢复原生底栏）；
- **手动开关**：创建或删除 `uniglassbar/disable` 文件即可停用/重新启用模块注入，无需卸载。

## 已知限制

- 车控/爱车页若有地图（SurfaceView/TextureView），玻璃对应区域采样为空白（WeKit 同款限制）。
- API < 31 时 miuix 部分效果（如 `InteractiveHighlight`）自动降级。
- 图标两态若库内部不是 selector，选中态依赖首次切换后更新。

## 构建

工程未带 wrapper；CI 用 `gradle/actions/setup-gradle` 固定 Gradle 9.7.0 + JDK 21。
本地构建：`gradle :app:assembleRelease`。

## 工程结构

```
app/src/main/
   (legacy 入口已移除, 仅 libxposed 102)
├── resources/META-INF/xposed/            libxposed 102 注册 (java_init.list / module.prop / scope.list)
└── java/dev/uni/glassbar/
    ├── entry/LxpHookEntry.kt + LxpHookBridge.kt               libxposed 102 入口 + 原生 hook
    ├── BarInjector.kt                    探测底栏 → 提取 → 隐藏 → 注入 ComposeView overlay
    ├── bar/
    │   ├── TabExtractor.kt               运行时图标/标题提取 (多层回退)
    │   ├── PagerBarBridge.kt             宿主 ViewPager 反射桥接 (动态代理)
    │   └── GlassBarScreen.kt             Compose 底栏 (FloatingBottomBar + ViewBackdrop)
    └── ui/                               取自 WeKit 的 Compose 组件 (包名已改)
        ├── content/FloatingBottomBar.kt / ViewBackdrop.kt / ViewBackdropCaptureState.kt
        │                 / DragGestureInspector.kt
        ├── content/animation/            DampedDragAnimation / InteractiveHighlight
        ├── content/liquid/               CombinedBackdrop / InnerShadow / Lens / Vibrancy
        └── utils/                        XposedLifecycleOwner / LifecycleOwnerProvider / setLifecycleOwner
```
