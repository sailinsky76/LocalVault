# 本地保险库 LocalVault

> **状态:仓库已公开,尚未发布正式版本。**
> 代码可编译、单元测试通过、已在真机测试。但在 v1.0.0 发布之前,
> 建议只用测试账号,不要用它存放你的真实密码。

**一个不联网的 Android 密码管理器,重点解决国内 App 的自动填充适配。**

[English below](#localvault-english)

---

## 为什么会有这个东西

市面上成熟的密码管理器不缺,但它们的自动填充在国内 App 上普遍不好用:
登录框不是标准控件、`autofillHints` 不填、账号框和手机号框混在一起、
输入框自己会把 `18623456789` 重排成 `186 2345 6789` 再交回来。
结果就是填不进去,或者填进去了保存不下来。

这个项目把大部分工夫花在了那件脏活上——字段角色识别、结构规则、
包名与域名的信任判断,以及保存链路上那些平台不会告诉你的坑。

第二件事是**不联网**。这不是一句承诺,是一个你可以自己核对的事实。

---

## 没有 `INTERNET` 权限

整个 App 只声明两条权限:

```
android.permission.USE_BIOMETRIC       生物识别快捷解锁
android.permission.POST_NOTIFICATIONS  自动填充保存后的确认通知
```

没有 `INTERNET`,进程就无法建立任何 socket——数据在技术层面不可能离开设备。
装好之后请自己去「系统设置 → 应用 → 权限」核对一遍,别信 README。

配套的几项:

- `android:allowBackup="false"` —— 保险库不会被 `adb backup` 或云备份带走
- `FLAG_SECURE` —— 挡住常规截屏与多数录屏
- 剪贴板默认 15 秒自动清除(可调 15 / 30 / 60 / 120 秒,或关闭)
- **永远不提供明文 CSV 导出**,换机走加密备份文件

---

## 加密设计

```
主密码 ──Argon2id──► KEK ──AES-256-GCM──► 库主密钥(32B) ──AES-256-GCM──► 整库密文
```

| 环节 | 选型 |
|---|---|
| KDF | Argon2id,默认 64 MiB / t=3 / p=1;低配设备 32 MiB / t=4 |
| KDF 兜底 | PBKDF2-HMAC-SHA512,600,000 轮(原生库加载失败时) |
| 加密 | AES-256-GCM,12 字节 nonce,16 字节标签 |
| 文件格式 | `.lvault` v1,魔数 `LVAULT`,**文件头整体作为 AAD** |

两层密钥不是为了好看:改主密码只需重新包一次 32 字节的主密钥,不必重新加密整个库;
生物解锁用 Keystore 硬件密钥把同一个库主密钥再包一份,两条路径共存,
而**主密码始终是唯一的真凭据**。

文件头进 AAD 意味着攻击者不能把 KDF 参数改弱之后让 App 用低成本参数去派生——
改一个字节,解密直接失败。

选 AES-GCM 而不是 XChaCha20-Poly1305 的理由写在 `core/crypto/Aead.kt` 里:
ARMv8 手机有 AES 硬件指令,而 XChaCha20 在 Android 上要额外背一个原生依赖,
为一个不会更安全的算法多背一个依赖不划算。算法编号写进了文件头,将来要换不会卡住老文件。

---

## 功能

- 主密码 + 快速解锁(PIN / 生物识别),带尝试次数限制
- 条目增删改查、搜索(**备注不进搜索索引**,那里是用户放密保答案和证件号的地方)
- 密码生成器
- 加密备份与恢复
- CSV 导入:Bitwarden / Chrome / Firefox / LastPass / 1Password
- 自动填充四条路:填充、保存、开关与交代、输入法内联建议

**刻意不做的:** kdbx 导入、CXF 格式对接、明文 CSV 导出(最后这条永远不做)。

---

## 已知的不稳与边界

写在这里而不是等着被提 issue:

- **自动填充的保存链路在部分 App 上仍不稳定。** 不同 App 的登录页结构差异很大,
  系统保存框弹不弹、密码字段回传是否为空,都不完全由本 App 决定。遇到没保存上的,
  开 Issue 时请附上 App 名称和 Android 版本。
- **忘记主密码 = 数据永久丢失。** 没有后门,没有找回。请自己保管好加密备份文件。
- **防不住的东西**(root 设备、恶意输入法、无障碍权限滥用、物理胁迫)
  完整列在 [SECURITY.md](SECURITY.md) 的「范围外」一节。请读一遍再决定要不要用它装真密码。

---

## 构建

```bash
git clone git@github.com:sailinsky76/LocalVault.git
cd vault
./gradlew assembleRelease
```

要求:JDK 17,Android SDK(compileSdk 36),最低支持 Android 8.0(API 26)。
`minSdk 26` 是 Autofill Framework 和 Keystore 用户认证绑定的下限,不是随便定的。

跑测试:

```bash
./gradlew test
```

约 5.7 万行 Kotlin,其中 1.7 万行是单元测试(57 个测试文件)。
存储层与仓库层刻意做成能在纯 JVM 上跑,不必上真机。

---

## 发布与校验

- 每个 Release 附 APK 与 `SHA256SUMS`
- 官方签名证书 SHA-256 指纹:`E2:E6:D8:77:47:EE:4E:71:90:3D:34:D2:C7:7B:08:D3:FC:18:47:3A:E6:47:E8:5D:E4:31:1F:46:F5:A9:2D:C7`
- 校验方式:`apksigner verify --print-certs LocalVault-x.y.z.apk`

**签名指纹变更一定会在 Release notes 里显著说明。没有说明的变更请当作可疑。**

---

## 参与

Issue 和 PR 都欢迎,尤其是自动填充适配——如果某个 App 填不进去或存不上,
带上 App 名称、Android 版本和现象描述开个 Issue,这类反馈最有用。

安全问题**不要**开公开 Issue,走 [SECURITY.md](SECURITY.md) 里的私密渠道。

设计决策与踩坑记录都在 [PROGRESS.md](PROGRESS.md),想改动核心逻辑之前建议先翻一下,
里面有不少「为什么撤掉了上一版」的记录。

---

## 许可

GPL-3.0。选它而不是 MIT/Apache 的原因很直接:密码管理器最怕的是有人拿去
套壳、加后门、改个名字重新上架。GPL 要求衍生版本同样开源,不能完全阻止这件事,
但至少让它做不到"闭源地"做。

---
---

<a name="localvault-english"></a>

# LocalVault (English)

**An offline Android password manager, focused on autofill compatibility with
Chinese apps.**

## Why this exists

There is no shortage of mature password managers, but their autofill tends to
fall apart on Chinese apps: login fields are non-standard widgets, `autofillHints`
are absent, username and phone-number fields are conflated, and input boxes
reformat `18623456789` into `186 2345 6789` before handing it back.

Most of the work in this project went into that unglamorous layer — field role
inference, structure rules, package and domain trust decisions, and the parts of
the save chain the platform documents poorly.

The second thing is that it **does not go online** — a claim you can verify yourself.

## No `INTERNET` permission

The app declares exactly two permissions:

```
android.permission.USE_BIOMETRIC       biometric quick unlock
android.permission.POST_NOTIFICATIONS  confirmation after an autofill save
```

Without `INTERNET`, the process cannot open a socket — the data physically
cannot leave the device. Check it yourself under Settings → Apps → Permissions
rather than taking this README's word for it.

Alongside that: `allowBackup="false"` (no `adb backup`, no cloud backup),
`FLAG_SECURE`, clipboard auto-cleared after 15s by default (15/30/60/120s or off),
and **no plaintext CSV export, ever** — device migration goes through an
encrypted backup file.

## Crypto design

```
master password ──Argon2id──► KEK ──AES-256-GCM──► vault key (32B) ──AES-256-GCM──► vault ciphertext
```

| Layer | Choice |
|---|---|
| KDF | Argon2id, 64 MiB / t=3 / p=1 default; 32 MiB / t=4 on low-end devices |
| KDF fallback | PBKDF2-HMAC-SHA512, 600,000 iterations (if the native lib fails to load) |
| Cipher | AES-256-GCM, 12-byte nonce, 16-byte tag |
| Format | `.lvault` v1, magic `LVAULT`, **the entire header is used as AAD** |

Two key layers are not decoration. Changing the master password only rewraps a
32-byte key instead of re-encrypting the whole vault; biometric unlock wraps the
same vault key a second time with a hardware-backed Keystore key, so both paths
coexist while **the master password remains the only real credential**.

Putting the header in the AAD means an attacker cannot downgrade the KDF
parameters and have the app derive with cheap settings — flip one byte and
decryption fails outright.

AES-GCM over XChaCha20-Poly1305: ARMv8 devices have AES hardware instructions,
while XChaCha20 on Android needs an extra native dependency for no security gain.
The cipher ID is stored in the header, so switching later will not orphan old files.

## Features

Master password plus quick unlock (PIN / biometric) with attempt limiting;
entry CRUD and search (**notes are deliberately excluded from the search index**
— that is where people put security answers and ID numbers); password generator;
encrypted backup and restore; CSV import from Bitwarden, Chrome, Firefox,
LastPass and 1Password; and four autofill paths (fill, save, toggle with
explanation, and inline IME suggestions).

Deliberately absent: kdbx import, CXF interop, plaintext CSV export (that last
one is permanent).

## Known limitations

- **The autofill save chain is still unstable on some apps.** Login page
  structure varies widely; whether the system save dialog appears and whether the
  password field comes back populated is not entirely up to this app. If a save
  fails, please open an issue with the app name and Android version.
- **A forgotten master password means permanent data loss.** No backdoor, no recovery.
- **What it cannot defend against** (rooted devices, malicious IMEs, accessibility
  abuse, physical coercion) is listed in full under "Out of scope" in
  [SECURITY.md](SECURITY.md). Please read it before trusting this with real credentials.

## Building

```bash
git clone git@github.com:sailinsky76/LocalVault.git
cd vault
./gradlew assembleRelease
./gradlew test
```

Requires JDK 17 and the Android SDK (compileSdk 36). Minimum supported version is
Android 8.0 (API 26) — that is the floor for the Autofill Framework and for
Keystore user-authentication binding.

Roughly 57k lines of Kotlin, 17k of which are unit tests across 57 test files.
The storage and repository layers run on plain JVM, no device required.

## Releases and verification

Each release ships an APK plus `SHA256SUMS`. Official signing certificate
SHA-256 fingerprint: `E2:E6:D8:77:47:EE:4E:71:90:3D:34:D2:C7:7B:08:D3:FC:18:47:3A:E6:47:E8:5D:E4:31:1F:46:F5:A9:2D:C7`. Verify with
`apksigner verify --print-certs`.

**Any fingerprint change will be announced prominently in the release notes.
Treat an unannounced change as hostile.**

## Contributing

Issues and PRs welcome, autofill compatibility reports most of all — app name,
Android version, and what you observed is the single most useful kind of report.

Security issues go through the private channels in [SECURITY.md](SECURITY.md),
**not** public issues.

Design decisions and the record of what was tried and reverted live in
[PROGRESS.md](PROGRESS.md); worth reading before changing core logic.

## License

GPL-3.0. The reasoning is direct: the worst outcome for a password manager is
someone reskinning it with a backdoor and republishing. Copyleft cannot fully
prevent that, but it does prevent it being done in closed source.
