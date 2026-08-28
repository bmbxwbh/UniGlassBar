import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application") // AGP 9 内置 Kotlin
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.uni.glassbar"
    // miuix-blur 0.9.4-rc01 的 AAR 元数据要求 compileSdk >= 37 (targetSdk 不受影响)
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.uni.glassbar"
        minSdk = 33 // miuix-blur 要求 33 (RuntimeShader 玻璃效果本就需要 Android 13+); 不影响被 hook 的宿主
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
    }

    signingConfigs {
        // release 直接用 debug 签名, 免配置 keystore, Actions 产物即可安装
        getByName("debug") {
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    // 注入模块是运行时行为, 放开 release 构建的 lintVital 卡点
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // 注意: 不要排除 META-INF/** —— META-INF/xposed/* 是 libxposed 102 入口的注册文件
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // 仅 libxposed 102 (新 API); 不引用 de.robv legacy API
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("org.jetbrains:annotations:24.1.0") // InteractiveHighlight 的 @Language

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3:1.5.0-alpha26")
    implementation("androidx.lifecycle:lifecycle-runtime:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.4") // ViewModelStore(Owner) / setViewTreeViewModelStoreOwner
    implementation("androidx.savedstate:savedstate:1.5.0")
    implementation("androidx.core:core-ktx:1.17.0") // ViewBackdrop 的 withTranslation

    // miuix 系 (WeKit 同源): drawBackdrop/vibrancy/blur/lens 玻璃效果 + LayerBackdrop
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-shader-android:0.9.4-rc01")
}
