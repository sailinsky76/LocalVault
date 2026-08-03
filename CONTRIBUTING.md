# 参与贡献 / Contributing

> 中文在前，[English below](#contributing-english)。

## 先说三件事

**1. 安全问题不要开公开 Issue。** 走 [SECURITY.md](SECURITY.md) 里的私密渠道。

**2. 最有价值的贡献是自动填充兼容性报告。** 不需要会写代码。某个 App
填不进去、存不上、不弹窗——带上 App 名称、包名、Android 版本和现象开个
Issue，用「🧩 自动填充兼容性」模板。这类信息作者手上永远不够。

**3. 这个项目对 PR 的审查会比一般项目慢，尤其是核心逻辑。**
不是不欢迎，是因为它装的是用户的全部账号。请不要因为等得久而觉得被冷落。

## 关于 AI 辅助编码

本项目的代码大量在 AI 辅助下编写，设计决策、审阅与测试验证由作者负责，
过程记录在 [PROGRESS.md](PROGRESS.md)。

你用 AI 辅助写 PR 也可以，但有一条硬要求：**你必须能解释你提交的每一行在做什么，
以及为什么这样做。** 审查时会问。看不懂自己的 PR，就不要提。

另外请自己确认提交的代码不是从某个有版权的项目里整段复述出来的——
一旦混进 GPL 不兼容的代码，清理起来非常麻烦。

## 开发环境

- JDK 17
- Android SDK，compileSdk 36
- 最低支持 Android 8.0（API 26）

```bash
git clone git@github.com:sailinsky76/LocalVault.git
cd vault
./gradlew test            # 存储层与仓库层在纯 JVM 上跑，不必上真机
./gradlew assembleDebug
```

`local.properties` 由 Android Studio 自动生成，**不要提交**。

## 流程

1. Fork，从 `main` 切分支：`fix/autofill-taobao-save`、`feat/xxx`、`docs/xxx`
2. 小步提交，一个 PR 只做一件事
3. 提交前跑 `./gradlew test`
4. 开 PR，填完模板里的自检清单

Commit message 用祈使句，中英文皆可：

```
autofill: 放开对 importantForAutofill=no 的排除

淘宝和有知有行实测证明 ifa=NO 的 View 在写入侧不被系统拦，
之前那道排除让这两个 App 完全填不进去。

Fixes #12
```

## 代码约定

- Kotlin official code style（`kotlin.code.style=official`，Android Studio 默认）
- 新增源文件必须带 GPL-3.0 文件头，见 `scripts/add_license_headers.py`
- **注释写「为什么」，不写「是什么」。** 这个仓库里已有的注释大多在解释某个
  决定背后的取舍和踩过的坑，请保持这个风格
- 撤掉一个之前的做法时，在 PROGRESS.md 里留一条记录，说明为什么撤

## 需要额外说明的改动

下列区域的 PR 请在描述里写清设计理由，并预期多轮讨论：

| 区域 | 为什么谨慎 |
|---|---|
| `core/crypto/` | 加密实现的 bug 通常静默失败，测试很难覆盖 |
| `.lvault` 文件格式 | 改动会影响存量用户的数据，必须保证向后兼容 |
| 解锁链路 / 尝试次数限制 | 绕过即等于完全失守 |
| 自动填充的包名 / 域名匹配 | 匹配错了就是把凭据交给了错误的 App |
| 新增第三方依赖 | 每一个依赖都是一个供应链入口，见下 |

## 不会被接受的改动

- **任何引入 `INTERNET` 权限或联网能力的东西。** CI 里有一道检查会直接卡掉。
- **明文 CSV 导出。** 永久不做。
- 云同步、账号体系、遥测、崩溃上报（哪怕是「匿名的」）。
- 引入一个只为了省几行代码的重量级依赖。这个 App 的依赖清单会被人一条条读，
  越短越好。

## 关于依赖

新增依赖的 PR 请回答：它解决了什么手写解决不了的问题？维护状态如何？
许可证是什么（必须与 GPL-3.0 兼容）？它会不会拉进传递依赖？

现有依赖只有一个非 AndroidX 的第三方库（argon2kt），这是刻意的。

## 许可

提交 PR 即表示你同意你的贡献以 GPL-3.0 发布，且你有权这样做
（比如它不是你雇主拥有的代码）。本项目不使用 CLA。

---

<a name="contributing-english"></a>

# Contributing (English)

**Security issues do not go in public issues** — use the private channels in
[SECURITY.md](SECURITY.md).

**The most valuable contribution is an autofill compatibility report**, and it
needs no code. If an app won't fill or won't save, open an issue with the app
name, package name, Android version and what you observed, using the
"🧩 Autofill compatibility" template.

**Review of core logic will be slow.** This app holds users' entire credential
sets; that is the reason, not a lack of interest.

### AI-assisted code

Much of this project was written with AI assistance; design decisions, review
and test verification are the author's, and the process is recorded in
[PROGRESS.md](PROGRESS.md). You may use AI assistance in your PRs too, on one
condition: **you must be able to explain every line you submit and why.** You
will be asked. Also make sure your submission is not a verbatim reproduction of
some copyrighted project — GPL-incompatible code is painful to unwind later.

### Setup

JDK 17, Android SDK (compileSdk 36), minSdk 26.

```bash
./gradlew test           # storage and repository layers run on plain JVM
./gradlew assembleDebug
```

Never commit `local.properties`.

### Process

Fork, branch off `main`, one concern per PR, run `./gradlew test` before
opening, fill in the PR template checklist. Commit messages in the imperative,
English or Chinese.

### Conventions

Kotlin official code style. Every new source file carries the GPL-3.0 header
(`scripts/add_license_headers.py`). **Comments explain *why*, not *what*** —
match the existing style, which mostly documents trade-offs and dead ends. When
reverting an earlier approach, record why in PROGRESS.md.

### Changes needing extra justification

`core/crypto/`, the `.lvault` format, the unlock path and attempt limiter, the
autofill package/domain matching, and any new third-party dependency.

### Changes that will not be accepted

Anything introducing `INTERNET` or network capability (CI blocks it), plaintext
CSV export (permanent), cloud sync, accounts, telemetry or crash reporting
(even anonymous), and heavyweight dependencies added for convenience. The
dependency list is meant to be read line by line — keep it short.

### Licensing

Submitting a PR means you agree your contribution is released under GPL-3.0 and
that you have the right to do so. There is no CLA.
