# 更新日志 / Changelog

本文件格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

对密码管理器而言，这份文件有一个额外用途：**每个版本改了什么，用户需要能核对**。
所以变更条目尽量写具体，不要写「优化了若干细节」。

涉及 `.lvault` 文件格式的改动一律单列一节并说明兼容性。

## [未发布]

### 新增
### 变更
### 修复
### 安全

---

## [1.0.0] - <发布日期>

首个公开版本。

### 新增

- 主密码解锁（Argon2id，默认 64 MiB / t=3 / p=1；低配设备 32 MiB / t=4）
- PBKDF2-HMAC-SHA512 兜底 KDF（600,000 轮），用于原生库加载失败的情况
- 快速解锁：PIN 与生物识别，带尝试次数限制
- 条目增删改查与搜索（备注字段不进搜索索引）
- 密码生成器
- 加密备份与恢复（`.lvault` v1）
- CSV 导入：Bitwarden / Chrome / Firefox / LastPass / 1Password
- 自动填充：填充、保存、开关与交代、输入法内联建议
- 设置项「尊重应用的『请勿填充』声明」（默认关闭）

### 安全

- 不声明 `INTERNET` 权限
- `android:allowBackup="false"`
- `FLAG_SECURE`
- 剪贴板默认 15 秒自动清除（可调 15 / 30 / 60 / 120 秒或关闭）
- 不提供明文 CSV 导出

### 文件格式

- `.lvault` v1，魔数 `LVAULT`，AES-256-GCM，文件头整体作为 AAD

### 已知问题

- 自动填充的保存链路在部分 App 上不稳定，见 README「已知的不稳与边界」

[未发布]: https://github.com/sailinsky76/REPO/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/sailinsky76/REPO/releases/tag/v1.0.0
