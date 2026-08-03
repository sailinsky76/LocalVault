# 第三方组件与许可 / Third-party notices

本项目以 GPL-3.0 发布，使用了下列第三方组件。Apache-2.0 与 GPL-3.0 单向兼容
（Apache-2.0 的代码可以并入 GPL-3.0 的作品），因此下表中的组件均可合法使用。

> ⚠️ 首次发布前请逐条核对实际版本的许可证。下表按依赖发布时的常见情况填写，
> 不能替代你自己去看一遍各项目的 LICENSE 文件。核对完删掉这行。

| 组件 | 用途 | 许可证 |
|---|---|---|
| AndroidX Core / Lifecycle / Activity / Fragment | 基础组件 | Apache-2.0 |
| Jetpack Compose（BOM 及 ui / material3 / navigation） | 界面 | Apache-2.0 |
| AndroidX Biometric | 生物识别解锁 | Apache-2.0 |
| AndroidX Autofill (`androidx.autofill:autofill`) | 输入法内联建议的 Slice 构造 | Apache-2.0 |
| AndroidX DataStore | 设置项持久化 | Apache-2.0 |
| kotlinx.serialization | 保险库数据序列化 | Apache-2.0 |
| argon2kt (`com.lambdapioneer.argon2kt`) | Argon2id 的 JNI 实现 | Apache-2.0（含上游 Argon2 参考实现，CC0 / Apache-2.0 双许可） |
| Kotlin 标准库 | — | Apache-2.0 |
| JUnit 4 | 单元测试（不进 APK） | EPL-1.0 |

## Apache License 2.0 要求的声明

上述 Apache-2.0 组件的完整许可证文本见
<https://www.apache.org/licenses/LICENSE-2.0>。
各组件的版权归其各自作者所有。

## App 内的开源许可页面

Google Play 与多数应用商店要求在应用内可查看开源许可。
建议的做法是把这份文件的内容打包进 `assets/`，在「设置 → 关于 → 开源许可」里展示。
