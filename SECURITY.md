# 安全策略 / Security Policy

> 中文在前，English below.

---

## 中文

### 报告漏洞

**不要用公开 Issue 报告安全问题。** 公开 issue 在修复发布之前就把利用方式
告诉了所有人,而这个 App 里装的是用户的全部账号。

两种私密渠道:

1. **GitHub 私密报告**(推荐):本仓库 → Security → Report a vulnerability
2. **邮件**:`shiqi_zheng@126.com`

报告里请尽量包含:受影响的版本 / commit、复现步骤、设备与 Android 版本、
以及你判断的影响范围。有 PoC 更好,但不是必需——描述清楚也一样有价值。

### 响应承诺

这是一个个人维护的项目,不是有值班表的公司产品。承诺按实际能做到的写:

| 阶段 | 时限 |
|---|---|
| 确认收到 | 5 个自然日内 |
| 初步评估(是否成立、严重程度) | 14 个自然日内 |
| 修复并发版 | 高危 30 天内,其余视情况 |

如果超时未回复,可以在 Issue 里发一条不含技术细节的催促(比如
「我在 X 月 X 日发过一封安全邮件」),这不会泄露任何东西。

### 披露方式

默认走协调披露:修复发版后,在 Release notes 和本文件的致谢区公开问题,
并署上报告者(除非你要求匿名)。如果你有自己的披露时间表,在报告里说明,
我们尽量对齐。

### 范围内

- 加密与密钥管理:KDF 参数、AEAD 使用、nonce 复用、密钥在内存中的留存
- 保险库文件格式:能绕过认证、篡改 KDF 参数、或造成解密后数据被静默改写的问题
- 解锁链路:快速解锁 / 生物识别绕过、尝试次数限制绕过、锁定状态被越过
- 自动填充:把凭据填给了不该收到的 App 或域名(包名/域名匹配、子域与公共后缀判断)
- 意外的明文外泄:日志、剪贴板、截屏、系统备份、`/data` 下的残留文件
- 构建与发布:依赖投毒、构建脚本问题、发布产物与源码不一致

### 范围外

这些不是"我们不在乎",而是**这个 App 在设计上就防不住**,写清楚是为了不让
用户产生错误的安全感:

- **已被 root 或已被植入恶意软件的设备。** 具备 root 的进程能读到解锁后
  内存里的明文,任何本地密码管理器都是如此。
- **恶意输入法(IME)。** 你选定的输入法能看到你敲进去的一切,包括主密码。
- **物理胁迫。** 本 App 没有胁迫密码 / 假库功能,1.0.0 也不打算做——
  半吊子的假库比没有更危险。
- **忘记主密码。** 没有后门、没有找回、没有"客服重置"。这是设计目标,不是缺陷。
- **屏幕录制与无障碍服务滥用。** `FLAG_SECURE` 能挡住常规截屏和多数录屏,
  但拿到无障碍权限的恶意 App 依然能读取屏幕内容。
- **供应链下游。** 从非官方渠道拿到的 APK。请只从本仓库 Release 或
  README 中列出的渠道安装,并核对签名指纹。

### 验证你装的是不是这份源码

「没有 `INTERNET` 权限」这句话,只有在你手上的 APK 和这份源码对得上时才成立。

- 每个 Release 附带 `SHA256SUMS`
- 官方签名证书指纹见 README 的「发布与校验」一节
- 安装后可在系统设置 → 应用 → 权限里自行核对权限清单

签名指纹一旦变更,一定会在 Release notes 里显著说明。**没有说明的指纹变更,
请当作可疑,不要安装。**

---

## English

### Reporting a vulnerability

**Please do not open a public issue for security problems.** A public issue
hands the exploit to everyone before a fix ships, and this app holds users'
entire credential sets.

Two private channels:

1. **GitHub private reporting** (preferred): this repo → Security → Report a vulnerability
2. **Email**: `shiqi_zheng@126.com`

Please include where you can: affected version or commit, reproduction steps,
device and Android version, and your read on the impact. A PoC helps but is
not required.

### Response commitment

This is a personally maintained project, not a staffed product. The timelines
below are what can realistically be met:

| Stage | Target |
|---|---|
| Acknowledge receipt | within 5 days |
| Initial assessment | within 14 days |
| Fix released | 30 days for high severity; otherwise case by case |

If you get no reply past these windows, feel free to post a detail-free nudge
in an issue (e.g. "I sent a security email on <date>") — that leaks nothing.

### Disclosure

Coordinated disclosure by default: once a fix ships, the issue is described in
the release notes and credited here, unless you ask to stay anonymous. If you
have your own disclosure timeline, say so in the report and we will try to align.

### In scope

- Cryptography and key management: KDF parameters, AEAD usage, nonce reuse, key material lingering in memory
- Vault file format: anything that bypasses authentication, downgrades KDF parameters, or silently corrupts decrypted data
- Unlock paths: quick-unlock or biometric bypass, attempt-limiter bypass, lock-state bypass
- Autofill: credentials delivered to the wrong app or domain (package/domain matching, subdomain and public-suffix handling)
- Unintended plaintext exposure: logs, clipboard, screenshots, system backup, leftover files under `/data`
- Build and release: dependency poisoning, build-script issues, release artifacts not matching source

### Out of scope

Not "we don't care" — these are threats this app **cannot** defend against by
design. They are listed so no one derives false confidence:

- **Rooted or already-compromised devices.** A root-capable process can read
  plaintext from memory after unlock. True of every local password manager.
- **Malicious input methods (IMEs).** Your chosen keyboard sees everything you
  type, master password included.
- **Physical coercion.** There is no duress password or decoy vault, and none
  is planned for 1.0.0 — a half-built decoy is worse than none.
- **A forgotten master password.** No backdoor, no recovery, no support reset.
  That is the design goal, not a bug.
- **Screen recording and accessibility-service abuse.** `FLAG_SECURE` blocks
  ordinary screenshots and most recorders, but an app granted accessibility
  permission can still read the screen.
- **Downstream supply chain.** APKs obtained from unofficial mirrors. Install
  only from this repo's Releases or the channels listed in the README, and
  verify the signing fingerprint.

### Verifying what you installed

The "no `INTERNET` permission" claim only holds if the APK on your device
matches this source.

- Every release ships `SHA256SUMS`
- The official signing certificate fingerprint is in the README, under "Releases and verification"
- After install, check the permission list yourself in Settings → Apps → Permissions

Any change to the signing fingerprint will be called out prominently in the
release notes. **Treat an unannounced fingerprint change as hostile and do not
install it.**
