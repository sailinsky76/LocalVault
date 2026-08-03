plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cn.localvault.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.localvault.app"
        minSdk = 26          // Autofill Framework 与 Keystore 用户认证绑定的最低要求
        targetSdk = 36
        // 1.0.0 定版：versionCode 从 1 起，之后每次发布只加不减
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 明确禁掉备份：保险库文件绝不允许被 adb backup / 云备份带走
        // （见 AndroidManifest 的 allowBackup=false）
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    testOptions {
        // 让 android.util.Log 之类的桩方法返回默认值而不是抛异常，
        // 这样存储层和仓库层的逻辑可以在纯 JVM 上单测，不必上设备。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)

    // M4-4b 内联建议。**这是 M4 里唯一新增的依赖**，而且只用到一个类：
    // `InlineSuggestionUi`——它负责把两行字装成一个 `Slice`，也就是
    // 输入法建议条上那一格的内容。那个 Slice 的格式是平台和输入法之间的
    // 私下约定（androidx.autofill.inline.v1），手写等于把一份没有文档的
    // 二进制布局抄进工程里，抄错了不报错、只是那一格画不出来。
    // 它**不带任何权限、不联网、不带资源**，见 ui/autofill/InlineViews.kt。
    implementation(libs.androidx.autofill)

    // BiometricPrompt 的公开签名要的是 FragmentActivity，所以 MainActivity 从
    // ComponentActivity 换成了 FragmentActivity。biometric 本来就把 fragment
    // 传递带进来了，这一行不会让 APK 变大一个字节，只是把「我们确实用到了它」
    // 写在明面上——免得将来有人换掉 biometric 时莫名其妙编译不过。
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.serialization.json)

    // Argon2id 的 JNI 实现。若首次构建拉不到，可临时把 CryptoProvider 切到
    // Pbkdf2Kdf（纯 JCE，无依赖），见 core/crypto/Kdf.kt 的说明。
    implementation(libs.argon2kt)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
