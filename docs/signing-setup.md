# 签名配置：给 `app/build.gradle.kts` 打的补丁

当前 `app/build.gradle.kts` 里没有 `signingConfigs`，所以 `assembleRelease`
产出的是**未签名 APK**，装不上任何设备。这一步必须在首次发布前做完。

对一个密码管理器来说，签名密钥不只是「能装上」的问题：Android 用签名判断
「这是不是同一个 App 的升级」。**密钥泄露 = 任何人都能给你的用户推送一个
带后门的静默升级；密钥丢失 = 已安装用户永远收不到你的任何更新。**

---

## 第一步：生成密钥

```bash
keytool -genkeypair -v \
  -keystore localvault-release.jks \
  -alias localvault \
  -keyalg RSA -keysize 4096 \
  -validity 10950
```

`-validity 10950` 是 30 年。Google Play 要求密钥有效期至少到 2033 年，
留足即可，反正到期后没法换。

交互式提问里的 `CN`、`OU`、`O`、`L`、`ST`、`C` 会写进证书并**永久出现在
每一个 APK 里，任何人都能 dump 出来**。用笔名开源的话，这里不要填真名、
不要填公司、地区填个省级即可。

生成后立刻做两件事：

```bash
# 1. 记下 SHA-256 指纹，填进 README 的 <TODO: 填入你的签名指纹>
keytool -list -v -keystore localvault-release.jks -alias localvault | grep SHA256
# Windows: | findstr SHA256

# 2. 备份。至少三份，其中至少一份离线、一份异地。
#    密钥文件 + 两个口令要分开存（比如密钥放 U 盘，口令放纸上）。
```

`.gitignore` 已经覆盖 `*.jks`，但**不要把 keystore 放在工程目录里**——
放到工程外的独立目录，从根本上避免误提交。

---

## 第二步：`keystore.properties`

在工程根目录（与 `settings.gradle.kts` 同级）建 `keystore.properties`：

```properties
storeFile=D:/keys/localvault-release.jks
storePassword=你的库口令
keyAlias=localvault
keyPassword=你的密钥口令
```

这个文件已在 `.gitignore` 里。建好之后立刻 `git status` 确认它不在待提交列表中。

---

## 第三步：改 `app/build.gradle.kts`

### 3.1 在文件最顶部（`plugins { }` 之前）加

```kotlin
import java.util.Properties
import java.io.FileInputStream
```

### 3.2 在 `android { }` 块之前加

```kotlin
// 签名配置从工程外的 keystore.properties 读取，该文件不进版本控制。
// 文件不存在时（比如别人 clone 下来构建、或 CI 只跑测试）不报错，
// 只是 release 构建会退化成未签名——这是有意的：
// 别人应该能在没有你的密钥的情况下构建和审计这份源码。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        load(FileInputStream(keystorePropsFile))
    }
}
val hasSigningConfig = keystoreProps.getProperty("storeFile") != null
```

### 3.3 在 `android { }` 里，`buildTypes { }` **之前**加

```kotlin
    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")

                // v1 是 APK 签名方案 v1（jar 签名），已被弃用且有已知弱点。
                // minSdk 26 意味着所有目标设备都支持 v2/v3，直接关掉 v1。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }
```

### 3.4 修改现有的 `buildTypes { release { ... } }`

在原有内容里加一行：

```kotlin
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // 新增：
            signingConfig = if (hasSigningConfig) signingConfigs.getByName("release") else null
        }
```

---

## 第四步：验证

```bash
./gradlew assembleRelease

# Windows 下 build-tools 路径类似：
#   %LOCALAPPDATA%\Android\Sdk\build-tools\36.0.0\apksigner.bat
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

输出里的 `Signer #1 certificate SHA-256 digest` 应该和你第一步记下来的指纹一致。
把它填进 README 和 SECURITY.md 的对应位置。

顺手再核一遍权限：

```bash
aapt2 dump permissions app/build/outputs/apk/release/app-release.apk
```

应该只有 `USE_BIOMETRIC` 和 `POST_NOTIFICATIONS` 两条。CI 里也有一道同样的检查。

---

## 关于 Google Play 应用签名

如果将来上 Google Play，安装流程会推荐你启用「Play 应用签名」——
把签名密钥托管给 Google，你只保留一个上传密钥。

对普通 App 这是好事（密钥丢了能找回）。但对本项目它和 SECURITY.md 里
「核对签名指纹以确认你装的是这份源码」的自证逻辑直接冲突：**用户在
Play 上装到的 APK 是 Google 签的，不是你签的。**

务实的做法：GitHub Release 和 F-Droid 走你自己的密钥，如果确实要上 Play，
在 README 里明确写出两个渠道的指纹不同以及为什么。不要让用户自己去发现这件事。
