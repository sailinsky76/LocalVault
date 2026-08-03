# 本地保险库 · 开发进度


> **本轮（M4-4b）**：就地追加。**以后也只留这一份。**

工程：`LocalVault` ／ 包名 `cn.localvault.app` ／ Kotlin + Jetpack Compose
minSdk 26 · targetSdk 36 · 单模块 `:app`

---

## 界面调整

### UI-1 列表页多选：撤掉顶栏那个对勾按钮 ✅

**改动**
| 文件 | 改了什么 |
|---|---|
| `ui/list/VaultListScreen.kt` | 顶栏 `actions` 里那个 `IconSlot(Glyph.Check, "选择条目")` 删掉；新增私有 `SelectHint()`，作为清单的最后一个 item；页头 KDoc 与顶栏那段注释改写成新的取舍 |
| `ui/list/ListSelection.kt` | 纯加法：`LONG_PRESS_HINT` / `HINT_MIN_ENTRIES` / `showHint(entryCount, selecting)` |
| `src/test/.../ListSelectionTest.kt` | +4 个用例（提示说清怎么进和干什么、不提已撤掉的按钮、一条时不摆、选择模式下不摆）|

**没有动的**：`EntryRow` 的 `onLongClick`、`VaultSession.deleteEntries`、
删除确认框、分组全选、`Glyphs.kt`（`Glyph.Check` 还有五处在用）、
依赖、`AndroidManifest.xml`、任何一个别的页面。**导航一行没动。**

**为什么撤掉它是对的，见决策(220)**；这里只记一条实现上的提醒：
撤掉之后**长按是唯一入口**，所以那行小字不是装饰，是这个功能的全部可发现性。
将来若有人觉得列表末尾那行字碍眼想删掉，要先回答一个问题——
删掉之后，一个从没长按过列表的用户，从哪儿知道能多选？

**上机验证**：① 列表顶栏现在只有「N 条 · 放大镜 · 齿轮」三样，没有对勾；
② 滚到清单最底下能看到那行灰字，它在加号按钮上方、不被压住；
③ 长按任意一条 → 进选择模式，且那一条已经被选上；进去之后那行字消失；
④ 库里只有一条时那行字不出现；⑤ 选择模式下顶栏右边仍是「全选 / 取消全选」，
左上角是叉，按系统返回键退出选择而不是退出应用。

---

### UI-2 解释性文字与操作分层：长文另开一层 ✅

**病症**：这个工程的文案量大，而且大得有理由（M4 里那些「刻意不填」的决定，
在屏幕上全都长成同一个样子——什么都没弹出来，不解释等于坏了）。
但每一段解释都和按钮、输入框**平铺在同一列**，于是「解释」和「操作」抢同一份垂直空间：
自动填充设置页一个开关配着大半屏说明；从备份恢复页密码框和「恢复到这台设备」
被四段说明顶到了第一屏之外——一个刚换机、手上这份文件是最后一根绳子的人，
进来第一眼看到的是一屏要读的字。

**处方**（`ui/components/Explain.kt` 文件头是权威版本，这里只记要点）：

1. 和控件平铺的文字**最多两三行**，只说用户此刻要做的判断；
2. 完整那几段**一个字不删**，收进弹窗，用「详细说明 ›」挂上去；
3. 整块的长解释（症状清单、边界条件）连一句都不平铺，直接做成一行可点的卡片。

**没用折叠展开**：展开后仍长在这一列，会在用户刚点了一下、注意力正在别处的
那一刻把按钮*再*推走一次。弹窗是独立一层，读完关掉，底下那一屏原封不动。
弹窗显式写死 `SecureFlagPolicy.SecureOn`——Compose 的 `Dialog` 是独立 Window，
`FLAG_SECURE` 不继承（沿用 `VaultDialog` 里那条既有判断）。

#### 组件（`ui/components/Explain.kt`，新文件）

| 名字 | 用途 |
|---|---|
| `ExplainBlock` | 内容模型：`Para` / `Bullets` / `Section`。**纯 Kotlin 不带 Compose**，好让文案继续住在各页的 Model 层 |
| `explain(vararg)` | `List<Para>` 的糖 |
| `ExplainLink` | 黄铜色「详细说明 ›」，40dp 热区，和上方正文左对齐 |
| `ExplainDialog` | 内容区自己滚，只有一个「知道了」 |
| `ExplainNote` | 用法一：一句短说明 + 链接。`detail` 为空时退化成普通说明文字 |
| `ExplainRow` | 用法二：整块解释收成一行可点的卡片。副标题写**里面有什么**，不写「点击查看详情」 |
| `ExplainBanner` | 用法三：横幅照留、只收短，完整版挂在 `Banner` 现成的 `actionText` 槽上（UI-3 加） |

顺带给 `Glyphs.kt` 加了第 26 个手绘图标 `Glyph.Info`（圆圈 i）——枚举里数一遍是 26 个，`Glyphs.kt` 早先注释里那个「24」是 `Globe` 和 `Info` 之前的数。
**没复用 `Shield`**：盾牌在 `Banner` 里已经是「这事关乎安全」的意思，
共用会让每段说明都读起来像警告。

#### 改过的页面

| 轮次 | 页面 | 做了什么 |
|---|---|---|
| v3 | 自动填充设置 | 页顶段 + 开关下四五段灰字 → 两个开关 + 两行入口，一屏装得下 |
| v3 | 从备份恢复 | 重排为「一句话 → 选文件 → 事实卡 → 密码框 → 恢复 →（按钮下）之后会怎样」 |
| v3 | 导出备份 / CSV 导入 | 同类处理；网盘警告条和 `PLAINTEXT_NOTE` **留在外面**只收短 |
| v4 | 快捷解锁 | 页顶两整段 → 一句（37 字）；「绑定已失效」那条 73 → 31 字 |
| v4 | 删除保险库 | 导出备份 / 覆写擦除 / 指纹为什么不算数，三段各收成一句 |
| v4 | 改主密码 | `BEFORE_WARNING` 横幅 55 → 32 字，走 `ExplainBanner` |
| v4 | 清空重来 | 六段全收，最长的 109 → 22 字 |

#### 四条不要随手改的

1. **文案一律加字段，不改字段。** `Row.noteShort` / `OptOutRow.noteShort` /
   `BiometricRow.noteShort` 都带默认值 `= note`，长版原样保留——
   `AutofillSettingsModelTest`、`RestoreModelTest` 那批用例把原文案钉得很死
   （`note.contains("只认一个")` 这类），改字段会当场全红。
2. **有三处解释刻意留在页面上，没进弹窗**：导出页那条网盘警告、
   删除页「不做多次覆写」那一句（决策⑧）、改主密码页那条横幅。
   共同点是它们都是「这么做有代价」，而最该看见的恰恰是不会去点链接的那批人——
   收进弹窗等于把它藏了，和不说没区别。
3. **删除页和清空重来页的排版顺序一格没动**，v4 收的只有长度。
   别的页面收短是为了让主按钮浮上第一屏，这两页正相反：主按钮本来就该
   在读完之后才够得着（决策(126)(127)(128)）。一个能一眼看见并按下去的
   清空按钮，是这一页最不该有的东西。
4. **`ERASURE_NOTE` 长短两版都由 `ResetVaultModel` 引用 `DeleteVaultModel`**
   （决策(131)：同一件事不许有两份字）。v4 之后要守的是两份，不是一份。

#### 测试：`src/test/.../ShortTextTest.kt`（新文件，共 27 条）

守的是这套改动**最安静的失败方式**——拆的时候两份都对，
然后某次改文案只改了其中一份。屏幕上看不出任何异常：短句还在，弹窗还能开，
只是两处说的不是同一件事了。盯三样：

- **短的确实短**：`INLINE_MAX = 45` 字（14–15sp 下约两行多一点），
  `SUBTITLE_MAX = 30`，且强制 `短版.length < 长版.length`；
- **短的没把要紧的意思弄丢**：每条断言对应一个「这个词删掉用户就会做错事」的关键词；
- **收起来不等于删掉**：钉住 `LIMITS.size >= 3`、`WHY_NOT_SHOWING.size >= 7`、
  `WHAT_HAPPENS.size == 4`——排版改了，信息量不许变。

**v4 放宽了一条规则，只此一条**：原来禁止所有短文案出现「找回」二字，
收紧成了具体说法（`找回主密码` / `可以找回` / `帮你找回` / `破解` / `客服` /
`军工级` / `绝对安全`）。理由：清空重来页最要紧的一句就是
「主密码**没有找回通道**」，一条禁掉这两个字的规矩会正好禁掉这个产品
最该说出口的实话。和 `ResetVaultModelTest` 里那条（禁的是「找回主密码」）对齐。

#### 没有动的

依赖表、`AndroidManifest.xml`、导航、任何控制器、任何加密/存储路径、
任何一句**长版**文案。`Glyphs.kt` 只加不减。

#### 上机验证（这一节还没做，下次开工先跑）

1. `ExplainDialog` 的高度：`Modifier.weight(1f, fill = false)` 应当让弹窗
   在内容短时收窄、长时才顶到上限（`padding(vertical = 44.dp)` 就是那个上限）。
   开一次「它为什么有时候不出现」（7 条，最长）和一次「关于这个开关」（1 段，最短）对比看；
2. 从备份恢复页：选中一份能认的备份之后，**「换一个文件」按钮应当消失**，
   且「恢复到这台设备」不用滚动就看得见；选中一份认不出的（随便挑张图片），
   那个按钮**必须还在**；
3. 清空重来页：「先说清楚」那两句、两个问句、「清空之后」那张清单
   应当落在前两屏内，而抄写框和按住三秒仍在下面；
4. 六页每一处「详细说明 ›」都点一遍，确认弹窗里的正文和改之前逐字相同；
5. 弹窗打开时截屏，确认是黑的（`SecureFlagPolicy.SecureOn` 生效）。

---

## 修复记录

### FIX-4 首次跑通单元测试：6 条红的，一半是被测代码的真 bug ✅

`:app:testDebugUnitTest` 1481 条里 6 条失败。**逐条判过「是用例写错了还是代码错了」**，
结论是三对三——照着用例改到绿，会把三个真 bug 一起埋掉。

**被测代码的问题（3）**

| 文件 | 病根 | 改动 |
|---|---|---|
| `RestoreModel.kt` | `canSubmit = blockReason(...) == null`，而 `blockReason` 在 `busy` 时**刻意返回 null**（忙着不必再配一句话）。于是「没话说」被当成「可以按」：恢复进行中按钮仍可点，连点两下跑两趟恢复 | 改成 `!busy && blockReason(...) == null`，与 `DeleteVaultModel.canSubmit` 一致；注释写明这两件事不是一回事 |
| `PasswordGen.kt` | `SYMBOLS` 里带着 `&`，而它自己上面那段注释明写反引号和 `< > \| &` 要排除（怕被粘进 shell 展开）——常量和它的理由对不上 | 删掉 `&`（23 → 22 个）。`poolSize` 那条用例引的是 `symbolSet().length`，不受影响 |
| `PublicSuffix.kt` | `isTopLevel` 两字母一律当国家码，没要求都是字母。于是 `u1` 被当成 ccTLD，`u1.github.io` 首尾都像顶级域又有三段 → `looksLikePackage` 判成**包名** → `DomainMatch` 给 `WrongKind` 而不是 `None` | 两字母且都是字母才算。顶级域里从来没有数字，而 `u1.` `s3.` 这种首段满地都是 |

**用例本身写错的（3）**

| 文件 | 病根 | 改动 |
|---|---|---|
| `SettingsModelTest.kt` | `now = 10_000_000L` 减三天是负数 → 命中 `lastBackupAt <= 0` 那一支，这条一直在考别的东西 | `now` 换成真实量级；加 `contains("上次备份")` 钉住走的是哪一支 |
| `FieldGroupsTest.kt` | 用缺省 `FillContext` 考「明说别填的框不进组」，而缺省是**不听**那个旗子（决策见 `FieldRoles.DEFAULT_RESPECT_OPT_OUT`） | 显式 `respectOptOut = true`；另加一条钉住出厂默认行为（照样进组，3 个框） |
| `StructureRulesTest.kt` | 同上 | `contextOf(..., respectOptOut = true)`；补一句断言钉住「祖先排除 → 字段被改写成 `NO`」确实落到了字段上 |

**留下没动的一处**：同一处启发式还有个更常见的漏网——首段恰好两字母的主机名
（`mp.weixin.qq.com` / `cn.bing.com` / `us.example.com`）仍会被判成包名，
后果是这些站的条目在网页上永远填不进去。可行的规则是「两头都像顶级域时，
末段若是三字母以上的已知 gTLD 就判主机名」，但它会把 `com.foo.app` /
`com.foo.dev` 这类真包名一起推到主机名那边。方向是保守的那边，代价是那些应用
填不了——**这一刀属于决策，先记在这儿，不随手切。**

---

### FIX-3 切回本 app 有时提示「指纹传感器不可用」，再切一次就好了 ✅

**病根：把 `ERROR_HW_UNAVAILABLE` 和 `ERROR_HW_NOT_PRESENT` 归成了同一种失败。**
`BiometricFailure.HardwareUnavailable` 同时装着「这台机器没有传感器」（不会自己好）
和「传感器现在腾不出手」（等半秒就好），而 `biometricStillUsable` 对它返回 false。
于是自动锁定后切回应用时：指纹框在 Activity 还在 resume 路上、传感器还被上一个应用
（或系统锁屏）占着的时候就被拉起 → 拿到「腾不出手」→ 弹红字 +
**把指纹按钮撤掉一整屏** → 用户被逼去输长主密码。而他切出去再切回来就好了
——因为那给了传感器交接的时间。

**改动**
| 文件 | 改了什么 |
|---|---|
| `BiometricPolicy.kt` | `BiometricFailure` 拆出 **`HardwareBusy`**（可重试，不撤按钮）；`HardwareUnavailable` 只留给「真没传感器」；两条文案分开写 |
| `BiometricUnlock.kt` / `BiometricEnroll.kt` | `classifyBiometricError` / `classifyEnrollError` 拆开两个错误码；解锁侧那个 `catch (t: Throwable) → HardwareUnavailable` 改为按 `KeystoreFailure` 分类 + 记日志；新增 `probe` 参数 |
| `QuickUnlockModel.kt` | 绑定侧补 `HardwareBusy` 文案 |
| `QuickUnlockScreen.kt` | 自动弹框改为 **RESUMED 之后 + 250ms** 才拉起；「传感器正忙」**静默重试**最多 2 次；`promptActive` 防重入；不再只凭错误码撤按钮 |
| `BiometricPolicyTest.kt` | 两条新用例钉住「正忙不许撤入口 / 没传感器必须撤入口」 |

**三个新的守卫**
1. `resumed` —— `ON_RESUME` 才弹，而不是「这一屏组合出来了就弹」。再加 250ms
   让系统把传感器交接完：`ON_RESUME` 只说明界面可交互，不等于传感器已归还。
2. `busyRetries` —— 「正忙」时静默重试（600ms 后），不弹错误、不撤按钮。
   等于把用户自己发现的那个土办法（切出去再切回来）自动化了。上限 2 次：
   再多就变成「按了没反应而我们在后台偷偷试第五次」，那时如实说一句更尊重人。
3. `promptActive` —— 弹框正在显示时不许再弹；第二次 `authenticate()`
   会把第一次取消掉，那才是真会让用户看见一条莫名错误的路径。

另外 `rememberBiometricUnlocker` 的 `canAuthenticate()` 缓存加了 `probe` 参数：
它缓存的答案**是会变的**，刚回到前台那一瞬间问会得到「不可用」，
只问一次的后果是这一屏从生到死都不画指纹按钮。

---

### FIX-2 快捷解锁「设置时正常，自动锁定一次就失效」✅

**症状**（真机上报）
指纹和 PIN 都设置成功，切到别的 app 再回来触发自动锁定，两个快捷解锁都用不了，
提示「这台设备上的快捷解锁绑定已经不能用了」。

**病根：`setUnlockedDeviceRequired(true)`。**
它是那份 `KeyGenParameterSpec` 里唯一一个**能让一把已经生成成功的钥匙在之后突然不可用**的属性
——生成时不报错，用的时候才看设备状态。而它当时无条件加在**两把钥匙**上，
所以指纹和 PIN 一起失效，症状完全对称。属性写进钥匙就改不掉，
光改生成代码治不了已经绑过的设备。

**为什么去掉它不损失安全性**（详见 `KeystoreKeys.doGenerate` 里那段注释）：它在两把钥匙上都是冗余的。
- `AUTH_REQUIRED`：每次使用都要现场过一次强生物识别（0 秒时间窗），这个条件严格强于「设备已解锁」。
- `DEVICE_BOUND`：它防的是「文件被拷走后离线爆破 PIN」，靠的是钥匙出不了这台设备的安全硬件，和设备锁没锁无关。而且 M4 的自动填充要从锁屏上工作，这个属性到时候本来就得去掉。

**改动**
| 文件 | 改了什么 |
|---|---|
| `KeystoreKeys.kt` | 去掉 `setUnlockedDeviceRequired`；别名升到 **v2**；降级阶梯简化为 StrongBox → TEE；新增 `purgeLegacyKeys()` 清理 v1 钥匙、`containsKey()` 返回**三态** `Boolean?` |
| `QuickUnlock.kt` | 构造时清理 v1 钥匙 + 跑一次 `healStaleBindings()`；`enrollPin` / `finishBiometricEnrollment` 在写 prefs **之前先自检一次** |
| `UnlockController.kt` | `explain()` 按 `KeystoreFailure` 分五种说法，只有「钥匙没了」那一种才建议重新绑定 |

**两条结构性改进**（比上面那个属性更要紧，因为它们防的是**下一次**同类问题）

1. **绑定前自检。** `enrollPin` 现在写 prefs 之前先把刚生成的包裹用设备绑定密钥**读回来比一遍**；
   `finishBiometricEnrollment` 则确认拿存好的 IV 还能 init 出一个解密 Cipher。
   任何一步不过就一个字都不写。这把「设置时好的、下次开门才发现坏了」
   这一整类问题提前到当场暴露——加密成功不代表以后解得开，这次就是这么栽的。

2. **启动自检 `healStaleBindings()`。** 包裹在 SharedPreferences 里，钥匙在 Keystore 里，
   两者生命周期并不同步（指纹库变更、锁屏凭据变更、系统升级、厂商 ROM）。
   只剩包裹时界面会长期摆着一个点了必定失败的入口。现在启动时对一遍，
   钥匙确实不在就把包裹也清掉，用户看到的是「快捷解锁没开」——一句真话，而且他自己能修。
   `containsKey()` 特意返回 `Boolean?`：**问不出来 ≠ 不在**。
   应用启动那一刻恰好是 Keystore 最可能答不上话的时候，
   把「问不出来」当「不在」会误删一份好的绑定。只在确定 `false` 时才清。

**升级到这一版之后**：v1 钥匙会在下次启动时被清掉，对应的两份包裹跟着清掉，
快捷解锁显示为「未开启」。到设置里重新开一次指纹和 PIN 即可——这次绑定会当场自检。

**真机验证看 logcat**：`KeystoreKeys`（设备能力、哪一档成功、v1 清理）、`QuickUnlock`（自检结果）、
`BiometricEnroll`、`PinSetup`。

---

### FIX-1 快捷解锁在部分机型上整体失效（指纹绑定 + PIN 设置双双报错）✅

**症状**（真机上报）
1. 设置 → 快捷解锁 → 打开「用指纹解锁」：立刻提示「指纹传感器暂时不可用，这次没能开启，过一会儿再试」，指纹框根本没弹出来。
2. 设置 PIN：两次输入一致，仍提示「这次没能设置 PIN，可以再试一次；保险库和里面的数据没有影响」。

**病根：`core/keystore/KeystoreKeys.kt` 的降级判断太窄。**
两条路唯一的交集是这个文件——指纹绑定走 `authCipherForEncrypt()` → `AUTH_REQUIRED` 钥匙，
PIN 设置走 `encryptWithDeviceBoundKey()` → `DEVICE_BOUND` 钥匙，两把钥匙都在
`doGenerate()` 里生成，而那份规格无条件带着 `setIsStrongBoxBacked(true)` 和
`setUnlockedDeviceRequired(true)`，降级却**只**接住 `StrongBoxUnavailableException` 一种异常。
真机上安全芯片拒收规格时抛的常常是泛泛的 `ProviderException` / 带 -68 / -38 错误码的
`KeyStoreException` / `InvalidAlgorithmParameterException`，一个都不在接住的范围内。
于是钥匙一把也建不出来，两个功能同时报废，而且每次都报废。
（这正是本文件「已知风险」第 5 条点出的那个盲区：`KeystoreKeys` 是全工程唯一无法纯 JVM 验证的文件。）

**报错文案为什么和真实原因无关**——两处调用点都把原始异常吃掉了，且一行日志都没留：
- `BiometricEnroll.kt`：`catch (t: Throwable) { onFailure(HardwareUnavailable) }`，把任何异常都说成「传感器暂时不可用」。
- `PinSetupScreen.kt`：`runCatching { ... }`，失败就是那一句「可以再试一次」。

**改动**
| 文件 | 改了什么 |
|---|---|
| `core/keystore/KeystoreDiagnosis.kt` | **新增**。`KeystoreFailure` 五分类 + `classify()`，按异常链的类名/消息文本判断。**无一行 `android.*`**，可纯 JVM 测 |
| `core/keystore/KeystoreKeys.kt` | 三档**降级阶梯**（StrongBox+锁屏受限 → TEE+锁屏受限 → TEE），每档失败记日志、清残留、再降；StrongBox 改为查 `FEATURE_STRONGBOX_KEYSTORE` 而不是靠异常试探；`getOrCreate` 加 `allowReset`，读取侧不再悄悄重建钥匙；`wrapFailures` 统一翻译异常 |
| `ui/unlock/BiometricEnroll.kt` | 新增 `classifyEnrollThrowable()`，不再无条件报 `HardwareUnavailable`；三处 catch 全部记日志 |
| `ui/settings/PinSetupScreen.kt` | 保留并记录原始异常，按真实原因分文案 |
| `ui/settings/PinSetupModel.kt` | 新增 `ENROLL_FAILED_KEYSTORE` / `ENROLL_FAILED_LOCKED` 两条文案 |
| `core/keystore/QuickUnlock.kt` | 构造时问一次设备能力；`beginBiometricUnlock` 接住 `KeystoreUnavailableException`，只在「钥匙作废」时清残留 |
| `ui/unlock/UnlockController.kt` | `explain()` 加一条分支，Keystore 侧失败不再显示为「解锁失败：XxxException」 |
| `src/test/.../KeystoreDiagnosisTest.kt` | **新增** 14 个用例，覆盖真机上出现过的各种异常链形状 |

**顺手修掉的一个潜在 bug**：`existing()` 原本 `runCatching{}.getOrNull()`，把「钥匙读不出来」
和「钥匙不存在」吞成同一件事，然后悄悄重建一把新的。发生在 **PIN 解锁**路径上时，
外层解不开 → GCM 校验失败 → `WrongPinException` → 屏幕显示「PIN 不正确」并吃一次退避，
连错十次后快捷解锁被自动关掉——用户输的 PIN **是对的**，整条链上没有一句真话。
现在读取侧一律不重建，抛 `KeystoreUnavailableException`，`UnlockController` 也不会把它计入退避。

**真机验证时看 logcat 这几个 tag**：`KeystoreKeys`（哪一档成功、哪几档失败、原始异常）、
`BiometricEnroll`、`PinSetup`。第一次进快捷解锁页时 `KeystoreKeys` 会打一行
「设备能力：StrongBox=… SDK=…」。

---

## 已完成

### M0 工程骨架与设计系统 ✅
- `settings.gradle.kts` / `build.gradle.kts` / `gradle/libs.versions.toml`（版本目录）
- `AndroidManifest.xml` —— **只声明 `USE_BIOMETRIC`，没有 `INTERNET`**
- `res/xml/data_extraction_rules.xml` —— 全面禁止云备份与设备迁移带走数据
- `ui/theme/` —— Color / Type / Shape / Theme，与交互原型 1:1 对应的设计令牌

### M1 密码学内核 ✅
| 文件 | 作用 |
|---|---|
| `core/crypto/SecureBytes.kt` | 敏感字节的持有与清零；手写 UTF-8 编码，全程不产生 String |
| `core/crypto/Kdf.kt` | KDF 抽象 + PBKDF2-HMAC-SHA512 兜底实现 + 注册表 |
| `core/crypto/Argon2idKdf.kt` | Argon2id 主力实现（唯一依赖原生库的文件）+ 设备校准 |
| `core/crypto/Aead.kt` | AES-256-GCM + 无模偏差的安全随机源 `Rng` |
| `core/vault/VaultModel.kt` | `VaultData` / `VaultEntry` / `VaultMeta` 数据模型 |
| `core/vault/VaultFile.kt` | `.lvault` 文件格式 v1：create / open / reseal / rewrap |
| `src/test/.../VaultFileTest.kt` | 13 个单元测试，纯 JVM 可跑 |

**已用等价实现验证通过的性质：** 加解密往返一致、错误口令被识别、同内容两次加密密文不同、
篡改 KDF 参数无法降级、篡改密文必被发现、reseal 不重用 nonce、改密码后旧口令失效且数据不丢、
`openWithKey` 偏移正确且错误密钥被拒。

### M2 存储与会话 ✅
| 文件 | 作用 |
|---|---|
| `core/vault/VaultStorage.kt` | 原子写盘（tmp → fsync → 轮换 → rename）+ 崩溃恢复 |
| `core/vault/VaultRepository.kt` | 建库/解锁/保存/改密码；**写盘前当场解密自检** |
| `core/session/VaultSession.kt` | 解锁态、改动即落盘、切后台自动锁定 |
| `core/keystore/KeystoreKeys.kt` | 设备绑定密钥 + 认证必需密钥（StrongBox / 指纹变更即失效） |
| `core/keystore/QuickUnlock.kt` | 指纹解锁与 PIN 解锁的绑定与解绑 |
| `core/keystore/AttemptLimiter.kt` | 失败退避（纯逻辑，可单测） |
| 测试 | `VaultStorageTest` / `VaultRepositoryTest` / `VaultSessionTest` / `AttemptLimiterTest`，共 36 个用例 |

**已验证的落盘性质：** 崩在写临时文件途中保住旧版、崩在两次改名之间扶正新版、
主文件丢失时从备份恢复、首次保存中断不会留下空库、连续 20 次保存主备始终各差一版。

### M3-1 界面基础设施 ✅
| 文件 | 作用 |
|---|---|
| `ui/components/Glyphs.kt` | 21 个 Canvas 手绘图标，不引入 material-icons |
| `ui/components/Basics.kt` | 页面骨架 `VaultScreen`、顶栏、三种按钮、卡片、Banner、空状态、设置行 |
| `ui/components/Seal.kt` | **顶部封条** `SealBar` + 剪贴板倒计时条 `ClipboardBar` + `SealSlot` 组合槽 |
| `ui/components/Fields.kt` | `SecureTextState`／`SecurePasswordField`（EditText 互操作）、`PlainField`、强度条、遮蔽值 |
| `ui/components/Keypad.kt` | PIN 键盘、`PinDots`、`PinBuffer`（CharArray 缓冲） |
| `ui/components/Entry.kt` | `EntryTile`（首字母 + 名称哈希取色）、`EntryRow`、分组标头 |
| `ui/util/SecureClipboard.kt` | 复制敏感内容 + 15 秒自动清除 + 只清自己那一份 |
| `ui/util/PasswordStrength.kt` | 熵估算 + 规律性惩罚 + 中英文弱口令名单，全程 CharArray |
| `ui/util/Format.kt` | 相对时间、KDF 参数标签、文件名时间戳 |
| `ui/LocalProviders.kt` | 五个 CompositionLocal + `CryptoInfo` |
| `ui/nav/Routes.kt` | 路由常量 |
| `ui/nav/VaultNavHost.kt` | `VaultRoot`：按会话状态切三张互不相通的图，含全部占位屏 |
| `MainActivity.kt` | 接 `VaultRoot`；`VaultApp.kt` 增加 `clipboard` 与 `currentKdfParams()` |

**这一步就能上机验证的：** App 起来后按 `NoVault / Locked / Unlocked` 自动落到对应的图；
顶部封条如实显示当前 KDF（Argon2 拉不到时显示 PBKDF2 且转灰）；每个占位屏都能点进去。

### M3-2a 首次引导建库流 ✅
| 文件 | 作用 |
|---|---|
| `ui/onboarding/CreateVaultController.kt` | 建库执行器：KDF 校准 → 派生落盘 → 会话接管；含「已落盘但没接管」的补救 |
| `ui/onboarding/WelcomeScreen.kt` | 欢迎页：三条可被核实的承诺 + 建库入口 + 恢复入口 |
| `ui/onboarding/CreateMasterScreen.kt` | 设置主密码：输入与确认同屏、强度条、弱口令二次确认、阶段进度 |
| `ui/components/Dialogs.kt` | `VaultDialog`：弹窗必须自己声明 `FLAG_SECURE`；主/次/取消三个回调分开 |
| `ui/components/SealHost.kt` | `DefaultSeal()`：全工程统一接法，防止某一页把封条写成假话 |
| `src/test/.../CreateVaultControllerTest.kt` | 8 个单元测试，纯 JVM 可跑 |

改动的既有文件：
- `ui/components/Fields.kt` —— `SecureTextState` 增加 `revision` 计数（长度不变但内容变了也要重算强度）
- `core/session/VaultSession.kt` —— 保留文件头，新增 `headerKdfParams`
- `MainActivity.kt` —— 封条改显示**当前这个库文件头里**的 KDF 档位
- `ui/nav/Routes.kt` —— 去掉 `CREATE_CONFIRM`；`FIRST_BACKUP` 划归主图
- `ui/nav/VaultNavHost.kt` —— 接线；`Stub` 改用 `DefaultSeal`
- `AndroidManifest.xml` —— 补 `windowSoftInputMode="adjustResize"`（`imePadding` 生效的前提）

**这一步能上机验证的：** 全新安装 → 欢迎页 → 设置主密码（强度条实时变化、两次不一致会拦、
弱口令弹二次确认）→ 点创建后看到「正在测算本机能承受的加密强度…」→ 落进保险库列表；
杀掉重进变成解锁页（M3-2c-1 起是真页面）。低配机上封条里的
Argon2 档位会比默认档低——那是校准生效了，不是 bug。

### M3-2b 首次备份导出 ✅
| 文件 | 作用 |
|---|---|
| `ui/backup/ExportController.kt` | 导出执行器 + `ExportSink` 接口；写前自检、写后回读比对，两道都过才记 `lastBackupAt` |
| `ui/backup/SafExportSink.kt` | SAF 实现（`"wt"` 截断写入 + 回读 + 取显示名）。**不需要任何存储权限** |
| `ui/backup/BackupScreen.kt` | 备份页。`firstRun=true` 时挡在主图前面，`false` 时在设置里复用 |
| `src/test/.../ExportControllerTest.kt` | 8 个用例，用假 sink 模拟写一半 / 读不回来 / 写入抛错 |
| `src/test/.../VaultSessionInterludeTest.kt` | 7 个用例，钉死可信中断的自动锁定行为 |

改动的既有文件：
- `core/session/VaultSession.kt` —— 新增可信中断 `beginSystemInterlude` / `endSystemInterlude`
- `ui/util/Format.kt` —— `Fmt.backupFileName()`
- `ui/nav/VaultNavHost.kt` —— 主图起始点按 `lastBackupAt == 0L` 决定；设置页的备份入口接真页面

**这一步能上机验证的：** 建库完成后不再直接进列表，先落到「先备份一次」；
点导出弹系统文件选择器（注意此时应用信息里**依然一个权限都没有**）；
选好位置后依次看到「正在读取并自检 → 正在写入 → 正在读回来核对」，
成功卡片里显示真实文件名和字节数；按返回不会退回备份页。
选「暂时跳过」则下次解锁还会挡在前面。
把自动锁定调成「立即」再走一遍导出——这条以前是走不通的，现在能走通。

### M3-2c-1 解锁内核与主密码解锁页 ✅
| 文件 | 作用 |
|---|---|
| `ui/unlock/UnlockGuard.kt` | 解锁守卫接口：退避状态、快捷失败计数、PIN 解包、关闭快捷解锁。**整个文件没有一行 `android.*`** |
| `ui/unlock/QuickUnlockGuard.kt` | 上面那个接口的线上实现，唯一碰 Keystore / prefs 的一层 |
| `ui/unlock/UnlockController.kt` | 解锁执行器：三个入口汇到同一套退避；失败分类（输错 vs 故障）；错误文案 |
| `ui/unlock/UnlockMasterScreen.kt` | 主密码解锁页：退避倒计时、自动锁定提示、快捷解锁被关掉的交代、忘记密码弹窗 |
| `src/test/.../UnlockControllerTest.kt` | 12 个用例，纯 JVM 可跑 |

改动的既有文件：
- `core/session/VaultSession.kt` —— 新增 `LockReason` 与 `lastLockReason`；`lock()` 拆出带原因的内部实现
- `core/keystore/QuickUnlock.kt` —— 新增 `quickFailCount`（与总失败计数分开持久化）
- `ui/util/Format.kt` —— `Fmt.countdown()`
- `ui/nav/VaultNavHost.kt` —— `LockedGraph` 接线，控制器挂在图这一层
- `ui/nav/Routes.kt` —— 写清 `UNLOCK` 与 `UNLOCK_MASTER` 的分工

**这一步能上机验证的：** 建好库后杀掉进程重进 → 落到解锁页（不再是占位屏）；
输错密码出红条，连错 5 次开始倒计时、封条转红、按钮变成「等待 0:05」，
倒计时归零自动恢复；杀掉进程重进，剩余时间**照样准**（挂钟时间不是倒计数）。
把自动锁定设成「立即」，切后台再回来，解锁页顶部会说明「上次因长时间未操作已自动锁定」——
一动手输入这条就让位。点「忘记主密码了？」看到的是实话，不是客服电话。

### M3-2c-2 快捷解锁：PIN 键盘与指纹 ✅
| 文件 | 作用 |
|---|---|
| `ui/unlock/BiometricPolicy.kt` | 生物识别失败的语义分类 + 处置策略。**纯 Kotlin，无 Android 依赖** |
| `ui/unlock/BiometricUnlock.kt` | `BiometricPrompt` + `CryptoObject` 接线；错误码 → 语义只有一个 `when` |
| `ui/unlock/QuickUnlockScreen.kt` | PIN 键盘页：六格圆点、左下角指纹键、退避倒计时、主密码退路 |
| `src/test/.../BiometricPolicyTest.kt` | 8 个用例 |
| `src/test/.../PinBufferTest.kt` | 6 个用例 |

改动的既有文件：
- `MainActivity.kt` —— `ComponentActivity` → `FragmentActivity`（`BiometricPrompt` 的公开签名要求）
- `ui/components/Glyphs.kt` —— 新增 `Glyph.Fingerprint`（四条同心弧，仍是 Canvas 手绘）
- `ui/nav/VaultNavHost.kt` —— 起始点按 `isAnyEnrolled` 决定；两页互跳都不留返回栈
- `gradle/libs.versions.toml` / `app/build.gradle.kts` —— **M0 之后第一次动依赖**：
  显式声明 `androidx.fragment`。它本来就由 biometric 传递带进来，APK 不会变大一个字节，
  写在明面上只是为了将来换掉 biometric 时不会莫名其妙编译不过。

**这一步能上机验证的（需要真机，模拟器请先在系统里录一枚指纹）：**
绑定入口在 M3-6 设置页，所以现在要验证快捷解锁得先手动造一份绑定；
未绑定时行为不变——直接落到主密码页，这条路本身也要回归一遍。
绑定之后：进解锁页会**自动弹一次**指纹框；按「用主密码」关掉不出任何红字（取消不是错误）；
PIN 按满六位后按钮才点亮（**不自动提交**）；连错 PIN 到第 10 次会被自动送到主密码页，
并在那里看到「为什么 PIN 没了」的交代——那条横幅是 M3-2c-1 写好但一直到这一步才走得到的。
去系统设置里增删一枚指纹再回来，应看到「不是故障、数据没动」的说明，指纹键随即消失。

### M3-3a 列表内核与保险库列表页 ✅
| 文件 | 作用 |
|---|---|
| `ui/list/VaultIndex.kt` | 分组排序 + 搜索打分 + 域名归一。**整个文件没有一行 `android.*`，也没有一行 Compose** |
| `ui/list/VaultListScreen.kt` | 保险库列表页：备份提醒条、分组清单、空状态、右下角新增钮 |
| `src/test/.../VaultIndexTest.kt` | 26 个用例，纯 JVM 可跑 |

改动的既有文件：
- `ui/nav/VaultNavHost.kt` —— `Route.LIST` 接真页面；`Route.SEARCH` 的占位屏改标 M3-3b

**已在等价实现上验证过的性质：** 匹配越完整排越前（Exact→Prefix→WordPrefix→Contains）、
同档内名称优先于账号优先于网址、账号完全命中胜过名称里碰巧含有、同分时收藏在前、
一个条目最多出一行、**备注与密码搜不到**、中文按拼音排而不是按码点排、
网址收敛到主机名且路径里的 `@` 不会把主机名切没、`www.` 与子域名一概不剥。

**这一步能上机验证的：** 解锁（或跳过首次备份）后落到真的列表页而不是占位屏；
库是空的时看到空状态和「添加第一条」；顶栏右上角显示条目数；
从没备份过时列表顶部常驻黄铜色提醒（点「去备份」进设置里那张备份页，
不是首次备份那道关卡）；备份过之后再改条目，提醒会变成「有 N 条改动还没进备份」。
条目和分组现在只能靠 M5 导入或手动造数据来验证——新增入口在 M3-5，
点右下角那个加号目前还是占位屏。

### M3-3b 搜索页 ✅
| 文件 | 作用 |
|---|---|
| `ui/list/SearchHighlight.kt` | 命中切片与高亮内核：以命中为中心开窗、代理对不切开、字段标签。**没有一行 `android.*`，也没有一行 Compose** |
| `ui/list/SearchScreen.kt` | 全屏搜索页：搜索栏、可搜字段说明、分类快捷键、结果高亮、无结果去处 |
| `ui/nav/DraftHandoff.kt` | 页面间的一次性草稿交接槽 + `LocalDraftHandoff`（关键词不走路由参数的落点） |
| `src/test/.../SearchHighlightTest.kt` | 20 个用例，纯 JVM 可跑 |

改动的既有文件：
- `ui/nav/VaultNavHost.kt` —— `Route.SEARCH` 接真页面；`UnlockedGraph` 里挂 `DraftHandoff` 并 provide 下去

**已在等价实现上验证过的性质：** 长文本里靠后的命中不会被尾部截断吃掉、
关键词比窗口还长时宁可这行更长也不切高亮、窗口守得住宽度、emoji 不会被切成半个方框、
空区间退化成「不高亮」而不是给出一个错位的高亮、搜索内核给的区间可以直接拿来切片
（高亮出来就是用户打的那几个字）、归一后的网址切片不会带出路径、备注依旧搜不到。

**这一步能上机验证的：** 列表页右上角放大镜 → 全屏搜索页，进来键盘自动弹出；
没输入时看到「可以搜到的」说明卡（写明备注和密码不参与搜索）和分类快捷键，
点一个分类就等于把分类名填进关键词；边打边出结果，命中处黄铜色高亮，
右侧标出命中的是账号 / 网址 / 分类（名称命中不标）；
造一条账号很长的数据（`zhangsan_backup_2019@company-mail.example.com`）
搜 `example`，命中那段**看得见**，前面是省略号——这一条是这个模块的全部理由；
一开始滚动结果键盘就收起；搜一个库里没有的词，看到的是「新增「XXX」」而不是
「换个词试试」，点进去目前还是 M3-5 的占位屏（关键词已经放进交接槽，等 M3-5 取）；
转屏会丢掉正在输入的关键词——那是刻意的，见决策㊲。

### M3-4a 条目详情页 ✅
| 文件 | 作用 |
|---|---|
| `ui/detail/EntryDetail.kt` | 详情内核：删除快照与原位撤销、账号打码、确认弹窗文案、行清单。**没有一行 `android.*`，也没有一行 Compose** |
| `ui/detail/EntryDetailScreen.kt` | 详情页：遮蔽 / 复制 / 收藏 / 删除 + 墓碑页 |
| `src/test/.../EntryDetailTest.kt` | 22 个用例，纯 JVM 可跑 |

改动的既有文件：
- `ui/components/Glyphs.kt` —— 新增 `Glyph.Pencil`（仍是 Canvas 手绘，第 22 个图标）
- `ui/nav/VaultNavHost.kt` —— `Route.DETAIL` 接真页面并从路由取 id；`Route.EDIT` 的占位屏改标 M3-4b

**已在等价实现上验证过的性质：** 撤销把条目放回**原来的位置**且字段一个没变、
连点两下撤销不会出现两份、原位置越界时也放得回去、删不存在的 id 不动列表也不产生快照、
手机号保留后四位而其它一律只留头不留尾、**确认弹窗文案里绝不出现密码和备注**、
空字段不占位、行顺序固定、剪贴板标签只是字段名不带条目名。

**这一步能上机验证的：** 列表点进任意一条 → 真的详情页（不再是占位屏）；
密码是 12 个圆点（**不按真实长度画**，位数本身就是爆破时最值钱的边信息），
点眼睛显示、再点收起，且**不会自己变回去**；备注同样默认藏着；
点复制图标不弹任何 toast——顶部封条那条倒计时就是回执；
点右上角星星立刻落盘（列表页的「有 N 条改动还没进备份」会 +1，这是对的，见决策 (52)）；
滚到底点「删除这个条目」，弹窗里只有名称和打过码的账号；确认后**停在墓碑页**，
可以撤销一次；点「完成」返回列表，那一条已经不在了；
进详情 → 点铅笔（M3-4b 起是真编辑页）→ 返回，密码是重新遮住的。

### M3-4b 编辑页 ✅
| 文件 | 作用 |
|---|---|
| `ui/edit/EntryForm.kt` | 表单内核：草稿模型、网址切行与去重、修剪规则、脏检查、改动摘要。**没有一行 `android.*`，也没有一行 Compose** |
| `ui/edit/EntryFormFields.kt` | 六个字段的可复用组件块，**M3-5 新增流的最后一步直接用它** |
| `ui/edit/EditEntryScreen.kt` | 编辑页：底部固定保存、返回拦截、失败横幅 |
| `src/test/.../EntryFormTest.kt` | 25 个用例，纯 JVM 可跑 |

改动的既有文件：
- `ui/nav/VaultNavHost.kt` —— `Route.EDIT` 接真页面并从路由取 id

**已在等价实现上验证过的性质：** 装载再原样存回时条目一个字段都不变、
保存不会碰收藏 / `totpSecret` / `createdAt`、时间戳仍由 `VaultSession.updateEntry` 统一刷新、
**密码首尾的空格原样保留**而其它字段一律 trim、备注保留中间的换行、
网址按换行/逗号/分号/空白切开且空行被丢掉、留下来的那些一个字符都不改写、
指向同一主机的不同写法只留第一个、子域名一个都不合并、包名原样保留、
清理是幂等的、尾随空格与多按的回车不算改动而密码末尾的空格算、
**「放弃修改」弹窗的摘要里绝不出现任何字段的内容**。

**这一步能上机验证的：** 详情页点右上角铅笔 → 真的编辑页（不再是占位屏）；
进来**不弹键盘**，也不自动聚焦；密码默认是圆点，点眼睛显示，下面出现强度条
（密码为空时强度条不出现）；分类框下面列出库里已有的分类，点一下就填进去；
网址框里粘一整条 `https://mail.example.com/inbox` 再保存，详情页显示的**还是那一整条**，
不会被偷偷截成主机名；把同一个站写两遍（`example.com` 和 `https://example.com/login`）
保存后只剩第一条；名称清空 → 保存按钮变灰并出现红字；什么都没改 → 保存按钮也是灰的，
下面写着「还没有改动」；改一个字后按系统返回键 → 弹「放弃修改？」，
**主按钮是「继续编辑」**，点弹窗外面等于什么都不做（停在编辑页），
弹窗里只写「未保存：账号 · 密码」这种字段名，一个值都没有；
密码末尾故意加一个空格再保存，详情页复制出来粘到别处，那个空格**还在**。


### M3-5a 密码生成器 ✅
| 文件 | 作用 |
|---|---|
| `ui/generate/PasswordGen.kt` | 生成器内核：两种模式、字符类、无偏差洗牌、容斥算熵。**没有一行 `android.*`，也没有一行 Compose**；随机源是参数，默认 `Rng.int` |
| `ui/components/Toggle.kt` | `VaultSwitch` / `ToggleRow` / `Stepper` / `PresetChip`，**M3-6 设置页要复用同一批** |
| `ui/generate/GeneratorPanel.kt` | 面板内容：分色结果、熵条、模式切换、两套选项。三个调用场合共用这一个 |
| `ui/generate/GeneratorSheet.kt` | 覆盖层 + `GeneratorHolder`（选项的存放处）+ `LocalGenerator` |
| `src/test/.../PasswordGenTest.kt` | 28 个用例，纯 JVM 可跑 |

改动的既有文件：
- `ui/components/Glyphs.kt` —— 新增 `Glyph.Minus`（第 23 个 Canvas 手绘图标，与 `Plus` 共用同一条横线）
- `ui/edit/EditEntryScreen.kt` —— 整页包进 `Box`，接上 `onGenerate`；采用之后自动把密码显示出来
- `ui/edit/EntryFormFields.kt` —— 只改了 `onGenerate` 那段注释（槽位本身 M3-4b 就留好了）
- `ui/nav/Routes.kt` —— **删掉 `Route.GENERATOR`**，原地留下为什么删的说明
- `ui/nav/VaultNavHost.kt` —— 去掉生成器占位屏；`UnlockedGraph` 里挂 `GeneratorHolder` 并 provide 下去

**已在等价实现上验证过的性质：** 打开的每一类都至少出现一次而关掉的一个都不出现、
**补位之后确实洗过牌**（用恒返回 0 的假随机源钉死，这是唯一能在纯逻辑上抓住这条的办法）、
洗牌不增不减字符、从不向随机源要 0 边界、四类全关时强制打开小写而不是抛异常、
合法化幂等、熵随长度单调增且**永远不超过天真上界**（短密码上看得见差距，长密码上收敛）、
避开易混时那五个字符一个不出现而不开时它们确实会出现、
易读模式的音节数与分组数对得上、末尾数字正好两位且只在末尾、
生成结果不含任何空白字符、符号集里没有引号 / 反斜杠 / shell 元字符。

**这一步能上机验证的：** 详情页点铅笔 → 编辑页 → 密码框右边现在有第二个图标（黄铜色的循环箭头，
之前是不画的）→ 点它从底部升起生成器；结果那一行**字母、数字、符号三色分开**，
右边的循环箭头每点一次换一串；把长度从 20 调到 12，熵条会掉一档，
调到 64 那一行写「约 410 bit」；把四个字符类逐个关掉，**最后一个关不动**，
它旁边写着「至少要留一类字符」；打开「避开易混字符」再连点十几次重新生成，
`0 O 1 l I` 一个都不会出现；切到「易读」看到的是 `bamo-tenlai-…` 这种念得出来的东西，
下面明写着它比随机模式弱以及该用在哪儿。
按「用这个密码」回到编辑页，密码框里是**明文**的那一串（不用再点眼睛核对），
保存按钮同时亮起；按系统返回键**先关掉生成器**，再按一次才轮到「放弃修改？」那道拦截；
点覆盖层外面的暗处等于关掉，什么都不会丢。
另外：从生成器里「只复制」出去，顶部封条的剪贴板倒计时会起来，
而系统剪贴板面板上显示的标签只有「生成的密码」五个字。

### M3-5b 新增 3 步流 ✅
| 文件 | 作用 |
|---|---|
| `ui/add/AddFlow.kt` | 新增流内核：三步的字段划分、能不能往下走、跳步、判重、回顾行、新条目 id 的取法。**没有一行 `android.*`，也没有一行 Compose** |
| `ui/add/AddEntryScreen.kt` | 三步页面：进度条、回顾卡、重复提醒、返回即上一步、放弃拦截 |
| `src/test/.../AddFlowTest.kt` | 33 个用例，纯 JVM 可跑 |

改动的既有文件：
- `ui/components/Fields.kt` —— `PlainField` 增加 `fieldModifier`（作用在**输入框本体**上，
  目前唯一用途是挂 `FocusRequester`）。带默认值，既有调用一个字没改。
- `ui/edit/EntryFormFields.kt` —— 增加 `visible`（只画其中几个字段）和 `autoFocus`
  两个带默认值的参数；六个字段各自包进一个 `if`。**编辑页的调用一个字没改。**
- `ui/nav/VaultNavHost.kt` —— `Route.ADD` 接真页面；存完 `popUpTo(LIST)`

**已在等价实现上验证过的性质：** 三步的字段**不重不漏**（并起来正好六个、两两不相交）、
名称没填时点进度条也绕不到后面去、往回跳永远允许、
「放弃新增」摘要里只有字段名没有任何值、回顾卡上的密码永远是**固定 12 个圆点**且不含原文、
同一个站的两个不同账号**不算**重复、名字写得不一样但同主机同账号拦得住、
子域名不同不算同一个站、强信号（同名同账号）优先于弱信号（只是重名）、
提醒文案里有名称但没有账号也没有密码、新条目不管落在列表哪个位置都找得到它的 id、
一条都没多出来时返回 null 而不是随便给一个。

**这一步能上机验证的：** 列表页右下角那个加号 → 真的新增页（不再是占位屏）；
进来**光标已经在名称上、键盘已经弹出来**；名称空着时「下一步」是灰的，
下面写着为什么灰；填上名称 → 第二步，这一屏**不弹键盘**，密码框下面摆着一个
黄铜色的「生成一个强密码」，点开就是 M3-5a 那个覆盖层，采用之后按钮自动收起来
（一屏上不长期并存两个同样的入口）；这一步的密码是**明文**的，
和编辑页默认遮蔽刻意相反；第三步顶上是一张回顾卡，密码那行是 12 个圆点、
**没有眼睛**，右上角「改」跳回第一步。
在第三步按返回 → **回到第二步**，不是退出（这条是三步流最容易做错的地方）；
一路返回到第一步再按返回，什么都没填时**直接退出、不弹任何框**，
填过东西才弹「放弃新增？」，主按钮是「继续填写」，弹窗里只写「已填写：名称 · 密码」。
按保存后**落到刚存的那一条的详情页**，再按返回回到列表（不会退回新增页）。
判重：库里先存一条「招商银行 / 13800000000」，再新增一条同名同账号的，
第一步就会出现黄铜色横幅「库里已经有一条「招商银行」，账号也是同一个」——
**它只是提醒，保存照样点得动，也没有「打开那一条」的按钮**（点过去等于把草稿丢了）；
把账号改成另一个，横幅立刻消失（同一个站的两个账号是正常的）。
从搜索页走这条路更值得试一遍：搜一个库里没有的词 → 点「新增「XXX」」→
名称栏里已经是那几个字，而**光标落在账号上**（名称不用再打一遍）；
存完之后**退回的是列表，不是那张显示「没找到」的旧搜索页**。

### M3-6a 设置内核 · 设置主页 · 关于页 ✅
| 文件 | 作用 |
|---|---|
| `ui/settings/SettingsModel.kt` | 设置内核：两张档位表、什么时候该出说明、备份行副标题、关于页的事实/权限/依赖清单。**没有一行 `android.*`，也没有一行 Compose** |
| `ui/settings/SettingsScreen.kt` | 设置主页：自动锁定、剪贴板自动清除、备份入口、关于入口、立即锁定 |
| `ui/settings/AboutScreen.kt` | 关于页：真实参数 + 权限清单 + 「这个应用没有的东西」+ 依赖清单 |
| `src/test/.../SettingsModelTest.kt` | 29 个用例，纯 JVM 可跑 |

改动的既有文件：
- `ui/util/SecureClipboard.kt` —— **关掉自动清除时照样发一条 `Pending`**（`totalSeconds = 0` 是标记）。
  不发的话，复制这个动作会变成完全没有反馈（决策(51) 说回执就是那条倒计时，倒计时没了回执不能跟着没）
- `ui/components/Seal.kt` —— `ClipboardBar` 增加 `autoClear`，为 false 时文案改成「不会自动清除」并转黄铜色；
  `SealSlot` 透传
- `ui/components/SealHost.kt` —— `DefaultSeal` 按 `pending.totalSeconds > 0` 传 `autoClear`
- `ui/generate/GeneratorSheet.kt` —— 那句「复制出去的内容 15 秒后自动从剪贴板清除」在自动清除被关掉后会变成谎话，改成两种说法
- `ui/util/Format.kt` —— `Fmt.bytes` 增加 Long 重载（`File.length()` 是 Long），Int 那个委托给它
- `core/vault/VaultRepository.kt` —— 新增 `fileSizeBytes()`，关于页用它兑现「整个库就是一个文件」
- `ui/nav/VaultNavHost.kt` —— `Route.SETTINGS` / `Route.SETTINGS_ABOUT` 接真页面
- `ui/nav/Routes.kt` —— 写清 `SETTINGS_SECURITY` **这一步刻意没有注册**的原因

**已在等价实现上验证过的性质：** 档位表里没有的值被**插进去**而不是四舍五入（45 秒的库打开设置页不会变成 30 秒）、
负数归一成 0 且不多出第二个「立即」、远超上限的值照实显示、取档位不改动档位表本身、
同一个 `0` 在两张表里的文案绝不相同（自动锁定 = 立即，剪贴板 = 不自动清除，且不叫「关闭」「永不」）、
关掉剪贴板自动清除时的说明里必须写出手动清除在哪儿、中间几档一律不出说明、
备份副标题在「从未备份」和「有 N 条改动」时才是要紧的、都没问题时**只报事实不写「已是最新」**、
**权限清单只有一条且不含网络/存储字样**、降级到 PBKDF2 时关于页必须写出「降级」二字、
「没有的东西」清单里不出现「军工级」「绝对安全」这类不能核实的词、建库时间为 0 时写「未知」不写 1970 年。

**这一步能上机验证的：** 列表页右上角齿轮 → 真的设置页（不再是占位屏）；
「自动锁定」一排片子里当前那一档是黄铜色，右上角同时用文字写着它（一处看颜色、一处看字，重复是有意的）；
点「立即」，下面出现一句说明——**去导出一次备份，它不会被打断**（这一档以前是走不通的，见决策⑳）；
点「5 分钟」，说明换成那一档的代价；点中间几档，说明整句消失（不是变灰，是不占位）。
点完立刻生效也立刻落盘：切后台再回来，行为就是刚选的那一档；杀进程重进，选择还在。
**但列表页那条「有 N 条改动还没进备份」不会因为你改了设置而 +1**（决策(90)，对比收藏那条决策(52)）。
「剪贴板自动清除」选到最右边的「不自动清除」→ 说明出现；去详情页复制一个密码，
顶部那条**不再倒数、也不会自己消失**，写的是「密码 已复制 · 不会自动清除」，颜色从玉色转成黄铜色，
点「立即清除」才收；再打开生成器，底下那句话也跟着换成了「你关掉了剪贴板自动清除」。
「关于」里显示真实的 KDF 档位（Argon2 拉不到的机器上写「已降级」）、条目数和**库文件的真实字节数**
（加一条再回来看，那个数字会变大）；权限那一栏只有一条 `USE_BIOMETRIC`，
按它说的去系统设置里核对，应用信息里确实只有这一条。
底部「立即锁定」按一下当场回到解锁页（这次不是自动锁定，所以解锁页顶部不会有那句提示）。
**设置页上暂时没有「快捷解锁」和「修改主密码」两行**——见决策(96)。
（「快捷解锁」已由 M3-6b-1 补上，「修改主密码」已由 M3-6c-1 补上。）

### M3-6b-1 快捷解锁内核 · 指纹绑定 · 安全设置页 ✅
| 文件 | 作用 |
|---|---|
| `ui/settings/QuickUnlockModel.kt` | 绑定页内核：设备支持度的语义分类、开关该长什么样、绑定期的失败文案、设置主页那一行的副标题。**没有一行 `android.*`，也没有一行 Compose** |
| `ui/unlock/BiometricEnroll.kt` | 绑定侧接线：`beginBiometricEnrollment` → `CryptoObject` → `finishBiometricEnrollment`；作废旧钥匙的重建、半途失败的残留清理 |
| `ui/settings/SecuritySettingsScreen.kt` | 安全设置页（`Route.SETTINGS_SECURITY`）：指纹开关、失效状态的交代、「去系统设置录入指纹」 |
| `src/test/.../QuickUnlockModelTest.kt` | 27 个用例，纯 JVM 可跑 |

改动的既有文件：
- `ui/settings/SettingsScreen.kt` —— 「安全」分区里插入「快捷解锁」一行；新增 `onSecurity` 参数（**这个没有默认值**，见下）
- `ui/nav/VaultNavHost.kt` —— `Route.SETTINGS_SECURITY` 接真页面并接线 `onSecurity`
- `ui/nav/Routes.kt` —— 把「这里还没修路」改成路已经修通了，并写清它只在已解锁相位可达

**已在等价实现上验证过的性质：** 开关的位置永远等于「绑没绑过」而不是「现在能不能用」、
已绑定时开关一定能动（用户必须有办法把失效的绑定关掉）、
**凡是灰掉的开关一定配着一句解释**、一切正常时一句废话都不说、
「状态未知」和「暂时不可用」不灰开关（不替用户下连系统都没敢下的结论）、
「去录指纹」的出口只在录一枚指纹真能解决问题时才给（没硬件的机器上不给）、
**绑定期的文案和解锁期的文案没有一句是相同的**、绑定期的文案里绝不出现
「用主密码解锁」这类只在门外才成立的说法、绑定失效的说明里必须写出「不是故障」和「数据一条没动」
且不出现「丢失」「损坏」这种吓人又不准确的词、设置主页那一行不打分不劝导。

**这一步能上机验证的（需要真机，或在模拟器系统里先录一枚指纹）：**
设置页「安全」分区现在有第三行「快捷解锁」，副标题写着当前开着什么；
点进去是真的绑定页（`SETTINGS_SECURITY` 以前根本没注册）。
把「用指纹解锁」打开 → 弹系统指纹框，标题是「开启指纹解锁」而不是「解锁保险库」；
按完之后开关留在开着的位置，返回设置页那一行变成「已开启：指纹」。
**这时候杀掉进程重进，落到的是 PIN/指纹页而不是主密码页**——
M3-2c-2 写好的那条路，到今天才第一次不用手动造数据就能走通。
在指纹框上按「取消」：开关弹回去，**不出任何红字**（取消不是错误）。
把自动锁定调成「立即」再开一次指纹——这条以前是走不通的（指纹框一弹库就锁了，
按完指纹回来已经没有密钥可借），现在走得通，理由同导出备份（决策⑳）。
没录指纹的设备上开关是灰的，下面写着为什么灰，并且有一个「去系统设置录入指纹」；
去录完按返回键回来，**那个开关自己就亮了**（不用退出重进）。
已经绑好之后去系统设置里把指纹全删掉再回来：开关还是开着的（prefs 里确实还躺着一份），
副标题变成「绑定已失效」，下面是黄铜色的一段话——先说不是故障、再说数据一条没动、
最后给出「关掉它清干净、重新录了再开」。关掉它不弹任何确认框（可逆的小动作不配弹窗）。
**这一页上的 PIN 那一行由 M3-6b-2 补上。**

### M3-6b-2 PIN 设置流 ✅
| 文件 | 作用 |
|---|---|
| `ui/settings/PinSetupModel.kt` | PIN 内核：两步推进、两次比对、弱 PIN 的五种识别与各自的说法、安全设置页那一行。**没有一行 `android.*`，也没有一行 Compose** |
| `ui/settings/PinSetupScreen.kt` | 设置 / 修改 PIN 两步页：两个 `PinBuffer`、弱 PIN 弹窗、不一致退回第一步、写盘失败清残留 |
| `src/test/.../PinSetupModelTest.kt` | 35 个用例，纯 JVM 可跑 |

改动的既有文件：
- `ui/settings/SecuritySettingsScreen.kt` —— 「PIN」分区（开关 + 「修改 PIN」）；
  新增 `onSetupPin(change: Boolean)` 参数（**这个也没有默认值**，理由同 M3-6b-1 的 `onSecurity`）；
  底部那句「一项都没开」的条件改成两条都没开
- `ui/nav/Routes.kt` —— 新增 `SETTINGS_PIN`（带 `{change}` 参数）并写清**它凭什么可以带参数**
- `ui/nav/VaultNavHost.kt` —— `Route.SETTINGS_PIN` 接线；`SETTINGS_SECURITY` 补 `onSetupPin`

**已在等价实现上验证过的性质：** 五条弱 PIN 规则各自认得出该认的
（全同 / 连号含跨 9 环绕 / 两位三位循环 / 键盘直线斜线与常见串 / 四种日期排法），
没规律的六位数一个都不误伤、位数不对或含非数字时**不下结论**（那不是「强」）、
规则打架时报更具体的那个（`121212` 报循环不报常见，`102030` 报常见不报日期）、
**弱 PIN 的说法里不许出现「离线」「爆破」「拷走」**——那套风险模型对 PIN 根本不成立（决策⑥）、
五段说法的落点统一是「被猜到 / 被试中」且互不重样、
**文案函数的入参里根本没有 PIN**（于是弹窗里写不出那六位数）、
两步的标题副标题都不重样、差一位算不一致且**短的前缀绝不算对上**、
比对不改动传进来的两份缓冲、「修改 PIN」只在已经设过时才给。

**这一步能上机验证的：** 设置 → 快捷解锁 → 现在多出「PIN」一栏；
打开开关**不是就地生效**，而是跳进设置流（开关此刻不动，这是有意的）；
第一步输 `123456` 会弹「这个 PIN 是一串连号」，
主按钮是「换一个」、次按钮是「我知道，就用它」，点弹窗外面 = 什么都不做（那六位还在）；
输一个生日（`901231`）弹的是另一句话——说的是「认识你的人猜得到」，
**不是**建库页那句「文件被拷走就只剩它挡着离线爆破」（那句对 PIN 是假的）；
第二步故意按错一位 → 退回**第一步**、两份都清空、顶上一条红字说「请重新输入一遍」；
两次一致后按「完成」，回到快捷解锁页，那一行已经是「已开启 · 只在这台设备上」，
下面多出一个「修改 PIN」。
**这时候杀掉进程重进，落到的是 PIN 键盘页**——M3-2c-2 写的那条路，
到今天连指纹都不用录就能走通了。
「修改 PIN」**不问旧 PIN**（理由见决策(107)），设一个新的，
回来用新 PIN 解锁；再杀进程用旧 PIN 试，会被当成输错、进退避。
把开关关掉不弹任何确认框，关完那行变回「未开启」，「修改 PIN」跟着消失；
两个开关都关着时，页面底下才会出现「一项都没开也完全正常」那句话。
第二步按系统返回键 → **回到第一步**，不是退出（同新增 3 步流）。

### M3-6c-1 修改主密码 ✅
| 文件 | 作用 |
|---|---|
| `ui/settings/ChangeMasterModel.kt` | 内核：提交拦截的四种原因与先后、失败文案、指纹/PIN 会怎么样、改完之后的去处、设置页那一行。**没有一行 `android.*`，也没有一行 Compose** |
| `ui/settings/ChangeMasterController.kt` | 执行器：核对旧口令 → 重新校准 → 重新包裹落盘 → 更新会话文件头 → 记一笔修改时间；四阶段进度、异常归类、两份口令副本的清零 |
| `ui/settings/ChangeMasterScreen.kt` | 三框同屏的表单页 + 改完之后那张「现在去重新备份」的卡片 |
| `src/test/.../ChangeMasterModelTest.kt` | 27 个用例，纯 JVM 可跑 |
| `src/test/.../ChangeMasterControllerTest.kt` | 12 个用例，**跑的是真的库文件**（临时目录 + 真加解密，只把 KDF 换成廉价参数） |

改动的既有文件：
- `core/vault/VaultModel.kt` —— `VaultMeta` 新增 `masterChangedAt`（`@SerialName("mc")`，默认 0，老库读进来照样是 0）
- `core/vault/VaultRepository.kt` —— `changeMasterPassword` 改为**返回新文件头**，并新增收裸 `ByteArray` 的重载
  （给 `withVaultKey { }` 用，免得为了迁就签名把库主密钥多复制一份）；新增 `verifyMasterPassword`
- `core/session/VaultSession.kt` —— 新增 `onMasterPasswordChanged(header)`；
  文件头那一份从「私有字段 + 普通 getter」改成 `headerKdfParamsFlow: StateFlow`（理由见决策(115)）
- `MainActivity.kt` —— 封条改为订阅上面那个 flow，不再拿相位当 `remember` 的 key
- `ui/util/PasswordStrength.kt` —— 新增 `MASTER_MIN_LENGTH`（主密码硬下限，两处共用，见决策(117)）
- `ui/onboarding/CreateMasterScreen.kt` —— 私有的 `MIN_MASTER_LEN` 改指向上面那个常量；
  私有的 `MatchHint` 挪进 `ui/components/Fields.kt` 供两页共用
- `ui/components/Fields.kt` —— 新增公共组件 `MatchHint`
- `ui/settings/SettingsScreen.kt` —— 「安全」分区**末尾**插入「修改主密码」一行；
  新增 `onChangeMaster` 参数（**这个也没有默认值**，理由同 M3-6b-1 的 `onSecurity`）
- `ui/nav/Routes.kt` —— 新增 `SETTINGS_MASTER`（不带参数）
- `ui/nav/VaultNavHost.kt` —— 接线；成功页的「去备份」是 `navigate` 而不是 `popBackStack`

**已在等价实现上验证过的性质（控制器那 12 条跑的是真文件）：**
改完之后新口令能开、**旧口令真的开不了了**、数据一条不丢、
**库主密钥一个字节都没变**（拿改密码之前借出来的那把钥匙依然能 `openWithKey` 打开新文件——
这就是「指纹和 PIN 不用重新设置」那句话的全部依据）、
会话改完仍是解锁的（不把用户踢回解锁页）、会话里的文件头跟着换成新档位（封条不会显示旧的）、
`masterChangedAt` 记上了且大于 `lastBackupAt`（于是设置页那一行知道备份口令已经过期）、
旧口令输错时**文件一个字节都没动**且新口令没有「生效半个」、
成功和失败两条路上两份口令副本都被清零、已经改完之后再提交一次会被拒绝且照样清零、
会话锁着时什么都不会被改动。
文案那 27 条：拦截原因按「当前口令 → 长度 → 两遍一致 → 和旧的一样」的顺序报且只报一条、
**只有「和旧的是同一个」有话说**（前三条屏幕上各自已有表达）、
**四条失败文案每一条都必须写出「保险库没有被改动、原来的主密码依然有效」**且互不重样、
失败文案里不出现「损坏」「丢失」、提交前那条横幅同时说清「旧备份不跟着变」和「没有找回通道」
且不出现「建议」「定期」、**没绑快捷解锁时关于指纹/PIN 一个字都不说**、
绑了哪一项就只说哪一项、从没备份过的人不在成功页再被喊一遍备份、
`lastBackupAt == 0` 不算「备份口令过期」、改完立刻备份（两个时间戳相等）也不算过期。

**这一步能上机验证的：** 设置 → 安全分区最下面多出「修改主密码」一行，
新库上副标题是「从未修改过」。点进去是三个框同屏：当前主密码、新主密码、再输一次。
新的两遍打成一样但和旧的是同一个 → 按钮不亮，上面一条黄铜横幅说「这样改完，一切照旧」；
新的太弱 → 弹二次确认，主按钮是「改一个更强的」（同建库页，点弹窗外面 = 什么都不做）。
按下按钮后依次看到「正在核对当前主密码… → 正在测算本机能承受的加密强度… →
正在用新主密码重新封装保险库…」，这一页的等待比建库页长（两次派生），所以三句话都要有。
**故意把当前主密码输错**：出红条，写着「保险库没有被改动，原来的主密码依然有效」，
点「知道了」还能重来；**这一条不会进退避**——连错十次也不会被锁在门外（决策(113)）。
改成功后整屏换成一张卡片，主按钮是「现在重新导出备份」，
下面一行小字写着这次校准落到了哪一档，**同时顶部封条上的档位当场就变了**（不用退出重进）。
杀掉进程重进，用新主密码开门；用旧主密码试，开不了。
**已经绑了指纹或 PIN 的话，改完之后直接按指纹/输 PIN 就能开**——一个字都不用重设，
这一条是这一步最值得亲手验一遍的。
回到设置页，「修改主密码」那一行如果你之前备份过，现在是黄铜色的
「手上那份备份还认旧主密码」；按提示重新导出一份，它就变回「上次修改：刚刚」。
**「删除保险库」已由 M3-6c-2 补上。**

### M3-6c-2 删除保险库 ✅
| 文件 | 作用 |
|---|---|
| `ui/settings/DeleteVaultModel.kt` | 删库内核：事实清单、备份处境三分、跟着一起没的东西、覆写擦除的如实交代、弹窗与失败文案、设置页那一行。**没有一行 `android.*`，也没有一行 Compose** |
| `ui/settings/DeleteVaultController.kt` | 执行器 + `VaultRemnants` 接口：验口令 → 清残留 → 删文件 → 相位翻回 `NoVault`；异常归类带 `purged` 标记 |
| `ui/settings/QuickUnlockRemnants.kt` | `VaultRemnants` 的线上实现（Keystore / prefs / 剪贴板）。单独一个文件，同 `QuickUnlockGuard` 之于 `UnlockGuard` |
| `ui/settings/DeleteVaultScreen.kt` | 删除页：事实卡 → 备份状况 → 会没掉什么 → 擦除实话 → 主密码 → 危险按钮 → 确认弹窗 |
| `src/test/.../DeleteVaultModelTest.kt` | 31 个用例，纯 JVM 可跑 |
| `src/test/.../DeleteVaultControllerTest.kt` | 17 个用例，**跑的是真的库文件**（临时目录 + 真加解密，只把 KDF 换成廉价参数） |

改动的既有文件：
- `core/session/VaultSession.kt` —— 新增 `onVaultDeleted()`。终点是 `State.NoVault` 而**不是** `Locked`：
  翻到 Locked 的话，用户看到的是一张要他为一个已经不存在的库输入主密码的解锁页
- `ui/settings/SettingsScreen.kt` —— 页面最下方新增独立的「危险区」分区；
  新增 `onDelete` 参数（**这个也没有默认值**，理由同 M3-6b-1 的 `onSecurity`）
- `ui/nav/Routes.kt` —— 新增 `SETTINGS_DELETE`，并写清它**刻意**不挂在解锁图上的理由（决策(124)）
- `ui/nav/VaultNavHost.kt` —— 接线。**没有 `onDone` 回调**：删完之后相位翻回 NoVault，
  整张图连同这一页一起被换掉，那行代码永远执行不到

**已在等价实现上验证过的性质（控制器那 17 条跑的是真文件）：**
库文件和 `.bak` 上一版副本一起没了、相位翻回 `NoVault` 而不是 `Locked`、
库主密钥被擦掉（改完再 `withVaultKey` 会抛）、`lastLockReason` 归零、
**清残留发生在删文件之前**（用假清理器记录「被调那一刻文件还在不在」来钉死）、
剪贴板也清了一次、清残留抛异常不影响库被删掉、
口令不对时**文件一个字节都没动且快捷解锁一点没碰**、口令不对时会话仍是解锁的、
两条路上口令副本都被清零、已经删完之后再提交一次会被拒绝且照样清零、
文件删不掉时报 `FilesRemain` 且库确实还在（该用例在 root 环境下自动跳过而不是给假通过）。
文案那 31 条：事实清单只有数量/大小/时间、建库时间为 0 时写「未知」不写 1970 年、
备份三分与列表页提醒条同口径、**Fresh 档也不说「可以放心删」**、
没绑快捷解锁时一个字不提指纹和 PIN、清单里写出安全芯片那把钥匙也会一起删、
擦除说明里不出现「粉碎/彻底销毁/军工级/安全擦除」且必须写出「全盘加密」与「磨损均衡」、
弹窗正文里只有条数（**函数签名里根本收不到条目**）、主按钮写「永久删除」而不是「确定」、
三条失败文案每条都写出「保险库还在」且互不重样、不出现「损坏/丢失/崩溃」、
整页任何一句话都不出现「可恢复/30 天/撤销删除/找回」。

**这一步能上机验证的：** 设置页拉到最底下，「立即锁定」下面多出一个独立的「危险区」，
里面只有一行红字「删除保险库」，副标题写着「连同快捷解锁一起清空，无法恢复」——
**它永远不会变成黄铜色**（对比备份和改主密码那两行，理由见决策(123)）。
点进去第一屏是三行事实：条目数、库文件的**真实字节数**、建库时间；
下面那条横幅按你的备份处境变三种说法——从没备份过是红的、备份后又改过几条也是红的
（写明到底几条没进备份）、备份是最新的则是中性色，而且**那一档也不写「可以放心删」**，
写的是「那份文件现在还在你手上吗？它的主密码你还记得吗？」。
再往下是「删掉之后」那张清单（绑了指纹/PIN 才会出现第三条，会写明安全芯片里那把钥匙也一起删）、
「导出到别处的备份不受影响」、以及关于覆写擦除的实话。
主密码框**不自动聚焦、不弹键盘**——这一页进来第一件事是读字不是打字。
框是空的时候下面写着「指纹和 PIN 在这一步不算数：它们能开门，但证明不了现在拿着手机的是你」，
一开始输入这句话就让位。**这一页没有「请抄写『删除保险库』」那一步**，见决策(119)。
按红色按钮弹最后一道确认框，**点弹窗外面关不掉**（`danger = true`），
正文里只有条数、没有任何一条条目的名称；主按钮写的是「永久删除」不是「确定」。
按下去依次看到「正在核对主密码… → 正在清除指纹 / PIN 的绑定与剪贴板… → 正在删除保险库文件…」，
然后整个应用当场变回**欢迎页**——那一屏就是回执，不另弹「删除成功」（决策(122)）。
**故意把主密码输错**：出红条，写着「保险库没有被删除，也没有任何东西被改动」，
点「知道了」还能重来；这一条同样**不进退避**（同决策(113)）。
最值得亲手验一遍的两条：
① 删之前先绑好指纹和 PIN，删完新建一个库，**上一个库的指纹/PIN 一个都不在了**，
   安全设置页两个开关都是「未开启」，而且新库第一次解锁**不会带着上一个库的失败计数**；
② 删之前先从详情页复制一个密码让倒计时跑着，删完之后去别处粘贴——粘不出东西了。
**「我忘了主密码，想重来」那条路这一步刻意没做**，见决策(124) 与待办里的 M3-6c-3。

---

### M3-6c-3a 重来内核 ✅
| 文件 | 作用 |
|---|---|
| `ui/unlock/ResetVaultModel.kt` | 重来内核：两道门槛（抄写短语 + 按住三秒）、四段实话、会没什么／不会没什么、失败文案、解锁页那个弹窗的落点文案。**没有一行 `android.*`，也没有一行 Compose** |
| `ui/unlock/ResetVaultController.kt` | 执行器：清残留 → 删文件 → 相位从 `Locked` 翻回 `NoVault`。`submit()` **不收任何参数** |
| `src/test/.../ResetVaultModelTest.kt` | 40 个用例，纯 JVM 可跑 |
| `src/test/.../ResetVaultControllerTest.kt` | 14 个用例，**跑的是真的库文件**（临时目录 + 真加解密，只把 KDF 换成廉价参数） |

改动的既有文件：**一个都没有。** 这一步只加了两个新文件和两个新测试文件，
既有代码一个字节没动——`VaultRemnants`（M3-6c-2 留的接口）和
`DeleteVaultModel.ERASURE_NOTE` 都是直接引用过来的，不是复制。
它放在 `ui/unlock/` 而不是 `ui/settings/`：这一页只在**解锁相位**可达，
和它做的事像不像设置页没关系，和它站在哪张图上有关系（决策⑪）。

**已在等价实现上验证过的性质（控制器那 14 条跑的是真文件）：**
库文件和 `.bak` 上一版副本一起没了、相位从 `Locked` 翻到 `NoVault` 而不是停在 `Locked`、
`lastLockReason` 归零、**清残留发生在删文件之前**（用假清理器记录「被调那一刻文件还在不在」钉死）、
剪贴板也清了一次、清残留抛异常不影响库被清掉、
**把库文件写成一团垃圾字节照样清得掉**（全程一次都没打开过库，这是这一页存在的另一半理由）、
库文件本来就不在了也算清干净而不报错、已经清完之后再按一次会被拒绝、
文件删不掉时报 `FilesRemain` 且库确实还在、失败之后能再来一次
（后两条在 root 环境下自动 `Assume` 跳过而不是给假通过）。
文案那 40 条：抄写一字不差才算数、空白和结尾句号宽容但中间标点和繁体不放行、
抄的那一句里没有「删除／清空」这类命令词、按住是 3000ms 且过程中一直报剩余秒数、
剩余归零时不显示「0 秒」、抄错时按钮是灰的（不是按了给一句「抄错了」）、
两个问句真的是问句、没绑快捷解锁时一个字不提指纹和 PIN、
**擦除说明是删除页那一份的引用（`assertSame`）而不是抄的第二份**、
两条失败文案都写出「还在这台设备上」并各自跟上一句「还能怎么办」、
**没有「输错口令」那一支**、整页不出现「联系客服／稍后重试／破解／找回主密码」、
不出现「可恢复／撤销／回收站」、不出现「成功」、不出现「谨慎操作／三思」。

**这一步能上机验证的：什么都验不了。** 入口（解锁页那个弹窗的次按钮）、
页面、导航接线全在 M3-6c-3b。先交内核是因为这一页的全部风险都在
「门槛松紧」和「话怎么说」上，而那两样恰好是纯 JVM 能钉死的部分。

### M3-6c-3b 重来页与入口 ✅
| 文件 | 作用 |
|---|---|
| `ui/components/HoldProgress.kt` | 「按住不放 N 毫秒」的计时账本：进度、剩余、**完成只报一次**、松手即作废、时钟倒退的兜底。**没有一行 `android.*`，也没有一行 Compose**，时间由调用方传进来 |
| `ui/components/HoldButton.kt` | 长按按钮：`pointerInput` + `awaitEachGesture`，抬手或被父滚动抢走都中止；进度从左往右长满整个按钮 |
| `ui/unlock/ResetVaultScreen.kt` | 清空重来页（`Route.RESET`）：坏消息 → 两个问句 → 会没什么 → 抄写 → 按住三秒 |
| `src/test/.../HoldProgressTest.kt` | 14 个用例，纯 JVM 可跑 |

改动的既有文件：
- `ui/unlock/UnlockMasterScreen.kt` —— 「忘记主密码了？」弹窗补上次按钮与那一段落点文案；
  新增 `onReset` 参数，**刻意没给默认值**（理由同 `SettingsScreen.onSecurity`）
- `ui/nav/Routes.kt` —— 新增 `Route.RESET` 并写清它凭什么只在解锁相位注册；
  `SETTINGS_DELETE` 那段「留给 M3-6c-3」改成「那条路走的是 RESET」
- `ui/nav/VaultNavHost.kt` —— `LockedGraph` 里注册 `Route.RESET`，接线 `onReset`

**已在等价实现上验证过的性质（`HoldProgress` 那 14 条）：** 没按着时进度为 0、剩余是全程，
且怎么 tick 都不触发；差一毫秒不算数、正好到点算数；**越过终点之后每一帧都不再报**
（用 16ms 一帧走满五秒钉死——漏掉这条 `submit()` 会被连着调几十次）；
到点后剩余停在 0 不减成负数；**松手是中止不是暂停**（按到还差 100ms 松手，
再按 200ms 依旧不算数）；完成之后再按仍要重新按满；时钟倒退时进度不为负也不触发；
按住全程按钮上的秒数正好是 3 → 2 → 1，**不出现 0**（这一条钉的是
`HoldProgress.remaining` 和 `ResetVaultModel.holdLabel` 之间的接缝，两边各自都测过）。

**这一步能上机验证的：** 解锁页点「忘记主密码了？」——那个弹窗现在多了一句下文
和一个次按钮「清空这一份，从头开始」；**主按钮仍然是「我再想想」，也仍然不是红的**
（红色跟着危险动作走，不跟着弹窗走）；点弹窗外面的空白只会关掉弹窗，
绝不会走到清空那一页去（决策⑮ 把次按钮和取消手势拆成两个回调，这一条现在第一次派上用场）。
点次按钮进到真的清空页：第一屏是坏消息（没有找回通道、唯一的生路是备份文件 + 当时那个主密码），
接着是两个我们答不上来、只能还给用户的问句，再往下是「清空之后」那张清单——
**没绑指纹 / PIN 的机器上不会出现那一条**；玉色那一格是全屏唯一的好消息
（导到别处的备份不受影响）。
抄写框里随便打点什么，按钮是灰的、也**不会跳出「抄错了」**；一字不差抄完
（结尾多一个句号、中间被输入法带出空格都放行，繁体不放行），按钮亮成铁锈色。
按住它：填充从左往右长，字变成「继续按住…3 / 2 / 1」；**中途松手当场归零**，
再按是从头数，不是接着数；按着不动往上滑（想回去看上面那几段字）也等于松手。
按满三秒抬手之前就已经开始执行了，屏幕上依次闪过「正在清除指纹 / PIN 的绑定与剪贴板…」
「正在删除保险库文件…」，然后**整个应用变回欢迎页**——没有「清空成功」四个字，
欢迎页本身就是回执（决策(122)）。执行途中按返回键不动（同删除页）。
从清空页按返回键回到解锁页，什么都没发生。

**接完这一步之后，见决策(132)：M5 之前不要出内测包。**
这一页把用户指向「你导出到别处的备份还在，清空之后就是拿它们回来的时候」，
而拿回来那条路（欢迎页上的 `Route.RESTORE`）至今还是 `Stub("从备份恢复", "M5 迁移")`。
话没有撒谎，但让一个刚清空完的人卡在占位屏上是不能接受的。

### M5-1a 恢复内核 ✅
| 文件 | 作用 |
|---|---|
| `ui/restore/RestoreModel.kt` | 恢复内核：认文件（四类）、文件头事实、提交拦截、三句进度、八条失败文案。**没有一行 `android.*`，也没有一行 Compose** |
| `ui/restore/RestoreController.kt` | 执行器 + `ImportSource` 接口：读文件 → 认头 → 验口令（只派生一次）→ 原样落盘 → 会话接管 → 记一笔备份 |
| `src/test/.../RestoreModelTest.kt` | 37 个用例，纯 JVM 可跑 |
| `src/test/.../RestoreControllerTest.kt` | 19 个用例，**跑的是真的库文件**（临时目录 + 真加解密，只把 KDF 换成廉价参数） |

改动的既有文件：
- `core/vault/VaultFile.kt` —— `VaultFormatException` 改成 `open`，分出两个子类
  `VaultNotRecognizedException`（不是我们的文件 / 短得装不下文件头）和
  `VaultTooNewException(fileFormatVersion)`。**行为一个字没变**，抛出的仍然是
  `VaultFormatException`，既有的 `catch` 一处都不用改
- `core/vault/VaultRepository.kt` —— `restoreFrom(bytes, password)` 换成
  `restoreAndOpen(bytes, password, onVerified)`：返回 `Opened`（少派生一次）、
  拒绝覆盖已有的库、落盘后逐字节读回比对。旧签名**没有任何调用点**，直接删掉

**已在等价实现上验证过的性质（控制器那 19 条跑的是真文件）：**
装进磁盘的就是那份备份文件本身（`contentEquals` 钉着）、不会拿本机默认档位重新封装
（封条显示的是这份文件当年那台设备定下的档位）、恢复完会话直接是解锁的且数据一条不少、
恢复完能用同一个主密码重新解锁、**记了一笔 `lastBackupAt`**（否则刚装完机就被首次备份关卡挡住）、
口令不对 / 选错文件 / 版本太新 / 密文被改坏这四条路上**这台设备上都没有留下半个库**、
口令错了之后不用重新选文件就能再来一次、已经有库时拒绝恢复且**现有的库一个字节没动**、
成功之后那份文件从内存里清掉、口令验过之后那个回调抛异常不影响恢复、
三条路（成功 / 失败 / 没选文件就被拒绝）上口令副本都被清零。
文案那 37 条：三种坏文件分成三类且各自给出不同的下一步、扩展名完全不参与判断、
认文件不改动传进来的字节、事实只有文件头里读得出来的四行且**绝不出现条目数**、
主动交代为什么数不出条目数、「会怎样」清单必须写明**指纹和 PIN 不会跟着过来**、
拦截理由按严重程度只报一条且「已经有库」排最前、
**八条失败文案每一条都写出「你手上那份文件没有被改动」**且互不重样、
口令错那条不许说文件坏了也不许劝人换备份、版本太新那条绝不劝人拿更早的备份将就、
算法不支持那条要同时撇清文件和主密码、整页不出现「找回 / 破解 / 客服 / 稍后重试 / 军工级」。

**这一步能上机验证的：什么都验不了。** 入口（欢迎页那个「从备份恢复」）、
页面、SAF 选文件、导航接线全在 M5-1b。先交内核的理由同 M3-6c-3a：
这一页的全部风险在「认文件」和「话怎么说」上，而那两样恰好是纯 JVM 能钉死的部分。

### M5-1b 恢复页与入口 ✅
| 文件 | 作用 |
|---|---|
| `ui/restore/SafImportSource.kt` | `ImportSource` 的 SAF 实现：`ACTION_OPEN_DOCUMENT` 拿到的 `Uri` → 文件名 + 字节。**只有 read，没有 write** |
| `ui/restore/RestoreScreen.kt` | 恢复页：选文件 → 事实卡 → 为什么数不出条目数 → 会怎样 → 主密码 → 恢复；三句进度、失败横幅、灰按钮的解释 |

改动的既有文件：
- `ui/nav/VaultNavHost.kt` —— `Route.RESTORE` 从 `Stub("从备份恢复", "M5 迁移")`
  换成真页面；`RestoreController` 挂在**引导图**这一层（同 `CreateVaultController`）。
  **顺手把 `Stub` 这个函数整个删掉了**——它是工程里最后一个占位屏，
  留着就是一个没人调用的私有函数（连带十二个只为它存在的 import）
- `ui/nav/Routes.kt` —— `RESTORE` 的注释从「M5 迁移模块交付」换成它真正的约束
  （只注册引导图、不带任何参数、那一整个库为什么不能进路由）
- 欢迎页 `WelcomeScreen` **一个字没改**：`onRestore` 从 M3-2a 起就一直在那儿等着接线

**排版顺序是有理由的：先选文件，后输主密码。** 主密码只对某一份具体的文件有意义
（`PASSWORD_NOTE`：认的是导出那一刻那个），反过来先让人输密码，
等于请他先凭空回忆一个口令，再去找那个决定该回忆哪一个的口令的文件。

**选择器刻意不按 MIME 过滤**（`arrayOf("*/*")`，决策㉒在界面这一侧）：
导出用的是 `application/octet-stream`，某些 ROM 会存成 `.lvault.bin`，
用户自己改过名的更是常事。填一个具体 MIME，那些文件在选择器里就会变灰点不动——
一个把备份改了个名字的人会得到「我的备份不见了」这个结论，而文件好端端躺在那儿。
代价是什么都点得到，于是「点错了」成了这条路上最常见的失误，
那正是 `Probe.NotVaultFile` 单独分一类、单独给一句话的原因。

**这一页没有的东西**：没有退避倒计时（这台设备上还没有库，没有门可守，
`RETRY_NOTE` 把这件事明说了）；没有「恢复成功」页（`session.adopt` 一执行相位就翻，
整棵引导子树连同这一页被换成保险库列表，那一屏就是回执，而且比任何一句
「恢复成功」都硬——他的条目都在上面）；**没有 `beginSystemInterlude()`**
（照导出页抄一行过来的话，那是一行永远不生效的代码：这一页处在 `NoVault` 相位，
根本没有自动锁定这回事）。

**这一步能上机验证的（第一次有了，而且是整条主干）：**
欢迎页「我已有 .lvault 备份文件」→ 选文件 → 事实卡显示的档位是**那份文件当年那台设备**的
→ 输对主密码 → 列表页上条目一条不少 → 杀进程重进是解锁页 → 同一个主密码能开门
→ **不会被首次备份那道关卡挡住**（`lastBackupAt` 记过了）。
反面那几条也全都能在真机上走一遍：选个 jpg / 选个改坏的备份 / 输错口令 /
在恢复途中按返回（按不动）/ 恢复完去设置里看指纹和 PIN（确实没跟过来，
而这件事在页面上提前说过）。

**决策(132) 那条「M5 之前不出内测包」到此解除**：全工程再没有一个占位屏，
每一个点得动的入口后面都是一张真页面。

---

### M5-2a-1 CSV 解析内核 ✅
| 文件 | 作用 |
|---|---|
| `ui/importer/CsvText.kt` | 第一层：字节 → 文本。BOM（UTF-8 / UTF-16 LE / BE / UTF-32 单独报）、无 BOM 时严格 UTF-8 → 严格 GBK、二进制拦截、16 MiB 上限、四条失败文案。**没有一行 `android.*`，也没有一行 Compose** |
| `ui/importer/CsvParser.kt` | 第二层：文本 → 一张表。RFC 4180 状态机（引号 / `""` / 引号里的换行 / CRLF·LF·CR）、分隔符猜测（逗号·分号·制表符）、参差行、六类记账、六类失败文案。同样没有一行 `android.*` |
| `src/test/.../CsvTextTest.kt` | 25 个用例，纯 JVM 可跑 |
| `src/test/.../CsvParserTest.kt` | 51 个用例，纯 JVM 可跑 |

**既有文件一个字节都没动。** 这一步只加了两个新文件和两个新测试文件。

**为什么先交这一半：** 这一层的全部风险都在纯 JVM 能钉死的地方——
一台按逗号 split 的解析器会把 `"a,b"` 切成两半，**而且不报错**；
一次编码猜错会让「名称」变成「鍚嶇О」，**同样不报错**。
这两种失败都会一路走到加密写盘，用户几个月后登录失败时才发现，那时源文件早删了。
列名映射和判重（M5-2a-2）建在这上面，页面（M5-2b）建在那上面。

**已用等价实现验证过的性质（解析器那 51 条）：**
引号里的逗号 / 分号不是分隔符、`""` 是一个引号、引号里的换行留在格子里、
引号里的 CRLF 归一成 LF、三种行尾都认、末尾没换行也认、空单元格保留位置；
分隔符按**第一行**的多数派猜且引号里的不参与统计（分号那一条是中文区 Excel 的默认导出）；
短行补空、长行进 `overflow` **一个字都不丢**、每一行的格数永远等于表头列数；
空行跳过但**文件末尾多敲的回车不记账**；引号开了没关 / 结束引号后面还有字 /
引号前有空格这三种不合规都是「读进来 + 记账」，**都不改数据**；
行号照算引号里的换行（用户拿它去源文件里定位）；
`Row` / `Table` / 失败对象 / 失败文案**四处都不吐单元格内容**；
六类失败文案互不重样、都不出现「稍后重试 / 联系客服 / 已导入部分」、
单格超长那条必须说清「整份都没有导入」。
文本层那 25 条：BOM 被剥掉（留着的话第一列列名会以一个看不见的字符开头，
然后列映射永远对不上——一个极难自查的失败）、GBK 中文不会被当成 UTF-8 解出乱码、
UTF-32 的 BOM 不会被当成 UTF-16（前两个字节一模一样）、
含 NUL 的直接判成不是文本、**两百份随机二进制没有一份蒙混过关**、
空文件和「不是文本」分成两类、四条失败文案各自指向一个不同的下一步。

**这一步能上机验证的：什么都验不了。** 入口、SAF 选文件、列映射、预览、写库全在后面两步。
先交内核的理由同 M3-6c-3a 和 M5-1a。

---

### M5-2a-2① 列名映射内核 ✅
| 文件 | 作用 |
|---|---|
| `ui/importer/CsvMapping.kt` | 第三层：一张表 → **哪一列是什么**。九种列角色、七家导出的真实表头、列名归一、精确表 + 受限宽松匹配 + **排除表**、格式识别、表头行认定、重复角色处置、手工改（`withRole`）、两条拦截、五条记账文案。**没有一行 `android.*`，也没有一行 Compose** |
| `src/test/.../CsvMappingTest.kt` | 55 个用例，纯 JVM 可跑 |

**既有文件一个字节都没动。** 这一步只加了一个新文件和一个新测试文件。

**为什么把 M5-2a-2 切成两半：** 原计划里「列名映射 + 行→`VaultEntry` + 判重 + 三种处置」
是一步，但这四件事的失败方式完全不同——前者错在**猜**（猜错哪一列是密码），
后者错在**算**（判重算宽了会静默丢条目，算窄了会导出一堆重复）。
放在一起写，测试就会互相遮掩：判重用例里那张表的列映射是手工写死的，
于是映射本身错没错，一条用例都没在管。所以①只管「哪一列是什么」，
②（下一步）只管「这一行变成什么、和库里哪条撞了、撞了怎么办」。

**这一层为什么值得单独钉：** 因为猜错哪一列是密码**不会报错**。
1Password 的导出里有一列 `Password Hint`（密码提示），
一台按「列名里带 password 就是密码」写的映射器会把提示语当成密码导进去——
用户的真密码丢了，而那条提示语在保险库里长得和一条正常密码一模一样，
事后没有任何办法分辨，源文件那时多半已经按我们自己的提示删掉了。
Firefox 的 `timePasswordChanged` 会变成一串时间戳，同理。
所以自动映射分两遍走：**整列名精确匹配**（七家的真实表头都在表里，逐条带出处注释），
剩下的列才做宽松的片段匹配，而宽松匹配前面挡着一张排除表
（`hint / 提示 / 强度 / history / changed / time / guid / fields …`）——
中了排除词的列**永远不参与自动映射**，宁可让用户在 M5-2b 那一页手点一下，
点之前他看得见列名。

**已用等价实现验证过的性质（那 55 条）：**
Chrome / Bitwarden / 1Password / LastPass / KeePass / Firefox / 中文列名七套真实表头逐列钉死；
`Login URI`、`login_uri`、`login-uri` 三种写法归到同一个键（`normalizeName` 只留字母数字汉字），
`帐号` 那个异体字和 `用户名（登录）` 那种带括号的写法都认；
**密码提示 / 密码修改时间 / 确认密码 / 密码强度 / 密码历史五条都不会被当成密码**，
而真密码列就在提示列旁边时照样认得出来；
Bitwarden 的 `fields` 和 `reprompt` 一列都不碰（前者结构完全不同，硬塞进备注就是往用户库里倒垃圾）；
一个角色最多占一列、靠前的赢、重复会记账；
`withRole` 是不可变的（原方案不变）、把角色挪到别的列时**原来那列自动让位**
（漏了这一条的表现是两列都往密码里写、后写的赢、而预览上显示的是先写的那一列）、
改成同一个角色返回自己、越界的列号不抛异常（这个入口接的是界面事件，崩在这里没有好处）、
**改动之后记账要重算**（用户解决了重复就别再说重复）；
没有密码列 / 名称和网址都没有各拦一条，文案都不出现「稍后重试 / 联系客服」；
没有表头的文件认得出来（`://`、`@`、超长、纯数字）且**认出来时一列都不许猜**，
真表头不会被误判，认出了任何一列列名就不再怀疑第一行；
五条记账文案互不重样、按枚举声明顺序输出（界面措辞稳定）；
`Plan.toString()` 和 `summary()` **都不吐 header**——没有表头的文件里那一行是真实数据，
密码可能就在里面。

**这一步能上机验证的：什么都验不了。** 页面在 M5-2b。先交内核的理由同 M5-2a-1。

---

### M5-2a-2② 行 → 条目与判重 ✅
| 文件 | 作用 |
|---|---|
| `ui/importer/CsvImport.kt` | 第四层：一行 → 一条条目，以及它和库里已有条目的关系。四条跳过理由、七条记账、三档判重、源文件内判重、三种处置、**覆盖时的合并规则**、四条文案入口。**没有一行 `android.*`，也没有一行 Compose** |
| `src/test/.../CsvImportTest.kt` | 66 个用例，纯 JVM 可跑，其中大半是直接从一段 CSV 文本走完 文本 → 表 → 映射 → 候选 → 处置 整条链路 |

**既有文件一个字节都没动。** 这一步只加了一个新文件和一个新测试文件。

**判重为什么不给「是 / 不是」：** 因为两种错法都是静默的，方向还相反。
算宽了（把不同的条目当成同一条）→ 用户点「覆盖」，一条好好的旧密码被别的站点的密码盖掉，
没有备份、没有回收站、屏幕上什么都不报；算窄了（同一条没认出来）→ 库里出现两条「微信」，
哪条是新的看不出来，改密码时改了不常用的那条，下次登录失败。
所以这里只给**三档强度**（同名同账号 / 同网站同账号 / 只同名），
每一档在 M5-2b 上都会明说「凭什么算撞了」，处置由用户选，默认是最不会毁数据的「跳过」。

**规则不写第二份：** 行 → 条目走的是 `EntryForm` 那一套（`domainLines` 切行去重、
`newEntry` 造条目，前者内部复用 `VaultIndex.normalizeDomain`），id 和三个时间戳一律留空交给
`VaultSession.addEntry`。这不是为了少写几行——规则一旦分叉，同一个库里就会有两种数据：
导入进来的条目网址带着 `https://`、手工新增的不带，将来 M4 自动填充按哪一份匹配都对不齐。

**「没有密码那一列的行怎么办」定下来了（决策(149)）：** 有名字有账号、只是没密码的行**照样导入**并记一笔账。
理由是这两种错误的代价不对称：多导一条垃圾用户删得掉，少导一条真数据他发现不了。
只有「账号和密码都空」（那多半是源文件里的分组行）、「名称和网址都空」、
「整行都空」、「类型列明说这不是登录记录」这四种才跳过。

**已用等价实现验证过的性质（那 66 条）：**
密码不 trim 而其它字段 trim（空格可以是密码的一部分）、密码里的逗号一路活到条目上、
网址原样保留不被归一改写但多个网址按归一去重、没名称时用主机名当名字；
Bitwarden 的安全笔记 / 银行卡 / 身份行跳过而 `login` 行照导、
**类型列是不认识的值时不跳过**（拿不准的一律导）；常用列认得出五种写法；
动态验证码密钥**原样存不解析**（这版本还不会算验证码，现在解错没人会发现）；
源文件内同名同账号第二条记账但照样导入（替用户合并等于替他做主），
而**两条都没账号不算重复**（否则文件里所有无账号的行会互相撞成一片）；
三档判重各自钉死、账号都为空不算同账号、取最强的一档且与库里先后无关、同强度取靠前那条、
被跳过的行不参与判重；三种处置各自钉死、没撞上的行三种处置都照样新增、默认是「跳过」；
**覆盖时空的不覆盖**——空分类、空备注、空密码、空网址都不会清掉已有内容，
网址两边合并去重且写法留旧的，收藏和 `createdAt` 不丢，
旧备注非空时新备注不写进去也不拼接（拼接会在反复导入之后攒出一堆重复的话）；
覆盖对象在预览期间被别处删掉时当成新增；
**被处置跳过的行不记它的账**（它没导入，说「有条目没有密码」是误导）；
跳过分「按行」和「按处置」两种分别计数、按理由归并；
`Candidate` / `Hit` / `Outcome` 的 `toString`、`hitNote`、`summary` **四处都不吐内容**，
只带行号和条数；四组文案互不重样、按枚举声明顺序输出。

**这一步能上机验证的：还是什么都验不了。** 下一步 M5-2b 那一页把这些摆出来，
落盘则要在会话层补一个批量入口（一条一条 `addEntry` 会把 500 条导入变成 500 次加密写盘）。

---

### M5-2b-1 批量落盘入口 · 导入控制器内核 ✅
| 文件 | 作用 |
|---|---|
| `core/session/VaultSession.kt` | **改了这一个既有文件**，只加了一个方法 `importEntries(add, replace)`：一次 `mutate`、一次加密、一次写盘 |
| `ui/importer/ImportController.kt` | 第五层：把前四层串成一条用户走得完的路（选文件 → 解码 → 解析 → 认列 → 预览 → 落盘），并且是**唯一**碰 `VaultSession` 的那一层。没有一行 `android.*` |
| `src/test/.../ImportControllerTest.kt` | 32 个用例，纯 JVM 可跑，**跑的是真的库文件**（临时目录 + 真的加解密 + 真的落盘），只把 KDF 换成廉价参数 |

**为什么先切出这一半：** M5-2b 原本是一整块「导入页」，但里面有两件性质完全不同的事——
一件是「500 条怎么写进磁盘」（错了会毁数据，且**在真机上看不出来**），
一件是「列映射那一屏长什么样」（错了用户当场就看见）。
前者独立成一步，好处是它能被 32 条用例钉死；混在一起做，那些性质就只能靠肉眼在真机上看。

**批量入口解决的是一条会毁数据的路（决策(151)）：**
`mutate` 的规矩是「改了就一定存了」，代价是每调一次就把整个库序列化、加密、原子写盘、
再读回来验一遍。平时一次改一条看不见这个代价；导一份 500 条的 CSV 就是 500 次全库重写——
几百 KB 的库要写掉上百 MB，几十秒起步，中途一直握着明文表。
更糟的是它**不是原子的**：第 317 条上磁盘满了，用户得到的是一个导进去 316 条的库，
而屏幕上写着「导入失败」；他重来一次，那 316 条又会撞上判重。
走一次 `mutate` 之后，要么 500 条全在，要么一条都没进、内存也回滚了。

**落盘前以当下的库重算一遍（决策(152)）：** 预览摆在屏幕上的时间是不确定的——
用户会去翻源文件核对，会切出去，会在别的页面上删掉一条正好撞上的条目。
拿旧快照落盘的话，「覆盖 3 条」里那 3 条指的可能已经不是他看到的那 3 条。
重算的代价是几十毫秒，换的是「屏幕上那句话和磁盘上的结果说的是同一件事」。
`CsvImport.apply` 里那条「覆盖对象不见了就当新增」处理的正是这中间的缝，
`VaultSession.importEntries` 里同一个判断的下半段**保留它原来的 id**。

**改列映射和改处置走两条不同的路（决策(153)）：**
改处置只影响 `CsvImport.apply`（O(n) 纯内存），`outcome` 是个 getter，下一帧就是新的；
改列映射要重算 `convert` + `against`（O(行数 × 库条目数)），扔到工作线程上，
并且**后一次取消前一次**——用户逐列点过去时，中间那几份结果没有人要。

**失败分三种，因为按钮不一样（决策(154)）：** 文件本身不行的（不是文本、空的、太大、没有数据行）
→「换一个文件」，同一份文件重试一百次都一样；写盘失败的 →「再试一次」，
源文件还在内存里，而且磁盘上什么都没变；库在中途锁上的 →「回去解锁」。
按错按钮的代价是用户拿同一份文件反复重试同一个必然失败。

**已用等价实现验证过的性质（那 32 条）：**
空清单不落盘也不算失败、未解锁时拒绝；新增补 id 与三个时间戳、没有密码时 `passwordUpdatedAt` 是 0、
**一次导入的所有条目共用同一个时间戳**（时钟每读一次就走，于是「只走了一次 mutate」是可观测的，
对照组是一条一条 `addEntry`）、真的落盘（锁定后重新解锁还在）、500 条一次导入且 id 互不相同；
覆盖是就地替换位置不动、`createdAt` 留旧的、密码变了才刷新 `passwordUpdatedAt`、
覆盖对象被删了就当新增且**保留原 id**、新增追加在末尾且保持给进来的顺序；
控制器：选中 Chrome 导出后进预览（表头 4 列 / 3 行 / 可以导）、二进制和空文件和只有表头的
各自被挡住**且说的是前面那一层自己的那句话**、读文件抛异常时说的是「读不下来」而不是「格式不对」、
没有密码列时拦着不让导、改列角色后候选跟着变**且原来占着那个角色的列被自动清空**、
全清之后导不了而「恢复自动识别」又能导；撞上一条时三种处置的结果各不相同、默认是跳过、
**全都撞上而处置是跳过时导入按钮是灰的**（一次什么都不做的成功比灰按钮更让人困惑）；
导入成功后报告数字和库里的东西对得上、密码里的逗号一路活到库里、
**覆盖时空的不覆盖端到端也成立**（CSV 没有分类列 ≠ 请清空分类）、
报告一定带着删源文件那句话（`CsvText.PLAINTEXT_NOTE`）、导完明文表就丢掉了（再点一次不会重复写）、
落盘前重算（预览后那条被删 → 报告说的是新增 3 条）、库中途锁上时一条都没进去；
`discard` 把这一份文件的一切都清掉、文件不行时关掉提示等于回到还没选文件（连文件名一起清）；
`Report` / `Plan` 的 `toString` 和那几条记账文案**都不吐任何一格的内容**。

**这一步能上机验证的：还是什么都验不了。** 但从这一步起，
「一份 CSV 从字节走到磁盘上的库」这条链路是完整且被测过的——
M5-2b-2 只剩下把它摆到屏幕上。

---

### M5-2b-2 CSV 导入页 ✅
| 文件 | 作用 |
|---|---|
| `ui/importer/ImportScreen.kt` | 一页四段：先说明文 → 选文件 → 逐列核对映射 → 预览与处置 → 结果 |
| `ui/importer/ImportPieces.kt` | 只有这一页用得到的零件：列映射行、角色选择弹窗、处置三选一、判重清单、计数行 |
| `ui/nav/Routes.kt` | 加 `SETTINGS_IMPORT`（**只挂已解锁图**，和 `RESTORE` 正好反过来）|
| `ui/nav/VaultNavHost.kt` | 控制器挂在图这一层 + 注册这一页 + 设置页多一个回调 |
| `ui/settings/SettingsScreen.kt` | 「备份」那一格里多一行「从 CSV 导入」，多一个参数 |

**M5-2b 到此收尾，CSV 导入这条路整条通了**：设置 → 选文件 → 认列 → 预览 → 落盘 → 结果。

**「这是明文」这句话说两遍，第一遍在最前面（决策(155)）：**
常规做法是导完之后提示「记得删掉源文件」。那时候他已经把文件从旧手机传到新手机、
发过一次微信文件传输、电脑的下载目录里可能还留着一份。
提前说，他至少有机会选择「先把它挪到一个不会被同步走的地方」；
导完再说一遍，因为那才是他真正会动手删的时刻。结果页那一条是这一整页**唯一**的红色横幅。

**这一页显示不了任何一格内容（决策(156)）：** 没有表格预览，没有「前五行长这样」。
内核那一层已经把「不吐内容」钉进了每一个 `toString`（决策(144)），界面这一层要接住它——
否则那一层的规矩就只是自我安慰。而且这一屏正是最容易被人凑过来看一眼的时候
（用户在两台手机之间来回核对），一份做成表格的 CSV 预览等于在一屏上摊开几百个明文口令。
用户失去的是「一眼看出这份文件对不对」的便利，补偿是**列名、行号、条数、四类跳过理由**——
它们足够回答「哪一列是什么」和「哪些行没进来、为什么」这两个真正要紧的问题。
唯一显示的是表头：列名不是数据，不显示它「这一列是什么」就没法问了。

**没有「撤销导入」（决策(157)）：** 因为它做不到这两个字承诺的事。
新增那部分删得干净，覆盖那部分改掉的是用户原有的条目，那些旧值在落盘那一刻就没了
（决策⑧：这个应用没有回收站）。一个只能撤销一半的按钮比没有按钮更危险。
力气因此全花在按下去之前：默认处置是「跳过」、覆盖的合并规则写在按钮上方、
撞上的行按行号列出来、每一种处置都**带着自己那句说明**而不是选中之后才显示
（三种处置在按钮上只差两个字，其中一种不可撤销地改动已有数据）。

**没认出来的列照样占一行：** 折叠起来的话，用户看到的是一份「全都认好了」的假象，
而真正该他决定的那几列被藏在「展开更多」后面。1Password 的 `Password Hint`
被排除表挡掉之后就落在这一行上（决策(147)），他扫一眼会发现「哦，提示语那列没导，对的」。
没认出来的列也**不标红**——一列没被认出来不是错误，多数文件都有好几列用不上。

**复用而不是新写的三处：** 文件来源用恢复页那个 `SafImportSource`（连 64 MiB 上限一起复用，
比 `CsvText` 的 16 MiB 宽，于是超限时说话的是 `CsvText.message` 那条更准的）；
失败文案全部来自出错的那一层自己（`CsvText.message` / `CsvParser.message`）；
判重的措辞取 `Match.label` / `Match.why` 那一份——「凭什么算撞」必须和真正的判据一个字不差，
否则用户会按一句不准确的解释去选处置，而选错的那一种会盖掉他的旧密码。

**几个界面上的小决定：**
选文件前调 `session.beginSystemInterlude()`（这一页在已解锁相位，
自动锁定是真会发生的，而导入的人翻文件夹翻得比谁都久；恢复页那一处刻意没调，
因为它在 `NoVault` 相位——照抄过去会是一行永远不生效的代码）；
角色选择弹窗**自己声明防截屏**（独立 window 不继承 `FLAG_SECURE`，决策⑭）
并且限高可滚（否则十个角色里排最后的 `Kind` / `Favorite` 会被屏幕直接切掉）；
「换一个文件」那个失败按钮真的会把选择器拉起来，否则用户点完只看到横幅消失、
然后在一张空页面上愣住；灰按钮配的那句解释分三种说法，因为下一步完全不同
（还在算 / 上面去改 / 改处置）。

**这一步能上机验证的：整条导入路。** 找一份 Chrome 的 `passwords.csv` 或者
Bitwarden 的导出，走一遍：设置 → 从 CSV 导入 → 选文件 → 看列认得对不对 →
故意把某一列点错再点回来 → 看预览数字 → 导入 → 核对列表。
再导第二遍（同一份文件）就能看到判重和三种处置的差别。

---

### M4-1a 域名归属内核 ✅
| 文件 | 作用 |
|---|---|
| `ui/autofill/PublicSuffix.kt` | 公共后缀表（只列多段的）、可注册域、兄弟判定、「这是包名还是主机名」、IDN 归一 |
| `ui/autofill/AutofillTarget.kt` | `Origin`（原生 / 网页两种，各自带着宿主包名）、`HostTrust` 接口、内置浏览器包名表 |
| `ui/autofill/DomainMatch.kt` | 六档 `Verdict` + 单条判定 `judge` + 多行网址取最好的 `best` |
| `ui/autofill/AutofillMatch.kt` | 从整库挑候选、排序、截断；手动挑那一侧的 `inspect` |
| `src/test/.../PublicSuffixTest.kt` | 24 个用例 |
| `src/test/.../DomainMatchTest.kt` | 22 个用例 |
| `src/test/.../AutofillMatchTest.kt` | 13 个用例 |

改动的既有文件：
- `ui/list/VaultIndex.kt` —— 新增公开的 `NAME_ORDER`（既有私有 `BY_NAME` 的读取器）。
  **纯加法**，行为一个字没变，`VaultIndexTest` 一条都不用改。

**这四个文件里没有一行 `android.*`，也没有一行 Compose**，59 个用例纯 JVM 可跑。
`AssistStructure` 怎么拆、`Dataset` 怎么装，全是 M4-2 的事；这一步只回答一个问题：
**这一条条目，能不能填给这一组输入框。**

**为什么先做这个：** 全工程再没有第二个函数的错误代价有这么大。判宽了，用户点一下
就把密码发给了别人，事后一点痕迹都没有；判窄了，无非是这次得自己去搜索里挑一条。
两种错误差着几个数量级，而它偏偏又是**在真机上最难验证**的一块——
要复现 AutoSpill 得先写一个恶意应用，写得出来也不该留在仓库里。所以它只能在单测里钉。

**决策㉝ 欠的债在这一步还了。** 那条决策说「域名归一只做语法剥离，`www.` 都不剥，
因为剥子域名这件事必须靠公共后缀表认真做，属于 M4」。表现在有了：
`VaultIndex.normalizeDomain` 仍然一段都不剥（存储和搜索那一侧不变），
剥不剥、算不算同一个站，全部在 `PublicSuffix` 里判。**两边共用同一个归一函数**，
`DomainMatch.judge` 第一行调的就是 `VaultIndex.normalizeDomain`，没有第二份。

**表只列多段后缀（决策(159)）：** 完整的 PSL 近万条、两百多 KB，而且每周都在变。
但绝大部分条目不需要内置——PSL 自己就有一条默认规则：没列出的单段后缀，它自己就是公共后缀。
于是 `.com` `.dev` `.xyz` 以及明年才出现的新 gTLD 全都不用写。
真正要列的只有 `co.uk` / `com.cn` / `bj.cn` / `github.io` 这一类，几百条量级。
最后那一段是**私有后缀**：托管平台把子域分给互不相识的用户，性质和国家注册局一模一样，
`user1.github.io` 和 `user2.github.io` 是两个陌生人。

**六档判定，每一档都是一句能对用户说的话：**

| 档 | 什么情况 | 自动建议 |
|---|---|---|
| `Exact` | 逐字相同的主机名或包名 | 是 |
| `SameSite` | 同一可注册域下的不同子域 | 是（界面必须和精确档分开显示）|
| `UntrustedHost` | 站点对得上，但承载这个网页的应用不是已知浏览器 | 否 |
| `NoEvidence` | 原生应用的框 ↔ 条目里存的是网址 | 否 |
| `WrongKind` | 网页的框 ↔ 条目里存的是安卓包名 | 否 |
| `None` | 不相干 | 否 |

后四档不是「失败」，是四种要分别交代的处境。一个空荡荡的填充条，用户唯一的结论是
「这功能坏了」——他不会想到那正是它在保护他。

**这一步能上机验证的：什么都没有。** 这是全工程第一个纯内核步骤里最彻底的一个：
App 装上去跑起来，界面一个像素都不会变，设置里也多不出一行。
可验证的东西全在那 59 个用例里，能上机的从 M4-2a 起。

---

### M4-1b-1 字段角色识别内核 ✅
| 文件 | 作用 |
|---|---|
| `ui/autofill/FieldModel.kt` | `RawField` / `FillContext`（一次填充请求在纯 Kotlin 侧的样子）+ `AndroidInput`（抄过来的平台位值）|
| `ui/autofill/FieldRoles.kt` | 四档证据、五种角色、两张负面表；`classify` / `classifyAll` |
| `src/test/.../FieldRolesTest.kt` | 28 个用例（覆盖 49 个场景），纯 JVM 可跑 |

**没有改任何一个既有文件。** 这两个文件里也没有一行 `android.*`、没有一行 Compose。

**五种角色：** `Username`（用户名 / 邮箱 / 手机号，最后都填 `entry.username`，
分开只会多出三条走同一个分支的代码）、`Password`、`NewPassword`、`Otp`、`Other`。

**证据分四档硬度，硬的压过软的：** `autofillHints` → HTML `autocomplete` →
`inputType` / `<input type>` → 关键词。前面那档给出答案就不再往下走。
这不只是效率问题：软信号翻硬信号的案子，表现是「明明声明了 `current-password`
却被当成新密码」，而作者能做的补救只剩下改变量名。

**两个方向的错误代价差得远：** 认不出来 → 这个框不给填，用户自己粘一下，烦一次；
认错了 → 密码被填进验证码框（跟着短信回显出去），或者旧密码被填进「新密码」栏
（用户直接点提交，以为自己改过密码了）。所以整套规则往「宁可不认」偏。

**两张负面表是分开的，这一点写坏过一次：** 一开始只有一张排除表，
里面放着「地址 / address」——然后「邮箱地址」和「Email Address」
这两种最常见的账号框写法全被挡掉了。分开之后：
`NOT_CREDENTIAL` 说「这个框是别的东西」（卡号 / 搜索 / 收货），命中即出局；
`NEGATE` 说「这个词只是**提到**了密码」（提示 / 忘记 / 强度 / 密保问题），只让正向匹配作废。
真正的收货地址栏本来也匹配不上任何正向词，落到最后自然是 `Other`——
**负面表只用来挡「会误命中正向表」的那些词**，不用来穷举世上所有不是密码的东西。

**三道硬性排除走在所有猜测前面：** 看不见的（隐藏密码框是个老套路：
放一个不可见的框骗管理器填进去，再用脚本读走）、应用明说 `importantForAutofill=no` 的、
不是文本框的。

**这一步能上机验证的：还是什么都没有。** 界面一个像素不变。
可验证的全在 28 个用例里；M4-1b-2 把字段切成组、算出各自的归属之后，
M4-2a 才第一次能在真机上看到填充条。

---

### M4-1b-2 分组与归属 ✅
| 文件 | 作用 |
|---|---|
| `ui/autofill/FieldGroups.kt` | 把一屏框切成几组「表单」，**每组各算各的 `Origin`**；切组的两条依据（归一后的 `webDomain` + 角色出现顺序）|
| `ui/autofill/FillPlan.kt` | 每组填哪几个框（六档 `Kind`）、哪几个刻意留空（四档 `Skipped`）、主表单挑哪一个、真正要写下去的 `Write`、两组文案 |
| `src/test/.../FieldGroupsTest.kt` | 30 个用例，纯 JVM 可跑 |
| `src/test/.../FillPlanTest.kt` | 35 个用例，纯 JVM 可跑 |

改动的既有文件：
- `ui/autofill/AutofillTarget.kt` —— `Origin.App` / `Origin.Web` 各补了一个手写的 `toString`
  （只报 `Origin.App` / `Origin.Web`，不吐主机名和包名）。`equals` / `hashCode` 仍由
  `data class` 生成，**判定逻辑一行没动**，`DomainMatchTest` / `AutofillMatchTest` 一条都不用改。
  补它的理由见决策(171) 末段：M4-1a 写下 `RawField.toString` 时已经说明「主机名是一份
  不该外泄的清单」，而同一个文件里的 `Origin` 却是个 `data class`——那是当时漏掉的一处。

**这一步是决策(158) 唯一能被写错的地方，所以整段的重心都在归属上。**
一屏上可能同时有原生框、有 WebView 框、还有来自另一个网站的 iframe，
而系统把它们**装在同一个请求里**交过来。写错的形态只有一种，而且写起来非常顺手：
扫一遍整棵树、拿第一个非空的 `webDomain` 当作「这次请求是给哪个网站的」——
那正是 AutoSpill 走的门。所以分组的第一把钥匙就是归一后的 `webDomain`，
原生框和网页框永远不可能落进同一组，两个不同的 `webDomain` 也不可能，
而 `hostApp` 一律取 `FillContext.activityPackage`（系统给的，应用改不了）。

**已用等价实现验证过的性质（那 65 条）：**
原生框那一组的归属是承载它的应用、网页框那一组同时带着自称的网站和承载它的应用、
**`hostApp` 永远取请求里那个包名而绝不取 `webDomain`**、
套了 WebView 的恶意应用一路走到判定这一步会被拦成 `UntrustedHost`（端到端）、
同一次请求里原生框和网页框各算各的归属（**原生那组绝不继承 `webDomain`**）、
两个不同 `webDomain` 切成两组且不拿第一个代表整屏、同一个网站的框被别家的框隔开也不并组；
`webDomain` 是一整条 URL / 带端口 / 大写时照样归一到主机名（走的是
`VaultIndex.normalizeDomain`，不另写一份）、空串和纯空白按原生算（没有「自称」可采信）；
看不见的框和应用明说别填的框不进任何一组、认不出角色的框不产生空组、
**只剩验证码框的组要保留**（它是一句要对用户说的话）；
登录表单和注册表单同屏时切成两组、又来一个密码框不切组、连着两个账号框不切组、
新密码框也算密码、验证码框不触发切组；组内和组间的顺序都稳定；
`focused` 落在光标真正所在的那一组。
填充清单那一侧：登录表单填账号和密码且账号在前、只有账号 / 只有密码各自是分屏登录的两屏、
注册表单只填账号而新密码框全留空、**改密码表单填账号和「当前密码」而新密码那栏留空**、
两个以上分不出新旧的密码框**一个都不填只填账号**且这一档压过「有新密码框」那一档、
验证码框永远不填但要记一笔、多个账号框只填第一个、**计数为 0 的跳过项一个都不出现**；
写值时**空的那一格不写**（不用空串把用户已经打进去的东西擦掉）、密码首尾的空格原样写下去、
账号密码都空的条目一个字都不写；
主表单优先取光标那一组、光标那一组什么都填不了时让位给账号密码齐全的那一组、
都没有时取第一个有东西可填的、一个都没有时 `primary` 是 null；
`Target` / `Form` / `Plan` / `Write` / `Group` / `Field` **六处 `toString` 都不吐主机名、
不吐包名、不吐值**；文案那几条：一切照常的两档一句废话都不说、其余四档互不重样、
没有一句把它说成故障（不出现「失败 / 出错 / 稍后重试 / 联系客服」）、
新密码那一句必须写出后果而不是只说「留空了」、密码歧义那一句必须写明只填了账号并交代下一步。

**这一步能上机验证的：还是什么都没有。** 界面一个像素不变，设置里也多不出一行。
`FillPlan.forRequest` 已经能把一屏框算成一份「填哪个句柄、填什么」的清单了，
但那一屏框还得靠 M4-1b-3 那层薄壳从 `AssistStructure` 里摊出来，
而填充条要到 M4-2a 才真正露面。

---

### M4-1b-3 结构薄壳 ✅
| 文件 | 作用 |
|---|---|
| `ui/autofill/StructureRules.kt` | 走树的**规则**：`NodeFacts` / `Tree` 抽象、三条继承（网站 / 可见 / 别填）、三个上限、`Walker`、摊成 `RawField`。**没有一行 `android.*`** |
| `ui/autofill/AssistShell.kt` | 走树的**那层皮**：`ViewNode` 的十几个 getter → `NodeFacts`，外加句柄 ↔ `AutofillId` 对照表。全工程第一个 `import android.app.assist` 的文件 |
| `src/test/.../StructureRulesTest.kt` | 34 个用例，纯 JVM 可跑 |

**没有改任何一个既有文件。** 没有动依赖，没有动 `AndroidManifest.xml`
（`BIND_AUTOFILL_SERVICE` 仍然是 M4-2a 那一步的事，关于页那份权限清单眼下还是只有一条），
没有加图标，没有加组件，界面一个像素不变。

**原计划说这一层「不写单测」，这一步改了主意，理由值得记一笔（决策(176)）。**
`SafExportSink` / `SafImportSource` 那两个薄壳当得起「薄」字——读到底、写下去，
错了当场看得见。走树不是：它里面藏着三条**错了也不报错、只是从此填错人或者不填**的规则，
而这三条**没有一条需要 `android.*`**。

1. **`webDomain` 沿着树往下继承。** 浏览器只在 WebView 那一层（有时是页面根节点）
   写上自称的网站，底下每个 `<input>` 上是空的。不继承，网页框全都变成
   「说不出自己属于哪个网站」的原生框，于是一律拿承载它的浏览器包名去匹配——
   用户的所有网站密码从此一条都填不出来。
2. **继承只能往下，不能往旁边。** 这一条就是决策(158)/(171) 在树上的形态：
   恶意应用套一个 WebView，那棵子树上的框如实带着 `webDomain = 你的网银`，
   而同一屏上它**自己的原生框是 WebView 的兄弟，不是它的后代**。
   只要继承严格顺着父子边走，那些原生框就永远拿不到那个 `webDomain`；
   写成「记住上一个见过的 `webDomain`」（一个循环外面的可变变量，比传参顺手得多）
   就漏了——那正是 AutoSpill 走的门。所以 `Inherited` 是不可变的，且只作为参数往下传。
3. **看不见 / 明说别填，是整棵子树的事。** 一个 `visibility != VISIBLE` 的容器，
   它底下的框在屏幕上一个都看不见，可各自的 `visibility` 全是 `VISIBLE`——
   隐藏密码框那个老套路正是这么摆的。同理 `NO_EXCLUDE_DESCENDANTS`
   要往下传，而且后代自己写 `yes` **翻不回来**（谁更外面谁说了算）。

于是这一步把**走树本身**也搬到了纯 Kotlin 那一侧：`Tree<N>` 抽掉「节点长什么样」，
`Walker` 负责走，薄壳只剩一个 `Tree` 的实现（一串 getter）。
线上和用例里走的是**同一个 `Walker`**。

**已用假节点验证过的性质（那 34 条）：**
`file://` / `content://` 下的自称一律不采信（应用把一份 HTML 写进自己目录、
里面写上 `<base href="https://银行">` 就能让那棵子树自称是银行）、
读不到协议时（API 28 以下没有 `getWebScheme`）按采信处理（一律不采信等于
在 26/27 上把网页填充整个关掉）、空的和纯空白的自称当作没说、
**这一层不做归一**（原样往下传，归一只有 `VaultIndex.normalizeDomain` 一份，决策㉝）；
HTML 属性名大小写不敏感（见过 `TYPE` 和 `autoComplete`，用 `Map` 直接查
是最容易在某一家浏览器上悄悄失灵的写法）、同名属性取第一个、只认 `<input>` / `<textarea>`；
网站往后代传、后代自己声明的压过祖先的（iframe 里嵌着另一个站是正常的）、
祖先看不见后代一律看不见、`NO_EXCLUDE_DESCENDANTS` 传给后代且后代翻不回来、
只说自己别填的不牵连后代；
`<input type=hidden>` 直接算看不见、被祖先排除的框写成「应用明说别填」
（**不在薄壳里直接丢掉**，那个结论仍然由 `FieldRoles` 那一处做出来）；
先序、句柄从 0 连续发、多次 `feed`（几个窗口）接着往下发、没有 `AutofillId` 的容器不占句柄、
不是文本框的不收、DOM 的壳不收；
读事实抛异常的节点跳过而其余照收（自定义 View 的 getter 里抛异常是真见过的）、
孩子读不出来不炸、太深的子树整个不看、框太多时**保留已经收到的而不是整屏扔掉**、
一切正常时不报截断；
端到端四条：WebView 子树里的框归到它自称的网站、
**同屏的原生框绝不继承兄弟 WebView 的网站**（走到 `FieldGroups` 切成两组、
两个 `Origin` 各自算对）、藏在看不见的容器里的密码框一路走到判定都不算数、
祖先说了整块别填时底下的框一个都不进组；
`NodeFacts` / `Inherited` / `Picked` 三个新 `toString` 都不吐主机名、不吐页面文案。

**几件到了 M4-2a 会用上的事：**
- `AssistShell.parse(structure)` 拿不到 `activityComponent` 时**返回 null，一个框都不收**。
  唯一还剩的包名来源是节点上的 `idPackage`，而那一栏是应用自己填的——
  拿它当归属等于把决策(158) 里那条「最硬的事实」换成一句自称（决策(177)）。
- 句柄**只在这一次请求里有意义**（就是先序序号），不要存下来，
  更不要拿它当「上次在这个框里用了哪一条」的键——那种账本身就不该记（决策(163)）。
- `Parsed.truncated` 记的是撞没撞到上限，`AssistShell` 那行日志**只有数字**：
  窗口数、框数、截没截断，包名和主机名一个都不打（决策(144)）。
- **这个文件里读不到用户打的字**：`getText()` / `getAutofillValue()` 一次都没出现，
  `NodeFacts` 里也根本没有能放它的字段（决策(165)）。M4-3 的保存流程要读那个东西，
  那是另一条路、另一个模型，到时候单独建。

**这一步能上机验证的：还是什么都没有。** 但从这一步起，
「一屏真实的安卓界面 → 一份 `FillPlan.Plan`」这条路在代码上已经通了，
差的只有 M4-2a 那个服务组件去调它。

---

### M4-2a-1 填充响应内核 ✅
| 文件 | 作用 |
|---|---|
| `ui/autofill/AutofillOffer.kt` | 一次请求的全部判断：三条路（没有库 / 锁着 / 解锁）、填充条上那几行长什么样、**每个表单各判一次归属**、四句「为什么没出现」 |
| `src/test/.../AutofillOfferTest.kt` | 30 个用例，纯 JVM 可跑 |

**没有改任何一个既有文件。** 没有动依赖，没有动 `AndroidManifest.xml`
（`BIND_AUTOFILL_SERVICE` 是 M4-2a-2 的事），没有加图标，界面一个像素不变。

**M4-2a 拆成两步的理由**：那一步原本要一口气做完服务组件、Manifest、
`RemoteViews`、`IntentSender` 和关于页那份权限清单。可其中真正要紧的东西
——「这一次到底该出什么」——一行 `android.*` 都不需要，
而它偏偏是**最后一道能把前面四个文件全部小心作废的地方**（见下）。
所以先把判断钉死，M4-2a-2 那层壳就只剩「句柄换 `AutofillId`、文字塞 `RemoteViews`」。

**三条底线，每一条都在用例里钉着：**

1. **填充条上永远没有密码。** `Item` 里根本没有能放它的字段：要写下去的值封在
   `writes` 里（`FillPlan.Write.toString` 不吐值），给人看的只有名称和账号。
   填充条是**系统进程**画的，输入法和无障碍服务看得见，也会进截屏和录屏——
   那是一块公共屏幕，不是保险库里面。
2. **每一个表单各判一次归属，判不过的一个字都不写（决策(179)）。**
   顺手的写法是「主表单判过了，那就照 `plan.forms` 全写一遍」，
   而同一屏上完全可能一组是 `example.com` 的 iframe、另一组是承载它的应用
   自己的原生框（M4-1b-2 已经把它们切成两组、各算各的 `Origin` 了）。
   全写一遍，密码就顺着第二组流进了不该去的地方——**前面四个文件守住的东西，
   在这最后一行上全漏光**，而真机上看不见：填充条照样弹出来，用户点一下，一切正常。
3. **一条点下去什么都不会发生的填充项，不如不出现**（决策(174) 在这一层的兑现）。

**已验证的性质（那 30 条）：**
判断顺序——「这一屏有没有能填的框」排在库状态**前面**（那一问不需要知道库的任何事，
也就不会因为回答它而泄露任何事）；不往本应用自己的界面上填（决策(180)）；
没有库 / 锁着 / 解锁三条路各自出什么；锁着时那一条**不说库里有什么**
（连条数都不说——不是不肯说，是数不出来，库文件是密文）；
名称在上账号在下、**密码不出现在任何一个字符串里**、没有账号的条目显示一句话而不是空白
（但密码照填）、没有名称的条目退回账号、两样都没有时也不显示空白、
兄弟域必须写出「你存的是 mail.example.com」而逐字对上的那一档不摆这一行（决策(159) 第二道兜底）；
同屏另一组框归属对不上时一个字都不写、两个不同网站的表单同屏时各写各的、
同一个网站被隔开的两组都写、承载的应用不是浏览器时一条都不自动出（AutoSpill）、
原生框配网址条目也不自动出；
只有名称的条目不出现、新密码那一屏只填账号所以没有账号的条目整条不出；
**一条都没匹配上时仍然是 `Offer` 而不是 `Silent`**（决策(181)：那条搜索入口是
「不自动 ≠ 不许手动」的落点，何况空荡荡的填充条只会让人以为功能坏了）、库是空的时候也一样；
超过 8 条时只报条数、没被截掉时搜索那一行不提条数、收藏的排在前面；
一切照常的那一屏一句废话都不说、要设新密码和分不出新旧的两屏各先说一句；
四句「为什么没出现」互不重样、没有一句说成故障（不出现「失败 / 出错 / 稍后重试」）；
`Silent` 的 `toString` 只报原因。

**M4-2a-2 拿到 `Response` 之后要做的只剩三件事：**
`Item.writes` 里的句柄拿 `AssistShell.Parsed.autofillId` 换成 `AutofillId`、
两行文字塞进 `RemoteViews`、给 `Response.Unlock` 配一个拉起解锁页的 `IntentSender`。
另外那一步要动 `AndroidManifest.xml`（加 `BIND_AUTOFILL_SERVICE` 服务组件
+ `res/xml/autofill_service.xml`），**关于页那份「权限只有一条」的清单必须同步改**——
`SettingsModel` 里那一行写死着 `USE_BIOMETRIC`，不能让它继续说「只有一条」。

**这一步能上机验证的：还是什么都没有。** 界面一个像素不变。

---

### M4-2a-2① 浏览器身份核验 ✅
| 文件 | 作用 |
|---|---|
| `ui/autofill/BrowserTrust.kt` | 三档信任、摘要归一、判档规则、三句话；内置摘要表（**现在是空的，见下**）。没有一行 `android.*` |
| `ui/autofill/AndroidHostTrust.kt` | `HostTrust` 的线上实现：`PackageManager` 取签名证书 → SHA-256 → 交给 `BrowserTrust` 判档；按包名缓存 |
| `src/test/.../BrowserTrustTest.kt` | 21 个用例，纯 JVM 可跑 |

**没有改任何一个既有文件。** 没有动依赖，没有动 `AndroidManifest.xml`，没有加图标。

**这是决策(164) 欠下的那一步。** `KnownBrowsers` 只按包名认，而它的文档里写死着
「它给的是必要条件，不是充分条件」——因为**包名是可以被占位的**：
安卓只保证同一台设备上包名唯一，不保证某个包名归谁。用户手机上没装 Chrome 的话，
一个侧载应用完全可以把自己叫做 `com.android.chrome`，堂堂正正通过那张表，
于是 `DomainMatch` 把 `UntrustedHost` 降格成 `Exact`，密码自动出现在它的填充条上。

**三档而不是两档（决策(182)）：** 内置的摘要表注定不全（浏览器一直在增加、各家会换签名，
而这个应用没有网络权限，没法在线拉一份名单）。两档只有两种写法，两种都是坏的：
表里没有就当不可信 → 少一家，那个浏览器上从此再也不出填充条，而用户查不出原因；
表里没有就当可信 → 这一层等于没做。所以第三档 `PackageOnly` 说的是实话
——**这一家我们只核对了包名**；它照样自动建议，但界面上那句话不一样。
真正被挡下的是第三种情况：**表里有这一家的摘要、而装在这台设备上的包签名对不上**。
那不是「我们不认识它」，是「它不是它自称的那个」。

**`FINGERPRINTS` 现在是空的，这是有意的，不是漏了。**
摘要必须从官方渠道的 APK 上亲手算出来才能往里加（`apksigner verify --print-certs`
或 `keytool -printcert -jarfile`，**要在一台干净的设备/机器上算**）。
编一个假的进去比空着糟得多：填错一条的后果是那个浏览器从此判成 `Unknown`，
用户在最常用的浏览器里再也见不到填充条，而没有任何一处会告诉他为什么；
空着的后果只是所有浏览器都停在 `PackageOnly`——也就是这一步之前的水平，一步没退。
表被填之后，那几条规则不会跟着变：`decide` 有一个 `internal` 的重载收外部表，
用例注入的是自己的一张小表。

**已验证的性质（那 21 条）：**
摘要归一（`keytool` 的大写带冒号 / 空白 / 短横 / 长度不对 / 非十六进制 / 已归一的原样）；
不在包名表里的一律不认、表里有且对得上就是已核验、**命中任意一个摘要就算过**
（签名轮换和渠道多签会让实际摘要多于一个；要求全等会在轮换那天把正版判成冒充，
而这不放松安全——私钥不在手上就签不出那个签名）、
**表里有但对不上判的是「不认识」而不是退回「只认包名」**（退回去等于这张表白建）、
签名读不出来时判不认识（查自己设备上已安装包的签名不需要权限，读不到几乎只可能有鬼）、
表里没有这一家就只核对包名、包名的空白与大小写不影响、表里的摘要写成大写带冒号照样对上、
表里写坏的那一条不算数（退回只认包名，而不是把实际摘要全判成对不上）；
两档够格自动建议、一档不够格；
**内置表的两条守卫**（每个包名都要在 `KnownBrowsers` 里且小写、每条摘要都要是合法 SHA-256）
——将来往表里加条目时，它们挡的是「摘要加了、包名却拼错了」这种没有任何一处会说话的错；
三句话互不重样、**已核验那一句不说「安全」也不当作对页面的背书**
（核对的是「这个包是它自称的那个包」，不是「这个页面不是钓鱼网站」；
一句听起来像背书的话，会让用户在真该停下来看一眼的时候放心地点下去）、
没有一句说成故障、「不认识」那一句要说清用户还能做什么。

**这一步能上机验证的：还是什么都没有**（`FINGERPRINTS` 空着时行为和之前一模一样）。
到 M4-2a-2② 服务接上之后，logcat 里 `HostTrust` 那个 tag 会在读不到签名时说话。

---


---

### M4-2a-2② `AutofillService` 组件 ✅
| 文件 | 作用 |
|---|---|
| `ui/autofill/AutofillRow.kt` | 填充条上**一行**的三段文字：压成一行、剔控制字符与双向控制符、按码点截断。没有一行 `android.*` |
| `ui/autofill/AutofillViews.kt` | `Row` → `RemoteViews`。三次 setText 加一次 setViewVisibility |
| `ui/autofill/AutofillResponses.kt` | `Response` → `FillResponse`：句柄换 `AutofillId`、值包成 `AutofillValue`、锁着那一条配 `IntentSender`。**没有一个 `if` 是判断** |
| `ui/autofill/VaultAutofillService.kt` | 服务本体。`onFillRequest` 只有管道，没有规则 |
| `ui/autofill/AutofillUnlockActivity.kt` | 解锁跳板：接住系统塞来的 `AssistStructure` → 复用主界面那两屏解锁 → 当场算出响应交回 `EXTRA_AUTHENTICATION_RESULT` |
| `res/layout/autofill_row.xml` | **全工程唯一一个 XML 布局**（`RemoteViews` 只认这种） |
| `res/xml/autofill_service.xml` | 服务元数据 |
| `src/test/.../AutofillRowTest.kt` | 28 个用例，纯 JVM 可跑 |

改动的既有文件：
- `AndroidManifest.xml` —— 加 `<service>`（`BIND_AUTOFILL_SERVICE`）和 `<activity>`（跳板页）。
  **`<uses-permission>` 一条没加**，见下。
- `ui/settings/SettingsModel.kt` —— 新增 `AUTOFILL_NOTE`（四句）。`PERMISSIONS` **一个字没动**。
- `ui/settings/AboutScreen.kt` —— 权限卡片后面多一格「自动填充」。
- `src/test/.../SettingsModelTest.kt` —— 三条新用例。

**从这一步起，这个功能第一次能在真机上看见。** 前面五个纯内核步骤加起来
（`PublicSuffix` / `DomainMatch` / `FieldRoles` / `FieldGroups` / `FillPlan` /
`StructureRules` / `AutofillOffer` / `BrowserTrust`，247 个用例）界面一个像素都没变过，
这一步把它们接上电：设置成默认填充服务 → 在浏览器里点登录框 → 填充条弹出来。

**这个文件通篇只有管道，没有一条规则。** 一次请求走的是一条直线，每一站都在别处测过：
`AssistShell.parse`（34 条）→ `FillPlan.forRequest`（65 条）→ `AutofillOffer.respond`（30 条）
→ `AutofillResponses`（不判断）。想在服务里加一个 `if` 之前先停一下——
它十有八九该加在 `AutofillOffer` 里：这个文件跑在**别人的应用**触发的一次系统回调里，
既没有界面也没有用例，加在这儿的判断是整条链上唯一一段没人看得见的代码。

**`BIND_AUTOFILL_SERVICE` 不是这个应用申请的权限（决策(183)）。**
原待办里写着「这是 M0 之后第一次给权限清单添东西，`SettingsModel` 那一行要同步改」——
那句话是错的。它写在 `<service>` 标签的 `android:permission` 上，意思是
**「谁想绑定这个服务，必须持有它」**，而持有它的只有 `system_server` 一个。
它是一道**锁**，不是一项**能力**：「应用信息 → 权限」里不会多出一行。
把它加进 `PERMISSIONS` 才是那句谎话——用户照着那份清单去系统里核对会对不上，
而关于页的全部价值就在于**每一条都能被自己核实**。
所以 `PERMISSIONS` 保持一条，另起一格 `AUTOFILL_NOTE` 说清楚这件事的下半句
（系统那屏「它将能够看到你屏幕上的内容」是对所有填充服务说的同一句话，
不接下半句，用户只能自己瞎猜它到底读得到什么）。

**新增的三条守卫（决策(184)~(186)）：**

1. **用户内容进公共浮层之前先洗一道**（决策(184)）。填充条那三行字不是我们写的：
   条目名称和账号是用户打进去的，或者从一份 CSV 里导进来的——而决策(156) 明说
   导入预览一格内容都不显示，于是**它到这一刻为止从没被人看过一眼**。
   而这一行要被画进**系统进程**的浮层，浮在别人的应用上面。三件事必须做完：
   压成一行（带换行的名称会把填充条撑成半屏高，把下面两条候选顶出屏幕，
   而用户不会知道自己少看见了两条）、剔掉控制字符尤其是**双向控制符**
   （`U+202E` 之后的字符倒着画，`moc.knab\u202E` 和 `bank.com` 在屏幕上一模一样）、
   按**码点**截断（按 `Char` 切会把 emoji 的代理对切成半个）。

2. **跳板页必须自己接自动锁定的两个回调**（决策(185)）。这一条最容易漏，
   而且在应用里怎么点都试不出来：倒计时由「Activity 走 `onStop`」点着，
   跳板页要是不接，用户在浏览器里解了锁、填充完成、这一页 `finish` 掉，
   **没有任何一个 Activity 会为此走 `onStop`**（主界面那次 onStop 发生在更早以前，
   倒计时早就烧完并锁过一次了）。结果是库从这一刻起一直开着，
   直到用户下次亲手打开应用再退出去。

3. **失败一律 `onSuccess(null)`，从不 `onFailure`**（决策(186)）。
   `FillCallback.onFailure(CharSequence)` 那句话会**画在填充条上**，
   出现在别人的应用里。它能说的没有一句是用户此刻用得上的
   （「结构解析失败」对一个正在登录的人意味着什么？），
   却给了旁边那个应用一个探针：反复变换页面结构，看我们什么时候开口。
   出不了手就安静地不出手，理由留在 logcat 里。

**这一步留下的一处缺口，是有意的：** `AutofillOffer` 那一层规定
「一条都没匹配上时仍然是 `Offer` 而不是 `Silent`」（决策(181)），
靠的是末尾那条「在保险库里搜索」；而那一行要跳到 M4-2b 的挑选页，**那一页还不存在**。
所以眼下 `AutofillResponses` 在装不出任何 `Dataset` 时返回 null（这次不出填充条）。
文案已经在 `AutofillRow.forSearch` 里备好了，M4-2b 补上那个 `IntentSender` 即可，
内核一个字都不用改。在那之前，`UntrustedHost` / `NoEvidence` 那几档的用户
看到的是「没弹出来」而不是「弹出来但只有一条搜索」——**一步没退，但也还没到位**。

**第一次能上真机验证的东西（全部要手动走一遍）：**
1. 系统设置 → 密码与自动填充 → 选「本地保险库」。选完那一屏系统会警告一句，
   关于页那一格说的就是它的下半句。
2. 库解锁着 → 浏览器打开一个登录页 → 点账号框 → 填充条出现，
   **两行字里没有密码**，点一下账号和密码一起填上。
3. 库锁着 → 同一屏 → 填充条上只有一条「先解锁」，**不报条数**；
   点它 → 跳板页（PIN / 指纹 / 主密码都能用）→ 解开之后**当场填上**，不用再点一次。
4. 承载页面的不是已知浏览器（找一个套 WebView 的普通应用）→ 一条都不自动出。
5. 存的是 `mail.example.com`、页面是 `example.com` → 候选上多出黄铜色的第三行。
6. 解锁完成后放着不动，等过自动锁定的时长，再点输入框 → 只剩「先解锁」
   （这一条钉的是决策(185)：跳板页把倒计时接上了）。
7. logcat 看 `AutofillSvc` / `AutofillUnlock` / `AssistShell` / `HostTrust`：
   **每一行只有数字和原因，包名、主机名、条目名一个都不打**（决策(144)）。

---

### M4-2b-1 挑选内核 ✅
| 文件 | 作用 |
|---|---|
| `ui/autofill/AutofillPick.kt` | 挑选页的全部判断：整页该不该出现、默认摆哪两段、搜索怎么标注、**最后往哪几个框写**、四句警告 + 「会交给谁」那一行 |
| `src/test/.../AutofillPickTest.kt` | 71 个用例，纯 JVM 可跑 |

改动的既有文件（**两处都是纯加法，一条既有调用都没改，一条既有用例都没动**）：
- `AutofillOffer.kt` —— `labelOf` 从 `private` 改成 `internal`。挑选页要用**同一条**
  「名称 → 退回账号 → `NO_NAME`」的规则；抄一份过去的后果是某天两处不一样了，
  填充条上写着账号、挑选页上写着「（这一条没有名称）」，指的却是同一条。
- `AutofillMatch.kt` —— `suggest` 多了一个**带默认值**的 `limit`。
  `MAX_SUGGESTIONS = 8` 是**填充条的**上限（系统只露出两三行），
  而挑选页是全屏、用户点进来的那一行写的正是「还有 12 条」。
  在那一页另写一套排序，就会出现「填充条上排第一的和这一页上排第一的不是同一条」，
  而没有任何一处能解释为什么。

**M4-2b 拆成两步的理由，和 M4-2a 是同一个：** 那一步原本要一口气做完 Compose 页面、
`IntentSender`、Activity、`AutofillResponses` 里补上的那条搜索行。
可其中真正要紧的东西——「用户越过了归属判断之后，到底往哪几个框写」——
一行 `android.*` 都不需要，而它偏偏是**最后一道能把前面八个内核文件全部小心作废的地方**。

**这一层最要紧的一句话（决策(187)）：自动那一侧和手动这一侧，闸门不是同一道。**

自动那一侧（`AutofillOffer.writesFor`）的闸门是**归属判断**：同屏每一组框各判一次，
判不过的一组一个字都不写。手动这一侧，那道闸门被用户**主动越过了**——
他挑的这一条对这一屏本来就不够格，不然它早就自动出现了，他也不必来这一页。
于是有两条错路，两条都得躲开：

- 照 `writesFor` 那样一组一组判 → 判不过的组都不写，而他挑的这一条对主表单本来就判不过，
  结果是**一个字都写不出去**：这一页点下去什么也没发生，屏幕上不会有任何解释；
- 反过来「既然用户已经同意了，那就照 `plan.forms` 全写一遍」→ **那正是 AutoSpill 那条路**。
  同一屏上完全可能一组是 `example.com` 的 iframe、另一组是承载它的应用自己的原生框，
  用户看见的、点头的是前一组，密码却顺着后一组流进了别人的进程。

正解是**换一道闸门，而不是把闸门拆掉**：手动挑时**只往主表单那一组写**。
主表单就是 `FillPlan.pick` 挑出来的那一组（优先是光标所在的那一组，
也就是他此刻正看着的那几个框）。越过归属这件事只越过一次，而且只在他看得见的那一处越过。
`AutofillPickTest` 里那一条「同屏另一组框，手动挑也一个字都不写」就是钉这个的——
把那一行改成 `plan.forms.flatMap { ... }`，它立刻从「写 2 格」变成「写 4 格」而红掉。

**另外三条新守卫：**

1. **手动挑那一侧有第四句警告（决策(191)）。** `Verdict.needsWarning` 只管三档，
   因为 `None` 在自动那一侧永远不出现（自动只收前两档）——而在这一页上它是**最常见的一档**。
   `None` 的两种成因代价差得很远，必须分开：一行网址都没存（很平常，这一页正是为它存在的，
   为它摆警告只会让人学会跳过所有小字）vs. **存了网址、存的是别的站**
   （极可能是他点错了行，两条名字相近的条目、或一份导进来的 CSV 里挨着的两行），
   而这是唯一一处能拦住他的地方。

2. **「会交给谁」那一行同时写应用名和包名，而且应用名要先洗一道（决策(188)）。**
   应用名是**那个应用自己声明的字符串**，它可以把自己叫做「Chrome 浏览器」，
   也可以在名字里塞一个 `U+202E` 让它倒着画出来。这比填充条那一处更要紧：
   填充条上那三行是**用户自己的**数据，这一行是**被填对象提供的**数据，
   而它正是用户做决定时唯一看的那句话。洗（复用 `AutofillRow.clean`，不写第二份）+
   **永远把包名一起写出来**——名字骗得了人，`com.example.free.wallpaper` 骗不了。
   读不到名字时只写包名，**不写「未知应用」**（那四个字听起来像出了故障，
   而包名已经把该说的都说了）。

3. **进这一页时不摊开整库，但搜索搜得到整库（决策(189)）。**
   默认清单只有两段：这个站够格自动的那几条（不截断）+ 最近改过的 12 条。
   过滤**只发生在默认清单上**，`search` 一律不过滤——不摊开 ≠ 搜不到，
   后者就成了「替用户决定他自己那条数据能去哪儿」（决策(160)）。
   搜索也不拿归属重排（决策(190)）：他打了那几个字，那几个字比我们的判断更能说明他要哪一条。

**已验证的性质（那 71 条）：**
整页不出现的两种情形，且判断顺序同 `AutofillOffer.respond`（「有没有能填的框」排在最前，
那一问不需要知道库的任何事）；名称退回账号、再退回 `NO_NAME`、换行被压成一行、
双向控制符被剔掉、超长按码点截断；六档 `Verdict` 各自认得出、命中的那一行带出**原文**；
「存了别的站」和「一行都没存」分得开，只剩空白的那一行算没存；
`fillable` 三种情形（都空的不给按、只有账号的在登录屏能按、只有密码的在只有账号框那一屏不能按）；
`Row.toString` 不吐名称、账号和密码；
默认清单只收够格两档、**不套用那个 8 条上限**、顺序和填充条上一样（精确压兄弟、收藏在前）、
第二段不重复摆第一段的条目、有上限且如实报「没摆全」、摆全了就不提、
库空 / 这个站没对上 / 一切正常三句话（第三种是 null，一句废话都不说）、
这一屏没有可填的框时第一段是空的而不抛异常；
搜索按关键词排而不按归属重排、够不上档的照样搜得到、空关键词不出结果、
**备注和密码搜不到**（白名单是从列表页那边继承的，不在这儿另开一份）、结果有上限；
**只往主表单那一组写**（同屏另一组一个字都不写）、归属对不上的条目照样写得出来、
新密码栏手动挑也不填、分不出新旧的两个密码框手动挑也一个都不填、值空的那一格不写、
没有主表单时一个字都不写；
够格两档一句警告都不摆、兄弟域那一句把两个域名都写出来、
不是浏览器承载时摆一句而**不摆两遍**（`UntrustedHost` 和 `BrowserTrust.Unknown`
说的是同一件事，摆两遍的后果是用户学会跳过这一整块）、归属无话说而浏览器不认识时那一句补上来、
「只核对了包名」和「已核验」两句是陈述句不进警告、要设新密码那一屏先说一句、
一切照常时陈述句也一句不摆、会写哪几格如实报出来（**只有格位，没有值**）、
什么都填不出来的那一条不给按、「会交给谁」那一行永远在、**确认屏上没有一处出现密码**；
应用名 + 包名 + 主机名三样都写、原生那一行不提「页面」、读不到名字时不说「未知」、
双向控制符 / 换行 / 超长 / 全空白 / 名字恰好等于包名 五种输入各自的样子；
四句警告互不重样、够格两档没有话说、**十句文案里没有一句说成故障**
（不出现「失败 / 出错 / 错误 / 稍后重试 / 异常」）。

**这一步能上机验证的：什么都没有。界面一个像素不变。**
`AutofillResponses` 眼下装不出 `Dataset` 时仍然返回 null，那条「在保险库里搜索」
还没摆上去——它要一个 `IntentSender` 和一个 Activity，那是 M4-2b-2。

**M4-2b-2 拿到这一层之后要做的只剩四件事：**
① 一个 `AutofillPickActivity`（同 `AutofillUnlockActivity`：`FLAG_SECURE`、
从 Intent 里接 `AssistStructure`、自己接 `onEnterForeground` / `onEnterBackground`——
**决策(185) 对它同样成立**，它也是一个能让库从锁着变成开着的入口）；
② 一个 Compose 页面，把 `Listing` / `Row` / `Choice` 摆出来
（搜索框复用 M3-3b 那套，`Choice.warnings` 里的每一句都不许折叠成「查看详情」）；
③ `AutofillResponses` 末尾加上那条搜索行，文案取 `AutofillRow.forSearch(hidden)`；
④ 确认之后拿 `AutofillPick.writes(plan, entry)` 装一个 `Dataset` 交回
`EXTRA_AUTHENTICATION_RESULT`。**一行判断都不用再做。**

---

### M4-2b-2 挑选页与接线 ✅
| 文件 | 作用 |
|---|---|
| `ui/autofill/AutofillPickFlow.kt` | 挑选页**此刻该摆哪一屏**（四档 `Phase`）+ 「会填哪几格」那一行 + 全部文案 |
| `ui/autofill/AutofillPickScreen.kt` | 清单屏 / 确认屏 / 拒绝屏三个 Compose 页面 |
| `ui/autofill/AutofillPickActivity.kt` | `FLAG_SECURE` + 接 `AssistStructure` + 自动锁定两回调 + 交答卷 |
| `src/test/.../AutofillPickFlowTest.kt` | 28 个用例，纯 JVM 可跑 |

改动的既有文件（六处）：
- `AutofillResponses.kt` —— 新增 `searchDataset()`（末尾那条「在保险库里搜索」）、
  `picked()`（把用户挑中的那一条装回 `Dataset`）、`pickSender()`；
  **`datasets()` 多了一个 `plan` 参数，刻意没给默认值**（理由同 `SettingsScreen.onSecurity`：
  搜索行要靠 `plan` 才知道覆盖哪几个框，参数可省略的话某天会静静地少摆一行，
  而编译器一声不吭）。两个调用点已经改好。
- `AutofillRow.kt` —— 新增 `forPick()`；`forSearch()` 上那段「M4-2a-2② 还没摆这一行」的注释改掉了。
- `AutofillUnlockActivity.kt` —— `UnlockHost` 从 `private` 放宽到 **`internal`**（见下）；
  两处过时注释（「到 M4-2b 那条搜索入口摆出来之前只能安静退出」）改掉了。
- `VaultAutofillService.kt` —— 一行：`datasets` 调用点补上 `plan`。
- `AndroidManifest.xml` —— 注册 `AutofillPickActivity`，属性和解锁跳板**逐条相同**。
  **`uses-permission` 仍然只有 `USE_BIOMETRIC` 一条**（同决策(183)，
  这一页也是 `exported="false"`，只由我们自己那个 `PendingIntent` 拉起）。

**M4-2b-1 末尾列的那四件事，四件都做完了**，而且如那一节所写：
**`AutofillPick.kt` 一行都没有改。**

**这一层最要紧的一句话（决策(192)）：搜索行是「数据集级」认证，不是「响应级」。**

`unlock` 那一条用的是 `FillResponse.setAuthentication`——整份响应都还没算出来，
因为库锁着，我们连有几条都数不出来。搜索这一条反过来：上面那几条候选**已经
实实在在装好了**，用户点的只是其中一行。用响应级认证会把那几条一起吞掉——
用户点了搜索、进去又改主意退出来，回到填充条上时那几条候选得重新算一次，
而中途库可能已经自动锁定了，于是他看到的是「先解锁」。
他明明什么都没做，填充条却退化了一档，而屏幕上不会有任何解释。
数据集级认证只替换它自己那一行，别的原样留着。

配套的一条是 `setValue(id, null)` 那几行**不是占位垃圾**：一个带认证的 `Dataset`
必须先声明「我覆盖哪几个框」，值给 null 表示「等认证回来再说」。一个都不声明的话，
系统认为这一行填不了任何东西，它根本不会画出来——表现是**「一条候选都没有的时候
填充条整个不出现」，而那正是这一行要治的病**。

**三条新守卫：**

1. **`AutofillPickFlow` 是这一步唯一新增的可测内核，它守的是真机上看不出来的那一条
   （决策(193)）。** 用户点开挑选页，摊开一屏条目，然后接了个电话；回来时
   自动锁定已经过了。如果这一页不跟着相位走，那一屏清单就一直摆在别人的应用上面——
   **库在会话里是锁着的，界面上却还留着一份摊开的资产目录**。页面还在，字还在，
   一切正常，没有任何一处会报错。所以 `phase()` **每一帧重新算**，
   `Locked` 一到就把清单收起来换成解锁屏；解开之后回到清单，不是回到一片空白。
   这一条在应用里怎么点都试不出来（应用里锁定会整个换掉导航图），
   它只在这一页上成立。真机上要复现得等满自动锁定的时长，
   并且盯着一屏**没有变化**的界面看——那正是「等价实现验证」该接手的地方。
   `delivered` 也做成了 Compose 状态而不是普通 `Boolean`：普通字段改了不触发重组，
   那一句「交过就走」在界面上永远等不到，全靠 `deliver()` 里那行 `finish()` 兜住,
   **能跑，但那是巧合，不是设计**。

2. **判断顺序：拒绝排在库状态之前**（同 `AutofillPick.refusal` / `AutofillOffer.respond`）。
   「这一屏有没有能填的框」「是不是我们自己的界面」这两问不需要知道库的任何事，
   也就不会因为回答它们而泄露任何事（决策(180)）。反过来写——先看库锁没锁、
   先弹一次解锁框、解开之后才发现这一屏根本没有可填的框——
   **等于为一件注定做不成的事，向用户要了一次主密码。**

3. **确认是换整屏，不是从底下推一个半高的 sheet（决策(194)）。**
   决策(160) 说手动挑这一下之所以被允许，靠的是「自动的那一下用户可能没看清，
   手动的那一下他一定看清了」。而 `warningsFor` 那几句每一句都是三四行的完整句子——
   塞进半屏 sheet 里，它们会变成一个需要滚动的小窗口，或者被人顺手折成一句「查看详情」。
   **那两种做法都会让上面那条前提不再成立。** 于是 `Choice.warnings` 里的每一句
   在这一页上逐句原样摆出来，一句都不许折叠、不许省略号、不许「展开更多」。
   同理，「会交给谁」那一行**钉在顶上不随列表滚走**——它是这一页上唯一一句
   用户做决定时非看不可的话（决策(188)），而列表往下滚两屏之后它就再也不在视野里了。

**另外几条界面上的小取舍（都写在文件注释里）：**
- **进这一页不自动弹键盘**，和 `SearchScreen` 反着来。搜索页是用户专门点搜索图标进去的，
  他进去就是要打字；这一页他点进来是为了看看有哪几条，而默认清单第一段
  往往就是他要的那条。一进来就顶上一块键盘，等于把那一段挤出屏幕。
- **搜不到时不给「新增一条」那个出口**（搜索页上有）。那一页在应用里，用户坐下来在建条目；
  这一页浮在一个正等着他登录的表单上面。让他此刻去走一遍新增三步流，
  回来时这次填充会话早就没了，而他手上那个登录框还空着——那是把人送进一条死路。
- **填不出东西的那一条画成禁用而不是藏起来**（同决策(174) 的思路）。
  藏起来的后果是用户在这一页上找不到他明明记得存过的那一条，然后开始怀疑库里的数据没了。
- **够格自动的那两档只给一个不打眼的黄铜点，不写「推荐」两个字**——
  那是在替用户排序，而他来这一页正是因为我们排的那个序里没有他要的那条。
- 挑中一条之后按返回，**退的是「这一条」而不是整页**：直接退出的话，
  用户想换一条就得从头再点一次填充条。
- `query` 用 `remember` 而不是 `rememberSaveable`（同 `SearchScreen`，理由也相同）。
  这一页比搜索页更该守这一条——它的宿主 Activity 浮在别人的应用上面，
  被系统回收重建的概率高得多。

**为什么 `UnlockHost` 从 `private` 放宽到 `internal`：** 挑选页也会遇到「库锁着」，
那一刻它要摆的是和跳板页一模一样的两屏。抄一份过去的后果是——某天有人给解锁流
加一条新规矩而另一份没跟着改，于是同一个 App 里，从填充条上解锁和从挑选页上解锁
行为不一样，而没有任何一处能解释为什么。

**已验证的性质（那 28 条）：**
四档相位各自到得了且互不重样；库开着摆清单、进来时锁着先解锁；
**摆着清单时被自动锁定当场收起来、解开之后回到清单而不是一片空白**；
没有可填的框 / 是自己的界面各摆哪一句、**拒绝排在库状态之前**（锁着也不先要主密码）、
拒绝那句话原样传出去一个字不改写；交过答卷就走且连拒绝那一句也不再摆、
库被删掉时安静走人、没有库时拒绝那一句仍然排在前面；
「会填哪几格」两格 / 一格 / 重复格位只说一遍 / 空清单整行不出现 / **里面不会出现任何一个值**；
网页那一屏说「这个网站」、原生那一屏说「这个应用」、没有主表单时不抛异常；
**十一句文案里没有一句说成故障**（不出现「失败 / 出错 / 错误 / 稍后重试 / 异常」）、
搜不到时不把人支去新增条目、四句分段标题互不重样；
交回去那一份也是两行字没有密码、不带标记、**再洗一遍不会洗出第二种结果**（幂等）、
名称和账号都空时退回那两句现成的话。

**这一步能上机验证的（M4 整条路第一次能从头走到尾）：**
1. 设置 → 系统 → 自动填充服务 → 选「本地保险库」。
2. 打开 Chrome，进一个登录页，点账号框 → 填充条上该有候选，**末尾多出一行
   「在保险库里搜索…」**（之前这一行是没有的）。
3. 点那一行 → 挑选页浮出来 → 顶上一条黄铜色的「这些内容会交给 Chrome
   （com.android.chrome），填进它正在显示的 example.com 页面里」。
4. 挑一条**存了别的站**的条目 → 确认屏上该有那一句「这一条存的网址和这一屏不是同一个站」
   （决策(191) 那一句，自动那一侧永远看不到它）。
5. 按确认 → 退回浏览器，账号密码当场填上。
6. **同屏两组框的那一条**：找一个内嵌 iframe 的登录页，光标放在主表单上，
   手动挑一条 → 只有主表单那两格被填上，另一组一个字都不写（决策(187)）。
7. **自动锁定那一条（这一步最要紧，也最容易漏）**：点开挑选页，
   放着不动等过自动锁定的时长 → 清单该当场收起来变成解锁屏；解开之后回到清单。
8. 库里一条都没有 / 这个站一条都没对上 → 填充条上**只剩那一行搜索**（不再是「什么都不出」）。
9. logcat 看 `AutofillPick` / `AutofillSvc`：**每一行只有数字和原因，
   包名、主机名、条目名一个都不打**（决策(144)）。第 5 步那一下打的是「手动挑 → 写 N 格」。

**M4-2b 到此收尾。下一步是 M4-3（保存流程）。**（M4-3a 已完成，见下一节。）

---

### M4-3a 保存内核 ✅
| 文件 | 作用 |
|---|---|
| `ui/autofill/SavedFields.kt` | 保存这一路**独立的**字段模型（决策(165) 欠的那一份）+ 值的收与拒 + `SaveContext` |
| `ui/autofill/AutofillSave.kt` | 该不该弹 / 新增还是更新 / 改了哪几样 / 存成什么样 / 全部文案 |
| `ui/autofill/SaveHandoff.kt` | 明文不进 `Intent` 的一次性交接槽（带票号、取一次就清、五分钟兜底） |
| `src/test/.../AutofillSaveTest.kt` | 75 个用例，纯 JVM 可跑 |

改动的既有文件（**一处**）：
- `AutofillPick.kt` —— `identify()` 从 `private` 放宽到 **`internal`**（理由同 `UnlockHost`：
  「这些内容会交给谁」和「这一条会记在谁名下」是同一条规矩的两个方向，
  抄一份过去的后果是某天两页对同一个应用的称呼不一样，而没有任何一处能解释为什么）。
  **一个字的逻辑都没改。**

**这一层和前面九个内核的区别：方向反过来了。**
M4-1 到 M4-2 那一整条链回答的都是「往外交什么」，错了是把密码交给不该交的人。
这一层往**库里**写，错了换了一种形状，而且换得比想象中重：

- 存错一条，库里就长期躺着一条错误的关联，**以后每一次自动填充都用它**——
  一次填错只是一次，存错一次是从此以后每一次；
- 「改错一条」更狠：把 A 账号的密码覆盖到 B 账号那一条上，**旧值当场没了**。
  这个 App 没有条目级历史版本，也没有撤销（同决策(157)），
  用户下次登录 B 时会发现密码不对，而他手上再没有第二份。

**四条新守卫：**

1. **模型是另一份，`RawField` 上一个 `text` 字段都没加（决策(195)）。**
   决策(165) 在 M4-1b-1 就写下了这句话，欠的东西正是 `SavedFields.kt`。
   加一个字段确实省事，当天也不会有任何症状；代价在别处——`RawField` 是
   `FieldRoles` / `FieldGroups` / `FillPlan` 三层的输入，它一旦抱着明文，
   那三层的每一个 `toString`、每一条日志、每一个异常消息就都成了泄露点，
   而那三层里有几十个分支根本不需要知道值是什么。
   现在这条边界是**物理的**：填充那条路上的对象，编译期就装不下用户打的字。

2. **收值只做取舍，不做改写（决策(195) 后半）。**
   洗字符串是这个工程里的常规动作（`AutofillRow.clean` 那三件事），
   但那道洗是**给屏幕看的**。这里洗出来的东西要被**存进库、以后原样填回登录框**——
   一个被「压成一行、剔掉控制字符」之后才存下去的密码登不进任何网站，
   而用户要到下次登录时才发现，且不会想到是保存那一步动的手。
   所以：**要么原样收下，要么整格拒收**。唯一的例外是账号的首尾空白
   （那一头的空白几乎总是键盘带进来的，而密码那一头相反，见文件里那段）。

3. **不够格自动填的来源，永远只能新增，不能更新（决策(199)）。**
   后面四档在**填充**那一侧是「不自动出手，但用户可以手动挑」——挑错了顶多是这次填错。
   保存这一侧不能照搬：一个套着 WebView 假冒登录页的应用（`UntrustedHost`），
   拿到的不只是这一次输入，它还能借这个保存框**改掉用户库里那条真的**，
   于是用户以后每次登录**真网站**填出去的都是被改过的值。
   新增的代价是库里多一条他看得见、删得掉的东西；更新的代价是一条他看不见、也找不回来的丢失。

4. **明文不进 `Intent`（决策(198)）。** `putExtra("password", pwd)` 看起来只是
   「传给我自己的另一个页面」，实际上要经过 `system_server`：extras 会被 parcel 出去、
   在系统进程里被解析、排进 `ActivityManager` 的记录，还会被 `dumpsys activity` 打出来。
   `DraftHandoff` 早就为**搜索关键词**画过同一条界限，理由一字不差；
   这里只是同一条界限上更硬的一段。进 `Intent` 的只有一个票号——**一个数字什么也说明不了**。

**几条落笔时的取舍（都写在文件注释里）：**
- **判断顺序：拒绝排在库状态之前**（同 `AutofillPick.refusal` / `AutofillOffer.respond`）。
  「是不是我们自己的界面」「有没有读到值」「两个密码框分不分得清」三问都不需要知道库的任何事。
- **「库里已经一模一样」这一句只能在解锁之后说**（决策(197)）。库锁着时我们数不出库里有什么，
  也就没法提前知道这次是白跑。宁可让用户白解锁一次，也不能因为库锁着就把他刚打的密码丢掉——
  刚注册完那一次正是最值钱、也最不可能再打一遍的一次。
- **只读到密码、这个站却有好几条 → 一条都不动**（`CannotTellEntry`）。
  分屏登录第二屏没有账号可对，猜错就是把另一个账号的密码覆盖掉。
- **`domainLine` 存归一后的主机名，不上卷到可注册域；而建议的名称反过来用可注册域**（决策(202)）。
  两者故意不一样：名字是给人看的标签，那一行是凭据的适用范围，
  而扩大匹配面是这条链上唯一一个代价大的方向。
- **屏幕上永远不摆密码**：`Change.shown` 在密码那一档构造时就是 `null`。
  用户要确认的是「改的是哪一条、动了哪几样」，不是核对密码字符串——
  把库里那个旧密码也摆出来，才是这一屏上唯一真正新增的泄露面。
- **不给按的画成禁用而不是藏起来**（同决策(174)）：藏起来的后果是用户找不到
  他明明选中的那一条，然后开始怀疑功能坏了。
- 那三档非自动的警告**原样复用 `AutofillPick.warning`，一个字都不改写**——
  同一件事在两页上说成两个样子，用户会以为那是两件事。

**已验证的性质（那 75 条）：**
密码一个字符不动 / 账号只剔首尾 / 空的与全空白收不下 / 超长整格拒收不截断 /
控制字符与双向控制符**整格拒收而不是洗掉** / 新密码压过已有密码 /
两个密码框值一样不算分不清、不一样就算；
自己的界面不存、没读到值不弹、两个分不出新旧的密码一个都不存、
**三条拒绝一次都不用碰库**（空库也给同样的答案）、只读到密码而这个站有两条时一条都不动、
恰好一条时改那一条、一模一样时安静走人、六档理由互不重样且没一句说成故障；
账号逐字相同的那条被改 / 对不上就新建 / 好几条同账号取最近改过的且其余进 `alternatives` /
**不够格自动填的来源只能新建** / 原生屏上不去改那条存网址的 / `updatable` 只收两档 /
硬换到不够格或别人账号的条目上**不给按**、账号是空的那条可以补上账号；
**更新时名称 / 分类 / 备注一个字不动、已有账号绝不被换掉、已有网址一行不删只追加、
同一个站不会被重复追加（哪怕写法不一样）**、密码一样不列改动、空的算 `Add` 不算 `Replace`、
什么都没变的提案不给按、**整份提案里只有密码可能是 `Replace`**、更新那条 id 不变、新建那条 id 是空的；
密码那条改动永远不带值、`Proposal` / `Change` / `Value` / `SaveContext` 四个 `toString` 一个字内容都不吐、
每条改动念得出一句互不重样的话、换密码那句说清旧的会没、
「记在谁名下」永远带包名且读不到名字时不写「未知应用」、应用名里的双向控制符被洗掉；
网页存主机名 / 原生存包名 / 名字用可注册域且和存下去那一行故意不一样 / 名字也要洗一道；
一切照常时一句废话不说、改密码那屏先说一句、**不认识的承载应用上新建时那一句要靠承载应用去问
（verdict 永远是 `None`，问它是问不出来的）**、认得的浏览器和原生屏都不说那一句；
同样输入算两遍结果一样、存过一次再存同样的东西就没得改了、换目标后 `alternatives` 里没有它自己；
票号里没有明文、取一次就清、票对不上拿不到**而且不许清掉别人那一份**、过期的拿不到且照样被清、
同时只留一份、`clear` 之后什么都不剩。

**这一步能上机验证的：还是什么都没有。**
`onSaveRequest` 和确认页在 M4-3b。这一步把「一屏刚提交的登录表单 → 一份提案」
这条路在代码上打通了，差的只有薄壳去调它。

---

### M4-3b-1 保存的判断壳与相位机 ✅
| 文件 | 作用 |
|---|---|
| `ui/autofill/SavePlan.kt` | `SaveInfo` 该不该挂 / 看着哪几个框 / 哪个必填 / 那个旗子加不加 |
| `ui/autofill/AutofillSaveFlow.kt` | 确认页此刻该摆哪一屏 + 那一屏上由状态拼出来的全部文案 |
| `src/test/.../SavePlanTest.kt` | 28 个用例，纯 JVM 可跑 |
| `src/test/.../AutofillSaveFlowTest.kt` | 30 个用例，纯 JVM 可跑 |

改动的既有文件：**一个都没有。**（`AutofillSave.kt` 一行都没改，如待办里写的那样。）

**M4-3b 拆成两步的理由，和 M4-2a / M4-2b 是同一个：** 那一步原本要一口气做完
`SaveInfo` 的挂法、`onSaveRequest` 的读值、`AutofillSaveActivity`、Compose 确认页，
四样里只有前后两头是判断，中间两样是壳。判断混在壳里就再也测不到了——
而这一层的每一种错法在真机上都**不报错**。所以先把判断钉死，
M4-3b-2 那层壳就只剩「句柄换 `AutofillId`、读一遍 `getAutofillValue()`、照相位摆屏」。

**`SavePlan` 守的两件事，两件都是静悄悄的：**

1. **少挂一个框 → 保存框一次都不出现。**
   用户在改密码页把新密码打完、提交成功，什么都没发生。他不会来报告这件事，
   只会觉得这个功能不太行，然后回去手工复制粘贴。**这是这条链上最容易发生、
   也最不会被发现的失败。**
2. **多挂一屏 → 用户按下保存，然后什么都没发生**（`AutofillSave.refuse` 在那一步拒绝）。
   向用户要一次确认再告诉他这次做不成，比一开始就不问糟得多。

**这一层和 `FillPlan` 方向相反，两处刻意不复用（写在文件头上）：**
`FillPlan` 问「哪一组**填得出**东西」，`SavePlan` 问「哪一组用户**刚往里打了**东西」。
两个问题在大多数一屏上答案相同，但有一屏正好相反，而那一屏恰恰最要紧——
**一个只有新密码框的改密码页**：填充那一侧对它的答案是「一个框都不填」（底线一），
`Form.targets` 是空的，`FillPlan.pick` 会直接跳过它；保存这一侧对同一组的答案是
「这正是要看的那一组」。所以 `pickForSave` 是另写的，
**不许改成调 `FillPlan.pick` 来省事**：那一改当天没有任何症状，
代价是改密码页从此再也不弹保存框。`SavePlanTest` 里那一条会当场红。

**几条落笔时的取舍：**
- **所有密码框都看，包括新密码框**——和 `FillPlan` 底线一方向相反。
  注册页和改密码页上，用户刚打进去的值**只在**新密码框里。
  哪一个最后被存下去不在这一层定，那是 `SaveContext.effectivePassword` 的事。
- **required 只放一个**，而且优先是新密码框。系统对 required 的语义是
  「这几个框全都有值，保存框才弹」——把「密码 + 确认密码」两个都放进去，
  只要用户跳过了那个可选的确认框，保存框就再也不出现，
  而他刚注册完，那个密码此刻只存在于他的短期记忆里。账号也一律进 optional
  （分屏登录第二屏根本没有账号框）。
- **`FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE` 网页加、原生不加。**
  网页登录成功后 Activity 一动不动、只是 DOM 换了一批节点，不加它保存框永远不出现；
  原生应用一般会换 Activity，加上它的代价是用户只关了个浮层就被弹一次保存框。
- **不看值就知道存不成的那两档提前挡掉**（`OwnUi` / `AmbiguousPasswords`），
  但 `AutofillSave.refuse` 那三条一条都不删——从挂 `SaveInfo` 到 `onSaveRequest`
  之间页面完全可能又变了一次，护栏要长在落笔处。
- **「只有账号框」那一屏要说得出自己那一句**（`NoPasswordField`，不是 `NoForm`）。
  对着一个正盯着屏幕上那个账号框的人说「这一屏上没有认得出来的登录表单」，
  是一句假话，而 M4-4 的关于页要照着这几档念。

**`AutofillSaveFlow` 守的三件事，三件在真机上都看不出来：**

1. **摆着确认单被自动锁定。** 那一屏上有用户刚打的账号、有他库里那条条目的名称，
   浮在别人的应用上面；**而且这一页手上还揣着一份明文密码**。
   所以这一相位的动作不只是换屏，还要 `SaveHandoff.clear()` 一次
   （`SaveHandoff` 文件头第 3 条纪律的落点）。
2. **页面被回收后重建，`SaveHandoff.take` 第二次拿不到。** 这一档必须是 `Leaving`，
   否则用户看到的是一个按下去什么都不会发生的「存进保险库」按钮。
3. **解锁之后才算出来的那两档拒绝必须说一句，不能安静关掉**——
   这是这一页和挑选页**唯一**不一样的地方。`AlreadyStored` 和 `CannotTellEntry`
   都只能在解锁之后才知道（决策(197)），而用户刚刚为它输了一次主密码。
   屏幕直接关掉的话，他对这次交互的全部印象是「我解了锁，然后它闪了一下就没了」。

五个相位：`Refused` / `Unlocking` / `Working` / `Confirming` / `Leaving`。
`Working` 那一档看起来多余（提案算得很快），但没有它，
「解锁完成」到「提案算好」之间会有一帧摆着空清单的确认页，而那一帧上的按钮按得下去。

**两处刻意复用而不是抄一份：** `entryLabel` 直接调 `AutofillOffer.labelOf`
（同一条条目在填充条上和这一页上不许叫两个名字），
`WARN_HEADING` 直接等于 `AutofillPickFlow.WARN_HEADING`
（同一件事在两页上说成两个样子，用户会以为那是两件事）。

**一处刻意和别处相反：** `finalName` 对用户自己打的名字**一个字都不洗**，
只 `trim` 首尾空白——`AutofillSave.suggestedName` 那一串是被保存对象提供的
（应用名、主机名），可能塞着 `U+202E`，必须洗；这一串是用户坐在我们自己的界面上
一个键一个键打的，和他在 `EntryForm` 里给条目起名字是同一件事。
名称留空也**不拦着不让走**（退回建议名），理由同挑选页那条不给「新增一条」出口：
用户此刻站在一个正等着他登录的表单前面。

**已验证的性质（那 58 条）：**
自己的界面一个框都不看且包名不写死 / 一个框都没有和只有验证码框都算没有表单 /
只有账号框那一屏说得出自己那句 / 两个分不出新旧的密码框提前跳过 /
四档跳过话不重样且没一句说成故障；
登录表单看两个框、分屏第二屏照样挂、**注册页那两个新密码框都看**、
**只有一个新密码框的改密码页照样挂（且它在填充那一侧确实是空的）**、
当前密码和新密码记成两档、账号只看第一个、验证码框一个都不看；
**必填永远只有一个**、必填是新密码框而不是当前密码框、第二个新密码框进可选、
账号永远进可选、必填加可选正好等于看着的那几个框；
光标那一组优先、光标落在没密码的那组时挑真正带密码的那组、原生框和网页框不凑一组、
一组都没有时返回 -1；网页加旗子原生不加；三个 `toString` 一个内容都不吐、算两遍一样；
库开着摆确认单 / 提案没算出来时摆等待 / 库锁着摆解锁 /
**摆着确认单被自动锁定当场收回去** / 库被删掉和交接单取不到都安静走人 /
交接单没了盖过库开着也盖过手上那份提案 / 存完就走别的一概不问；
不碰库的三条拒绝排在库状态之前、**解锁后才算出来的那两档要说一句而不是走人**、
拒绝那句一个字不改写、六档理由都摆得出话；
新增和改动在顶栏和按钮上说成两句不一样的话、改动那行叫得出被改的那一条；
名称空了退回账号再退回那一句、没有条目时也是那一句、条目名里的双向控制符被洗掉、
没有账号的条目第二行不写密码；
**用户自己打的名字一个字不改只剔首尾空白**、留空退回建议名、建议名也空退回「未命名」；
警告原样在前一句不少、设新密码那屏末尾补那一句、**新注册也要补**、一切照常时一句废话不说；
警告小标题和挑选页共用一个、按钮上的字互不重样；相位 `toString` 一个内容不吐。

**这一步能上机验证的：还是什么都没有。**
`onSaveRequest`、`AutofillSaveActivity` 和 Compose 确认页在 M4-3b-2。
这一步之后，那一层壳里该剩的只有平台活。

### M4-3b-2① 服务接线与读值 ✅
| 文件 | 作用 |
|---|---|
| `ui/autofill/SaveCapture.kt` | 看着的那几个框 → 一份 `SaveContext` + 一份只有数字的记账 |
| `ui/autofill/SaveShell.kt` | **全工程唯一一处 `getAutofillValue()`**，走一遍树读值 |
| `src/test/.../SaveCaptureTest.kt` | 27 个用例，纯 JVM 可跑 |

改动的既有文件（三个，全是接线）：
- `AutofillResponses.kt`：加 `saveInfo()`（`SavePlan.Decision` → `SaveInfo`）与 `saveOnly()`，
  `datasets()` / `unlock()` 各多收一个 `save: SaveInfo?`（**刻意不给默认值**，见下）；
- `VaultAutofillService.kt`：`compose()` 里并排算一次 `SavePlan.decide` 并挂上，
  `onSaveRequest` 从空实现变成「重新解析 → 决策 → 读值 → 记账」；
- `AutofillUnlockActivity.kt`：交回去那份响应也挂 `SaveInfo`（见决策(208)）。

**`SavePlan.kt` / `AutofillSave.kt` / `AutofillSaveFlow.kt` / `SavedFields.kt` /
`SaveHandoff.kt` 一行都没改**，如 M4-3b-1 末尾所写。

**M4-3b-2 拆成两步的理由，和 M4-2a / M4-2b / M4-3b 是同一个：** 那一步原本要一口气做完
`SaveInfo` 的挂法、`onSaveRequest` 的读值、`AutofillSaveActivity`、Compose 确认页。
前两样是**管道加一层可测的收值内核**，后两样是**一页界面**——
两类活的错法完全不同（前者错在「不报错地读错一格」，后者错在「摆错一屏」），
混在一轮里写，收值那一层就又测不到了。

**这一层的三种错法，三种都不报错：**

1. **一格读不出来就整份作废。** 分屏登录第二屏根本没有账号框，`SavePlan` 也照样把它挂上了
   （那一屏的密码正是最该存的东西）。一格取不到值就返回 null，表现是
   **分屏登录的站从此再也存不进东西**——而分屏登录是大站的主流形态。
   所以规矩是一格一格收，收得下几格算几格；「够不够存」由 `AutofillSave.refuse` 判。
2. **顺手去重。** 在这一层写一句 `distinctBy { it.what }` 来「清理一下」，
   改密码页就只剩一格，`effectivePassword`（新的压过旧的）变成一场碰运气；
   而 `SaveContext.conflictingPasswords`（分不清就一个都不存）也当场失效。
   **一格都不合并、一格都不排序**，`SavePlan.of` 给的顺序原样保持。
3. **一格抛异常带走整个回调。** `onSaveRequest` 里一次未捕获的异常，
   用户看到的是别人的应用旁边弹了一条「保险库已停止运行」。
   抓的是 `Throwable` 不是 `Exception`——低版本上平台 getter 缺失时抛的是
   `NoSuchMethodError`（同 `AssistShell` 文件头那段）。

**`SaveShell` 只读 `getAutofillValue()`，不读 `getText()`（决策(206)）。**
后者是给屏幕和无障碍看的那一份，密码框上它可能是**一串圆点**——
存进去之后用户下次填出去的就是一串圆点，而当时屏幕上明明白白写着「已保存」。
它和 `AssistShell` 是一对镜像：那一个走同一棵树却一次都没读过值（决策(165)），
两个文件**分开**就是那条边界的物理形式。三个上限直接用 `StructureRules.Limits` 那一份，
不另立一套——两套上限会造出「填充那侧收了这个框、保存那侧走不到它」这种
只在超大页面上才发作的错。

**填不出东西 ≠ 不值得看着（决策(204)）。**
新注册那一屏正好两头都占：填充这一侧本来就没什么可填，保存这一侧却是这条链上最值钱的一屏
（一个刚生成、刚被网站接受、且只存在于用户短期记忆里的密码）。
所以 `Silent` 那一路不再返回裸的 `null`，而是退成一份只挂 `SaveInfo` 的响应
（`AutofillResponses.saveOnly`）。**锁着也照样挂**：保存这一路一次库都不用打开，
要解锁是等用户站到我们自己的确认页上之后的事。反过来写的代价是
「自动锁定过了的手机上，登录成功后一次也不会被问要不要存」。

**`save` 参数刻意不给默认值。** 给了 `= null` 之后，漏传的那一处会安静地编译过去，
表现是「某一条路上保存框永远不出现」——而这一层的每一种失败都是这个形状。
不给默认值，编译器就替我们把三处调用点全指出来了（决策(208) 那一处正是这么被发现的）。

**已验证的性质（那 27 条）：**
登录页两格都读到 / 分屏第二屏只有密码框照样收得出 / **账号那格读不出来密码那格照样收下** /
注册页读新密码框 / 改密码页两格都读且新的压过旧的 / **只有一个新密码框的改密码页收得出东西**；
**两个密码框两个不一样的值两格都留着** / 收下来的顺序和看着的那几个框一模一样 /
同一个 `what` 装两格时「分不清该存哪个」那条判据还在；
**密码那格一个字符都不洗（首尾空白留着）**、账号那格剔首尾空白；
空的记 `blank` 不记拒收、超长整格拒收记 `tooLong`、控制字符整格拒收记 `control`、
**一格都没收下时也给出 `SaveContext` 而不是 null**（那一档要说得出「没读到可以存的东西」）；
一格抛异常别的格照样收、**一格抛 `Error` 也接得住**、每格都抛也不会抛出这个函数；
收下的加丢掉的正好等于看着的那几格、`watched` 就是那几个框的个数；
归属和「这一屏在做什么」原样带过去、应用名读不出来就是 null 不编兜底字符串；
`toString` 三个都不吐值也不吐主机名和应用名、数字都在；同一份输入算两遍一样。

**这一步能上机验证的：第一次有了。**
挂 `SaveInfo` 已经接通，真机上**系统那个保存框会开始出现**，
logcat 里看得到 `看着 n 必填 + m 可选` 和 `收值：Tally(...)` 两行。
但**按下去目前什么都不会发生**——`SaveCapture.Capture` 眼下只进一行日志，
既不进 `SaveHandoff` 也不拉起页面，而这不是漏了两行：
往进程里放一份**没人来取**的明文密码，正是 `SaveHandoff` 文件头三条纪律反对的东西，
而那上面唯一能兑现「清掉」的两个时机（页面结束、自动锁定）在②之前一个都不存在。
所以照决策(132) 的老规矩：**M4-3b-2② 之前不要出内测包。**

### M4-3b-2② 确认页与落盘 ✅
| 文件 | 作用 |
|---|---|
| `ui/autofill/AutofillSaveScreen.kt` | 确认单那一屏、「换一条」那一屏、拒绝那一屏、算提案那一帧 |
| `ui/autofill/AutofillSaveActivity.kt` | 接票取货、按相位摆屏、**全工程唯一一处自动填充落盘**、明文三个清点 |

改动的既有文件（两个，都是接线）：
- `VaultAutofillService.kt`：`capture()` 末尾补上 `handOff()`（`SaveHandoff.offer` + 拉起页面），
  以及那两段过时注释（「①到这里为止」）；
- `AndroidManifest.xml`：加了第三个 `<activity>`，属性和另外两页**逐条相同**。

**`AutofillSave.kt` / `SavePlan.kt` / `AutofillSaveFlow.kt` / `SaveCapture.kt` /
`SaveShell.kt` / `SavedFields.kt` / `SaveHandoff.kt` 一行都没改**，如待办里写的那样。
**这一步没有新增测试**：新加的两个文件一个是 Compose 页面，一个是 `android.*` 外壳
（`FragmentActivity` / `Intent` / `WindowManager`，纯 JVM 测不了）。
这一页的可测部分在 M4-3a 和 M4-3b-1 就已经全部测完了
（`AutofillSaveTest` 75 条 + `SavePlanTest` 28 条 + `AutofillSaveFlowTest` 30 条），
这也正是当初把 M4-3b 一层层拆开的目的。

**手上那份明文，三个清点全在这一页上兑现**（`SaveHandoff` 文件头三条纪律）：
`take` 取一次就清（槽自己做的）、`onDestroy` 清一次、**自动锁定清一次**。
第三条有一处写错了当天没有任何症状：**不能一看见 `Locked` 就清**。
库锁着正是这条路最常见的入口（决策(204)：锁着也照样挂 `SaveInfo`），
进来时就锁着的话，一看见 `Locked` 就清等于用户还没来得及解锁、要存的东西已经没了——
而他刚打的那个密码此刻只存在于他的短期记忆里。所以清那一下由 `sawUnlocked` 守着，
只在**曾经解锁过之后又锁上**时才走。清掉之后下一帧相位自然是 `Leaving`，
这是有意的（`AutofillSaveFlow.Unlocking` 那段：躺了超过一次锁定周期的明文，宁可丢掉）。

**服务那一侧补的不是两行，是三件事：**
1. `SaveHandoff.offer` **必须排在拉页面那一步**前面。反过来写会出现
   「页面已经 take 过一次（拿到 null 走人）、我们随后才把东西放进槽」的时序，
   表现是保存框按下去闪一下就没了，而槽里还留着一份没人取的明文——两头都占。
2. **拉不起页面时要自己 `SaveHandoff.clear()`**。那时槽里躺着一份
   **没人会来取**的明文，而三条纪律里的第 2、3 条都长在确认页上，那一页根本没起来。
3. `Intent` 里只有那张票（一个数字），决策(198) 在这一层的落点。

> ⚠️ **上面第 2 条当时的写法是错的，M4-3c 才修掉**（决策(221)）：
> 那一版是服务自己 `startActivity`，而这条路在 Android 10 及以上会被
> 后台启动限制**静默**拦下——不抛异常，所以那个 `onFailure { clear() }` 一次都不触发。
> 真机症状：系统保存框弹得出来，按下「保存/更新」之后确认页一次都不出现、
> 库里什么都没变、logcat 里本应用侧最后一行是 `收值：Tally(...)`。
> 现在 28+ 走 `SaveCallback.onSuccess(IntentSender)`，由系统去拉；26/27 才走旧路。

**这一页上没有一处出现密码**，而且是**类型保证**不是纪律：
`AutofillSave.Change.shown` 在密码那一条上永远是 null（构造时就没赋值），
所以屏幕上摆的是「密码会被换掉」这句话本身，不是两个密码。
**别在这儿加一个「点一下看看存的是什么」的眼睛图标**——这一页浮在别人的应用上面，
那个眼睛是整条链上唯一会把明文画进那种窗口的东西。

**名称栏只在新增那一档画出来**，更新那一档根本没有它（不是画成禁用）。
决策(201)：用户当初给那一条起的名字是他在列表里认出它的唯一依据，
这一页上摆一个能改名的输入框，等于给了一条「顺手把它改掉」的路。

**几处落笔时的取舍：**
- **改动清单排在警告前面**，和挑选页那一屏（警告在前）反着来。那一页用户已经知道
  自己要哪一条（他刚点的），要提醒的是「交出去之后会怎样」；这一页他还不知道
  我们打算动哪几样——先把动作摆清楚，再说要小心的地方。
- **「换一条」走 `AutofillSave.proposeUpdate` 重算，不在页面里改几个字段。**
  两道护栏（不够格自动填的不许改、账号对不上的不许改）长在那个函数里，
  而护栏必须长在落笔处。备选池要**把原来那一条放回去**，否则换过去就再也换不回来。
- **落盘失败停在原地**，提案一个字不动（同 `AddEntryScreen` 最后一步）。
  把他退回别人的应用去，等于要他重新登录一次才能再被问一遍。
  失败时**绝不置 `committed`**——那是屏幕说存好了而磁盘上没有。
- **拒绝那一屏不复用挑选页的 `AutofillRefusalScreen`**，虽然两屏几乎一样：
  那一个标题写死成「要填哪一条？」，摆在这一页上是一句和处境无关的话。
  复用一个只差一个字符串的组件，代价是它很快会长出第二个、第三个参数。
- **`Working` 那一档摆的是一屏空的、什么都点不动的东西，不是转圈。**
  那一帧短到看不见，而一个来得及被看见的转圈会让人以为这一步很慢。

**这一步能上机验证的（M4 保存这条路第一次能从头走到尾）：**
1. 在浏览器或某个应用里登录成功 → 系统保存框弹出 → 按「保存」 →
   **确认页浮出来**（顶上一条「这一条会记在 …… 名下」，中间「会改成这样」逐条摆开）；
2. 库锁着时：先摆解锁那两屏，解锁之后才算提案（logcat 看不到明文，只有 `Tally(...)`）；
3. 按「新增这一条」→ 回到保险库能看到那一条，名称、账号、网址都对；
4. 已经存过同样的账号密码 → 解锁之后摆一句「没有需要改的地方」，**不是安静关掉**；
5. 摆着确认单切出去等自动锁定 → 切回来是解锁屏，再解锁一次会安静走人（明文已丢）；
6. 转屏 / 后台被回收 → 页面不会带着一份空确认单回来。

**M4-3b 到此收尾，决策(132) 那条「②之前不要出内测包」解除。**
下一步是 M4-4（开关与交代）。


### M4-4a 开关与交代 ✅

M4 从 4-1a 一路写到 4-3b，填和存两条路早就通了，但**开启的入口一直不存在**——
只能靠用户自己在系统设置里翻到「自动填充服务」那一项。这一步是那个缺口，
角色和 M3-6b-1（快捷解锁的绑定页）在工程里完全一样。

| 文件 | 作用 |
|---|---|
| `ui/settings/AutofillSettingsModel.kt` | **新增**。四档可用性、那一行开关长什么样、「为什么有时候不出现」七条、设置主页那一行的副标题。**无一行 `android.*` / Compose** |
| `ui/settings/AutofillAvailability.kt` | **新增**。碰平台的那一整侧：问 `AutofillManager` 三个方法、跳系统那两个 Intent。里面一个判断都不做 |
| `ui/settings/AutofillSettingsScreen.kt` | **新增**。开关一行 + 三条底线 + 七条「为什么不出现」+ 收尾那一句 |
| `src/test/.../AutofillSettingsModelTest.kt` | **新增** 17 个用例，纯 JVM 可跑 |

改动的既有文件（五个，全是接线）：
- `SettingsScreen.kt`：新增 `onAutofill`（**刻意不给默认值**，同 `onSecurity`）、
  「自动填充」单独一格摆在「备份」上面、页内问一次系统状态并接 `ON_START` 重问。
- `AboutScreen.kt`：新增 `onAutofill`（同上），自动填充那一格末尾补一条**指路牌**
  （`ABOUT_POINTER`），**不是那七条的副本**——见决策(212)。
- `SecuritySettingsScreen.kt`：`findComponentActivity` 从 `private` 提为 `internal`。
  **这是这次唯一一处改了既有实现的地方，而且没有改一行逻辑。**
- `Routes.kt` / `VaultNavHost.kt`：`Route.SETTINGS_AUTOFILL`，**只注册到已解锁那张图**。

**这一步没有动依赖，没有动 `AndroidManifest.xml`，没有加新图标，没有加新组件。**
`AutofillService` 那一侧（`VaultAutofillService` / `AutofillResponses` / `FillPlan` /
`SavePlan` / `AutofillPick` …）**一个字都没改**——这一步从头到尾没有碰过填充和保存那两条路。

三件这一页上和别的设置页不一样的事：

1. **这一页管的东西我们说了不算。** 是不是默认填充服务由系统那张列表决定，
   而 Android **没有**给应用「把自己撤下来」的 API
   （`disableAutofillServices()` 管的是另一件事：让本应用**自己的界面**不被别的填充服务填，
   名字长得像，做的事正好反过来）。所以那个开关两个方向拨都只是跳出去，
   而「已经是默认」那一档必须自己把这件事说出来——见决策(209)。
2. **四档，不是一个布尔值。** 「系统里没设过」和「设的是别人」在屏幕上都是开关关着，
   但后一种点下去会把用户正在用的那个密码管理器顶下去。见决策(210)。
3. **这一页大半屏是解释，不是设置。** M4 一路的克制在用户那边全都长成同一个样子：
   什么都没弹出来。见决策(211)。

**真机验证**：至少走三条路——(a) 一个没设过填充服务的系统；(b) 系统里已经设了别的
密码管理器；(c) 已经设成本应用，去点那个开关，确认它跳到系统设置而不是原地弹回。
每一条都要在从系统返回之后看那一行有没有自己刷新（靠 `ON_START`）。

---

### M4-4b 内联建议 ✅

Android 11 起，填充候选可以画在**输入法的建议条**上，而不是浮在输入框下面那一层。
这一步把 M4 那三处填充条各配一份内联版。**M4 到此全部收尾。**

分两小步写，都在同一轮里做完：

| 文件 | 作用 |
|---|---|
| `ui/autofill/InlinePlan.kt` | **新增**（4b-1）。摆几格 / 摆哪几条 / 每一格用第几份规格。四档 `Why`、`MAX_CHIPS = 4`。**无一行 `android.*`** |
| `ui/autofill/AutofillRow.kt` | 纯加法。`Chip` + `chipForItem` / `chipForUnlock` / `chipForSearch` + 两个更短的上限（24 / 28）。走的是**同一道洗** |
| `ui/autofill/InlineViews.kt` | **新增**（4b-2）。`InlineSuggestionsRequest` → `Ask`、`Slot` → `InlinePresentation`。碰平台的那一整侧，里面一个判断都不做 |
| `src/test/.../InlinePlanTest.kt` | **新增** 31 个用例，纯 JVM 可跑 |
| `src/test/.../AutofillRowTest.kt` | +6 个用例（内联那一格的洗、更短的上限、密码不出现） |

改动的既有文件（四个，全是接线）：
- `AutofillResponses.kt`：`datasets` / `unlock` 各多一个 `inline` 参数（**刻意不给默认值**，
  同 `plan` 那一处）；三处各挂一份内联版；新增 `setChip` 把那句 SDK 检查收成一处。
  锁着那一条走的是 API 30 的四参 `setAuthentication`（多一个 `InlinePresentation`），
  11 以下原样走三参那条。
- `VaultAutofillService.kt`：`compose()` 里多一行 `InlineViews.from(request)`，往下传。
- `AutofillUnlockActivity.kt`：交回去那份**明确传 `inline = null`**，理由写在调用点上（决策(217)）。
- `res/xml/autofill_service.xml`：`supportsInlineSuggestions="true"`。

**这一步动了依赖**：`androidx.autofill:autofill:1.1.0`——M4 唯一新增的一个，
而且只用到 `InlineSuggestionUi` 一个类。**它不带权限、不联网、不带资源**，
`AndroidManifest.xml` 一个字没动，`uses-permission` 仍然只有 `USE_BIOMETRIC` 一条。

四件这一层上和浮层那一层不一样的事：

1. **`supportsInlineSuggestions` 是总闸。** 少了 XML 里那一个属性，
   `getInlineSuggestionsRequest()` 永远返回 null，代码一行不报错、日志一句不说，
   表现只是「键盘上那一条永远不出现」——这一步最容易漏掉的就是它。
2. **全有或全无（决策(214)）。** 摆不齐就整份退回浮层。而**浮层那一份永远都在**：
   内联只是给 `Dataset` 多挂一份画法。待办里「不能两条都不出」这句话，
   在代码里就是 `dataset()` 里那一句注释旁边的两行。
3. **搜索那一格永远留着（决策(215)）**，兄弟域那几条不进内联（决策(216)），
   它们都计进那一格上的「还有 N 条」——于是内联那个数字和浮层那一行**可以不一样**。
4. **解锁跳板那一份不带内联（决策(217)）**：规格属于当时那次输入法会话，
   跨过一整屏解锁页之后再用是错的。

**这一轮的用例是真的跑过的**：写作环境里破例装了一次 Kotlin 编译器，
`InlinePlan.kt` + `AutofillRow.kt` 连同 `InlinePlanTest.kt` 在纯 JVM 上编译并执行，
31 条全通过（`AutofillOffer` / `FillPlan` 用等价桩顶替）。
碰平台的那三个文件仍然**没有编译过**——那需要 Android SDK 和 Compose，环境里没有。

**真机验证**：要三台（或三种系统）——(a) Android 10 及以下：确认填充条还是浮层那一条，
一条候选都不少；(b) Android 11+ 配 Gboard 一类支持内联的输入法：确认建议条上出现
候选 + 末尾那一格搜索，**兄弟域那几条不在上面而在浮层里**；(c) Android 11+ 配一个
不支持内联的输入法：确认自动退回浮层，而不是两条都不出。
另外单独走一遍**锁着**的情形（自动锁定之后切到浏览器点输入框）：
建议条上应该只有「解锁本地保险库」一格，点它 → 解锁 → 当场填上（那一步回到浮层，
决策(217)）。logcat 看 `AutofillSvc` 那两行 `内联：…`，里面只有数字。

---

---

## 关键设计决策（后续不要推翻）

**① 两层密钥**：主密码 →(Argon2id)→ KEK →(AES-GCM 包裹)→ 库主密钥 →(AES-GCM)→ 数据。
改密码只需重包主密钥；生物解锁是把同一个库主密钥用 Keystore 硬件密钥再包一份。
**主密码始终是唯一的真凭据，生物识别只是快捷方式。**

**② 6 位数字不能当主密码**。文件被拷走就能离线爆破，10⁶ 组合撑不住。
分工是：文件用长口令派生，日常解锁走生物/PIN——后者由 Keystore 硬件限速，6 位才安全。
原型里的数字键盘保留，但它是 PIN 键盘，不是主密码键盘。

**③ 不做局域网直传**。安卓上开任何 socket 都必须声明 `INTERNET`，
那等于放弃最大卖点。改成导出加密文件后交给系统共享面板／Nearby Share，传输由系统完成。
原型里的扫码配对页要相应改掉。

**④ 用 AES-256-GCM 而不是 XChaCha20-Poly1305**。ARMv8 有 AES 硬件指令，
而 XChaCha20 在 Android 上必须再背一个原生依赖。cipherId 已写进文件头，将来要换不受阻。

**⑤ 单文件整库加密，不用 SQLCipher**。解锁后整库读进内存，保存时整体重写。
好处是「保险库文件本身就是备份」，换机迁移天然成立；代价是条目上万时要改增量方案，
但个人用户的真实量级在几百条。

**⑥ PIN 是安全的，靠的是设备绑定而不是长度**。
6 位 PIN 只有 10⁶ 种组合，单靠它必然被爆破。所以做了两层：
`PIN --Argon2id--> pinKey --包裹--> 库主密钥` 得到 blob，blob 再用
Keystore 的设备绑定密钥包一层。攻击者拷走整个数据目录也没用——外层解不开，
他必须在这台机器上、在本应用进程里试，于是落进 `AttemptLimiter` 的退避。
**原型上那个数字键盘因此成立，但它是 PIN 键盘，不是主密码键盘。**

**⑦ 连错 N 次绝不清库**。那是把一个拒绝服务漏洞送给任何能碰到手机的人
（小孩乱按十次，全部密码没了）。我们只做延迟 + 关掉快捷解锁，数据永远不动。

**⑧ 删除保险库不做覆写擦除**。SSD/eMMC 有磨损均衡和 FTL 映射，
往同一路径写随机数根本盖不到原物理块，只是自欺欺人还磨损闪存。
真正的保障来自 Android 全盘加密。

**⑨ 永远不联网抓 favicon**。图标用名称哈希取色 + 首字母，见 `VaultEntry.tileColor`。

**⑩ 主密码输入框走 EditText 互操作，不用 `BasicTextField`**。
Compose 的输入框值类型是 `String`，敲一个 20 位主密码就在堆里留下 20 个擦不掉的前缀副本。
`EditText` 的 `Editable` 底层是可写 char[]，用完能原地覆盖成全零。
同时关掉 `isSaveEnabled`（否则转屏时密码进 Bundle）、关掉 autofill、关掉长按复制菜单。
**条目里的密码字段不用这套** —— `VaultEntry.password` 本来就是 String，在那儿绕开只是自我安慰。

**⑪ 三张互不相通的导航图，不是一张大图**。
「未建库 / 已锁定 / 已解锁」是三种权限状态而非三个页面。共用一张图会让 back stack 跨状态残留：
自动锁定发生在详情页时，重新解锁后按返回可能回到本该清掉的界面。
状态一变整棵子树连同 back stack 一起换掉，代价是没有页面级过渡（用淡入淡出兜底）。

**⑫ 图标全部 Canvas 手绘**。material-icons-extended 会塞进上千个用不到的矢量资源，
而 Google 正在把图标产物往 material3 里搬，绑死一个正在搬家的坐标只会给升级添堵。

**⑬ 剪贴板计时器挂在 Application scope，不是 Activity**。
「复制密码 → 切浏览器粘贴」是最常见路径，计时器不能因为界面进后台就停摆。
清除前会核对剪贴板里躺着的还是不是我们放进去的那份（靠 ClipDescription 里的一次性 token）。

**⑭ 弹窗必须自己声明 `FLAG_SECURE`**。Compose 的 `Dialog` 是一个**独立的 Window**，
Activity 上设的 `FLAG_SECURE` 不会传下去。哪天有人在弹窗里带出账号或密码明文
（「确定删除『招商银行 / 138****』吗」这种很自然就会写出来），那一屏就是可截图可录屏的。
所以全工程的弹窗一律走 `VaultDialog`，里面把 `SecureFlagPolicy.SecureOn` 写死。

**⑮ 弹窗的「次按钮」和「取消手势」必须是两个回调**。
合并成一个，在「次按钮才是危险动作」的场合会出人命：
弱口令确认框的主按钮是「回去改」、次按钮是「就用这个弱口令」，
一旦合并，用户点一下弹窗外面的空白就无声无息建了个弱口令保险库。
**取消手势永远只能意味着「什么都别做」。**

**⑯ 主密码的输入与确认在同一屏**。分两页意味着第一页的密码必须活过一次页面切换，
要么阻止 `SecureTextState` 自动擦除，要么先复制一份存进跨页面的持有者——
两条路都是给主密码多开一个副本、多加一段说不清何时结束的生命周期。
同屏两个框则同生共死，比对走 `contentEquals`（逐字符异或、不早退、不产生 String）。
所以没有 `CREATE_CONFIRM` 路由。

**⑰ 「首次备份」属于主图，不属于引导图**。放在引导图里，「必须备份」这条规矩
就只在建库那一次会话里成立：用户建完库把 App 划掉，下次进来再也不会被要求备份。
改成由主图按 `meta.lastBackupAt == 0L` 判断，没备份过就一直挡在前面。

**⑱ 导出必须「写完再读回来比一遍」**。一份打不开的备份比没有备份更糟：
没备份的人知道自己没备份，拿着坏备份的人以为自己安全了，
等发现不对时原设备通常已经不在了。所以三道检查缺一不可 ——
写前用库主密钥解一遍（拦代码 bug）、写后逐字节回读比对（拦截断和没截断的旧内容）、
两道都过才写 `lastBackupAt`。**「已备份」这个标记只能由验证过的事实产生，
不能由「用户点过导出按钮」产生。**

**⑲ 走 SAF，绝不申请存储权限**。`WRITE_EXTERNAL_STORAGE` 会让权限清单不再干净，
用户在应用信息里看到「存储」，「这个 App 什么权限都没有」当场作废。
SAF 一个权限都不用声明，位置还随便挑（本机 / U 盘 / 用户自己装的网盘客户端）。
另外 `openOutputStream` 必须用 `"wt"`：只写 `"w"` 时部分 provider 不截断，
用小文件覆盖大文件会留下尾巴。

**⑳ 拉起系统界面要先打「可信中断」的招呼**。文件选择器、指纹弹窗、系统分享面板
都会让 Activity 走 `onStop`，从会话看和用户按 Home 键离开一模一样，
自动锁定随即开始倒计时——默认 60 秒，翻两层文件夹就超了；
把自动锁定设成「立即」的用户**永远做不完一次导出**。
所以要区分「用户离开了」和「我们把用户送出去了」，后者给 180 秒宽限。
宽限是有限的：他可能在选择器里按了 Home 键把手机往桌上一放。

**㉑ 「跳过备份」是允许的，但它不会消失**。强制备份会把用户锁死在一个
他此刻可能完不成的动作上（空间满了、公司手机禁用了选择器），结果是卸载。
跳过不写 `lastBackupAt`，于是下次解锁照样挡在前面 ——
一次不痛不痒的打断重复到做完为止，比一堵墙有效。

**㉒ 导入侧不认扩展名，只认文件头**。系统文件选择器会按 MIME 类型改写扩展名
（某些 ROM 把 `.lvault` 变成 `.lvault.bin`）。认扩展名的话，
用户重命名一次文件就打不开了。`VaultFile` 头部那六个字节的 `LVAULT` 才是身份证。

**㉓ 退避三个入口共用一份，但「关掉快捷解锁」只看快捷解锁自己错了多少次**。
共用退避是为了堵住「换个门再来」——分开计数的话，攻击者会挑没被锁的入口继续试。
但 `shouldDisableQuickUnlock` 必须分开：它的用意是「有人在爆破 6 位 PIN，把这道
10⁶ 的门关掉」，而**主密码**连错十次说明的是「用户记不清主密码了」，
这时候把他唯一还记得的 PIN 也关掉，等于亲手把他锁在门外，攻击者毫发无伤。
所以 `QuickUnlock` 多了一个 `quickFailCount`。

**㉔ 「输错了」和「出故障了」必须分开处理**。只有 `WrongPasswordException` /
`WrongPinException` 计入退避。文件损坏、读盘失败、Keystore 抽风都不算——
它们既不是攻击的迹象，也不是「等一会儿」能解决的问题。把故障也算进去，
结果是一块坏掉的闪存把用户锁在自己的数据外面 15 分钟，
而他真正需要的是赶紧看到「请用备份恢复」这句话。

**㉕ 「上次是被自动锁定的」要用状态传，不能用事件传**。
`Event.AutoLocked` 是 `SharedFlow` 的一次性事件，而解锁页是**锁定之后才创建的**：
`lock()` 先翻状态、导航相位随即换掉整棵子树，新页面再去订阅时事件早发完了。
于是那句提示永远不会出现，自动锁定看起来就像应用自己崩了一次。
所以额外留一个 `lastLockReason` 状态，由解锁页在创建时读一次。

**㉖ 解锁页不显示任何库内信息**。没有条目数、没有最近修改时间、没有库的名字。
捡到手机的人不需要打开保险库，光看到「37 条 · 2 分钟前更新」就已经知道
这台设备值得带走。这一屏唯一的信息是封条上的加密参数，而那个反正写在文件头里。

**㉗ 生物识别的失败一律不计入我们自己的退避**。指纹认不认得出来是 BiometricPrompt
和安全硬件之间的事，那边已经有自己的限速（连错五次锁 30 秒，再错锁死）。
我们再罚一次是同一件事收两遍钱：用户的手指湿了几次，换来的是连主密码都要等 15 分钟，
而攻击者根本不会走这条路。判断标准始终是——**这次失败有没有消耗掉一次猜测机会**。
指纹没有，PIN 和主密码有。

**㉘ 不允许「设备锁屏凭据」作为生物识别的回退**。`setAllowedAuthenticators`
只给 `BIOMETRIC_STRONG`，不加 `DEVICE_CREDENTIAL`。加上确实更方便，
代价是把整个保险库的强度降到手机锁屏密码那一档——而锁屏密码常常是 4 位、
经常当着人输、家人多半也知道。我们的回退是**用户专为这个库设的主密码**。

**㉙ 指纹解锁的正确性由安全硬件保证，不由我们的判断保证**。
常见写法是「弹指纹框 → 回调说成功 → 把密钥读出来」，那种写法里指纹只是一道
界面上的关卡，改一行让回调直接返回成功，密钥照样到手。
我们走 `CryptoObject`：那份包裹是用 Keystore 里「每次使用都要认证」的钥匙加密的，
没真的通过认证，`cipher.doFinal()` 算不出结果。
**没有 `CryptoObject` 的认证成功回调一律当失败处理。**

**㉚ 「锁死」不删绑定，「指纹库变更」才删**。永久锁定只是暂时进不去，
用户拿设备锁屏凭据解锁一次就恢复了，替他删掉等于替他做了一个他没同意的决定，
还得回设置页重新绑一遍。而指纹库变更会让 Keystore 那把钥匙真的作废，
留着一份永远解不开的密文只会让每次解锁都多失败一次。

**㉛ PIN 按满六位不自动提交**。自动提交在别处很常见，但这里一次失败的代价更大：
第 5 次错开始退避，第 9 次错要等 15 分钟。而 PIN 恰恰是不看屏幕、凭肌肉记忆按的东西，
最后一位按错的概率不低——自动提交意味着连改正的机会都没有，
眼睁睁看着一次机会被烧掉。这条在 M3-1 写 `Keypad` 时就定了，这一步只是兑现。
指纹可以自动弹出，因为它不消耗猜测机会（见㉗）。

**㉜ 备注和密码永远不参与搜索**。搜索结果必须能解释「这一条为什么会出现」，
而唯一诚实的解释方式是把命中的原文亮出来。备注恰恰是用户放密保答案、
身份证号、银行预留手机号的地方——让它可搜，等于一次随手的搜索就把这些东西
摊在屏幕上，而用户按下那几个字时完全没预期会看见它们。
密码可搜就更直接：那是给「肩窥 + 猜前几位」开门。
可搜字段是一张**白名单**（名称 / 账号 / 网址 / 分类），不是「目前只实现了这几个」。

**㉝ 域名归一只做语法剥离，一个子域名都不剥——`www.` 也不剥**。
剥 `www.` 看着无害、业界也都这么干，但它是「哪些子域名算同一个站」这个滑坡的
第一步，而那件事必须靠公共后缀表认真做，属于 M4 的域名归属校验
（AutoSpill 那一类攻击正是从这儿进来的）。搜索里图省事先剥一层，
将来两边规则对不上，就会出现「搜出来是这一条，填进去是另一条」。
不剥也不影响手感：`www.example.com` 里的 `example` 紧跟分隔符，本来就算词首命中。
**`VaultIndex.normalizeDomain` 将来要和 M4 共用同一份，不许各写各的。**

**㉞ 备份提醒按「改了多少条」发，不按「多少天没备份」发**。
「距上次备份已 90 天」会在用户三个月什么都没改的时候照样每天弹一次，
而那三个月里他手上那份备份**一直是完好的**。骚扰换不来备份，
只换来用户学会无视横幅——等真有 20 条改动没进备份时，他也不会看了。
所以提醒的触发条件是 `updatedAt > lastBackupAt` 的条数。
反过来，两个条件都不满足时**什么都不显示**：
「你的备份是最新的」这种绿条是拿一整行屏幕说废话，看多了会让要紧的那条也被略过。

**㉟ 中文列表按拼音排，靠 `Collator`，不自己维护拼音表**。
`String.compareTo` 排的是码点，于是「北京银行」排在「安居客」前面
（北 U+5317 < 安 U+5B89），用户完全说不出这份列表按什么排的，
也就没法预测想找的那条在哪儿。同理**不做 A–Z 首字母索引条**：
取「微信」的首字母 W 要一张几千字的拼音表，塞进一个以「依赖少、体积小」
为卖点的 App 里不划算；只对英文名做索引条更糟——中文用户会得到一根
几乎全部落在「#」上的字母条。分组用用户自己写的分类，信息量比机器猜的首字母大。

**㊱ 收藏的条目只出现在「常用」组，不在自己的分类组里重复**。
重复会让顶栏那个「37 条」和用户拿手指头数出来的行数对不上，
而一个说不清自己到底有多少条数据的密码管理器，很难让人相信它没弄丢东西。


**㊲ 搜索关键词绝不落盘：不做搜索历史，也不用 `rememberSaveable`**。
关键词**本身就是库内容的投影**——用户打下「招商」两个字，这两个字就等于
「这个库里有招商银行」。做一份「最近搜索」，等于在那个加密文件之外
又开了一份未加密的目录索引，而这个产品的整个前提是「除了那一个文件，
别处不留任何库内容」。`rememberSaveable` 是同一个洞的另一个入口：
它会把字符串写进 `savedInstanceState`，那也是明文落盘。
代价是转屏丢掉正在输入的关键词，这个代价我们认。

**㊳ 关键词交接走内存槽，不走路由参数**。`nav.navigate("add?name=招商")`
最省事，也最像无害的一行代码，但路由参数会随 back stack 进 `savedInstanceState`，
正是㊲要堵的那条路。所以有了 `DraftHandoff`：挂在**已解锁那张图**上，
锁定时随整棵子树一起消失（决策⑪），没有人需要记得清空它。
`Routes.kt` 顶上那条「只允许条目 id 进路由」的界限，到这一步第一次被真正考验。

**㊴ 高亮的窗口以命中为中心开，不从头截**。「一行放不下就在末尾加省略号」是
界面层的默认行为，而它会把命中处整个吃掉：
`zhangsan_backup_2019@company-mail.example.com` 搜 `example`，
尾部截断后屏幕上剩下 `zhangsan_backup_2019@compan…`，命中的字一个都没有。
那一行于是变成一条**没有理由的结果**，而㉜的整个立论就是「结果必须能解释自己」。
关键词比窗口还长时宁可这一行更长，也不切掉高亮。顺带：不从代理对中间切开——
条目名里放 emoji 很常见，切一半会留下一个方框，看起来像数据坏了。

**㊵ 结果行要标出命中的是哪个字段**。只有高亮不够：用户搜一串数字，
一条高亮在账号上、一条高亮在网址上，不标字段的话这两行看起来一模一样。
名称命中**不标**——名称本来就占着一行的主位，再写一次「名称」是拿屏幕说废话。

**㊶ 搜不到时给的是「新增一条」，不是「换个词试试」**。搜不到最常见的原因
不是拼错，是这条压根还没存进来：用户刚在某个网站注册完，回来找发现没有。
让他退出搜索、点右下角加号、再把刚打过的字重打一遍，是三次没必要的操作。

**㊷ 空关键词时不列全库**。列表页已经是那份清单，搜索页再列一遍会让人
以为这是两份数据。那块屏幕用来把㉜那张白名单**明明白白写出来**更值：
备注和密码不参与搜索是个刻意的决定，但用户不知道——他会拿备注里的
身份证号去搜，搜不到，然后合理地认为这个搜索坏了。

**㊸ 分类快捷键就是把分类名填进关键词，不是另一套筛选状态**。
真做成独立筛选，马上要回答「筛了『银行』又搜『招商』是 AND 还是 OR」，
还得在界面上表达「你正处于筛选中」。而分类本来就是可搜字段，
填进去的结果几乎一样，还顺带把名字里带「银行」却没归类的那几条也捞出来——
那通常正是用户想要的。

**㊹ 搜索不做防抖**。全库已经在内存里，几百条的线性扫描是微秒级。
加一层 debounce 只会在按键和出结果之间插进一段用户能感觉到的延迟，
还引入「刚打完就按返回、最后一次搜索还排在队里」的竞态。
真到条目上万那天（⑤已写明那时要改增量方案），再一起处理。


**㊺ 删除立刻落盘，撤销靠内存快照——不做「延迟 5 秒再真删」**。
两种做法的**失败方向相反**：延迟删除失败时（进程被杀、自动锁定、崩溃，
任何一种都会让那个还没执行的删除凭空消失），结果是**用户以为删了、其实还在**；
立刻落盘 + 内存撤销失败时，结果只是「想撤的时候撤不回来」。
密码管理器必须选后者——「以为删了其实还在」意味着他把手机卖掉、送修、
交给别人时以为已经清干净了。撤销要连**原来的位置**一起恢复：
撤销的语义是「什么都没发生过」，那就该连底层顺序一起没发生过。

**㊻ 删完停在墓碑页，不自动退回列表**。「删完跳回列表 + 底部飘一条
『已删除·撤销』」基本没人点得到：跳回去的同一瞬间列表在重排，
用户的注意力全在「我那条到哪去了」，5 秒就过去了。停在原地给了撤销一个明确的落点，
顺便让用户确认删掉的确实是这一条。附带好处是撤销状态不必跨页面传递，
于是也没有「撤销状态跨越一次自动锁定活下来」这种隐患。

**㊼ 遮蔽只防「路过一眼」，不防「拿到手机的人」**。所以详情页
**不做「显示 30 秒后自动变回圆点」**——显示明文的唯一用途就是照着抄，
倒计时恰好会在抄到一半时把内容抽走；也**不做「切后台回来自动重新遮蔽」**——
库这会儿还开着，能看到屏幕的人自己点一下眼睛就行了，遮蔽拦不住他，
真正拦得住的是自动锁定（默认 60 秒），而它已经在了。
加一个只能骗自己的开关比不加更糟，它会让人以为多了一层保护。
页面销毁时状态自然消失，所以「进详情 → 编辑 → 返回」后密码重新遮住，
这条是 `remember` 的生命周期本来就给的，不用额外写代码。

**㊽ 圆点用固定 12 个，不按真实长度画**。按长度画等于把密码位数印在屏幕上，
而位数是离线爆破时最值钱的一条边信息——8 位还是 20 位，工作量差十几个数量级。
方向和「解锁页不显示任何库内信息」（㉖）一致：遮起来的东西不该顺便交代自己有多长。

**㊾ 备注默认也藏起来**。理由和「备注不参与搜索」（㉜）是同一条：
备注恰恰是用户拿来放密保问题答案、身份证号、银行预留手机号的地方。
详情页一打开就把它摊平，等于在地铁上点开一条记录就把身份证号亮给旁边的人——
而他按下那一条时想找的多半只是账号。

**㊿ 危险操作放在滚到底的全宽按钮上，不放顶栏图标里**。
顶栏那个 44dp 方块和收藏、编辑挤在一起，误触代价却完全不同：
点错收藏无非多一次改动，点错删除是把一条密码删了。
危险动作要让人走到它跟前，并且看见「删除这个条目」五个字，
而不是看见一个需要辨认的小图标。

**(51) 复制之后不弹任何提示**。顶部封条上那条剪贴板倒计时就是回执，
而且比一句「已复制」有用——它同时告诉用户还有几秒会被清掉。
再叠一个 toast 只是把同一件事说两遍，还会挡住刚复制的那一行。
和「不做成功绿条」是同一条规矩。另外**剪贴板标签只写字段名，绝不带条目名**：
「招商银行的密码」印在系统剪贴板面板上，等于把「这台手机的主人有招商银行账户」
告诉了每一个能读剪贴板描述的应用。

**(52) 切换收藏也算一次改动，照样计入「未备份改动数」**。
有人会想给收藏开个后门（不刷新 `updatedAt`），让备份提醒安静一点。
但收藏确实要写进文件——开了后门，「你的备份是最新的」就成了假话。
宁可提醒多响一次，也不能让备份状态说谎。

**(53) 详情页不提供「打开网址」**。一个密码管理器把用户送进浏览器、
然后期待他在那儿粘贴密码，正是钓鱼最好的入口；而那条链接的可信度
完全来自用户自己当初粘进来的字符串。真正的正解是 M4 自动填充——
在**校验过归属的域名上**自动填，而不是给一个可点的链接。

**(54) 动态验证码字段留在数据模型里，但界面上一行都不画**。
显示一个点了没反应的「验证码」行比不显示更糟：用户会把它当成能用的功能，
然后在需要的时候发现它是个摆设。字段留着只是为了将来不用改文件格式。

**(55) 表单切成「内核 + 字段块 + 页面」三层**。这套字段 M3-5 新增流的最后一步
要原样复用。两边各写一份的话，「网址怎么切行」「名称要不要 trim」「密码能不能 trim」
这几条规则马上就会分叉，而分叉的表现是**同一个库里两种数据**——
新增流进来的条目网址带着 `https://`，编辑页改过的不带，
将来 M4 自动填充按哪一份匹配都对不齐。
所以规则全落在 `EntryForm`（可单测），字段块是一个 Composable，页面只管导航和保存。

**(56) 网址只丢，不改写；但去重按归一后的形式做**。
空白、空行、重复的会在保存时丢掉，**留下来的那些一个字符都不动**：
用户打的是 `https://mail.example.com/inbox`，存进去就是这一串。
顺手在这里调一次 `normalizeDomain` 把它收敛成 `mail.example.com` 看着更整齐，
但那是悄悄改写用户输入——他保存完看到的东西和刚才打的不一样，
而屏幕上没有任何地方解释是谁改的。归一属于**匹配**环节（M4），不属于存储环节，
这正是决策㉝画的那条界限。去重是唯一用得着 `normalizeDomain` 的地方
（`example.com` 和 `https://example.com/login` 指的是同一个主机），
而它**复用 `VaultIndex` 里那一份**，不另写——㉝那句「不许各写各的」的字面兑现。
同一组里留**第一次出现的写法**，那多半是用户自己打的，后面的往往是粘贴带进来的长串。

**(57) 密码不做 trim，其它字段做**。空格完全可以是密码的一部分。
替用户把首尾空格去掉，结果是他下次登录不上，而他永远猜不到是谁改的——
他能看到的只有一串圆点，圆点和圆点之间没有任何地方写着「这里少了一个空格」。
其它字段则必须 trim：账号、网址、分类几乎都是粘贴进来的，而粘贴几乎必然带走一个
尾随空格或换行；一个开头带空格的名称还会在列表里排到所有条目最前面（Collator 排序），
用户完全看不出为什么。备注 trim 首尾但保留中间的换行——它本来就是多行文本。

**(58) 名称是唯一必填项，密码可以为空**。确实有人拿它当通讯录用，只记账号不记密码
（详情页的 `EntryDetail.rows` 早就支持空密码不占位了）。名称不行，
因为列表和搜索都靠它认人：它是列表行的主位，也是搜索白名单（㉜）里
唯一命中了不用标字段名的那个。没有名称的条目在列表上就是一行空白。

**(59) 编辑页不自动保存**。「改一个字就落一次盘」在笔记应用里是对的，这里是错的：
一是条目一落盘 `updatedAt` 就刷新，列表页那条「有 N 条改动还没进备份」（㉞）跟着涨——
用户点进来看一眼、改了个字又改回去，不该因此欠下一次备份；
二是自动保存意味着「改错了想撤回」没有落点，而编辑没有墓碑页（㊺那种）可依靠，
真正的撤销落点就是**还没按下的那个保存按钮**。
代价是要自己处理「改了没存就返回」，那就是 (60)。
附带一条实现上的坑：原始草稿必须用 `remember(entryId)` 而不是 `remember(entry)`——
拿 entry 当 key 的话，那条条目的对象一换新，原始草稿就被重算成当前值，
脏检查立刻变 false，用户改了半天的东西不再受拦截保护，返回时一声不吭全丢。

**(60) 「放弃修改」弹窗：主按钮是「继续编辑」，次按钮才是「放弃修改」**。
决策⑮在这里第二次被兑现（第一次是弱口令确认框）：危险动作放次按钮，
而**取消手势（点外面、按返回）只能意味着「什么都别做」**，也就是停在编辑页。
反过来写的话，用户点一下弹窗外面的空白，刚改的东西就无声无息没了。
弹窗那行小字只列**改了哪几个字段**，一个字段值都不带——弹窗是独立 window（⑭），
而「密码将从 …… 改回 ……」这种话写起来非常自然，它会把两个密码同时摆上去。
这条由 `EntryFormTest` 里那个「摘要里绝不出现任何字段的内容」的用例盯着。

**(61) 没有改动时保存按钮是灰的，而且要说明为什么灰**。点一下「保存」照样会刷新
`updatedAt`，让「有 N 条改动还没进备份」凭空 +1，而用户其实什么都没改。
但灰着的按钮必须配一句话（「还没有改动。」／「名称是唯一必填项……」）——
一个没有解释的灰按钮，用户第一反应是「这个 App 卡了」。

**(62) 编辑页进来不自动弹键盘**。用户点铅笔多半是冲着某一个字段来的，
自动聚焦到名称会把键盘顶起来遮掉半张表单，还容易让他以为光标所在的地方
就是他要改的那一行。新增流不一样——那一步的第一个动作确实就是打名称，
由 M3-5 自己决定。

**(63) 备注在编辑页是摊开的，不像详情页那样默认藏起来**（对比㊾）。
详情页藏它，是因为用户点进一条多半只想看账号，不该顺手把身份证号亮在地铁上；
而编辑页是他自己主动来改东西的，一个默认藏起来的输入框会让「我到底改没改」
变得看不出来，也会让他在看不见原文的情况下往里追加内容。

**(64) 网址用一个多行框，不做「一行一个输入框 + 加号」**。
后者要处理增行、删行、焦点往哪跳，而真实使用里绝大多数条目只有一个网址。
多行框还顺带解决了最常见的那个动作：从浏览器地址栏整条粘进来，回车换行。
敢按空白切行，是因为**合法的网址和安卓包名里不可能出现空白字符**，
一行里出现空格只有一种解释——用户从别处粘了一串过来。

**(65) 分类用「输入框 + 已有分类快捷片」，不做下拉选择器**。
真做下拉，马上得再设计一套「管理分类」的界面（改名、删掉一个还有条目在用的分类、
空分类要不要留着）。快捷片直接复用 `VaultIndex.categories`，点一下就是把分类名
**填进输入框**，同时不挡着他随手写一个新的——和搜索页的分类快捷键（㊸）同一个做法。

**(66) 编辑页现在不放「生成密码」按钮，只留一个可空槽位**。
生成器是 M3-5 的交付物。先摆一个点了没反应的按钮比不摆更糟，用户会把它当成
能用的功能，然后在需要的时候发现它是个摆设——和 (54) 是同一条。
`EntryFormFields` 的 `onGenerate` 参数为 null 时那个按钮根本不画，
M3-5 接上去时不必再动这个文件的其它部分。
**（M3-5a 已兑现：编辑页现在传的是真回调，槽位一行没改。）**


**(67) 生成器不是一个路由，也不是一个 Dialog**。它必须把生成出来的密码**交回**
调用它的那一页，而页面之间回传值的正规通道是 `savedStateHandle` ——
那是一个 Bundle，会被系统写进 `savedInstanceState`，等于把一个刚生成的密码明文落盘。
这正是 `DraftHandoff` 那一整篇注释要堵的洞（决策㊳），只不过那次漏的是搜索词，
这次是密码本身。做成同一棵 composition 里的覆盖层，结果就是一个普通的 Kotlin 回调。
不用 `Dialog` 则是决策⑭的延伸：`Dialog` 是独立 window，`FLAG_SECURE` 不会继承，
而这一屏上明晃晃摆着一个密码明文——与其依赖「记得设那个 flag」，不如根本不新开 window。
`Route.GENERATOR` 因此从路由表里删掉了，M3-6 设置页里的入口也走同一个覆盖层。

**(68) 「每类至少出现一次」必须靠洗牌，不能靠事后补**。
很多网站硬性要求密码里有大写和数字，所以这条要保证。但天真的实现是
「先纯随机生成，发现缺数字就把第 0 位换成数字」——那会让**第 0 位是数字的概率
被人为拉高一大截**，而这个偏差在屏幕上完全看不出来（每个密码看着都很随机）。
本工程开源，攻击者能照着这个分布优先爆破。正确做法是：每类先抽一个占位 →
剩下的从整池抽 → **整体 Fisher–Yates 洗一遍**。
这条由「用恒返回 0 的假随机源」那个用例钉死：没洗牌时结果必然是 `aA0!aaaa`，
用真随机源则两种实现都只是一串乱码，断言分不出来。

**(69) 生成器显示的是算出来的熵，不是 `PasswordStrength` 估出来的强度**。
那套估算是给**用户自己打的**密码用的：面对来路不明的字符串只能猜。
而这串是我们刚生成的，规则完全已知，熵能算准。更要紧的是拿 `evaluate()` 评自己
生成的密码会**报低**——真随机的 20 位里出现 `abc` 或重复字符完全正常，
而那套估算会因此扣分，于是用户看到「刚生成的密码只有『较强』」，
然后合理地怀疑生成器有问题。随机模式的熵用**容斥**算准（`Σ(-1)^|S|(池−S)^len`），
因为「每类至少一次」是一条约束，`len × log2(池)` 偏高——
报高了不是保守，它会让刚卡在门槛上的密码显示成「强」。复用的只是强度条那个**样子**。

**(70) 符号集是挑过的 23 个，不是「键盘上所有符号」**。排除引号和反斜杠，
是因为它们是所有转义 bug 的源头，而那类 bug 的表现通常不是报错，是
**注册时存成一个样子、登录时比对成另一个样子**——用户拿着正确的密码登不进去，
而这个密码管理器言之凿凿地说密码没错。排除空格，是因为别人的输入框会 trim
（我们自己刻意不 trim 密码，见决策(57)）。排除反引号和 `< > | &`，
是因为这串东西经常被粘进 shell 和配置文件。每位少 0.1 bit，换掉「有百分之几的概率
生成一个用不了的密码」。

**(71) 「避开易混字符」只剔 `0 O 1 l I` 五个**。业界常见的做法把
`5S 2Z 8B 6G 9q` 一并剔掉，那会把字符池砍掉近四分之一（每位少 0.4 bit），
换来的是一个在任何等宽字体里本来就分得清的差别。这个开关的用途是
「我要抄到纸上 / 念给别人听」，不是「让密码好看」。

**(72) 易读模式用拼音音节拼，不内置词表；而且在界面上如实说它更弱**。
diceware 的做法是一份 2048 词的英文词表（每词 11 bit）。不这么做有两个理由：
一是**体积**，一份够用的词表十几 KB，而这个 App 的卖点之一是小到能整个读一遍；
二是**对中文用户没用**——`correct-horse-battery-staple` 好记是因为读的人认识那四个词，
而 `vigilant-plumage-thicket` 和乱码对中文用户的记忆成本几乎一样。
拼音音节念一遍就能复述：`bamo-tenlai` 是可以在电话里报给家人的。
18 个声母 × 14 个韵母 = 252 种音节（约 8 bit），只是每个音节 2–4 个字符。
关键是那句写在屏幕上的话：**它更弱**。不写清楚，「易读」听起来像
「一样安全但更好记」，用户会拿它去开网银。

**(73) 生成结果默认明文，采用之后自动把密码框展开**。详情页的密码默认遮蔽（㊾），
这里刻意反过来：此刻这串字符**还不是任何账户的密码**，泄露它的唯一后果是
用户按一下重新生成；而遮住它就没法核对，核对恰恰是这一屏唯一的用途。
采用之后顺手展开密码框，是因为不展开的话用户看到的是一串圆点——
他必然会去点那只眼睛核对一遍，既然那一下是必然的，不如替他点。

**(74) 生成器的选项活在「已解锁」那张图上，刻意不落盘**。用户会来回开好几次生成器，
每次都把长度从 32 调回 20 会让人恼火，所以要跨页面记住；但**哪怕落进加密的库文件也不行**——
决策⑤是单文件整库加密、保存即整体重写，为了记住一个数字付出一次全库加密和一次 fsync
不划算。锁定时它跟着整棵子树消失（⑪），和 `DraftHandoff` 认下的是同一笔账。

**(75) 长度用步进器 + 预设片，不用滑块**。密码长度是**要被记住和复述的整数**
（「我用的是 20 位」），滑块只能大概拖到那儿；而且拖动会连续触发重新生成，
从 12 拖到 32 的路上屏幕会闪几十次，既晃眼又是几十次随机数调用。
预设片沿用分类快捷片和搜索页快捷键的做法（㊸）：点一下**就是把值填进去**，
不另开一套「你正处于某个预设中」的状态。

**(77) 新增分三步，编辑是一整页**。两者的用户处境完全不同。
编辑是**冲着某一个字段来的**：他知道要改哪儿，一整页摊开让他一眼找到那一行最省事
（决策(62) 说的就是这件事）。新增则是从一张白纸开始，六个空框一次摆出来会让人
先花几秒钟决定「从哪儿填起」，而其中四个其实都可以留空——那四个空框每一个
都在无声地暗示「这里也得填」。分三步不是为了少填，是为了**把「必须填的」
和「可以不填的」在时间上分开**：第一步只有名称是硬要求，后两步全都能空着按下一步。

**(78) 三步共用一个草稿，步骤只是「现在画哪几个框」**。
三步各存一份状态的话，「第二步填的密码要不要在第一步返回时清掉」这种问题
会在每一次前后跳转上重新问一遍，而任何一次答错的表现都是**用户填的东西没了**。
一个 `EntryForm.Draft` 从头活到尾，前后跳转就只是换一组可见的框。
字段块仍然是编辑页那一个 `EntryFormFields`——决策(55) 在这里被真正兑现：
它多了 `visible` 和 `autoFocus` 两个带默认值的参数，
而**编辑页的调用一个字都没改**。两边各写一份的话，
「网址怎么切行」「密码能不能 trim」马上分叉，同一个库里就会出现两种数据。

**(79) 新增流中途一次盘都不落**。和编辑页那条（决策(59)）同源，在这里更硬：
中途落盘意味着库里会出现一条只有名称的半成品，它会立刻出现在列表上、被搜到、
并让「有 N 条改动还没进备份」（㉞）涨一格；用户中途退出后，那条半成品还得他自己去删。
写盘只发生在最后按下「保存」的那一下。

**(80) 第一步自动聚焦并弹键盘，第二步刻意不聚焦**（对比决策(62)）。
编辑页不自动聚焦，是因为用户点铅笔多半冲着某一个字段来，替他选一个只会挡住半张表单。
第一步不存在这个问题：这一屏只有两个框，而他此刻要做的第一个动作确实就是打名称。
**名称已经从搜索页带进来时（㊳ 那个交接槽），聚焦落在账号上**——
那几个字已经在了，再把光标顶在名称上，他得先按一下账号框，而那一下本来可以不用按。
第二步不聚焦，是因为这一步有两条路（生成一个 / 自己打一个已有的），
自动弹键盘等于替他选了后者，而且键盘会把「生成一个强密码」顶出屏幕。

**(81) 返回键 = 上一步，只有第一步的返回才是退出**。
这是三步流最容易做错的一处：把返回一律接成退出，用户在第三步想回去改一个错字，
按一下返回，整条草稿没了。返回键在安卓上本来就是「退一步」，这里的一步恰好就是一步。
顶栏那个返回箭头走同一套逻辑，不能一个是「上一步」另一个是「退出」。

**(82) 一个字都没填时退出不弹任何框**。用户点了加号、看了一眼、按返回——
这是这条流程上最常见的一次误触，而拦截框的全部作用就是让他多点一下。
拦截必须只在真的会丢东西时出现，否则他学会的是闭着眼睛点「放弃」，
等到真有东西要丢的那次也照点不误。判空按 `EntryForm.cleaned` 之后比，
所以网址框里多按的两个回车不算填了东西；但**密码里的一个空格算**（决策(57)）。

**(83) 新增流的密码默认摊开，编辑页默认遮蔽**。这不是不一致，是两种处境：
编辑页里那串东西**已经是某个账户的密码**，一进页面就摊开，等于用户每次去改个网址
都要把密码亮一遍。而这一步的密码要么是他此刻正在打的（打字时被遮住是错字的
头号来源，而这个错字他要到下次登录不上时才会发现），要么是刚生成出来、
还没被任何账户用上的一串字符（决策(73)）。这一屏唯一的用途就是把它弄对。

**(84) 判重只提醒，不阻拦，也不给「打开那一条」的按钮**。
「其实我已经存过了」是新增流独有的错误：用户在网站上重置了密码，回来想更新，
却顺手点了加号，于是库里出现两条「招商银行」，一条对一条过期，
而列表上它们长得一模一样——下次登录不上时他完全不知道该信哪一条。
一个密码管理器给出两个互相矛盾的答案，比给不出答案更糟。
但**同一个站上有两个账号是完全正常的**（私人邮箱和工作邮箱、自己的淘宝和给爸妈注册的），
所以两边账号都填了而且不一样时一律不报——那正是他此刻要做的事。
不给跳转按钮：点过去就是一次导航，而草稿此刻还没有任何落点，
要么当场丢掉，要么再造一套「把草稿存起来」的机制，而那套机制的存放处只能是内存或 Bundle，
后者正是 ㊳ 要堵的洞。这道提醒的用处是让他停一下想想，不是替他做决定。

**(85) 回顾卡上的密码是固定 12 个圆点，而且没有那只眼睛**。
前半条和详情页（㊽）一模一样，连不按真实长度画都一样；在这里更不该按长度画——
用户刚生成完一串 20 位的，回顾卡上画 20 个点等于把长度直接印在屏幕上。
后半条：想核对就点回第二步，那儿本来就是明文。在一张「快按保存了」的屏幕上
再开一个显示开关，是把同一件事做两遍，而多出来的那一遍恰好在最容易被人瞥见的时刻。

**(86) 进度条点得动，但只能往回点（以及往前点到已经满足的那一步）**。
往回改个错字不该有任何门槛。往前则要求沿途每一步都已经满足，
否则点一下进度条就绕过了「名称必填」，而那条规矩是列表和搜索的地基（决策(58)）。
**不摆「跳过」**：后两步的主按钮本来就点得动（那两步没有必填项），
再摆一个「跳过」等于给同一个动作两个名字，用户还得先分辨它们有什么不同。

**(87) 存完去详情页，并把搜索页一起弹掉**。去详情页而不是回列表：
他刚填了三屏，此刻最想确认的是「我填的东西是不是都进去了」，而列表上只看得到一个名字。
`popUpTo(LIST)` 顺手把搜索页也弹掉，是因为他是从「搜『招商』没搜到」进来的，
现在那一条已经存进去了，退回一张显示「没找到」的旧结果页只会让他以为没存上。

**(88) 新条目的 id 靠「存之前和存之后两份列表的差集」拿，不靠 `after.last()`**。
`VaultSession.addEntry` 自己生成 UUID（id 的生成只能有一个地方，否则新增流一个、
导入流一个，迟早会撞），所以调用方拿不到 id。写成 `after.last()` 是依赖
「新条目一定追加在末尾」这个当下成立、但没有任何地方承诺过的实现细节——
哪天排序改成按名称插入，保存完就会跳到一条不相干的条目上，
而这种 bug 在测试里几乎不会被发现。拿不到 id 时退回列表，
**绝不报错**：那一条确实已经存进去了，只是没法直接跳过去。

**(89) 保存失败停在原地，草稿一个字不动**。走到这一步用户已经填了三屏，
把他退回列表等于让他从头再来一遍，而失败的原因（空间满了、闪存出错）
多半重试一次就好了。横幅上要写明「刚填的内容还在」——
不写的话他不敢再按第二次，怕存出两条。

**(76) 「至少留一类」界面和内核各做一遍，这不是重复**。界面把最后一个还开着的
开关变灰**并写明为什么灰**（决策(61)：没有解释的灰控件，用户第一反应是应用卡了）；
内核里 `normalized()` 强制打开小写。两者的职责不同：界面负责让用户明白，
内核负责保证不管将来界面怎么改，都不会走到「字符池为空 → `rnd(0)` 抛异常」——
那种异常在生成密码这个动作上的表现是「点了没反应」。


**(90) 改设置立刻落盘，但**不**计入「未备份改动数」**。前半条和条目一样
（`VaultSession` 第 2 条规矩）：点一下就写文件，没有「保存」按钮，
因为设置项本来就是一次点击表达一个完整意图，不存在「改到一半」的中间态，
也就用不着编辑页那套脏检查和放弃拦截（决策(59)/(60) 管的是表单，不是开关）。
后半条是反直觉的地方：那个计数按**条目的** `updatedAt` 算（决策㉞），
meta 的改动不碰任何条目，于是改设置虽然重写了库文件，提醒却不会涨。
这是故意的——备份要保住的是用户攒下来的账号密码，一个自动锁定时长丢了没人会心疼，
为它响一次备份提醒，等于拿真正要紧的那次提醒去换一件无关紧要的事。
正好和决策(52)（收藏算改动）形成对照：收藏是**条目的内容**，自动锁定不是。

**(91) 自动锁定不给「永不」，剪贴板却允许关掉自动清除**。看起来不一致，其实是两件事。
一个永不自动锁定的密码管理器，在手机被顺走的那一刻等于没有密码管理器——
捡到的人划开最近任务就是一屏明文；而用户想要「永不」的真实动机几乎总是
「老是要重新解锁太烦了」，那件事的正解是快捷解锁（指纹按一下就开），
不是把门一直敞着。所以档位到 5 分钟为止。
剪贴板不一样：那里躺着的是用户**自己主动放进去的一条**，下一次复制就会被覆盖，
而且确实有关掉的正当理由（有些应用的粘贴框反应慢、有些流程要粘到好几处，15 秒真不够）。
硬不给这个开关，用户的替代方案是**把密码打在备忘录里**，那比留在剪贴板里危险得多。
给开关，但把后果写清楚——和「跳过备份允许但它不会消失」（决策㉑）是同一个思路。
顺带：**同一个 `0` 在两张表里意思正好相反**（自动锁定的 0 是最安全的一头，
剪贴板的 0 是最不安全的一头），所以两张表不共用「0 = 关闭」的文案，
排序方向也都统一成「从最安全到最不安全」，用户不必去想哪边是大哪边是小。

**(92) 档位表里没有的值，照实插进去，不四舍五入到最近一档**。
库可能是从别的设备拷来的、可能是将来某个版本写下的（那时档位表也许不一样）、
也可能是用户手改过的备份文件——毕竟整个库就是一个文件（决策⑤），
里面躺着一个 45 或者一个 -1 都不是不可能。
四舍五入的表现是：他打开设置页看了一眼、什么都没点，回头发现自动锁定从 45 秒
变成了 30 秒，而屏幕上没有任何地方交代是谁改的。
**设置页是用来显示用户的库的，不是用来悄悄改写它的**——和「网址只丢不改写」
（决策(56)）、「密码不做 trim」（决策(57)）是同一条。负数只在**显示和比较**时归一成 0，
不回写。

**(93) 关掉剪贴板自动清除之后，那条横幅改成常驻的，不是干脆不显示**。
决策(51) 定的是「复制之后不弹任何提示，顶部那条倒计时就是回执」。
一旦倒计时不存在，那条回执也跟着没了，于是点复制变成一个毫无反馈的动作：
用户不知道成没成功，会再点一次，甚至怀疑按钮坏了。
所以照样发一条，只是它不倒数、也不自己消失，一直挂到手动清除或复制别的东西为止
（`totalSeconds == 0` 就是这个标记）。它一直挂着不是副作用，是有意的——
那条横幅本身就在提醒「你现在有一份密码躺在剪贴板里」，而这正是关掉自动清除的代价。
颜色也从玉色（「一切按计划进行」）换成黄铜色（「需要你留意」）。

**(94) 关于页写的是事实和「没有的东西」，不写故事**。上半部分是能被核实的参数
（版本、真实 KDF 档位、库文件多少字节），下半部分是这个 App **没有**的东西——
权限只有一条、没有埋点、没有账号、没有内购。对一个本地密码管理器来说，
那些「没有」恰恰就是它的功能，而且每一条用户都能自己验证：权限列表去系统设置里看一眼、
断网用一整天、卸载前后翻一遍文件管理器。
**不写不能核实的话**：「军工级加密」「绝对安全」一个都不用——它们没有信息量，
还会连累旁边那几条真话（一份夹着广告词的说明书，读的人会自动把整页都打个折）。
权限清单那一条有测试盯着（只能有一项，且不许出现「网络 / 存储 / 位置」字样）：
它拦不住有人同时改 Manifest 和这里，但能保证那次修改是**故意的**——
「应用信息里的权限列表是空的」是欢迎页三条承诺里的第一条，
哪天它变成谎话，那会是最难被发现的一种，因为界面看起来完全正常。
另外**不印库文件的绝对路径**，只印大小：那个路径在应用私有目录里，
没有 root 的用户按图索骥也打不开，印出来只会让人去找一个找不到的东西；
他真正拿得到、也真正需要知道位置的那一份是导出的备份文件，
而那个位置是他自己在系统文件选择器里挑的。

**(95) 设置页不做「安全评分」**。「你的安全指数 82 分」看着专业，有两个问题：
一是构成完全是我们自己编的（凭什么开指纹加 15 分、剪贴板 30 秒扣 5 分），
二是它会让用户去**刷分**而不是理解风险——为了那 100 分把自动锁定调成「立即」，
用两天就烦了，索性把整个 App 卸了。这一页只做两件事：如实显示当前是什么状态，
以及在某个设置真有代价时把代价写出来。而且**只在有代价时才写**：
每一档都配一句说明的设置页读起来像免责声明，用户学会的是跳过所有小字，
等到真有一句要紧的（比如「立即」那档其实不影响导出），他也不会看了。
这和「备份是最新的就什么都不显示」（决策㉞）是同一条规矩。

**(96) 这一步不摆「快捷解锁」和「修改主密码」两行，`SETTINGS_SECURITY` 也不注册**。
决策(66)/(54) 在这里第三次兑现：先摆一个点了跳到占位屏的入口，比不摆更糟——
用户会把它当成能用的功能，然后在需要的时候发现它是个摆设。
路由常量留在 `Routes.kt` 里并写明「这里还没修路」，因为那个文件是这个 App 的页面地图，
地图上标着未通车比地图上干脆没有这个地方有用。

**(97) 「立即锁定」放在设置页底部，不放列表页顶栏**。它解决的是自动锁定解决不了的场景：
用户要**当面把手机递给别人**（给同事看一张照片、放在桌上去接杯水），
这时候他需要的是「现在就锁」，而 60 秒恰恰是他把手机递出去之后那段最没法控制的时间。
但不能放顶栏：那几个 44dp 方块已经挤着搜索和设置了，再塞一个锁的图标，
误触的代价是当场把自己关在门外（对比决策㊿——危险动作要让人走到它跟前）。
真要用它的时候，多点一下进设置完全来得及。

**(98) 绑定页只挂在「已解锁」这张图上，而且开启之前**不**再要求验证一次主密码**。
常见做法是「改安全设置前先重新验证身份」，这一页刻意不做，因为它在这儿挡不住任何人：
能走到这一页说明保险库已经是解锁状态，走到这儿的人早就能看到里面每一条密码了，
再验一次主密码保护的是什么呢——它唯一的效果是让真正的用户每次都多输一遍长口令。
真正的门槛在别处：开启指纹必须当场通过一次**系统的**生物识别，
而那一枚指纹不是拿着这台手机就能凭空录进去的。
（修改主密码是另一回事，那个动作会让所有旧备份的口令失效，M3-6c 会要求验证。）
挂在已解锁图上还有一个硬性原因：绑定要借库主密钥（`VaultSession.withVaultKey`），
而那把钥匙只在解锁期间存在，锁着的时候这一页无事可做。

**(99) 不做「快捷解锁」总开关，指纹和 PIN 各自一个开关**。它们是两条独立的路，
连限速机制都不一样（一个由安全硬件做，一个由我们的 `AttemptLimiter` 做），
用户完全可能只想开其中一条。一个总开关要么把两条绑死在一起，
要么就变成「总开关 + 两个子开关」——后者会多出一个中间态
（总开关开着但两条都关着），而那个状态在屏幕上是解释不清楚的。

**(100) 开关的位置反映的是「绑没绑过」，不是「现在能不能用」**。
用户去系统设置里把指纹全删了之后，我们 prefs 里那份包裹**确实还在**（只是再也解不开了）。
这时候把开关画成「关」看起来更"干净"，但那是在撒谎，而且是有后果的谎：
用户看到一个关着的开关，不会去动它，那份残留就一直留着；
而他下次解锁时仍然会被弹一次注定失败的指纹框——他会以为是应用坏了。
所以开关照实开着，副标题改成「绑定已失效」，把「关掉它可以清干净」直接写出来。
这条和「设置页只显示、不悄悄改写」（决策(92)）是同一条。

**(101) 「状态未知」和「暂时不可用」一律不灰开关**。
`canAuthenticate()` 在老机型和定制 ROM 上真的会返回 `BIOMETRIC_STATUS_UNKNOWN`，
而 `HW_UNAVAILABLE` 按字面意思就是可能过一会儿又能用了。这两种情况下灰掉开关，
等于我们替用户下了一个**连系统自己都没敢下的结论**，代价是他在一台其实能用指纹的手机上
永远打不开这个开关，而且屏幕上没有任何地方告诉他为什么。
让他按，按下去弹不出框的话会拿到一条真实的错误说明——那比一个灰按钮诚实得多。
反过来，`NO_HARDWARE` 和 `SECURITY_UPDATE_REQUIRED` 是确定的答复，那就照实灰掉并解释。

**(102) 绑定期的失败文案另写一份，不复用 `BiometricPolicy.message`**。
那一份是解锁页用的，每条的落点都是「请用主密码解锁」——而绑定页上用户已经在库里了。
跟一个门已经开着的人说「这个保险库现在可以用主密码打开」，读起来像应用把自己的状态
搞糊涂了，他对**所有**安全提示的信任会一起打折。绑定期的落点统一改成
「这次没绑上，库和数据都没受影响」。测试里有一条钉着：两份文案不许有任何一句相同，
且绑定期那份里不许出现「用主密码解锁 / 用主密码打开 / 解锁保险库」。

**(103) 关闭指纹解锁不弹确认框**。确认弹窗是留给**不可逆**动作的（删条目、删库）。
关掉指纹完全可逆——重新打开就是再按一次指纹的事，主密码从头到尾都能开门，什么都不会丢。
给一个可逆的小动作配弹窗，用户学到的是「这个应用什么都要问」，
等到真正危险的那个弹窗出现时，他已经养成了直接点确认的习惯。
（对比决策㊿：危险动作要让人走到它跟前。这一条是它的另一面——不危险的动作不许拦路。）

**(104) 绑定同样要走可信中断**。指纹框一弹，Activity 就走 `onStop`，
从会话看和「用户按 Home 走了」一模一样。把自动锁定设成「立即」的用户
**永远绑不上指纹**：按完指纹回来时 `withVaultKey` 已经没有密钥可借了。
这和导出备份踩的是同一个坑（决策⑳），修法也一样——`beginSystemInterlude()`。
「去系统设置录入指纹」那个跳转同理，而且它更需要：录一枚指纹要花上一分钟。

**(105) 绑定之前先把作废的旧 Keystore 钥匙删掉重建；绑到一半失败要清残留**。
`getOrCreateAuthRequiredKey()` 是「有就用、没有才建」，于是一把已经被
`setInvalidatedByBiometricEnrollment` 作废的旧钥匙会被原样复用，
`Cipher.init` 当场抛 `KeyPermanentlyInvalidatedException`——表现是「点了开关没反应」。
删掉重建一把就行，我们本来就要覆盖旧包裹，没有任何东西会丢；只重试一次，
第二次还失败就是真出问题了，再循环下去只会把错误藏起来。
另一头同样要守：`doFinal` 之后写 prefs 的过程中出任何岔子，都要把写了一半的包裹删掉——
留着它的表现是「开关显示已开启，但每次解锁都失败」，而用户要到**下次开门**才会发现。
还有一条底线和解锁侧一样：回调里拿不到 `CryptoObject` 就当绑定失败，
那种「界面上点了个头」的认证不该产出一份包裹。

**(106) 弱 PIN 的说法不许照抄弱主密码那一份**。建库页那句「保险库文件一旦被拷走，
挡住离线爆破的就只剩这个密码本身」对主密码是真的，**对 PIN 是假的**——
PIN 包裹外面还有一层 Keystore 设备绑定密钥，拷走文件的人根本解不开它，
谈不上离线爆破（决策⑥）。把那段话搬过来等于用一个不成立的理由吓唬用户，
而他哪天真查清楚了，会连带不再相信我们说的**其它**话——那是拿以后所有安全提示的
可信度换这一次的点击。PIN 的真实风险是「能拿到这台手机的人，当着这台手机试」，
那个人多半认识你、知道你的生日，而退避挡得住上千次、挡不住前面那几次。
所以五段说法的落点统一是「被猜到 / 被试中」，不是「被算出来」。
另外：`weaknessTitle` / `weaknessMessage` 的入参**只有一个枚举，没有 PIN**——
于是「弹窗里绝不出现用户刚输的那六位数」不是靠自觉守的，是写不出来
（同 `EntryForm` 那条「放弃修改的摘要里只有字段名」）。

**(107) 「修改 PIN」不要求先输一遍旧 PIN**。理由同决策(98)：走到这一页说明保险库
已经是解锁状态，能走到这儿的人早就能看见里面每一条密码了，旧 PIN 在这儿挡不住任何人。
而它的代价很实在——用户想改 PIN 的最常见原因就是**他快记不住现在这个了**，
拿旧 PIN 挡在门口，等于告诉他「想换掉这个记不住的东西，请先把它背出来」。
何况这道门根本挡不住：他把开关关掉再打开一样能设新的，只是多绕两步、
还多担一次「关掉会不会把数据弄没」的心。
（改**主密码**是另一回事——那个动作会让所有旧备份的口令失效，M3-6c 要求验证。）

**(108) 两次不一致时退回第一步，两份一起清**。只清第二份是更常见的做法，
但它建立在一个没根据的假设上：**打错的是第二次**。如果打错的其实是第一次，
用户会对着一个自己并不想要的 PIN 反复确认，直到某一次「对上了」——
于是他设下了一个和他以为的不一样的 PIN，而这件事要到**下次解锁**才暴露，
那时候他连怀疑的方向都没有。两份一起清，最坏是多按六下。

**(109) 打开 PIN 开关不是就地生效，而是跳进设置流；开关此刻一动不动**。
先把开关拨到「开」再去设置，用户中途退出就会看到一个开着、但其实什么都没设成的开关。
这是决策(100)（开关的位置永远等于「绑没绑过」）的另一面：那一条说的是
「失效了也照实开着」，这一条说的是「没设成就照实关着」——两条都是同一句话，
**开关的位置只反映 prefs 里到底有没有那份包裹，不反映用户的意图**。

**(110) 弱 PIN 的日期规则明显偏宽，这是有意的**。四种排法（YYMMDD / DDMMYY /
MMDDYY / YYYYMM）会把一百万个六位数里的约 8.8% 认成日期，里面必然混着一些
其实是随便敲的。但这一条**只是提醒不是拦截**：认错了的代价是多看一次弹窗、
按次按钮就过；认漏了的代价是一个用生日当 PIN 的人什么提示都没收到——
而那正是这条规则唯一的存在理由。真实分布里生日样式的占比远高于 8.8%，两边不对称。
也不检查历法（2 月 31 日照样算）：照着生日的样子敲的人不查万年历，
猜他的人也不会因此跳过那个方向。

**(111) PIN 那一行没有 `enabled`，也永远不出说明**。指纹那一行要处理六种设备支持度，
PIN 一种都没有——它不依赖任何传感器，用的也不是「每次使用都要认证」的那把 Keystore
钥匙，所以既不会因为系统里删了什么而失效，也没有「这台设备用不了」这回事。
既然没有异常状态，就一句说明都不出（决策(95)）：给一个从不出问题的开关配一句
「一切正常」，读者学会的是跳过所有小字。
另外这一页从头到尾**没有「显示 PIN」的眼睛**：长口令必须能核对（打错一个字符谁都看不出来），
而 PIN 只有六位、多半是站着当着人设的，那颗眼睛在这儿的主要作用是把六位数字亮给旁边的人看。

**(112) 改主密码是决策(98) 的唯一例外：动手之前要先验一遍旧主密码**。
(98) 说过，改安全设置之前再验一次身份挡不住任何人——能走到设置页的人早就看得见每一条密码了。
这一页反着来，理由不是「更安全」，是**这个动作会伤到真正的用户**：
改完之后他手上所有旧备份都只认旧口令，而旧口令刚刚被换掉。
一个把手机放在桌上转身接水的人回来发现主密码被改了，那不只是「别人看到了我的密码」，
那是**他自己再也进不去了**。所以这里验的不是「你有没有权限」，
是「你是不是知道旧口令的那个人」，顺带也拦住纯粹的误触——这一页上任何一步做完都撤不回来。
（对比决策(107)：改 PIN 不问旧 PIN，因为改 PIN 不会让任何东西过期。）

**(113) 旧口令在这一页输错**不**进退避**。`AttemptLimiter` 守的是门，
而走到这一页的时候门已经开着了；在这儿罚一次，罚到的是一个已经在库里的人——
表现是他被自己的保险库锁在门外 15 分钟，而他并没有做错什么。
真正的限速是 KDF 本身：每错一次都要实打实跑一遍 Argon2id，这台设备上一秒钟试不了两次。
至于「拿到解锁手机的人在这儿猜主密码」——他早就把里面的密码抄完了，犯不着来猜。

**(114) 这一页真正的收尾动作不是「改密码」，是把手上那份只认旧口令的备份换掉**。
主密码改了，之前导出的 `.lvault` 一个字节都不会变，它仍然只认旧主密码——
而旧主密码正是用户刚刚决定不再用、多半也不打算再记的那一个。
**这条路上没有任何东西会报错**：改密码成功了，备份文件还好好躺在网盘里，
一切看起来都对，直到某天真要用它。这是整个 App 里最安静的一条数据丢失路径。
所以 `VaultMeta` 加了 `masterChangedAt`，于是 `lastBackupAt < masterChangedAt`
成了一个能当场算出来、也能写在屏幕上的事实，并且在**三个时机**说同一件事：
提交前的横幅、成功页把「现在重新导出备份」摆成主按钮（而不是「完成」）、
设置页那一行转成黄铜色。三处不是啰嗦，是这条路上仅有的三个能说话的时机。
（`lastBackupAt == 0` 不走这条：那不是「一份过期的备份」，是「没有备份」，
那件事由列表页顶上那条常驻提醒管，两条提醒不互相稀释。）

**(115) 改密码顺带按这台设备重新校准 KDF；封条必须当场跟着变**。
参数写在文件头里，只有重新包裹主密钥时才会被换掉（决策①）——
于是这是整个 App 里**唯一**能把一个老库的档位提上来的时机：
换了新手机、或者从老机器的备份恢复过来的库，会一直带着当年那台机器定下的低档参数跑，
而用户完全看不出来。反过来，在更慢的设备上改密码会把档位调低，那也是对的：
校准的目标从来不是「越高越好」，是「这台机器能承受的最高档」——
高到每次解锁等三秒，用户的应对方式是把快捷解锁一开了事，那才是真降低了安全性。
代价是 `VaultSession` 里那个文件头**会在解锁期间变**，所以它从普通 getter 改成了
`StateFlow`：普通 getter 的话封条要到下一次锁定—解锁才跟上，
中间那段时间它显示的是这个库已经不用的档位——而封条的第 1 条规矩就是不许显示假话。
成功页还会把新档位写出来一行：一个无声无息变了数字的封条，比变之前更让人不安。

**(116) 记时间戳失败**不**算改密码失败**。分界线画在「磁盘上的口令换了没有」：
`storage.save` 是原子的，它之前的任何一步失败都意味着文件一个字节没动
（于是四条失败文案都可以理直气壮地写「原来的主密码依然有效」）；
它之后只剩一件事——把 `masterChangedAt` 记进 meta，那一步失败的后果是
设置页那一行少一句提醒，而把整件事判为失败的后果是**用户以为密码没改成，
继续用旧口令，然后发现开不了门**。两者不在一个量级。

**(117) 主密码的硬下限和「两次一致」那一行提成共用的一份**。
设主密码的地方有两处（建库、改密码），下限分开写的话，哪天有人只调其中一处，
结果就是「建库要 10 位，改密码 8 位就过」——用户可以通过改密码把主密码降到
建库时不允许的强度，而屏幕上没有任何地方会提到这件事。
`MatchHint` 同理：一个把「不一致」画成灰色小字、另一个画成红色叉号，
用户在第二处会以为那只是提示而不是拦截。
所以 `PasswordStrength.MASTER_MIN_LENGTH` 和 `components/Fields.kt` 里的 `MatchHint`
是唯一的一份，两页都指过去。

**(118) 「修改主密码」放在安全分区的末尾，不夹在两个开关中间**。
上面那三行（自动锁定、快捷解锁、剪贴板）是日常会调的东西，而这一行一个用户
可能一辈子只点一次。夹在中间唯一的效果是增加误触，而它是本分区里唯一一个
会让用户手上那份备份的口令过期的动作。这和决策㊿（危险动作要让人走到它跟前）、
决策(97)（「立即锁定」不放顶栏）是同一条。

**(119) 删库的门槛是主密码，不是「请抄写『删除保险库』」**。
抄写短语是这类操作的通行做法，它防的是**惯性点击**——用户闭着眼一路点主按钮，
抄写那一步会把他截停，这个作用是真的。但主密码框同样能截停他
（没有人能凭肌肉记忆无意识地打完一个 20 位口令），而且它多做一件抄写永远做不到的事：
**证明坐在这儿的是本人**。这一页真正的威胁不是「用户手滑」，
是决策(112) 那个场景的加强版——把解锁着的手机放在桌上转身接水的人，回来发现库没了；
改主密码尚且能拿备份救回来，这一个救不回来。
两道门都上是没必要的：抄写在主密码之外不新增任何保护，只新增一次仪式，
而仪式做多了，用户学会的是「照着抄就行」。
代价是靠指纹进来、确实想不起主密码的人在这一页删不掉——那正是这道门该拦住的形状。
他还有系统的「清除应用数据」，那条路我们本来也拦不住，所以如实写在页面底部，
但不放在标题上。

**(120) 先清快捷解锁的残留，后删库文件**。反过来（先删文件）看着更顺手，
因为「主要动作」先做完了。但那个顺序的中途失败是**不可收拾**的：
文件已经没了，而 prefs 里还躺着一份包着某个已不存在的库的主密钥的包裹，
Keystore 里还留着两把钥匙——它们不会崩溃，只会在用户下一次建库时变成
一堆解释不清的脏数据（`isAnyEnrolled` 是 true，解出来的钥匙却开不了新库）。
现在这个顺序的中途失败则是**可收拾**的：库还在、数据一条没少，
代价只是快捷解锁被关了，而用户刚刚才输过主密码，一定进得去。
这条约束顺带决定了失败文案的写法——`Failure.FilesRemain` 那段话里
「快捷解锁已经在这一步之前被关掉了」只在清残留之后成立，
所以异常归类要带一个 `purged` 标记，否则同一句话在两种情况下一真一假。
另外这里用的是 `QuickUnlock.disableAll()` 而不是 `UnlockGuard.disableQuickUnlock()`：
后者刻意保留退避计数（决策㉓），而库都没了，那份计数留下来会原封不动地
作用在用户下一次新建的库上——表现是「刚建好的库，第一次解锁就被告知还要等 15 分钟」。

**(121) 删除成败以「库还在不在」为准，不以 `deleteAll()` 的返回值为准**。
`VaultStorage.deleteAll()` 在**任何一个**文件（主文件、临时文件、上一版备份）
删不掉时都返回 false，但真正决定成败的只有一件事：这台设备上还能不能打开这个库。
一个残留的 `.tmp`（上次写盘崩在第一步留下的垃圾，本来就不是完整的库）
不该让整件事被报成失败，让用户对着一个其实已经删干净的库再点一次。
所以删完之后再问一次 `repo.exists()`，以它为准。

**(122) 删完之后不做「已删除」成功页**。`session.onVaultDeleted()` 一调，
相位就翻回 `NoVault`，整棵已解锁子树连同那一页一起被换成欢迎页（决策⑪）——
一个空白的、要你新建保险库的应用，没有比这更清楚的「删掉了」。
再插一句「删除成功」的提示，只会让用户在看到回执之前多点一次。
这也是导航图里那一处**没有** `onDone` 回调的原因：写了也永远执行不到，
那时候整张图已经不存在了。
（对比 M3-4a 删单条**有**墓碑页：那一个可以撤销，墓碑页存在的理由就是承载撤销；
这一个没有撤销，也就没有任何东西需要一屏来承载。）

**(123) 「删除保险库」单开一个「危险区」放在整页最下面，而且永远不变色**。
它本来更「像」是安全分区的一员（改主密码就在那儿），但那正是不能放的理由：
安全分区其余四行都是日常会点的东西，把一个不可逆的动作混进去只会增加误触。
单开一个只有一行的分区看着浪费，但那一行周围的空白本身就是一道门槛。
更要紧的是**它不带 urgent 标记、永远不用黄铜色**——「导出加密备份」和「修改主密码」
那两行会在有事要办时变色（决策(118)），因为那是提醒用户去做一件对他有好处的事；
我们没有任何立场提醒任何人去删自己的数据，把它标成显眼的颜色只会增加它被点开的次数。
副标题也只陈述后果（「连同快捷解锁一起清空，无法恢复」），
不写「谨慎操作」「不可撤销！」——真心想删的人会被吓唬话激怒，
误点进来的人在下一页第一屏（条目数和备份状况）就会退出去，那比三个感叹号有用。

**(124) 删除入口刻意不挂在解锁图上**。别的几页只挂在已解锁相位是因为
「锁着的时候没有库主密钥可借」，技术上做不了；这一条技术上完全做得了
（删文件不需要任何密钥），是**刻意**不给的。
因为删除的门槛是主密码（决策(119)），而挂在解锁页上的入口面对的恰恰是
「说不出主密码的人」——它会立刻退化成一个人人可按的
「清空这台手机上的保险库」按钮，也就是决策⑦ 明令不做的那个拒绝服务漏洞的手动版。
代价是「我忘了主密码，想重来」这条路目前还没有出口
（只能去系统设置清除应用数据，解锁页那个弹窗里也没提）。
它需要另一套确认方式，和这一页共用不了，留给 M3-6c-3。

**(125) 备份「是最新的」时也不说「可以放心删」**。
这个判断我们只能做到一半：我们知道**导出过**，而且知道那次导出是校验过的
（决策⑱：写后回读比对才记 `lastBackupAt`）。但那之后的事一概不知道——
文件还在不在那个网盘里、U 盘有没有被格式化过、以及最要命的一条：
**那份文件对应的主密码用户还记不记得**（中间改过主密码的话，它认的还是旧的，决策(114)）。
说一句「可以放心删」，等于替用户对三件我们看不见的事下了保证，
而这一页是全 App 唯一一个说错了就没法补救的地方。
所以这一档只陈述我们确实知道的那一件，剩下的用问句还给他：
「那份文件现在还在你手上吗？它的主密码你还记得吗？」

**(126) 重来页的门槛以系统的「清除应用数据」为标尺：不比它更难，也绝不比它更容易**。
先承认一件事：这一页拦不住一个铁了心要毁掉这个库的人——拿到这台手机的他
本来就能去「应用信息 → 存储 → 清除数据」，一路点下去效果一模一样，
那条路不归我们管。所以这一页的门槛不是拿来防攻击的（防不住），它只有这一把尺子。
更难没有意义：真想毁数据的人走系统那条，我们多设的门只折腾本人（而本人恰恰
是刚丢了主密码、最不需要再被折腾的那个）。更容易则是我们自己的问题：
那等于在一个人人可达的界面上提供一个比系统还顺手的毁数据入口，
决策⑦ 说的正是这个。系统那条路要「翻进应用信息 → 找到存储 → 点清除数据 → 再确认」，
所以这一页给两道：抄写一句话 + 按住三秒。

**(127) 抄的是一句关于用户自己的陈述（「我没有主密码了」），不是一个命令词**。
抄「删除保险库」「DELETE」只是打字，打完脑子里什么都没留下。
这一句不一样：它是一句**关于当事人处境的陈述**，而这一页最该拦住的那个人——
记得主密码、只是点错了地方的人——要打的这几个字是假的。
人在照抄一句关于自己的假话时会停顿，这个停顿就是我们要的全部。
配套的两条：① 屏幕上那句范文**不能做成可选中的文字**（Compose 的 `Text`
默认不可选中，现状凑巧是对的，但别哪天为了「方便」在外面套一个
`SelectionContainer`，那等于把这道门改成「长按 → 复制 → 粘贴」）；
② 输入框下面必须把这道门在防什么直说出来，不说的话用户学会的是
「照着抄就行」，那这道门就真的只剩仪式了（决策(119) 担心的正是这个）。
比对宽容空白和结尾句号（输入法会带出空格，抄完有人会顺手点个句号），
不宽容错字、中间标点和繁体——放行繁体等于承认「差不多就行」，
而这道门拦的正是「差不多就点了」。

**(128) 决策(119) 那句「两道门都上是没必要的」在这一页不成立**。
那边的理由是「抄写在主密码之外不新增任何保护」——**在主密码之外**是前提。
这一页根本没有身份证明可用（用户说不出主密码，这就是他在这儿的原因），
两道门不是「强的那道之外再添一道弱的」，是全部家当，而且各拦各的：
抄写拦的是没读就一路点主按钮的惯性，按住拦的是**刚打完字的手指顺势再点一下**——
抄写结束那一刻是这一页最危险的一刻，输入法收起来的同时按钮跳到手指底下。
三秒是有上限的仪式，再长就越过决策(126) 那把尺子了。
**但这一页没有最后那个确认弹窗**（对比删除页有）：按住三秒本身就是
「随时松手就中止」的确认，它比弹窗的一次点击更强，再加一个弹窗只是把仪式凑够三样。

**(129) 这一页什么元信息都不显示**：没有条目数、没有上次改动时间、没有文件大小。
删除页上摆了一整屏这样的事实（`DeleteVaultModel.facts`），这一页一条都没有，
不是偷懒：**库是锁着的，这些东西读不到，也不该读**。解锁页那一屏刻意什么都不显示
就是这个道理（捡到手机的人不需要打开保险库，看到「37 条 · 2 分钟前更新」
就已经知道这台设备值得带走），而这一页和解锁页是同一张图上的同一种可达性。
代价是用户在这儿做决定时最想知道的那两件事我们一件都答不上来，
所以照决策(125) 的办法办：不替他判断，把问题还给他
（「你导出过 .lvault 吗？它在哪儿？」「那份备份对应的主密码你还记得吗？」）。
另外要**主动交代**为什么这一页说不出条目数——用户在删除页上见过那张清单，
这里突然一条都没有，不解释会显得像是没做完。

**(130) `ResetVaultController` 不和 `DeleteVaultController` 合并成一个带
`password: CharArray?` 的控制器**。两者差的不是一个参数，是「有没有身份证明」
这件事本身（删除页第一步是验主密码，这一页根本没有第一步）。
合并等于让这个区别变成一个可空参数，而这是最不该用 null 表达的东西——
写错一次（传了 null 却走到删除页那条路）就是把决策(119) 那道门无声地拆掉。
两个类各自把话说死，编译器帮着看住。顺带的好处是失败文案也分得开：
删除页每条都写「保险库还在，数据一条没少」当安慰（那边的用户怕删了一半），
这一页的用户要的就是删掉，同一句话在这儿是坏消息，所以每条都得跟上
一句**还能怎么办**。执行顺序则一个字不改地照抄删除页（决策(120)）。

**(131) 覆写擦除那段话用引用，不抄第二份**。`ResetVaultModel.ERASURE_NOTE`
是 `DeleteVaultModel.ERASURE_NOTE` 的引用（`const val` 之间的引用是编译期常量，
不产生第二个字符串），并有一条 `assertSame` 钉着。
闪存怎么回事只有一个事实，两页各写一份迟早会有一页被改而另一页没有，
到那时用户会在两个界面上读到两种说法——这正是这类文案最要命的失败方式。

**(132) 重来页把用户指向的「从备份恢复」还是个占位屏（M5）**。
这一页的好消息是「你导出到别处的备份不受影响，清空之后就是拿它们回来的时候」，
而拿回来那条路在欢迎页上，`Route.RESTORE` 至今是 `Stub("从备份恢复", "M5 迁移")`。
话本身没有撒谎（备份文件确实不受影响，M5 落地后确实能装回来），
但**在 M5 之前不能让这条路进任何一个有真实用户的版本**——
那会让一个刚清空的人卡在占位屏上。这是一条发版顺序的硬约束，
写在这儿是为了不靠谁记性好：M3-6c-3b 接线之后，M5 之前，不出内测包。

**(133) 长按按钮不用 `combinedClickable` 的 `onLongClick`，手势自己接**。
那个东西只在满了之后通知一声，中间的两秒九对界面来说是一片空白：画不出进度
（用户分不出「按住中」和「这按钮坏了」）、说不出还剩几秒
（`ResetVaultModel.holdLabel` 那句「继续按住…3 / 2 / 1」没有数据来源）、
**也中止不了**（它的时长是系统的长按阈值，几百毫秒，而且没有「松手就作废」这个语义）。
所以直接接 `pointerInput` + `awaitEachGesture`，抬手用 `waitForUpOrCancellation`。
配套的三条硬性要求各有一条单测钉着（见 `HoldProgressTest`）：
① **完成只报一次**——帧回调一秒六十次，越过终点之后还会继续来帧，
漏掉这条 `onComplete` 会被连着调几十次，而它调的是 `ResetVaultController.submit()`；
② **松手是中止不是暂停**，再按从零开始——做成暂停的话「按三下每下一秒」
就等于按住三秒，这道门拦的那个「刚打完字的手指顺势再点一下」就白设了；
③ 手势被父容器的滚动抢走，和抬手是同一件事——手指还压着但已经滑走了，
就不能再当成他一直按着。
另外**刻意不加震动反馈**：`View.performHapticFeedback` 确实不需要 `VIBRATE` 权限，
但关于页那份「权限清单只有一条」是有单测钉着的招牌，把一个碰马达的调用塞进来，
迟早有人在别处顺手改成 `Vibrator`，那时清单就多一条了。
进度条 + 一秒一跳的剩余秒数已经足够说明「它在数着」。

**(134) 「忘记主密码了？」那个弹窗不加 `danger = true`，主按钮仍然是「我再想想」**。
`danger` 做两件事：主按钮画成红的，以及关掉「点外面即取消」。
这两件在这个弹窗上都是错的——它的常客不是已经死心的人，是抱着侥幸点开看看的人，
对他来说最好的结果就是关掉弹窗再想一会儿，所以取消手势必须一直好用。
红色要跟着**危险动作**走，不跟着弹窗走；这里的危险动作在次按钮上，
而次按钮点下去只是跳到一页还能反悔的页面，真正不可逆的那一下在那一页的按住三秒上。
把不可逆动作的入口放在次按钮上，只有在「次按钮和取消手势是两个回调」的前提下才安全，
而决策⑮ 早就把它们拆开了——那条决策写下来两年，到这一步才第一次真正派上用场。

**(135) 恢复只在「未建库」相位可达，绝不覆盖已有的库**。技术上完全做得到（把文件写下去就行），
是刻意不给的。覆盖是不可逆的数据破坏，而且这一页**没有身份证明**——
它认的是「用户手上那份文件的主密码」，不是「这台设备上这个库的主密码」。
一个能覆盖现有库的恢复入口，等于给任何拿到解锁手机的人一个「拿一份文件把你的库换掉」的按钮，
那比决策⑦ 说的拒绝服务更糟（它还能顺便装一个攻击者知道口令的库进去）。
真想换库的人手边有删除页（认主密码，决策(119)）和清空页（抄写 + 按住三秒，决策(126)），
先清干净再恢复，中间那一步正是他应该被拦一下的地方。
这条界限在**两处**各守一遍：页面上一道（`RestoreModel.blockReason` 排最前的那条），
仓库层一道（`restoreAndOpen` 开头的 `check`）。前者是给用户看的，后者是给「相位刚好在这一瞬间变了」准备的。

**(136) 装进磁盘的就是那份文件本身，一个字节都不改**。不重新封装、不换 nonce、
不按本机档位重新校准、不刷新任何时间戳。顺手做掉其中任何一样，
「这台设备上的库」和「用户手上那份备份」当场分叉——而分叉的表现是**没有表现**，
要到很久以后他拿那份文件去另一台设备恢复时才发现两边不一样。
配套的一条是导入侧的读回校验：落盘之后再读出来逐字节比对，
这是决策⑱ 那三道检查在导入方向上的镜像（那边是「写出去的能不能读回来」，
这边是「读进来的有没有原样写下去」）。
顺带解决了换机场景里一个很实际的问题：低配老机器上导出的库拿到新旗舰上恢复，
档位仍然是老机器那一档——封条会如实显示它，想提上来的正路是改一次主密码（决策(115)），
而不是在恢复时偷偷重封一遍。

**(137) 恢复成功之后记一笔 `lastBackupAt`**。决策⑱ 说「已备份」只能由**验证过的事实**产生，
而这次的事实比导出那次更硬：导出验的是我们刚写出去的文件，这次验的是一份在别处
存放过一段时间、经历过拷贝和同步、刚刚被真实主密码打开的文件。
恢复成功的那一刻，用户手上那份备份和这台设备上的库逐字节相同——那正是这个字段想回答的问题。
不记的话，一个刚拿备份装完机的人会立刻被首次备份那道关卡（决策⑰）挡住，
被要求再导出一份他刚刚才用过的东西，而那句「你还没有备份」是假的。
记这一笔**失败不算恢复失败**（分界线同决策(116)：磁盘上的库已经装好了；
判成失败的后果是用户以为没恢复上，再来一遍，然后撞上「这台设备上已经有一个保险库」）。

**(138) 「不是我们的文件 / 文件坏了 / 版本太新」必须是三个异常类型，不是三句中文**。
`VaultFormatException` 原来一个类装三件事，恢复页要分开说就只能去比对
`e.message` 里的那句中文——而那句话是给用户看的，早晚会改，一改判断就悄无声息地失效。
所以 M5 把它拆成父类 + 两个子类。**行为一个字没变**（抛的仍然是 `VaultFormatException`），
既有的 `catch` 一处都不用动。
分开的全部价值在于三个下一步完全不同：选错文件 → 换个文件（这是恢复流程上最常见的一次失误）；
文件坏了 → 换一份备份（坏消息得多）；版本太新 → 升级应用，而且**绝不能提示他拿更早的备份将就**，
那会让他用一份旧数据把新的盖掉。合成一句「文件无法识别」，用户唯一能做的就是三件事挨个试。

**(139) 恢复页输错主密码不进退避**。`AttemptLimiter` 守的是**这台设备上那个库的门**，
而这一页上还没有库，也就没有门可守。挡在这儿只会挡住一个正拿着自己的备份、
正在回忆旧口令的人——而他多半刚丢了手机或刚清空过库，是全 App 最不该再被罚一次的用户。
真正的限速是 KDF 本身：每错一次都要实打实跑一遍派生。
理由和决策(113)（改主密码页不进退避）同源，只是那边的前提是「门已经开着」，
这边的前提是「压根没有门」。而且要把这件事**写在屏幕上**——
用过 M3-2c-1 那个退避倒计时的人，会以为这一页也一样，于是不敢多试。

**(140) 恢复页不显示条目数，并主动交代为什么**。`.lvault` 的文件头是明文的
（魔数、格式版本、KDF 档位、盐），条目全在密文里，所以在输主密码之前
这一页能如实说出「多大、什么格式、什么加密参数」，一条也数不出来。
这不是功能没做完，这正是它该有的样子：一个不输口令就能告诉你「这份备份里有 37 条」的
密码管理器，等于把库内容的一个投影摆在任何拿到文件的人面前（同决策㉖对解锁页的要求）。
但要**主动说出来**——用户在删除页上见过一整屏事实清单（那里有条目数），
这里突然只剩四行，不解释会显得像是没做完（同决策(129) 在清空页上的做法）。

**(141) 「指纹和 PIN 不会跟着过来」必须在恢复之前说，不能等用户回头发现**。
它们包的确实是同一把库主密钥，但那份包裹外面还有一层 Keystore 的设备绑定密钥（决策⑥），
而那把钥匙生在原来那台手机的安全芯片里，拷不出来也拿不走——这恰恰是 PIN 只有六位
却依然安全的全部理由，所以它不是缺陷，是同一个设计的另一面。
不提前说的话，用户恢复完会发现指纹解锁不见了，然后合理地怀疑「是不是没恢复全」，
而其实数据一条不少。换机那天最不需要的就是这种怀疑。

**(142) `ImportSource` 只有 `read()`，没有 `write()`**。八条失败文案里那句
「你手上那份文件没有被改动」之所以敢在**每一条**路上都写，靠的是这个接口里
根本没有写入方法，而不是靠谁记得别写（同 `EntryForm` 那条「摘要函数收不到字段值」、
`PinSetupModel` 那条「文案函数收不到 PIN」的做法）。
这一页的用户多半刚经历过换机、丢手机或者忘记主密码，此刻他最怕的不是恢复失败，
是「我最后这份备份是不是也被弄坏了」。这句话必须在任何情况下都为真。

**(143) CSV 里那段明文擦不掉，别假装擦得掉**。全工程的敏感数据一直走 `SecureBytes`
（`ByteArray`，用完清零）。到了 CSV 这一层做不到：`CharsetDecoder` 的产物是 `String`，
String 在 JVM 上不可变，拿不到底层数组，也没有任何合法办法把它清零；
就算硬来，解析过程中还会分裂出几万个小 String 散落在堆上等 GC。
所以这里**不写一个叫 `wipe()` 的空方法来让自己心安**（那比不写更坏：
它会让下一个读代码的人以为这条路已经被处理过了）。能做的只有三件，三件都做了：
活得短、不外泄（决策(144)）、说实话——`CsvText.PLAINTEXT_NOTE` 明说那份 CSV
躺在「下载」目录里本身就是一份任何应用都读得到的明文密码表，
M5-2b 导入完必须强提示删掉它。**它的危险远大于我们内存里那几百毫秒。**

**(144) 导入链路上任何对象的 `toString()` 都不吐内容**。`CsvText.Decoded.Ok`、
`CsvParser.Row`、`CsvParser.Table` 三个都手写了 `toString()`，只报形状
（几行几列、第几行、什么编码）。它们**因此都不是 `data class`**——
`data class` 自动生成的那个 `toString` 会把每一个字段原样打出来，
而这几个对象的字段里装的是用户的全部明文密码。
同理，失败对象一律不携带单元格内容（`Parsed.SingleColumn` 只带猜的那个分隔符，
`Parsed.CellTooLong` 只带行号），失败文案里也不带。
哪天有人顺手把其中一个塞进一句日志或者一个异常消息里，那就等于把整份 CSV 抄进了 logcat。
用例钉着这四处（同 `EntryForm`「摘要函数收不到字段值」那条的做法：让它**做不到**，而不是让人记得别做）。

**(145) 编码只认三种，认不出宁可拒绝也不猜**。`String(bytes)` 永远能给出一个字符串，
只是里面全是垃圾——这是整条导入链上**唯一一种会静悄悄成功**的失败。
所以两条路都用 `CodingErrorAction.REPORT` 严格解码：坏字节报错，不替换成 U+FFFD。
顺序是 BOM → 严格 UTF-8 → 严格 GBK，两条都过不了就明说这不是文本文件。
GBK 那一条不是可选项：国内几个浏览器和管理器的导出至今是 GBK，
少了它，用户看到「鍚嶇О」得出的结论会是「这个 App 不支持中文」。
反过来也不能反着猜：GBK 的中文字节序列几乎不可能同时是合法 UTF-8，
所以这两条路互不抢生意（用例钉着）。

**(146) 单格超长是硬失败，其余不合规一律「读进来 + 记账」**。
这条是这一层唯一一处「整份拒绝」，值得写明理由：单格超过 32K 字符的成因基本只有一个——
某处引号没配对，而那意味着这张表的列**从那里开始整体错位**了，
于是「密码」那一列里装的可能是别人的备注，也可能是半截密码。
一条被截断的密码导进保险库之后，和一条好密码长得一模一样，**没有任何办法事后分辨**——
那是这条路上唯一一种静默且不可逆的损坏。
其余三种不合规（引号没关、结束引号后面还有字、引号前有空格）都能读，
所以都读进来、都记一笔账、都不改数据，由 M5-2b 摆给用户在导入**之前**过一眼。
尤其「引号前有空格」那条刻意**不去智能地把引号剥掉**：
万一用户的密码本身就叫 `"abc"`，剥掉就是改坏数据，而改坏一个密码是不会报错的。

**(147) 列名映射的宽松匹配前面必须挡一张排除表，而且排除表比别名表重要**。
别名表少一条，用户在 M5-2b 上手点一下就补回来了，代价是一次点击；
排除表少一条，`Password Hint` 就被当成密码导进去了，代价是一条**再也找不回来的真密码**——
因为提示语在库里和正常密码长得一模一样，而源文件那时已经按我们自己的提示删了。
两种错误的代价差了几个数量级，所以自动映射的默认姿态是**宁可不认**：
认不出来的列一律不导入（也不塞进备注），拿不准的列一律交给用户点。
新增别名时不必犹豫，往排除表里删词时必须写清删的是谁家的用户。

**(148) 认不出任何一列列名、而第一行本身长得像数据时，一列都不许猜**。
没有表头的导出是真实存在的（Firefox 老版本、用户自己拼的表）。
这时如果按位置猜「第一列是名称、第二列是账号」，猜对了用户看不出来，
猜错了他也看不出来——预览里每一格都有内容，只是名称栏里躺着密码。
所以这种文件一律给出空映射 + 一句「第一行看着像数据，它会被当成列名而不会被导入」，
让用户自己指。判据只取列名里几乎不可能出现的东西（`://`、`@`、超长、纯数字），
**宁可漏判也不误判**：漏判的表现是第一行不见了（看得见的错），
误判的表现是库里多一条叫「用户名」的垃圾条目（用户还以为自己导对了）。

**(149) 导入这件事上，拿不准的一律导**。两种错误的代价差得很远：
多导一条垃圾，用户在列表里看得见、删得掉；少导一条真数据，他**发现不了**——
源文件按我们自己的提示删掉之后，那条记录就只存在于「他以为自己导过了」这句话里。
所以只有密码而没有账号的行导、只有账号而没有密码的行也导（确实有人拿它当通讯录用），
只有四种情况跳过：整行都空、名称和网址都空（列表里会是一行空白，也搜不到）、
账号和密码都空（那是源文件里的分组行）、类型列明说这不是一条登录记录。
每一种跳过都要按理由归并计数摆到用户面前，不能默默少几条。

**(150) 「覆盖」是按字段合并，不是整条替换：空的不覆盖**。
CSV 里一个空格子的含义是「源那边这一列没有导出」，不是「请清空」——
1Password 不导出安卓包名，Chrome 不导出分类，Firefox 连名称列都没有。
如果覆盖等于整条替换，用户点一次「覆盖」就会把自己在这里补过的分类、备注、包名全部清空，
屏幕上不会有任何提示，也没有回收站。
所以 `id` / `createdAt` 留旧的（那是同一条条目的身份），
`favorite` 取或，网址两边合并去重（去重按归一后的形式，写法留旧的），
备注**只在旧的为空时**才写入且不拼接（拼接会在反复导入之后攒出一堆重复的话，
而备注恰恰是用户放密保问题答案的地方）。
三种处置的默认值是「跳过」而不是「覆盖」：默认值是用户不看就点下一步的那个东西，
所以它必须是最不会毁数据的那个。「都留着」也**不改名字**——
替他在名字后面加个「(1)」同样是替他改数据。

**(151) 批量导入只走一次 `mutate`**。理由不是快，是**原子**：
一条一条 `addEntry` 等于 N 次全库加密写盘，其中任何一次失败都会留下一个
「导进去一半」的库，而屏幕上写着失败。用户重来一次，那导进去的一半又会撞上判重，
一份本来干净的导入变成要手工收拾的烂摊子。走一次 `mutate` 之后只有两种结果：
全在，或者一条都没进且内存也回滚了。将来任何「一次改很多条」的功能
（批量删除、批量改分类、二期的健康体检批量换密码）都走这个入口，不要再开一条。

**(152) 落盘之前一定以当下的库重算一遍处置**。预览停留多久是用户说了算的，
这中间他可能在别处改过库。屏幕上那句「覆盖 3 条」和磁盘上真正发生的事必须是同一件——
做法是提交时重跑 `CsvImport.prepare` + `apply`，而不是用预览时那份快照。
重算之后无事可做时报成功、数字是 0，比报失败诚实：磁盘上确实没有需要写的东西。

**(153) 改处置不重算候选，改列映射才重算**。处置只进 `apply`（O(n)，纯内存，
`outcome` 做成 getter 就够了）；列映射要重跑行转条目和判重（O(行数 × 库条目数)）。
后者扔到工作线程并且后一次取消前一次——用户逐列点过去时，中间那几份结果没有人要，
排队算完只会让最后一次结果姗姗来迟。取消时**不要**把「正在重算」翻回 false，
接手的那一次刚把它设成 true，翻回去会让界面在整段连点期间闪一下「算完了」。

**(154) 导入的失败按「下一步该做什么」分类，不按「哪里出错了」分类**。
三种：换一个文件（文件本身的问题，重试一百次都一样）、再试一次（写盘失败，
源文件还在内存里，磁盘上也什么都没变）、回去解锁（库在中途锁上了）。
分类的用途是决定那一屏上放哪个按钮——按错按钮的代价是用户拿同一份文件
反复重试一个必然失败。文案本身仍然由出错的那一层自己给
（`CsvText.message` / `CsvParser.message`），这一层不另写一套：
同一件事有两套说法时，用户看到的那一套迟早会和真正发生的事对不上。

**(155) 「CSV 是明文」这句话在导入页上说两遍，第一遍在选文件之前**。
只在导完之后说的话，那时候源文件已经从旧手机传到新手机、发过一次文件传输、
电脑下载目录里还留着一份。提前说，用户至少有机会先把它挪到一个不会被同步走的地方。
导完再说一遍是因为那才是他真正会动手删的时刻，而且那一条是整页唯一的红色横幅——
它说的是一件此刻正在发生的、真实的暴露，不是一句风险提示。

**(156) 导入预览一格内容都不显示**。没有表格预览，没有「前五行长这样」。
内核每一层的 `toString` 都不吐内容（决策(144)），界面这一层要接住它。
判据不是「这一屏上有没有密码」，是「导入是别人的密码第一次进入这个应用的时刻，
而用户此刻正对着另一台手机核对，旁边最容易站着人」。
能显示的只有：**表头（列名不是数据）、行号、条数、跳过理由、判重档位**。
这条规矩往后所有涉及外部数据的页面都照此办理（M5-3 的 kdbx、M5-4 的 CXF）。

**(157) 不做「撤销导入」**。新增的那部分删得干净，覆盖那部分改掉的是用户原有的条目，
旧值在落盘那一刻就没了（决策⑧：没有回收站）。一个只能撤销一半的按钮比没有按钮更危险——
它会让用户放心地选「覆盖」。所以力气全花在按下去之前：默认处置是最不会毁数据的那个，
每一种处置都带着自己那句说明（而不是选中之后才显示），撞上的行按行号列出来。
将来任何「批量改动已有数据」的功能都适用这一条。

**(158) 归属算在「字段」上，不算在「请求」上**。AutoSpill（2023）那一类攻击的全部内容
就在这一句话里。一个恶意应用可以自己套一个 WebView，在里面显示一张和某网站一模一样的登录页；
系统交上来的填充请求里，那些输入框**确实带着** `webDomain = 那个网站`，因为它们真的是
那个网页里的框。管理器按「这个请求是给 example.com 的」下判断，于是把密码填进了
**恶意应用进程里的 WebView**，应用自己读得到，屏幕上不会有任何异样。
所以 `Origin` 刻意做成两条：`hostApp`（承载这些框的应用包名，系统给的，改不了）
和 `Web.host`（框自称属于哪个网站，**这是一句自称**）。两条永远同时看。
同一次请求里的原生框和 WebView 框**必须各算各的归属**——M4-1b 切字段组的时候要兑现这一条，
它是这条决策唯一能被写错的地方。

**(159) 公共后缀表只内置多段的，缺一条要靠三道兜底顶住**。理由见上面 M4-1a 那一段。
缺一条的后果是「两个陌生人被算成兄弟」，也就是漏密码，所以不能只靠「表够全」：
① 未知的两字母顶级域下，注册局惯用的那批二级域（`com.` `co.` `ac.` …）一律当公共后缀——
所有两字母顶级域都是国家码（IANA 的规矩），而多段注册几乎只出现在国家码底下；
这条规则的偏向是**切得更碎**，切碎只少给一条建议，切粗才漏密码。
② 兄弟档在界面上和精确档不是一个档，M4-2b 要如实写出「你存的是 A，你现在在 B」——
判断错了用户看得见。③ 表按「宁可缺、不可错」维护，拿不准的不加。

**(160) 「绝不自动建议」和「不许手动挑」是两件事**。网页的框配上一条存着安卓包名的条目
（`WrongKind`），绝不自动建议——那正是把原生凭据骗出去的路。但它**不禁止用户手动挑**：
禁止手动等于替用户决定他自己那条数据能去哪儿，而这个应用从头到尾不做这种事
（弱口令给二次确认而不是拒绝、清空库给两道门槛而不是不给）。
分界线是：**自动的那一下用户可能没看清，手动的那一下他一定看清了。**
所以四档非自动里有三档带 `needsWarning`，M4-2b 要把对应那句话原样写在按钮上方。

**(161) 原生应用配网址条目是「没有证据」，不是「不相干」，也不为它去联网**。
直觉上「微博 App 就该填 weibo.com 的密码」，业界不少管理器也确实默认这么干。
正规做法是查 Digital Asset Links（域名持有者在 `/.well-known/assetlinks.json` 里
声明哪些应用签名属于自己）——那要联网，而这个 App 从 M0 起连 `INTERNET` 权限都没声明，
做不到，也不打算为它破例（那等于把「没有网络权限」这个卖点换成一个自动填充的便利）。
于是单独给它一档 `NoEvidence`，说的是实话：不自动填，你可以手动挑，
但我们没法证明这个应用和这个域名是一家。**业界那些默认填了的，其实也没证明。**

**(162) 包名只认逐字相等，不认同厂商前缀**。`com.tencent.mm` 是微信，
`com.tencent.mobileqq` 是 QQ，两个账号体系。认前缀等于把 `com.google.*` 底下
所有应用当成同一个站，那和「剥子域名」是同一个错误的两种写法。

**(163) 自动填充不记「上次在这儿用过哪一条」**。用它来排序会更准，
但那要记一笔**谁在什么时候登录了什么**——这个应用不记这种账（同决策㊲ 不做搜索历史）。
宁可用「最近改动的在前」这条差一点、但不产生新数据的规则：
同一个站存了两条的人多半是刚改过密码又存了一条新的，那条规则对得上这个场景。

**(164) 浏览器可信度分两层，接口在内核、名单在内核、签名校验在 M4-2**。
`HostTrust` 是接口（同 `UnlockGuard` / `VaultRemnants` 的用意），
内置的 `KnownBrowsers` 只按包名认。这张表**挡得住**一个随手写的恶意应用套 WebView，
**挡不住包名占位**：安卓只保证同一台设备上包名唯一，用户手机上没装 Chrome 的话，
一个侧载应用完全可以把自己叫做 `com.android.chrome`。
所以 M4-2 的线上实现必须再比对签名证书摘要——那一层要用 `android.*`，
正是这个接口存在的理由。`KnownBrowsers` 的文档里写死了这句话：
**它给的是必要条件，不是充分条件**，免得将来有人看见「已经查过浏览器表了」就把签名那步省掉。

**(165) 字段模型里根本没有「这个框现在写着什么」**。`AssistStructure.ViewNode`
是给得出当前文本的（`getText()` / `getAutofillValue()`），而那正是最不能进这一层的东西：
屏幕上那个密码框里可能已经躺着用户上一次输入的口令，或者他刚打了一半的密码。
它一旦进了模型，就会跟着 `RawField` 一路传到分类器、分组器、日志、异常消息里去。
填充这条路**用不到它**——我们是要往框里写，不是要读框里有什么。
所以这不是「记得别读」，是模型里没有这个字段
（同 `EntryForm`「摘要函数收不到字段值」、`ImportSource` 只有 `read()` 的做法）。
M4-3 的保存流程确实要读用户刚打进去的东西，那是另一条路、另一个模型，到那时单独建，
**不许往 `RawField` 上加一个 `text` 字段来省事**。

**(166) 字段证据分四档硬度，硬的压过软的**。`autofillHints` → HTML `autocomplete`
→ `inputType` / `<input type>` → 关键词。前两档是作者**专门为填充写的**，
写它的人就是希望被填对；后两档是我们在猜。软信号翻硬信号的案子，
表现是「明明声明了 `current-password` 却被当成新密码」，
而作者能做的补救只剩下改变量名——那不合理。
唯一的例外是「新密码还是旧密码」：`inputType` 和 `<input type>` 都分不出来，
只能由关键词补，所以那一问放在第三档之后单独做。

**(167) 不认 `autocomplete="off"`**。那是网站在告诉浏览器「别记住这个」，
不是在告诉用户的密码管理器「别填」。把它当拒绝，最先失效的是银行——
银行的登录框几乎清一色写着 `off`，而那正是用户最需要一个长随机密码、
也最不可能手打的地方。浏览器自己也早就不认这一条了。

**(168) 负面表分两张，不合成一张**。这一点写坏过一次，值得记下来：
一开始只有一张排除表，里面放着「地址 / address」，结果「邮箱地址」和「Email Address」
这两种最常见的账号框写法全被挡掉了。分开之后
`NOT_CREDENTIAL` 说的是「这个框是别的东西」（卡号 / 搜索框 / 收货人），命中即出局；
`NEGATE` 说的是「这个词只是**提到**了密码或账号」（提示 / 忘记 / 强度 / 密保问题），
只让正向匹配作废。**负面表只用来挡「会误命中正向表」的那些词**，
不用来穷举世上所有不是密码的东西——真正的收货地址栏本来也匹配不上任何正向词，
落到最后自然就是 `Other`。这条经验和 M5 列名映射的决策(147) 是同一条，
但那边只需要一张表，因为列名不像界面文案这样一句话里同时提到好几件事。

**(169) 从安卓平台抄常量，而不是 `import android.text.InputType`**。
抄一份进 `AndroidInput`，`FieldRoles` 那一整层就能在纯 JVM 上跑单测——
而它恰恰是最需要单测的一层（几十条关键词规则，靠肉眼在真机上一条条试根本试不完）。
抄这几个值是安全的：它们是公开 API 的一部分，值改了会破坏所有已发布应用的二进制兼容，
平台不会动。真抄错了也会当场暴露——某一类框从此再也认不出来，而不是悄悄认错。
用例里专门有一条钉这几个位值。

**(170) 「新密码」是一个独立角色，绝不拿它当已有密码填**。
用户在改密码页看到填充条把旧密码塞进「新密码」栏，多半会直接点提交——
于是他的新密码和旧密码一样，而他以为自己改过了。这是一条**静悄悄的**失败，
和决策(56) 那条「悄悄改写用户输入」同类。这一档留给 M4-4 接密码生成器。
同理认出验证码框的全部价值也在于**别把密码填进去**：
不少页面的验证码框就在密码框正下方，`inputType` 也常常是数字，
认错一次，密码就跟着短信回显出去了。

**(171) 分组的第一把钥匙是归一后的 `webDomain`，原生框和网页框永不同组**。
这是决策(158) 从「一句话」变成「几行代码」的地方，也是它唯一能被写错的地方。
顺手的写法是先扫一遍树、拿第一个非空的 `webDomain` 当作「这次请求是给哪个网站的」，
再拿它去匹配整屏——那正是 AutoSpill 走的门：恶意应用套一个 WebView，
里面那几个框如实带着 `webDomain = 你的网银`，同一屏上它**自己的原生输入框**
会跟着一起被算成属于你的网银，用户点一下填充条，密码就写进了它读得到的框。
所以这里没有例外：`webDomain` 为空（原生框）和非空（网页框）分家，
两个不同的 `webDomain` 分家，`hostApp` 一律取 `FillContext.activityPackage`。
归一走 `VaultIndex.normalizeDomain`（决策㉝「不许各写各的」的第三次兑现），
空串和纯空白按原生算——一个说不出自己属于哪个网站的框，没有任何「自称」可供采信。
顺带把 `Origin` 那两个 `data class` 的 `toString` 手写了：主机名 + 承载应用的包名
合起来就是一条访问记录，而 `RawField.toString` 早就把这个理由写下来了（决策(144)），
同一个文件里却漏了 `Origin` 自己。

**(172) 不按控件层级切组**。「同一个 `<form>` 里的框算一组」听起来最正确，
但那棵树给不出这个信息：WebView 交上来的节点极少暴露 `<form>` 边界，
原生表单更是想怎么套 `LinearLayout` 就怎么套。真按父节点切，最常见的结果是
**每个框各成一组**（每个 `<input>` 各自裹着一层 div），于是账号和密码永远配不到一起，
表现是「自动填充只填账号不填密码」——而这种 bug 在真机上要装几十个 App 才碰得到一次。
所以只用两条拿得准的依据：归一后的 `webDomain`，和角色出现的顺序。
顺序那一条只切一刀——**已经收过密码框的桶不再收账号框**，
它切的是「登录表单和注册表单同屏」这种最常见的布局。
反过来**不因为又来了一个密码框而切**：那种形状（账号 密码 密码）几乎总是
同一个表单里的「密码 + 确认密码」，硬切开会得到一个只有密码框的第二组，
看起来像分屏登录的第二屏。一个表单里出现两个分不出新旧的密码框该怎么办，
是下一条的事，不是切组的事。

**(173) 分不出新旧的密码框，一个都不填；账号照填**。
一屏上出现两个以上都判成「已有密码」的框，说明我们**没认出**这是注册页还是改密码页
（作者既没写 `autocomplete`，也没在 id 或提示语里留下「确认 / 新」这类词）。
这时候往里填，等于把决策(170) 那条底线交给运气——而那条底线的失败是静悄悄的：
用户点提交，页面照样说「修改成功」，他的新密码和旧的一模一样，
代价要到下一次泄露事件才显出来。
账号照填，因为账号不是秘密，填错了他当场看得见。
这一档还要**压过「有新密码框」那一档**：两个已有密码 + 一个新密码，
连「哪个是现在的」都没有定论，那就更不该挑一个填。
同理只填第一个账号框——两个账号框最常见的成因是「邮箱 + 确认邮箱」，
往第二个里填其实无害，但也有别的成因（被认错的邀请码之类），
而这个应用在拿不准的时候一律少填（同决策(147)/(149) 那套代价不对称的算法）。

**(174) 值为空的那一格不写**。往框里写一个空串不是「什么都没做」，
是把用户可能已经手打进去的东西擦掉——他会以为自动填充把他的输入吃了，
而这件事发生在他正要登录的那一刻。只有账号没有密码的条目是正常存在的
（决策(149) 明说没有密码的行照样导入），那种条目在登录页上就只填账号。
全空时返回空清单，M4-2a 据此不为这一条建 `Dataset`：
一条点下去什么都不会发生的填充项，比不出现更让人怀疑功能坏了
（同 `AutofillMatch.hasSomethingToFill`，这一条是它在字段一侧的兑现）。

**(175) 主表单只挑一个，但所有表单都留在清单里**。系统的 `Dataset` 是按 `AutofillId`
装的，一次可以把同屏几套框都写好，而填充条只在光标所在的那个框上露出来——
所以没有理由丢掉非主表单。主表单的用处只有一个：决定**按哪个归属去挑候选条目**，
那个判断只能有一个答案。挑的顺序是「光标所在那一组 → 账号密码齐全的那一组 → 第一个有东西可填的」。
第一条要求「有东西可填」，是为了这种一屏：光标在验证码框里，而同屏还摆着一套空的账号密码；
拿那一组当主表单等于整屏都填不了，而用户明明看得见那两个空框。
**不拿「哪一组框最多」或者「哪一组在屏幕上更靠上」来排**：前者会被一屏杂框带偏，
后者这一层根本不知道坐标（`RawField` 里没有位置，也不打算加——见决策(165) 那条
「模型里没有的东西才是真的不会外泄」）。

**(176) 「这一层用 `android.*`，所以不写单测」不是一条通用理由，要看那层皮底下有没有判断**。
`SafExportSink` / `SafImportSource` 当得起「薄」字：读到底、写下去，错了当场看得见，
为它们搭一套假 `ContentResolver` 只会测到自己写的假货。走 `AssistStructure` 那棵树不是——
里面有三条**错了也不报错、只是从此填错人或者不填**的规则（`webDomain` 沿父子边继承、
继承只能往下不能往旁边、看不见和「别填」是整棵子树的事），而三条都不需要 `android.*`。
所以判据不是「这个文件 import 了什么」，是**「错了会不会有人看见」**：
看得见的错留给真机，看不见的错必须搬到纯 Kotlin 那一侧钉住。
做法是把节点抽成一个 `Tree<N>` 接口（同 `HostTrust` / `UnlockGuard` / `VaultRemnants`），
线上传 `ViewNode`，用例里传假节点，**走的是同一个 `Walker`**。
将来再遇到「薄壳」时先问这一句，别照抄结论。

**(177) 拿不到 `activityComponent` 就一个框都不收**。`AssistStructure` 正常总会带着它，
真拿不到时唯一还剩的包名来源是节点上的 `idPackage`——而那一栏是**应用自己填的**。
拿它当归属，等于把决策(158) 里那条「最硬的事实」换成一句自称：
一个恶意应用只要在自己的框上写 `idPackage = com.某银行`，就能把银行密码要走。
宁可这一次不出填充条：用户看到的是「这儿没弹出来」，他去别处复制粘贴一次；
另一条路的代价他一辈子都不会知道。**整个 M4 里，凡是「拿不准」的岔口都往这个方向倒。**

**(178) 走树的上限是三个，超限时保留已收到的，不抛异常**。
`MAX_FIELDS = 100` / `MAX_NODES = 6000` / `MAX_DEPTH = 96`，防的不是恶意，是一屏正常网页：
系统给 `onFillRequest` 的时间有限，拖久了用户看到的是「填充条没出来」而不是任何错误；
再赶上一个自己写的深嵌套布局，递归走法直接 `StackOverflowError`——
那是个 Error，会顺着 `catch (t: Throwable)` 变成一次静悄悄的「这次不填」。
所以走法是显式栈 + 深度上限，而不是递归 + 祈祷。
超限**停下来、保留已经收到的**：走到第 100 个框还没找到账号密码的页面，
多半也不会在第 101 个上有；而把已经收到的 99 个一起扔掉，代价是整屏都不填。
撞没撞到上限如实记在 `Parsed.truncated` 上，M4-4 那句「为什么有时候不出现」要拿它说话。

**(179) 一次 `Dataset` 里，每一个表单各判一次归属**。决策(158) 的最后一道落笔在这儿，
也是**最容易在收尾时把前面全部小心作废**的一行。决策(175) 说所有表单都留在清单里
（一次 `Dataset` 能把同屏几组框一起写好），于是顺手的写法就是
「主表单判过了，照 `plan.forms` 全写一遍」。那是错的，错法和 AutoSpill 同源：
同一屏上完全可能一组是 `example.com` 的 iframe、另一组是承载它的应用自己的原生框，
`FieldGroups` 早就把它们切成两组、各算各的 `Origin` 了，
而主表单判过的是**它自己那一组**的归属。所以 `AutofillOffer.writesFor` 对每一组
重新问一次 `DomainMatch.best`，只有 `Exact` / `SameSite` 才写，判不过的组一个字都不写，
而且**不为此说什么**——用户没要求往那一组填，它安静地空着才是对的。
这个错在真机上看不见：填充条照样弹出来，用户点一下，一切正常。

**(180) 不往本应用自己的界面上填，而且这一问排在「库里有什么」前面**。
拿自己的密码填自己的解锁页，先不说荒唐：那一屏是 `FLAG_SECURE` 的，
而填充条是**系统进程**画的，不受这个标记管；更要紧的是自动锁定那套相位（切后台就锁）
会和「系统为了画填充条把我们推到后台」打架。这一条也顺手挡住了系统设置里
挑默认填充服务时的那一屏。判断顺序同样是有意的：先问「这一屏有没有能填的框」、
再问「是不是自己」，最后才问库的状态——**前两问不需要知道库的任何事，
也就不会因为回答它们而泄露任何事**。`selfPackage` 由调用方传（`BuildConfig.APPLICATION_ID`），
不在内核里写死：写死一个字符串常量，改包名那天没有人会记得回来改它。

**(181) 一条都没自动匹配上时，出的是「空的填充条 + 一条搜索入口」，不是什么都不出**。
决策(160) 说「绝不自动建议」和「不许手动挑」是两件事，这就是它的落点：
`NoEvidence` / `UntrustedHost` / `WrongKind` 那几档条目一条都不会自动出现，
但用户手上很可能正有那一条。何况一个什么都不出的填充条，
用户唯一的结论是「这功能坏了」——而它恰恰是在保护他。
反过来，**锁着的时候那一条「先解锁」不说任何库内容**，连「这个网站存了 3 条」都不说：
不是不肯说，是**数不出来**，库文件是密文。这一点要写进文案里，
因为它正是这个应用和那些「锁屏时也能预览」的管理器的区别。

**(182) 浏览器信任分三档，而不是两档；内置摘要表宁可空着也不编**。
决策(164) 说线上实现必须校验签名，这条说的是校验不过时怎么办。
内置的摘要表注定不全——浏览器一直在增加、各家会换签名，而这个应用没有网络权限，
没法像别人那样在线拉一份名单下来。做成是非题只有两种写法，两种都是坏的：
「表里没有就当不可信」→ 表里少一家，用户在那个浏览器上**从此再也见不到填充条**，
而他没有任何办法查出原因；「表里没有就当可信」→ 这一层等于没做。
所以第三档 `PackageOnly` 说的是实话：这一家我们只核对了包名。
它照样够格自动建议（否则功能就废了），但界面上那句话不一样。
真正被挡下的是**表里有这一家、而装在这台设备上的包签名对不上**——
那不是「不认识」，是「它不是它自称的那个」，一个正常用户的手机上不会出现这种情况。
同理 `FINGERPRINTS` 现在空着：填错一条的后果是那个浏览器从此判成 `Unknown`，
表现是最常用的浏览器里再也不出填充条而且没有一处会说话；空着的后果只是停在原地。
摘要要从官方渠道的包上亲手算，拿不准的不加（同 `PublicSuffix` 那条「宁可缺、不可错」）。
还有一条顺带的：**已核验那句话里不许出现「安全」「已验证」这类词**。
我们核对的是「这个包是它自称的那个包」，不是「这个页面不是钓鱼网站」——
一句听起来像背书的话，会让用户在真该停下来看一眼的时候放心地点下去。

**(183) `BIND_AUTOFILL_SERVICE` 不进关于页那份权限清单**。
它写在 `<service>` 的 `android:permission` 上，意思是「谁想绑定这个服务必须持有它」，
而持有它的只有 `system_server`。它是一道**锁**，不是一项**能力**——
应用没有因此多要到任何东西，系统的「应用信息 → 权限」里也不会多出一行。
所以把它写进 `PERMISSIONS` 才是那句谎话：用户照着那份清单去核对会对不上，
而这一页的全部价值就在于每一条都能被自己核实。
但也不能装作没这回事：用户在系统里把这个应用设为默认填充服务时，
会看到一屏相当吓人的话（「它将能够看到你屏幕上的内容」），
那是系统对**所有**填充服务说的同一句话。所以另起一格 `AUTOFILL_NOTE`
接下半句：什么时候才看得到、看得到什么（框的类型和网站）、看不到什么（用户打的字）。
**不进权限清单，但要出现在权限清单旁边。**

**(184) 用户内容进「公共浮层」之前必须先洗一道**。
填充条上那三行字不是我们写的，而它要被画进**系统进程**的浮窗、浮在别人的应用上面
（输入法看得见、无障碍服务看得见、截屏录屏也录得到）。三件事：
压成一行、剔掉控制字符与**双向控制符**、按**码点**截断。
第二件是三件里唯一一件防的是恶意而不是失手：`U+202E` 之后的字符会倒着画出来，
把 `bank.com` 存成 `moc.knab\u202E`，在屏幕上一模一样。
这个应用里能塞进这种字符串的地方只有一个——用户自己那份 CSV，
而决策(156) 明说导入预览一格内容都不显示，于是它从没被人看过一眼。
**填充条是它第一次被画出来的地方，也就必须是它被洗掉的地方。**
`TextView` 的 `ellipsize` 不能替代这一道：只靠它的话，那个 200 字的名称
仍然会**整串**被交给系统进程去测量和布局。

**(185) 任何一个能让库从「锁着」变成「开着」的 Activity，都必须自己接
`onEnterForeground` / `onEnterBackground`**。
自动锁定的倒计时由 Activity 走 `onStop` 点着。填充用的解锁跳板页要是不接这两个回调，
用户在浏览器里解了锁、填充完成、跳板页 `finish` 掉——**没有任何一个 Activity 会为此走
`onStop`**（主界面那次 onStop 发生在更早以前，倒计时早就烧完并锁过一次了），
于是库从这一刻起一直开着，直到用户下次亲手打开应用再退出去。
这是一个只在自动填充这条路上才会出现的漏洞，在应用里怎么点都试不出来。
将来再长出第三个入口（快捷方式、通知、Credential Manager），第一件事是回来看这一条。

**(186) `FillCallback` 只用 `onSuccess`，永远不用 `onFailure`**。
`onFailure(CharSequence)` 那句话会**画在填充条上**，出现在别人的应用里。
它能说的没有一句是用户此刻用得上的（「结构解析失败」对一个正在登录的人意味着什么？），
却给了旁边那个应用一个探针：反复变换页面结构，看我们在什么条件下开口，
从而反推出我们的解析规则。出不了手就安静地不出手，理由留在 logcat 里
（而 logcat 那一行只有数字和原因，决策(144)）。同理，装不出任何 `Dataset` 时
返回 null 而不是一个空的 `FillResponse`——后者会让系统弹出一条**空的填充条**。

**(187) 手动挑那一下只往主表单那一组写。自动和手动的闸门不是同一道。**
自动那一侧（决策(179)）的闸门是归属判断：同屏每一组各判一次，判不过的一组一个字都不写。
手动这一侧，那道闸门被用户**主动越过了**——他挑的这一条对这一屏本来就不够格，
不然它早自动出现了。两条顺手的写法各错在一头：照 `writesFor` 一组一组判 →
他挑的这一条对主表单也判不过，于是**一个字都写不出去**，点下去什么也没发生而屏幕上没有解释；
改成「照 `plan.forms` 全写一遍」→ **那正是 AutoSpill**（一组是 `example.com` 的 iframe、
另一组是承载它的应用自己的原生框，他点头的是前一组）。
正解是**换一道闸门而不是把闸门拆掉**：只写主表单那一组，也就是
`FillPlan.pick` 挑出来的、优先是光标所在的那一组——**他此刻正看着的那几个框**。
越过归属只越过一次，而且只在他看得见的那一处越过。
主表单里那几个 target 是 `FillPlan.of` 挑好的，所以新密码栏（决策(170)）、
分不出新旧的密码框（决策(173)）、值为空的那一格（决策(174)）照样一个都没被越过。

**(188) 「会交给谁」那一行同时写应用名和包名，而且应用名要先洗一道。**
应用名（`ApplicationInfo.loadLabel`）是**那个应用自己声明的字符串**：
它可以把自己叫做「Chrome 浏览器」，也可以塞一个 `U+202E` 让名字倒着画出来。
这一处比填充条那一处（决策(184)）更要紧——填充条上那三行是**用户自己的**数据，
这一行是**被填对象提供的**数据，而它正是用户做决定时唯一看的那句话。
所以两件事一起做：洗（复用 `AutofillRow.clean`，不写第二份）+ **永远把包名一起写出来**。
包名由系统分配、应用自己改不了（决策(158)）：名字骗得了人，`com.example.free.wallpaper` 骗不了。
读不到名字时只写包名，**不写「未知应用」**——那四个字听起来像出了故障，
而包名已经把该说的都说了。

**(189) 挑选页进来时不摊开整库，但搜索搜得到整库。**
摊开最省事，「反正是我们自己的 `FLAG_SECURE` 页面」这个理由听着也成立。不摊开是三件事加起来：
他来这一页是为了找**一条特定的**，搜索比滚动快；一屏浮在别人应用上面的完整资产清单，
肩窥换来的只是省下两次打字；真正相关的那几条会被淹在几百行里，而它们是这一页的全部意义。
所以默认只摆两段：这个站够格的那几条（**不截断**，那个 8 是填充条的上限）+ 最近改过的十几条。
**但过滤只发生在默认清单上，`search` 一律不过滤**——不摊开 ≠ 搜不到，
后者就成了「替用户决定他自己那条数据能去哪儿」（决策(160)）。

**(190) 搜索结果按关键词的打分排，不拿归属重排。**
`VaultIndex.search` 给的顺序是「和你打的字最像的在前」。拿 `Verdict` 重排一遍，
会把用户明确搜出来的那一条压到下面去——他打了那几个字，
那几个字比我们的归属判断更能说明他要哪一条。归属只决定**标注和警告**，不决定顺序。
自动那一侧反过来（`AutofillMatch` 的排序以档位为首），因为那一侧没有关键词。
同理搜索本身**一个字都不重写**，直接用 M3-3b 那套：决策㉜ 已经把「哪些字段可以被搜」
钉死成一张白名单（名称 / 账号 / 网址 / 分类，**备注和密码不在里面**），
在这儿另起一套等于把那张白名单复制一份，而复制出来的那一份迟早会把备注也搜进去——
那正是用户拿来放密保答案和身份证号的地方。

**(191) 手动挑那一侧有第四句警告：`None` 而条目里存着别的网址。**
`DomainMatch.Verdict.needsWarning` 只管三档，因为 `None` 在自动那一侧永远不出现
（自动只收前两档）——而在挑选页上它是**最常见的一档**，这一页的整个用途就是挑一条没自动出现的。
而 `None` 有两种成因，代价差得很远，必须分开：
① 这一条一行网址都没存 → 很平常，谈不上「对不上」，这一页正是为它存在的；
为它摆一句警告，用户下次就学会跳过所有小字了（同决策(95)）。
② 这一条**存了网址，存的是别的站** → 极可能是他点错了行（两条名字相近的条目，
或者一份导进来的 CSV 里挨着的两行），而这是唯一一处能拦住他的地方，
拦住的代价只是他多看一眼。判的是「存过」而不是「存的那一行有没有意义」：
`domains` 里躺着一行空白（删干净了却留下那一行）和「一行都没存」是同一件事，
为它摆那句话会让用户去翻一条根本没有网址的条目。

**(192) 填充条末尾那条「在保险库里搜索」用的是数据集级认证，不是响应级。**
两条路都能把用户送到挑选页，差别在**别的那几行会怎么样**。
「先解锁」那一条只能用响应级（`FillResponse.setAuthentication`）：库锁着，
整份响应根本还没算出来，我们连有几条都数不出来。搜索这一条反过来——
上面那几条候选**已经实实在在装好了**，用户点的只是其中一行。
用响应级会把那几条一起吞掉：他点了搜索、进去又改主意退出来，回到填充条上时
那几条得重新算一次，而中途库可能已经自动锁定了，于是他看到的是「先解锁」。
**他明明什么都没做，填充条却退化了一档，而屏幕上不会有任何解释。**
数据集级（`Dataset.setAuthentication`）只替换它自己那一行。
配套的一条：那几行 `setValue(id, null)` **不是占位垃圾**——
带认证的 `Dataset` 必须先声明「我覆盖哪几个框」，值给 null 表示「等认证回来再说」；
一个都不声明的话系统认为这一行填不了任何东西，**它根本不会画出来**，
表现就是「一条候选都没有的时候填充条整个不出现」，而那正是这一行要治的病。

**(193) 挑选页的相位每一帧重算，不是进来时算一次。**
这一页浮在别人的应用上面，而用户可能在上面停留很久——翻一屏条目、接个电话、
切出去查个验证码。**自动锁定会在这中间过去。** 相位只在进来时算一次的后果是：
库在会话里已经锁上了，界面上却还摆着一份摊开的资产目录，页面还在、字还在、
一切正常，没有任何一处会报错。所以判断抽进纯逻辑的 `AutofillPickFlow.phase()`，
每一帧重新问一次；`Locked` 一到就把清单收起来换成解锁屏，解开之后回到清单
（而不是回到一片空白）。`delivered` 一并做成 Compose 状态：普通字段改了不触发重组，
「交过就走」那一句在界面上永远等不到，全靠 `finish()` 兜住——能跑，但那是巧合。
判断的**顺序**同样钉死：`refusal` 排在库状态之前（同决策(180)），
因为那一问不需要知道库的任何事；反过来写就是「为一件注定做不成的事，
先向用户要了一次主密码」。

**(194) 挑中之后换的是整屏，不是从底下推一个半高的 sheet。**
决策(160) 说手动挑这一下之所以被允许，靠的是「自动的那一下用户可能没看清，
手动的那一下他一定看清了」。而那几句警告每一句都是三四行的完整句子——
塞进半屏 sheet 里，它们会变成一个需要滚动的小窗口，或者被人顺手折成一句「查看详情」。
**那两种做法都会让上面那条前提不再成立，而这一页存在的全部理由就是那条前提。**
所以 `Choice.warnings` 逐句原样摆开：一句都不折叠、不省略号、不「展开更多」。
同理，「会交给谁」那一行钉在顶上不随列表滚走——它是这一页上唯一一句
用户做决定时非看不可的话（决策(188)），而列表往下滚两屏它就不在视野里了。

**(195) 保存那一路是另一份模型，而且只做取舍不做改写。**
决策(165) 欠的东西就是 `SavedFields.kt`。往 `RawField` 上加一个 `text` 字段省事、
当天也没有症状，但那个类是三层内核的输入，它一旦抱着明文，
那三层的每一个 `toString` / 日志 / 异常消息就都成了泄露点。
第二半同样要紧：`AutofillRow.clean` 那道洗是**给屏幕看的**，
而这一层洗出来的东西要被存进库、以后原样填回登录框——
洗过的密码登不进任何网站，用户要到下次登录才发现，且不会想到是保存那一步动的手。
所以这一层**要么原样收下，要么整格拒收**（超长、控制字符、双向控制符都是整格拒收）。
唯一的不对称是账号 trim 首尾空白而密码一个字符不动，理由写在 `SavedFields.capture` 上。

**(196) 不静默改库：`onSaveRequest` 只产出提案，落盘必须经过一屏看得见的确认。**
系统给的那个保存框只有「保存 / 不用」两个按钮，按下去之后发生了什么用户是看不见的——
那正是不能照抄的地方。这一层只产出 `Proposal`，一行都不落盘；
确认页上要逐条写清「改的是哪一条、动的是哪几个字段」。

**(197)「库里已经一模一样」这一句只能在解锁之后说。**
库锁着的时候数不出库里有什么，也就没法提前知道这一次是白跑。
宁可让用户白解锁一次，也不能因为库锁着就把他刚打的密码丢掉——
刚注册完那一次正是最值钱、也最不可能再打一遍的一次。
（同一条的另一面：`refuse` 那三问一次都不用碰库，所以它们排在库状态之前，同决策(180)。）

**(198) 明文不进 `Intent`，只进一个票号。**
`putExtra("password", pwd)` 看起来只是「传给我自己的另一个页面」，实际上要经过
`system_server`：extras 会被 parcel 出去、在系统进程里被解析、排进 `ActivityManager`
的记录，还会被 `dumpsys activity` 打出来。密码于是离开了我们这个进程，
落进一个我们既管不着生命周期、也不知道谁在 dump 的地方。
`DraftHandoff` 早就为**搜索关键词**画过同一条界限（不许进 `savedInstanceState`），
理由一字不差；这里只是同一条界限上更硬的一段。
`SaveHandoff` 取一次就清、同时只留一份、五分钟 TTL 兜底，
**票对不上时不许清掉手里那一份**（一张过期页面留下的旧票不该把刚放进来的那份清掉，
那会表现成「保存屏空着弹出来」，而他刚打的密码已经不在屏幕上了）。

**(199) 不够格自动填的来源，永远只能新增，不能更新。**
后面四档（`UntrustedHost` / `NoEvidence` / `WrongKind` / `None`）在**填充**那一侧
是「不自动出手，但用户可以手动挑」——挑错了顶多是这次填错，退出去重来即可。
保存这一侧不能照搬：一个套着 WebView 假冒登录页的应用拿到的不只是这一次输入，
它还能借这个保存框**改掉用户库里那条真的**，于是用户以后每次登录**真网站**
填出去的都是被改过的值。新增的代价是库里多一条他看得见、删得掉的东西；
更新的代价是一条他看不见、也找不回来的丢失。
这道护栏长在 `proposeUpdate` 里而不是挑选那一步里——挑选只是默认值，护栏必须长在落笔处。

**(200) 认不出该改哪一条时，一条都不动。**
两种形态各有名字：`CannotTellPassword`（同屏两个都判成已有密码的框，值还不一样，
对称于 `FillPlan` 底线二「一个都不填」）和 `CannotTellEntry`（只读到密码没读到账号，
而这个站在库里不止一条）。后一种是分屏登录第二屏的常态，猜错就是把另一个账号的密码覆盖掉。
两句话都要说清「所以这次没有动任何一条」以及「你可以自己打开那一条改」。

**(201) 保存只增不改：除了密码那一格，已有的值一个都不覆盖。**
名称、分类、备注、收藏一个字不碰——用户当初给这一条起的名字是他在列表里认出它的唯一依据，
用一个从主机名推出来的名字盖掉它，会让他在自己的库里找不到东西，
而他甚至不会知道是哪一步改的（同决策(150)「覆盖时空的不覆盖」）。
账号只在原来是空的时候补上；原来有值而对不上，是 `BLOCKED_OTHER_ACCOUNT`，
**画成禁用而不是悄悄改成新建**（同决策(174)：他明明选中了那一条）。
于是整份提案里**只有密码那一格可能是 `Replace`**，有用例钉着。

**(202) 存下去的那一行不上卷，建议的名称反过来上卷。**
`domainLine` 存归一后的主机名原样（`login.example.com`），不改写成可注册域：
上卷会让以后的匹配从 `SameSite` 变成 `Exact`，看起来更好用，
但那是**我们替用户扩大了这条凭据的适用面**，而扩大匹配面是这条链上唯一代价大的方向。
存窄了的代价只是以后在兄弟域上多看一句提示，而那一句本来就该看见。
`suggestedName` 则用可注册域（`example.com`）——名字是给人看的标签，
和凭据的适用范围本来就不该是同一个东西。
网址永远只追加不删除（同决策(56)「网址只丢不改写」），
判重比的是**归一之后**的形式，否则每登录一次就追加一行看起来一模一样的网址。

**(204) 「这一屏填得出什么」和「这一屏值不值得看着」是两个问题，各问一次。**
`FillPlan` / `AutofillOffer` 答前一个，`SavePlan` 答后一个，两条链在服务里**并排走**，
不许由前一个的答案推出后一个。有一屏上两者答案正好相反且都对：
**新注册**（填不出任何东西，同时是最值钱的一次保存）和
**只有新密码框的改密码页**（`FillPlan` 底线一说一个框都不填，`SavePlan` 说这正是要看的那一组）。
落点是 `AutofillResponses.saveOnly`：一条 `Dataset` 都装不出来时**不返回裸的 null**，
退成一份只挂 `SaveInfo` 的响应。**库锁着也照样挂**——保存这一路一次库都不用打开。
唯一一条 `SavePlan` 答不了的补在服务里：**这台设备上还没有库的时候不挂**
（那时按下保存框会走 `AutofillSaveFlow.Leaving`，安静关掉；
向用户要一次确认再什么都不做，比一开始就不问糟得多）。

**(205) 保存那一路必须重新 parse 一遍，句柄不许跨请求复用。**
手上明明有填充那一刻算好的 `Parsed` 和 `SavePlan.Info`，存成字段复用是最自然的写法，
而它是错的：句柄就是先序遍历的序号，只在一次 `AssistShell.parse` 里有意义，
而从挂 `SaveInfo` 到用户提交，中间隔着一次登录成功——网页那侧 DOM 已经换过一批节点。
拿旧句柄读新结构，读到的不是「没有值」就是**另一个框里的值**，
后者会把一个手机号、一段地址当成密码存进库。何况服务实例本来就可能已被解绑，
而 `SaveRequest` 里带的结构快照是自足的。

**(206) 只读 `getAutofillValue()`，永远不读 `getText()`。**
两个方法都能给出「这个框里的字」，但不是一回事：后者是给屏幕和无障碍看的那一份，
密码框上它可能是**一串圆点**。把圆点存进库，用户下次填出去的就是圆点，
而当时屏幕上写着「已保存」。所以不许加 `?: node.text` 来兜底——
那一改在非密码框上看不出区别，代价只落在唯一要紧的那一格上。
`isText` 不成立的值（开关、日期、下拉）一律不要：那种框本来就不该被判成账号或密码，
真读到了说明上游判错了。
这一条连同「读值只在 `SaveShell` 一个文件里发生」是决策(165) 那条边界的另一半。

**(207) 收值一格一格收：不作废整份、不合并、不改写。**
一格取不到就跳过并记一笔，「够不够存」交给 `AutofillSave.refuse`（`NothingCaptured`）——
在收值这一层提前返回 null，分屏登录的站会从此存不进任何东西。
不许 `distinctBy { it.what }`：改密码页会只剩一格，而 `conflictingPasswords`
（分不清就一个都不存）也当场失效。取舍全交给 `SavedFields.capture`
（账号 trim、密码一个字符不动、超长和控制字符整格拒收），
这一层只负责**别把它绕过去**——先 `trim()` 一遍再传进去，
「以空格结尾的密码原样存下来」这条保证就悄悄没了。
记账（`Tally`）只有数字，**不进屏幕**：用户要看的是那一句实话，不是我们的记账；
但 `tooLong` / `control` 不为零意味着我们把某个框判错了，那需要在 logcat 里看得见。

**(208) 凡是交回 `EXTRA_AUTHENTICATION_RESULT` 的那份响应，也要挂 `SaveInfo`。**
响应级认证的语义是「系统拿新的那份把旧的整个顶掉」，
所以解锁跳板页里漏一行的表现是：**凡是点过一次「先解锁」的那一次登录，
提交之后保存框不出现**——而那正是最该出现的一次（库刚解开、密码多半还没存进去）。
它不报错、不崩、当天没有任何症状。同一条也是「`save` 参数刻意不给默认值」的理由：
给了 `= null`，漏传的那一处会安静地编译过去。

**(203) 确认页上永远不摆密码。**
`Change.shown` 在密码那一档构造时就是 `null`，不是「记得别显示」。
用户在那一屏上要确认的是**改的是哪一条、动了哪几样**，不是核对密码字符串：
他刚打完那个密码，屏幕上多半还看得见；把库里那个旧密码也一起摆出来，
才是这一屏上唯一真正新增的泄露面——而它对这个决定没有任何帮助。

**(209) 那个开关两个方向拨都只是跳出去，而且必须说出来。**
Android 没有给应用「把自己从默认填充服务上撤下来」的方法。于是有两个选择：
把已经开着的开关画成灰的（老实，但用户会以为关不掉），
或者让它能点、点下去跳到系统那张列表。选了后者，
**代价是副标题里必须写清这一下不会当场关掉什么**——
一个拨过去自己会弹回来、还不解释的开关，比一个灰着的开关更让人生气。
这是决策(61)（不能点的控件必须自己解释为什么）在一个「能点但不是你想的那样」的控件上的变体。
`AutofillSettingsModelTest` 里有一条用例钉着那句话里必须同时出现「系统设置」和「撤下来」。

**(210) 「没设过」和「设的是别人」必须分成两档。**
两者在屏幕上都是开关关着，但后一档点下去会把用户**正在用的那个密码管理器顶下去**。
并成一档的后果不是他被吓一跳——是他真的换了，然后过几周在某个网站上发现
存在那一个里的密码不出来了，而他完全想不起来是这一下造成的。
所以那一档的说明必须同时说两件事：系统只认一个（这是代价），
以及那边的数据一个都不会动（这是让本该敢试的人敢试）。

**(211) 「为什么有时候不出现」是这一页真正的产出，不是附录。**
M4 里那些克制的决定——认不出输入框就什么都不猜、原生应用配网址判 `NoEvidence`、
浏览器不在名单里判 `UntrustedHost`、新密码框一律留空——**在屏幕上全都长成同一个样子：
什么都没弹出来**。一个功能坏了和一个功能在克制，用户分不出来，
那这份克制对他来说就等于坏了；而他的合理反应是换一个「什么都肯填」的管理器，
那正是 AutoSpill 那条路上的人想要的。
所以那七条不是帮助文档，是这个功能能不能被理解的前提。
它们按**发生的概率**排，不按技术上的严重程度排：绝大多数人来翻这一页
是因为头两条（没设默认 / 库锁着），而那两条一句话就能自己解决。
每一条「我们不填」都必须给出路（手动挑那条路始终在），
末尾还要把底线正着说一遍（`WHY_TAIL`）——七条里五条的结论是「故意不填」，
读完不补这一句，留下的印象是这东西毛病真多。

**(212) 关于页补的是一个指路牌，不是那七条的副本。**
待办里写的是「关于页补一段」。补的是一行 `ABOUT_POINTER` + 一个能点的落点，
而不是把 `WHY_NOT_SHOWING` 再摆一遍。两个理由：一是同一段话摆两处，
早晚会只改一处（决策(131) 那条引用常量的来意就是这个）；
二是两边的读者要的东西根本不同——**来翻关于页的人在判断这个应用可不可信，
去自动填充页的人手上有一个具体的、刚才没弹出来的输入框**。
同理，这一页那三条底线（`LIMITS`）和关于页那一段（`SettingsModel.AUTOFILL_NOTE`）
说的是同一件事却不是同一份字：两份都短、各自完整、谁都不必先读另一份。
有一条用例钉着它们**不许逐字相同**——逐字相同就该合并成一个常量，
摆两份的唯一正当理由是读者不同。

**(213) 设置主页上「自动填充」那一行永远不变色。**
备份那一行会转黄铜色，因为不备份**会丢东西**；主密码那一行会，
因为改完主密码旧备份就打不开了。而没开自动填充的人只是要多复制粘贴一次，
什么都不会失去——它不是一件待办。为了推销一个功能去染黄一行字，
代价是这一页的颜色从此不可信，等到备份那行真的黄了，用户也不会看了。
`settingsRowUrgent()` 这个恒返回 false 的函数存在，就是为了让这件事
是一个被写下来、被用例钉住的决定，而不是某次疏忽。同决策(95)。

**(214) 内联是「全有或全无」：摆不齐就整份退回浮层，绝不出半条。**
输入法给的规格可以是空的、可以是一份我们没见过的版本、也可以少于格数。
顺手的写法是「认得几格摆几格」，而它是错的：内联和浮层在同一次请求里是
**两种画法，不是两份内容**。摆出一条只画了两格的内联条，用户看到的是一个
缺了几行的填充条，而他没有任何办法知道自己少看见了什么——浮层那一条
本来是完整的，是我们把它换掉了。所以四道门（没问 / 要 0 格 / 一份规格都没有 /
规格不认得）里任何一道不过，整份都回到 M4-2a 那天写下来的那条老路上。
反过来说，**浮层那一份永远都在**：内联只是给 `Dataset` 多挂一份画法，
挂不上的那几条照样在浮层里。「不能两条都不出」在代码里就是这一句。

**(215) 内联条上永远留一格给「在保险库里搜索」，哪怕只剩一格。**
输入法只给一格时，那一格是搜索而不是排第一的候选。理由不是谦让，
是**没进内联的那几条不许悄悄消失**：那一格上写着「还有 N 条」，
N 里数着被 `MAX_SUGGESTIONS` 截掉的、兄弟域被挡下的、以及排在格数之外的全部。
少了它，用户看见一条候选（或者一条都没有），而他会以为这就是全部——
一个「悄悄少给两条」的密码管理器比一个不出现的更糟。
于是内联那一格上的 N 和浮层那一行上的数字**可以不一样**，
两处各自说的是自己那块屏幕上的真话。

**(216) 兄弟域那几条不进内联条。**
内联一格只有标题和副标题两行，摆不下「你存的是 mail.example.com」那一句，
而决策(159) 说那一句是公共后缀表出错时的第二道兜底，不许省、
也不许和精确档混在一起显示。两条路只能选一条：把那句话塞进副标题
（副标题会被窄的那种输入法直接丢掉，于是警告悄悄没了），
或者不摆。选不摆——它们计进决策(215) 那个 N 里，用户点一下就看得见。

**(217) 解锁跳板交回去的那份响应不带内联。**
`InlinePresentationSpec` 描述的是**那一次请求那一刻那个键盘**的建议条：
尺寸、字号、配色。它跟着 `FillRequest` 走，不跟着解锁跳板那个 `Intent` 走。
顺着 `PendingIntent` 把它塞过去做得到，但用户刚刚离开了那一屏、
在一整屏解锁页上过了一次指纹再回来——那期间输入法可能已经换过一轮会话，
甚至换了一个输入法。拿一份过期的规格去画，画出来什么样没有人说得准，
而它出现在**别人的应用**上面。所以那一份退回浮层：候选一条不少、
解开之后当场就填上（决策(185) 那一页存在的全部理由没有受影响），
只是那几条不出现在键盘那一条上。

**(218) 内联那一格上不出现密码，靠的仍然是类型而不是纪律。**
`AutofillRow.Chip` 收的是 `AutofillOffer.Item`（里面根本没有能放密码的字段），
`InlineViews` 只接洗过的 `Chip`，不接 `Item`，更不接 `FillPlan.Write`。
和填充条那一层（底线一）一字不差——因为 `Slice` 和 `RemoteViews` 一样要
parcel 出去交给系统，再交给**输入法进程**去画。多了一条路，就多一处要守；
让它守不住比守住难，是唯一可靠的写法。

**(219) 内联那一格不「钉住」（`pinned = false`）。**
钉住的格子会长期占着别人键盘上的位置，连他打字的时候也在。
这条建议条是输入法的地盘，我们只在他点到一个账号框或密码框时出现一下。
一个把自己钉在别人键盘上的密码管理器，第二天就会被卸载——
而它被卸载的那一刻，用户的密码还在这台设备上的那个文件里。

**(220) 多选只留长按一个入口，顶栏不给它按钮；提示放在列表末尾。**
顶栏是每一屏都在的地方，摆一个图标进去就等于宣称「这件事经常要做」。
多选不是——它一个月用不到一次，而旁边那两格（搜索、设置）是每天都点的；
三个 44dp 方块挤在一起，多的那一个换来的主要是误触（同决策(97) 不把
「立即锁定」放顶栏、决策㊿ 危险动作要让人走到它跟前）。
撤掉之后长按成了唯一入口，而长按在列表上是一个**没有任何提示的手势**——
不知道它存在的人永远不会去试，这一条不能靠「反正老手知道」搪塞过去。
接住它的是列表末尾那行灰色小字（`ListSelection.LONG_PRESS_HINT`），
三个位置上的取舍都写死了：
· **末尾，不是顶上。** 顶上那句每次打开保险库都会被看见，而它只需要被看见一次；
  常驻在扫读起点上，它会先变成噪音，再连累旁边那条真正要紧的备份提醒
  一起被略过（同决策㉞：备份是最新的时候什么都不显示）。
  滚到底的人恰恰是「翻来翻去、发现堆了一批废条目」的那个人。
· **两条起才摆**（`HINT_MIN_ENTRIES`）。只有一条的库上，「一次删掉几条」
  是句用不上的话，却会成为一个新用户那一屏上最显眼的东西。
· **不可点。** 做成能点的，等于把刚撤掉的按钮换个地方摆回来。
  这一行的用途是教会那个手势，不是替代它——学会之后哪一屏上都能用。
文案里同时说清「怎么进」和「进去能干什么」，且**不许再提右上角**：
界面上已经没有那个按钮了，一句让人去点不存在的东西的提示，
换来的结论是「这个功能坏了」。四条用例钉着这几件事。

**(221) 自动填充这条链上的三个页面，一个都不由我们自己 `startActivity`。**
解锁跳板和挑选页走 `setAuthentication` 的 `IntentSender`，保存确认页走
`SaveCallback.onSuccess(IntentSender)`。理由不是风格统一，是这三个页面都要在
**别人的应用**正在前台的时候浮出来，而那一刻我们这个进程没有任何可见窗口——
自己 `startActivity` 在 Android 10 及以上会被后台启动限制拦下，
而且**不抛异常**：`runCatching` 抓不到，日志里连一行失败都不会有。
保存那一页原来就是这么写的，代价是这个功能在真机上从来没走通过，
而且当天没有任何症状（M4-3c 那一节是现场记录）。
推论有两条，都得写死在代码注释里：① 26/27 上那个重载不存在，
所以旧路只在 `SDK_INT < P` 时才走；② 凡是「页面起不起来」这件事，
两条路上都要各有一次 `SaveHandoff.clear()`——起不来就等于没人来取，
而槽里躺着的是一份明文密码。

---

## 待办

- [x] **M3-1 界面基础设施**：组件库、封条、剪贴板、强度评估、导航骨架 ✅
- [x] **M3-2a 首次引导建库流**：欢迎页、主密码页、KDF 校准、会话接管 ✅
- [x] **M3-2b 首次备份导出**：SAF 落文件、写后回读校验、可信中断、`lastBackupAt == 0` 时挡在主图前面 ✅
- [x] **M3-2c-1 解锁内核与主密码解锁页**：三入口共用退避、失败分类、退避倒计时 UI、自动锁定提示 ✅
- [x] **M3-2c-2 快捷解锁页**：PIN 键盘、`BiometricPrompt` + `CryptoObject`、指纹失效的降级路径 ✅
- [x] **M3-3a 列表内核与列表页**：分组排序、搜索打分、域名归一、备份提醒条 ✅
- [x] **M3-3b 搜索页**：全屏搜索、命中高亮、可搜字段说明、分类快捷键、无结果时的去处 ✅
- [x] **M3-4a 条目详情页**：遮蔽 / 复制 / 收藏 / 删除 + 撤销墓碑页 ✅
- [x] **M3-4b 编辑页**：条目表单内核 + 可复用字段块 + 编辑页（M3-5 最后一步复用同一套字段）✅
- [x] **M3-5a 密码生成器**：两种模式、无偏差洗牌、容斥算熵、覆盖层（不占路由）✅
- [x] **M3-5b 新增 3 步流**：名称/账号 → 密码（复用 M3-5a 的生成器）→ 归类与网址，
      三步共用一个草稿、共用编辑页那套字段块；判重提醒；从搜索页带进来的关键词
      在 `DraftHandoff` 里取一次就清 ✅
- [x] **M3-6a 设置内核 · 设置主页 · 关于页**：自动锁定/剪贴板时长（点一下就落盘）、
      备份入口、关于页（真实参数 + 权限清单 + 「没有的东西」）、立即锁定 ✅
- [x] **M3-6b-1 快捷解锁内核 · 指纹绑定 · 安全设置页**：`beginBiometricEnrollment`
      → CryptoObject → `finishBiometricEnrollment`、解绑、六种设备支持度各自的说明、
      设置主页「安全」分区里那一行 ✅
- [x] **M3-6b-2 PIN 设置流**：`enrollPin`（复用 M3-1 的 `PinBuffer` 与 PIN 键盘）、
      两次输入比对、弱 PIN（全同/连号/循环/键盘直线/生日样式）的提醒、改 PIN、解绑 PIN，
      以及在安全设置页上补出 PIN 那一行。**M3.5 到此为止全部接通**，
      下一步是 M3-6c ✅
- [x] **M3-6c-1 修改主密码**：验旧口令（决策(112)）→ 重新校准 → 重包库主密钥 → 更新会话文件头
      → 记 `masterChangedAt`；已绑定的 PIN / 指纹**一个字都不用重设**（决策①，有真文件用例钉着）；
      「旧备份只认旧主密码」在三个时机各说一次（决策(114)）✅
      注：`VaultFile.rewrap` 其实**会**把数据密文重新加密一遍——新文件头换了 AAD，
      不重加密就等于给 KDF 降级攻击留门。原待办里「不重新加密数据」那句话是错的，
      决策① 说的是「不需要重新派生每条数据的密钥」，两回事。
- [x] **M3-6c-2 删除保险库**：删库不做覆写擦除（决策⑧）的如实交代、
      连带清掉快捷解锁的 Keystore 钥匙与 prefs（**清残留在删文件之前**，决策(120)）、
      删完相位翻回 `NoVault`（决策(122)）、确认方式是**主密码而不是抄写短语**（决策(119)）✅
      注：`VaultStorage.deleteAll()` 和 `VaultRepository.deleteEverything()` 在 M2 就写好了，
      这一步没有动它们一行——要补的全在「顺序、说什么、以什么为准」上。
- [x] **M3-6c-3a 重来内核**：两道门槛（抄写「我没有主密码了」+ 按住三秒，决策(126)~(128)）、
      四段实话、清残留 → 删文件 → 相位从 `Locked` 翻回 `NoVault`。
      **全程一次都没打开过库**（库文件坏掉的人走的也是这一页），
      控制器不和删除页那个合并（决策(130)）。既有文件一个字节没动 ✅
- [x] **M3-6c-3b 重来页与入口**：`ResetVaultScreen`（实话 → 两个问句 → 会没什么 →
      抄写框 → 长按按钮 → 两步进度 → 失败横幅）、解锁页那个「忘记主密码了？」弹窗
      补上次按钮的落点（`DIALOG_SECONDARY` / `DIALOG_SECONDARY_NOTE`；决策⑮ 已经把
      次按钮和取消手势拆成两个回调，所以放在次按钮上是安全的，那条决策到这一步
      才第一次派上用场）、`Route.RESET` **只注册到解锁图**、长按手势那个组件
      （`pointerInput` + `awaitEachGesture`，松手或被父滚动抢走都中止）✅
      新抽了一个纯逻辑的 `HoldProgress`（决策(133)），于是「完成只报一次」
      和「松手是中止不是暂停」这两条不用靠肉眼在真机上看。
      **M3.5 到此全部收尾。** 下一步见决策(132)：**M5 之前不出内测包**——
      这一页把用户指向的 `Route.RESTORE` 还是占位屏。
- [x] **M4-1a 域名归属内核**：公共后缀表（只列多段的 + 三道兜底）、可注册域与兄弟判定、
      「包名还是主机名」、IDN 归一、六档 `Verdict`、候选挑选与排序。
      **决策㉝ 欠了三个模块的那张表，在这一步补上了**；59 个用例纯 JVM 可跑 ✅
- [x] **M4-1b-1 字段角色识别内核**：`RawField` / `FillContext` 纯数据模型
      （**里面没有「这个框现在写着什么」**，决策(165)）、四档证据（决策(166)）、
      五种角色、两张负面表（决策(168)）、三道硬性排除。28 个用例覆盖 49 个场景 ✅
- [x] **M4-1b-2 分组与归属**：把一屏字段切成几组「表单」（按归一后的 `webDomain`
      + 角色序列，**刻意不按控件层级**，决策(172)），**每组各算各的 `Origin`**——
      决策(158) 唯一能被写错的地方就在这儿（决策(171)）；
      每组填哪几个框（六档 `Kind`：登录 / 分屏两屏 / 要设新密码 / 密码分不出新旧 / 没得填）、
      哪几个刻意留空（四档 `Skipped`）、主表单挑哪一个（决策(175)）；
      输出的 `Plan` + `writes()` 就是交给 M4-2a 的全部东西，它拿 `handle` 换 `AutofillId`
      即可，一行判断都不用再做。65 个用例纯 JVM 可跑 ✅
      注：`Glyphs.kt` / Manifest / 依赖一个字没动；唯一改的既有文件是
      `AutofillTarget.kt` 里 `Origin` 的两个 `toString`（纯加法，见决策(171) 末段）。
- [x] **M4-1b-3 `AssistStructure` 薄壳**：走那棵树、把 `ViewNode` 摊成 `RawField`、
      `handle` ↔ `AutofillId` 的对照表。**原计划「这一层不写单测」，改了**（决策(176)）——
      走树里藏着三条错了也不报错的规则（`webDomain` 往下继承、继承只能往下不能往旁边、
      看不见是整棵子树的事），而它们没有一条需要 `android.*`。
      于是走树本身搬进纯 Kotlin 的 `StructureRules`（`Tree<N>` 抽掉节点长什么样），
      `AssistShell` 只剩一串 getter + 句柄对照表；拿不到 `activityComponent` 就
      **一个框都不收**（决策(177)）。34 个用例纯 JVM 可跑 ✅
- [x] **M4-2a-1 填充响应内核**：`onFillRequest` 那三条路的**判断部分**——
      没有库 / 已锁定（只出一条「先解锁」，且**一个字的库内容都不说**）/ 已解锁（出候选）；
      填充条上**不显示密码**（`Item` 里没有那个字段）；
      **每个表单各判一次归属**（决策(179)，这一层是最后一道能把前面全部小心作废的地方）；
      不往自己的界面上填（决策(180)）；空的 `Offer` 不退化成「什么都不出」（决策(181)）。
      30 个用例纯 JVM 可跑 ✅
- [x] **M4-2a-2① 浏览器身份核验**：决策(164) 欠的那一步——`PackageManager` 取签名证书摘要，
      和内置表比对；三档信任（已核验 / 只认包名 / 不认识，决策(182)）；
      内置摘要表**刻意留空**（编一个假的比空着糟得多），判档规则由用例注入的小表钉住。
      21 个用例纯 JVM 可跑 ✅
- [x] **M4-2a-2② `AutofillService` 组件**：服务本体（`onFillRequest` → `AssistShell.parse`
      → `FillPlan.forRequest` → `AutofillOffer.respond` → `FillResponse`，**通篇只有管道**）、
      `RemoteViews` 那三行（`AutofillRow` 先洗一道，决策(184)）、
      `IntentSender` + 解锁跳板页（复用主界面那两屏，解开之后**当场填上**，不用再点一次）、
      跳板页自己接自动锁定的两个回调（决策(185)）、`FLAG_SECURE`、
      Manifest 加 `BIND_AUTOFILL_SERVICE` + `res/xml/autofill_service.xml`。
      **原待办这一条里「第一次给权限清单添东西」那句话是错的**（决策(183)）：
      `android:permission` 写在 `<service>` 上是一道锁不是一项能力，
      `<uses-permission>` 仍然只有 `USE_BIOMETRIC` 一条，`PERMISSIONS` 一个字没动，
      另起一格 `AUTOFILL_NOTE` 交代这件事。28 个用例纯 JVM 可跑 ✅
      注：末尾那条「在保险库里搜索」**这一步还没摆**（它要跳 M4-2b 那个挑选页），
      于是一条都没匹配上时眼下是「不出填充条」。文案已在 `AutofillRow.forSearch` 备好。
- [x] **M4-2b-1 挑选内核**：整页该不该出现（两条同 `AutofillOffer.respond`，顺序也一样）、
      默认摆哪两段（不摊开整库，决策(189)；但搜索一律不过滤）、
      搜索复用 M3-3b 那套 `VaultIndex.search`**一个字不重写**且不拿归属重排（决策(190)）、
      **四句**警告（决策(160)/(161)/(164) 那三句 + `None` 而存了别的站那一句，决策(191)）、
      「这些内容会交给 ⟨应用名⟩（⟨包名⟩）」那一行（应用名先洗一道，决策(188)）、
      **最后只往主表单那一组写**（决策(187)，这一层是最后一道能把前面八个内核全部作废的地方）。
      71 个用例纯 JVM 可跑 ✅
- [x] **M4-2b-2 挑选页与接线**：`AutofillPickActivity`（`FLAG_SECURE` + 接
      `AssistStructure` + **自己接自动锁定那两个回调**，决策(185) 对它同样成立）、
      Compose 页面（`Listing` / `Row` / `Choice` 三样摆出来，警告一句不折叠）、
      `AutofillResponses` 末尾补上那条「在保险库里搜索」（**数据集级**认证，决策(192)）、
      确认后拿 `AutofillPick.writes` 装 `Dataset` 交回 `EXTRA_AUTHENTICATION_RESULT`。
      **内核 `AutofillPick.kt` 一行都没改**，如 M4-2b-1 末尾所写。
      新抽了一个纯逻辑的 `AutofillPickFlow`（决策(193)），于是「摆着清单时被自动锁定，
      清单要当场收起来」这一条不用靠肉眼在真机上等满五分钟去看。28 个用例纯 JVM 可跑 ✅
      **M4-2b 收尾，「用户自己挑一条」这条路整条打通。**
- [x] **M4-3a 保存内核**：保存这一路**独立的**字段模型（决策(165) 欠的那一份，
      收值只取舍不改写，决策(195)）、`SaveContext`、三条不碰库的拒绝（同决策(180)）、
      新增还是更新（**不够格自动填的来源永远只能新增**，决策(199)；
      认不出该改哪一条时一条都不动，决策(200)）、改动清单（**只增不改**，
      整份提案里只有密码可能是 `Replace`，决策(201)）、存成什么样（决策(202)）、
      **屏幕上永远不摆密码**（决策(203)）、明文不进 `Intent` 的交接槽（决策(198)）。
      75 个用例纯 JVM 可跑 ✅
- [x] **M4-3b-1 保存的判断壳与相位机**：`SaveInfo` 该不该挂、看着哪几个框
      （**所有密码框都看，包括新密码框**——和 `FillPlan` 底线一方向相反）、
      哪一个进必填（**永远只有一个**，优先新密码框）、`FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE`
      网页加原生不加、四档不挂的实话；确认页五个相位
      （**摆着确认单被自动锁定要当场收回去并 `SaveHandoff.clear()`**、
      交接单取不到必须走人、**解锁之后才算出来的那两档要说一句而不是安静关掉**）、
      顶栏与按钮分两档、名称栏那条「用户自己打的字一个都不洗」。
      **`AutofillSave.kt` 一行都没改。** 58 个用例纯 JVM 可跑 ✅
- [x] **M4-3b-2① 服务接线与读值**：`FillResponse` 上按 `SavePlan.Info` 挂 `SaveInfo`
      （`AutofillResponses.saveInfo`，类型位 / 必填 / 可选 / 那个旗子）、
      **填不出东西时退成只挂 `SaveInfo` 的响应**（决策(204)，新注册那一屏）、
      锁着也照样挂、库都没有时不挂；解锁跳板交回去那份也要挂（决策(208)）；
      `onSaveRequest` → **重新** `AssistShell.parse`（决策(205)）→ `SavePlan.decide`
      → `SaveShell.values`（**全工程唯一一处 `getAutofillValue()`**，决策(206)）
      → `SaveCapture.capture`（一格一格收、不合并、不改写，决策(207)）→ 记账日志。
      `SavePlan.kt` / `AutofillSave.kt` / `AutofillSaveFlow.kt` / `SavedFields.kt` /
      `SaveHandoff.kt` 一行都没改。27 个用例纯 JVM 可跑 ✅
      注：`SaveCapture.Capture` 眼下只进一行日志，**不进 `SaveHandoff` 也不拉起页面**——
      往进程里放一份没人来取的明文，正是 `SaveHandoff` 三条纪律反对的东西。
      **② 之前不要出内测包**：保存框已经会出现，按下去还什么都不发生。
- [x] **M4-3b-2② 确认页**：`AutofillSaveActivity`（`FLAG_SECURE` + 自己接自动锁定那两个回调、
      **明文三个清点**——`take` 一次就清 / `onDestroy` / 自动锁定，第三条由 `sawUnlocked`
      守着，**不能一看见 `Locked` 就清**）、Compose 确认页（照 `AutofillSaveFlow.phase`
      摆屏、改动逐条摆开、警告一句不折叠、名称栏**只在新增那一档**、「换一条」走
      `proposeUpdate` 重算、不给按时画成禁用）、落盘走 `VaultSession.addEntry` /
      `updateEntry`（**全工程唯一一处自动填充落盘**，失败停在原地且绝不置 `committed`）；
      `VaultAutofillService.capture()` 末尾补 `handOff()`（**offer 排在 startActivity 前面**，
      拉不起来要自己 `SaveHandoff.clear()`）；`AndroidManifest.xml` 加第三个 `<activity>`。
      **`AutofillSave.kt` / `SavePlan.kt` / `AutofillSaveFlow.kt` / `SaveCapture.kt` /
      `SaveShell.kt` / `SavedFields.kt` / `SaveHandoff.kt` 一行都没改。**
      这一步没有新增测试（两个新文件一个是 Compose 页面、一个是 `android.*` 外壳），
      可测的那一半在 M4-3a / M4-3b-1 就测完了。**M4-3b 收尾，「存进保险库」这条路整条打通** ✅
- [x] **M4-4a 开关与交代**：设置页里那一行与独立的自动填充页、四档可用性
      （没有这套东西 / 没设过 / 设的是别人 / 就是本应用，决策(210)）、
      「去系统里设为默认」与「去系统里换掉」两个跳转、
      **应用没办法把自己撤下来这件事要说出来**（决策(209)）、
      三条底线 + 「它为什么有时候不出现」七条（把 `NoEvidence` / `UntrustedHost` /
      新密码框留空那几档说清楚，决策(211)）、关于页补一个**指路牌而不是副本**（决策(212)）、
      那一行永远不变色（决策(213)）。17 个用例纯 JVM 可跑 ✅
      注：`AutofillService` 那一侧一个字没改，Manifest / 依赖 / 图标 / 组件都没动。
- [x] **M4-4b 内联建议**（IME 里那一条，Android 11+）：`InlinePlan`（摆几格 / 摆哪几条 /
      用第几份规格，**无一行 `android.*`**，31 条用例）+ `InlineViews`（`InlineSuggestionsRequest`
      → `Ask`、`Slot` → `InlinePresentation`，碰平台的那一整侧）+ `AutofillRow.Chip`
      （**同一道洗**，内联上限更短）；`AutofillResponses` 那三处各挂一份内联版，
      锁着那一条走 API 30 的四参 `setAuthentication`；
      `res/xml/autofill_service.xml` 补上 `supportsInlineSuggestions`（**总闸**）。
      **内联那一条同样不许显示密码**——`Chip` 收的是 `Item`，那是类型保证（决策(218)）；
      摆不齐就整份退回浮层、而浮层那一份永远都在（决策(214)，「不能两条都不出」的落点）；
      搜索那一格永远留着（决策(215)），兄弟域那几条不进内联（决策(216)），
      解锁跳板交回去那份不带内联（决策(217)）。
      **这一步动了依赖**（`androidx.autofill:autofill:1.1.0`，M4 唯一一个，不带权限）✅
      **M4-4b 收尾即 M4 全部收尾——填、存、开关、内联四条路到此全通。**
- [x] **M5-1a 恢复内核**：认文件（不认扩展名，只认文件头，决策㉒）、文件头事实、
      提交拦截（**恢复绝不覆盖已有的库**，决策(135)）、八条失败文案；
      执行器：读 → 认 → 验口令（只派生一次）→ **原样落盘**（决策(136)）→ 会话接管
      → 记一笔 `lastBackupAt`（决策(137)）✅
- [x] **M5-1b 恢复页与入口**：`RestoreScreen`（选文件 → 事实卡 → 会怎样 → 主密码 → 恢复）、
      SAF 选文件的 `ImportSource` 实现（`ACTION_OPEN_DOCUMENT`，**一个权限都没加**）、
      欢迎页那个「从备份恢复」接上真页面、`Route.RESTORE` 只注册到引导图。
      **决策(132) 那条「M5 之前不出内测包」到此解除**，全工程再没有占位屏 ✅
- [x] **M5-2a-1 CSV 解析内核**：字节 → 文本（BOM / 严格 UTF-8 / 严格 GBK / 二进制拦截，决策(145)）、
      文本 → 表（RFC 4180 状态机、分隔符猜测、参差行、六类记账、六类失败文案）；
      单格超长是唯一的硬失败（决策(146)）；全链路 `toString` 不吐内容（决策(144)）✅
- [x] **M5-2a-2① 列名映射内核**：九种列角色、七家真实表头（1Password / Bitwarden / Chrome /
      Firefox / LastPass / KeePass / 中文列名）、列名归一、精确表 + 受限宽松匹配 + **排除表**
      （决策(147)）、格式识别、表头行认定（决策(148)）、重复角色、手工改 `withRole`、两条拦截 ✅
- [x] **M5-2a-2② 行 → 条目与判重**：行 → `VaultEntry`（复用 `EntryForm.domainLines` /
      `newEntry`，别再写第二份）、`Role.Kind` 认出安全笔记行、三档判重
      （同名同账号 / 同网站同账号 / 只同名）、源文件内判重、
      「跳过 / 覆盖 / 都留着」三种处置、**覆盖时空的不覆盖**（决策(150)）；
      没有密码的行照样导入（决策(149)）✅
- [x] **M5-2b-1 批量落盘入口 · 导入控制器内核**：`VaultSession.importEntries`
      （一次 mutate = 一次加密一次写盘，要么全进要么全不进，决策(151)）、
      `ImportController`（选文件 → 解码 → 解析 → 认列 → 预览 → 落盘的整条状态机、
      落盘前以当下的库重算（决策(152)）、改映射与改处置两条路（决策(153)）、
      失败按「下一步做什么」分三种（决策(154)））✅
      注：文件来源**复用恢复页那个 `ImportSource`**，不写第二个接口——
      那个接口只有 `read()` 没有 `write()`，于是「你那份 CSV 我们一个字都没改」
      在这一页也是靠类型系统成立的。
- [x] **M5-2b-2 CSV 导入页**：设置页「备份」那一格里的入口、选文件（复用恢复页那个
      `SafImportSource`，一个权限都没加）、列映射那一屏（逐列显示列名 + 认成了什么 + 点开改 +
      恢复自动识别）、预览（三个数字 + 跳过按理由归并 + 撞上的按行号列出 + 三种处置各带说明）、
      结果页 + **两次强提示删源文件**（决策(155)）；**全程不显示任何一格内容**（决策(156)）；
      不做撤销（决策(157)）✅ **M5-2b 收尾，CSV 导入整条路打通**
- [ ] **M5-3 kdbx 导入**（KeePass 格式）
- [ ] **M5-4 CXF 格式对接**。**刻意不提供明文 CSV 导出**
- [x] **M3.5 指纹那一半**：解锁侧在 M3-2c-2 接通，绑定侧在 M3-6b-1 接通。
      现在不用手动造数据也能走通「绑定 → 杀进程 → 指纹开门」这一整条了 ✅

## 二期（不要提前做）
通行密钥提供方（Credential Manager）、密码健康体检、动态验证码、多设备同步、共享、多密码库。

---

## 构建注意

1. 本工程在**无网络环境**下编写，**未经过实际编译**。首次在 Android Studio 打开时：
   - `gradle/libs.versions.toml` 里的版本号按 2026 年中的稳定版填写，
     若解析失败用 IDE 的版本提示更新即可。
   - Gradle Wrapper 未包含，用 Android Studio 的 *Sync* 或 `gradle wrapper` 生成。
   - 缺 `res/mipmap` 图标资源，先随便放一个或把 Manifest 里的 `android:icon` 注释掉。
2. `argon2kt` 是唯一的原生依赖。拉不到时 App 不会崩，会自动降级到 PBKDF2
   （见 `Argon2idKdf.registerIfAvailable()`），全流程照常跑通。
3. 先跑 `./gradlew :app:testDebugUnitTest` 验证内核——这 1284 个用例不需要设备。
   其中 `CreateVaultControllerTest` 需要 `compose-runtime` 在 JVM 测试类路径上
   （`mutableStateOf` 是纯 Kotlin 实现，正常可跑）。万一你的环境跑不起来，
   删掉这一个文件不影响其余用例。`ExportControllerTest`、`UnlockControllerTest`、`ResetVaultControllerTest` 同理。
   `BiometricPolicyTest` / `PinBufferTest` / `VaultIndexTest` / `SearchHighlightTest` /
   `EntryDetailTest` / `EntryFormTest` / `PasswordGenTest` / `AddFlowTest` /
   `SettingsModelTest` / `QuickUnlockModelTest` / `DeleteVaultModelTest` /
   `ResetVaultModelTest` / `HoldProgressTest` / `CsvTextTest` / `CsvParserTest` / `CsvMappingTest` / `CsvImportTest` /
   `PublicSuffixTest` / `DomainMatchTest` / `AutofillMatchTest` / `FieldRolesTest` /
   `FieldGroupsTest` / `FillPlanTest` / `StructureRulesTest` / `AutofillOfferTest` / `BrowserTrustTest` /
   `AutofillRowTest` / `AutofillPickTest` / `AutofillPickFlowTest` / `AutofillSaveTest` /
   `SavePlanTest` / `AutofillSaveFlowTest` / `AutofillSettingsModelTest` / `InlinePlanTest`
   不依赖 Compose，任何环境都能跑。
3i. M4-4b **是 M4 里唯一动了依赖的一步**：`gradle/libs.versions.toml` 加了
   `androidx.autofill = "1.1.0"`，`app/build.gradle.kts` 多一行 `implementation`。
   只用到 `androidx.autofill.inline.v1.InlineSuggestionUi` 和 `UiVersions` 两个类
   （把两行字装成一个 `Slice`）——那份 Slice 的格式是平台和输入法之间的私下约定，
   手写等于把一份没有文档的二进制布局抄进工程，抄错了不报错、只是那一格画不出来。
   **它不带权限、不联网、不带资源**，拉不到时可以先把 `InlineViews.kt` 整个删掉、
   把 `AutofillResponses` 那三处 `inline` 参数一律传 null，其余功能一个字都不受影响
   （那正是决策(214) 的默认行为）。
   它**动了 `res/xml/autofill_service.xml`**（加 `supportsInlineSuggestions="true"`），
   但**没有动 `AndroidManifest.xml`**，没有加新图标，没有加新组件，
   `uses-permission` 仍然只有 `USE_BIOMETRIC` 一条。
   `InlineViews.Support` 整个挂着 `@RequiresApi(R)`：它字段上那些 Android 11 才有的
   类型在低版本上永远不会被解析，因为 `InlineViews.from` 在 SDK 检查那一句就返回了 null，
   而 null 不触发类加载。**全工程判断内联可用性的地方只有那一句。**
   `InlinePlanTest` 不依赖 Compose、不需要设备、不需要 Argon2，任何环境都能跑。
   **这一轮破例做过一次真的编译与运行**（写作环境里装了 Kotlin 2.1.20）：
   `InlinePlan.kt` + `AutofillRow.kt` + `InlinePlanTest.kt` 在纯 JVM 上跑通，31 条全过；
   碰平台的那三个文件（`InlineViews` / `AutofillResponses` / `VaultAutofillService`）
   仍然没编译过——那需要 Android SDK 和 Compose。
3k. M4-3b-2② 没有动依赖，没有加新图标（确认页上只用到 `Glyph.Plus` / `Refresh` /
   `Chevron` / `Key` / `Shield` / `Lock`，都是现成的），也没有加新组件文件
   （`Banner` / `VaultCard` / `Eyebrow` / `PlainField` / `EmptyState` / `BrassButton` /
   `GhostButton` 全是 M3-1 备好的）。
   它**动了 `AndroidManifest.xml`**（M4 里第二步动它的，第一步是 M4-2a-2②）：
   加了第三个 `<activity>`，属性和另外两个填充页逐条相同。**权限清单一个字没加**，
   仍然只有 `USE_BIOMETRIC` 一条——这一页是我们自己 `startActivity` 拉起来的，
   不需要任何权限。
   它加了两个新文件（`ui/autofill/AutofillSaveScreen.kt` / `AutofillSaveActivity.kt`），
   并改了**一个既有文件**：`VaultAutofillService.kt` 的 `capture()` 末尾补了一行
   `handOff(captured.context)` 加一个新的私有方法，另外改掉两段过时注释。
   `AutofillSaveActivity` 复用了 `UnlockHost`（`internal`，同 `AutofillPickActivity`，
   理由见那个函数头上）和 `AndroidHostTrust`。
   `AutofillSaveScreen` 用到 `BackHandler`（`activity-compose`，M0 就在了）和
   `imePadding`（名称栏要给键盘让位，`adjustResize` 在清单里配好了）。
   **一处刻意没有复用**：拒绝那一屏另写了 `AutofillSaveRefusalScreen`，
   没有用挑选页那个 `AutofillRefusalScreen`——理由写在它头上（标题写死了）。
   落盘那一下是**同步调用** `session.addEntry` / `updateEntry`，没有开协程，
   和 `AddEntryScreen` 最后一步一样：那一步只做一次 AES-GCM 加密和一次原子写盘，
   不跑 KDF（改主密码那一路才要，所以那一路是在 `Dispatchers.Default` 上跑的）。
   **`AutofillSaveActivity` 里那个字段刻意不叫 `context`**：它是一个 `Activity`，
   本身就是 `Context`，两个名字撞在一起之后，某天有人写 `Intent(context, ...)`
   会得到一条完全说不通的错误，或者更糟——写成了 `this` 而以为是那一份提案。
3l1. M4-3b-1 没有动依赖，没有动 `AndroidManifest.xml`，没有加新图标，没有加新组件，
   没有动任何一个界面文件，**也没有改任何一个既有文件的任何一行**——它只加了
   两个新文件（`ui/autofill/SavePlan.kt` / `AutofillSaveFlow.kt`）和两个新测试文件。
   两个新文件里**一行 `android.*` 都没有**，也没有一行 Compose。
   `AutofillSaveFlow` 引用了 `VaultSession.State`（同 `AutofillPickFlow`）、
   `AutofillOffer.labelOf` 和 `AutofillRow.clean` 两个 `internal` 成员
   （同包，刻意复用而不是抄一份，理由见那两个函数头上）。
   `SavePlan` 用到 `FieldGroups.split` / `FillPlan.of`，但**刻意没有用
   `FillPlan.pick`**——理由写在 `pickForSave` 头上，`SavePlanTest` 里
   「只有一个新密码框的改密码页照样挂」那一条钉着它。
   `SavePlan.Watch` / `Info` / `Decision` 的构造器都是 `internal`：
   这几样只能经 `SavePlan.decide` / `of` 产出，于是「必填只有一个」
   这类不变量绕不过去（同 `SavedFields.Value` 那一处）。
   两个测试文件都不依赖 Compose、不需要设备、不需要 Argon2，任何环境都能跑。
3l2. M4-3a 没有动依赖，没有动 `AndroidManifest.xml`，没有加新图标，没有加新组件，
   **也没有动任何一个界面文件**。它加了三个新文件（`ui/autofill/` 下的
   `SavedFields.kt` / `AutofillSave.kt` / `SaveHandoff.kt`）和一个新测试文件，
   并改了**一个**既有文件：`AutofillPick.kt` 里 `identify()` 从 `private` 放宽到
   `internal`（一个字的逻辑都没改，理由同 `UnlockHost` 那一处）。
   三个新文件里**一行 `android.*` 都没有**，也没有一行 Compose——
   `SaveHandoff` 虽然是为 `Intent` 而存在的，但它自己只是个带票号的槽。
   `SaveContext` 的构造器是 `public`（薄壳和测试都要造它），
   而 `SavedFields.Value` 的构造器是 `internal`：值只能经 `SavedFields.capture` 进来，
   于是「超长 / 控制字符整格拒收」这一条绕不过去。
   `AutofillSaveTest` 不依赖 Compose、不需要设备、不需要 Argon2，任何环境都能跑。
   注意 `proposeCreate` 的 `trust` 参数**刻意没给默认值**（同 `SettingsScreen.onSecurity`）：
   新建那一侧的 verdict 永远是 `None`，那句「承载这一屏的不是我们认得的浏览器」
   只能靠 `trust` 去问；参数可省略的话，某天那句话会静静地不再出现，而编译器一声不吭。
3m. M4-2b-2 没有动依赖，也没有加新图标（挑选页上只用到 `Glyph.Search` / `Close` /
   `Warn` / `Share` / `Shield` / `Lock`，全是现成的），也没有加新组件文件——
   `VaultScreen` / `VaultCard` / `Banner` / `EmptyState` / `BrassButton` /
   `GhostButton` / `Eyebrow` / `IconSlot` / `HairLine` 都是 M3-1 就备好的。
   它**动了 `AndroidManifest.xml`**：多了一个 `<activity>`（`AutofillPickActivity`），
   属性和 `AutofillUnlockActivity` 逐条相同。**`uses-permission` 一个字没加，
   仍然只有 `USE_BIOMETRIC` 一条**——那一页是 `exported="false"`，
   只由我们自己那个 `PendingIntent` 拉起（同决策(183) 的道理）。
   它加了三个新文件（`ui/autofill/` 下）和一个新测试文件，并改了四个既有文件：
   · `AutofillResponses.datasets` **多了一个 `plan` 参数，刻意没给默认值**
     （理由同 `SettingsScreen.onSecurity`：搜索行要靠 `plan` 才知道覆盖哪几个框，
     参数可省略的话某天会静静地少摆一行，而编译器一声不吭）。
     全工程只有两个调用点（`VaultAutofillService` 和 `AutofillUnlockActivity`），都已改好；
   · `AutofillRow` 多了一个 `forPick`（纯加法）；
   · `AutofillUnlockActivity` 里的 `UnlockHost` 从 `private` 放宽到 `internal`
     （挑选页要摆同样那两屏，抄一份过去迟早两处行为不一样）；
   · `VaultAutofillService` 只改了一行（补 `plan`）。
   **`AutofillPick.kt` 一行都没改**，如 M4-2b-1 末尾承诺的那样。
   它用到 `BackHandler`（`activity-compose`，M0 就在了）、`imePadding`、
   `rememberScrollState` + `verticalScroll`，以及平台侧的
   `Dataset.setAuthentication` / `PendingIntent.FLAG_MUTABLE`（同解锁跳板）。
   `AutofillPickFlow` 里没有一行 `android.*`、没有一行 Compose，也没有 `mutableStateOf`——
   `AutofillPickFlowTest` 因此**不需要 `compose-runtime`**，任何环境都能跑，
   也不需要设备、不需要 Argon2、不碰文件系统。它跨包引用了
   `core.session.VaultSession.State`（同 `AutofillOfferTest` 已经在做的事）。
   **本轮没有实际编译**（写作环境里没有 Kotlin 编译器，网络白名单里也没有 Maven），
   只做了括号配平和逐项 API 存在性核对——这一点和 M4-2b-1 不同，
   那一步是在临时 JVM 沙箱里真跑过的。首次 Sync 时若有报错，最可能的三处是：
   `Dataset.Builder(RemoteViews)` 的弃用警告（已 `@Suppress`）、
   `setValue(id, null as AutofillValue?)` 的可空性、以及新页面里某个组件的具名参数。

3n. M4-2b-1 没有动依赖，没有动 `AndroidManifest.xml`，没有加新图标，没有加新组件，
   界面一个像素不变。它加了一个新文件（`ui/autofill/AutofillPick.kt`）和一个新测试文件，
   并改了两个既有文件，**两处都是纯加法，一条既有调用和一条既有用例都没动**：
   · `AutofillOffer.labelOf` 从 `private` 改成 `internal`（同一条「名称 → 退回账号 →
     `NO_NAME`」的规则要被两页共用，抄一份过去迟早两处不一样）；
   · `AutofillMatch.suggest` 多了一个**带默认值**的 `limit`
     （`MAX_SUGGESTIONS = 8` 是填充条的上限，挑选页是全屏）。
   `AutofillPick` 里没有一行 `android.*`、没有一行 Compose，也没有 `mutableStateOf`——
   `AutofillPickTest` 因此**不需要 `compose-runtime`**，任何环境都能跑，
   也不需要设备、不需要 Argon2、不碰文件系统。
   它跨包引用了 `ui.list.VaultIndex`（搜索和归一，同 `DomainMatch` / `AutofillMatch`
   已经在做的事，决策㉝：不许各写各的）。
   本轮在无网络环境下**用一个临时 JVM 沙箱实际编译并跑过这 71 条**（全绿），
   另外做了四处变异验证用例真的钉得住东西：把「只写主表单那一组」改成
   `plan.forms.flatMap`（那一条立刻从「写 2 格」变成「写 4 格」而红）、
   应用名不洗、去掉第四句警告、搜索按归属重排——四处各自被对应的用例抓住。
   **但整个 Android 工程仍未经过 Gradle 编译**（同「构建注意 1」）。
3o. M4-2a-2② 是 M4 里第一步**动了 `AndroidManifest.xml`** 的：加了一个 `<service>`
   和一个 `<activity>`。**`<uses-permission>` 一条没加**（决策(183)），
   关于页那份清单仍然只有 `USE_BIOMETRIC`，`SettingsModelTest` 里那两条老用例照旧绿。
   它也是全工程第一次出现 `res/layout/`（`RemoteViews` 只认老式布局，
   界面全是 Compose 这件事在填充条上不适用）和第一次出现 `res/xml/autofill_service.xml`。
   没有动依赖，没有加新图标，没有加新组件，`Glyphs.kt` 一个没加没减。
   改了三个既有文件：`AndroidManifest.xml`、`SettingsModel.kt`（**纯加法**，
   新增 `AUTOFILL_NOTE`，`PERMISSIONS` 一个字没动）、`AboutScreen.kt`（多一格）。
   用到的平台 API 都在 API 26 上就有：`AutofillService` / `FillResponse` / `Dataset` /
   `AutofillManager.EXTRA_ASSIST_STRUCTURE` / `EXTRA_AUTHENTICATION_RESULT`。
   三处 `Build.VERSION` 分叉：`PendingIntent.FLAG_MUTABLE`（31+，**必须**，
   系统要往那个 Intent 里塞结构；写成 IMMUTABLE 的话跳板页拿到空 Intent，
   表现是「指纹过了却什么都没填上」而且不报错）、`Intent.getParcelableExtra` 的
   带类型重载（33+）、`Dataset.Builder(RemoteViews)` 在 33 上被 `Presentations` 取代
   （那条新路要 minSdk 33，这一条在 26..36 上行为一样，带着 `@Suppress("DEPRECATION")`）。
   `AutofillUnlockActivity` 复用的是 `QuickUnlockScreen` / `UnlockMasterScreen` /
   `UnlockController`，**没有为它另写一套解锁界面**；它继承 `FragmentActivity`
   的理由同 `MainActivity`（`androidx.biometric` 只接这个）。
   Manifest 里那个 `taskAffinity=""` 不要删：不写的话跳板页会挤进主应用的任务栈，
   用户下次打开应用按返回会退到一个本该不存在的解锁页。
   `AutofillRowTest` 不依赖 Compose，也不需要设备；它造候选走的是
   `AutofillOffer.respond` 那条真路，没有手搓 `Item`（手搓就绕开了上一层的规则）。
   **这一步没有为 `AutofillViews` / `AutofillResponses` / `VaultAutofillService` /
   `AutofillUnlockActivity` 写单测**：四个都是 `android.*` 外壳（`RemoteViews` /
   `FillResponse` / 系统回调 / Activity），同 `SafExportSink` / `AssistShell`。
   可测的那一半在 `AutofillRow` 里，而判断那一半在 M4-2a-1 就测完了——
   这正是当初把 M4-2a 拆成两步的目的。
3p. M4-2a-2① 没有动依赖，没有动 `AndroidManifest.xml`，没有加新图标，没有加新组件，
   **也没有改任何一个既有文件的任何一行**——它加了两个新文件和一个新测试文件。
   `AndroidHostTrust` 里那处 `Build.VERSION` 分叉是必需的：API 28 起用
   `GET_SIGNING_CERTIFICATES` + `signingInfo`，26/27 上只有已弃用的 `GET_SIGNATURES`
   （那个常量在这两个版本上是安全可用的——它出名的那个 Janus 问题针对 v1 签名，
   而 API 26 起安装器要求 v2 签名方案）。**查一个已安装包的签名不需要任何权限**，
   关于页那份清单仍然只有 `USE_BIOMETRIC` 一条。
   `AndroidHostTrust` 的缓存**刻意不持久化**：一份写在磁盘上的「这些应用是可信浏览器」
   清单既是新的用户数据，也是新的攻击面，而它省下的只是几毫秒。
   `BrowserTrust.decide` 有一个 `internal` 的三参重载，只为用例存在
   （靠 Kotlin Gradle 插件把单元测试源集当作 friend module 才看得见，同 `CsvMapping.looksLikeData`）。
   `BrowserTrustTest` 里有两条是**给将来的人准备的守卫**：往 `FINGERPRINTS` 里加条目时，
   包名拼错或摘要写坏会当场变红，而不是悄悄退回「只认包名」。
3q. M4-2a-1 没有动依赖，没有动 `AndroidManifest.xml`，没有加新图标，没有加新组件，
   **也没有改任何一个既有文件的任何一行**——它只加了一个新文件
   （`ui/autofill/AutofillOffer.kt`）和一个新测试文件。
   这个文件叫 `AutofillOffer` 而不是 `FillResponse`，是**故意避开的**：
   M4-2a-2 要 `import android.service.autofill.FillResponse`，
   同名两个类会逼着那个文件写一串 `as` 别名，而那正是最容易看串的地方。
   它 `import` 了 `core.session.VaultSession`（只用那个嵌套的 `State`）——
   不另建一份平行的「库状态」枚举，是怕两份迟早会走样；`VaultSession` 本身
   只依赖 kotlinx-coroutines，纯 JVM 可跑，所以用例不需要设备也不需要 Compose。
   `AutofillOffer.Item` 的构造器是 `internal`（同 `FillPlan.Target` 那一批）：
   手搓一个 `Item` 等于绕过这一层的三条底线。
   用例里那个 `SELF` 常量写的是 `cn.localvault.app`，和 `BuildConfig.APPLICATION_ID` 一致；
   哪天真改了包名，这一条会红——那是有意的。
3r. M4-1b-3 没有动依赖，没有动 `AndroidManifest.xml`，没有加新图标，没有加新组件，
   **也没有改任何一个既有文件的任何一行**——它只加了两个新文件（`ui/autofill/`）
   和一个新测试文件。
   `AssistShell.kt` 是全工程第一个 `import android.app.assist` / `android.view.autofill`
   的文件（第二个会是 M4-2a 的 `AutofillService`）。它里面有两处**按 SDK 版本分叉**：
   `ViewNode.getImportantForAutofill()` 和 `getWebScheme()` 都是 API 28 才有的，
   而这个工程 minSdk = 26。低版本上直接调不会编译失败，会在真机上抛 `NoSuchMethodError`——
   一个 Error，顺着 `runCatching` 变成一次静悄悄的「这次不填」，
   然后 26/27 两个版本上从此再也没有填充条。**别把那两个 `Build.VERSION.SDK_INT` 判断删掉。**
   `StructureRules.Picked` 的构造器是 `internal`（同 `FieldGroups.Field` 那一批，
   靠 Kotlin Gradle 插件把单元测试源集当作 friend module 才看得见）；
   `Inherited` 的构造器是公开的，因为用例要直接造一个「祖先说了别填」的上下文。
   `StructureRulesTest` 不依赖 Compose，也不需要设备；它里面有一条会搭
   一百来层深的假树（`MAX_DEPTH + 5`），走的是显式栈，不会爆栈。
3s. M4-1b-2 没有动依赖，没有动 `AndroidManifest.xml`，没有加新图标，没有加新组件。
   它加了两个新文件（`ui/autofill/FieldGroups.kt` + `FillPlan.kt`）和两个新测试文件，
   并改了**一个既有文件**：`ui/autofill/AutofillTarget.kt` 里 `Origin.App` / `Origin.Web`
   各补了一个手写的 `toString`。`data class` 的 `equals` / `hashCode` 照旧生成，
   两个既有测试里那些 `assertEquals(Origin.App(...), ...)` 一条都不用改。
   `FieldGroups.Field` / `Group` 和 `FillPlan.Target` / `Form` / `Plan` / `Write`
   的构造器都是 `internal`（靠 Kotlin Gradle 插件把单元测试源集当作 friend module
   才看得见，同 `CsvMapping.looksLikeData`）——它们只该由 `split` / `of` / `writes` 造出来，
   手搓一个 `Form` 等于绕过这一层的全部判断。
   两个测试都不依赖 Compose、不需要设备；它们**从 `RawField` 一路走到 `Plan`**
   （中间真的调 `FieldRoles.classify`），所以角色识别那一层若哪天改坏了，
   这两个文件也会跟着红——那是有意的，切组和识别之间的接缝正是最容易漏测的地方。
3t. M4-1b-1 没有动依赖，没有动 `AndroidManifest.xml`，没有加新图标，没有加新组件，
   **也没有改任何一个既有文件的任何一行**——它只加了两个新文件（`ui/autofill/`）
   和一个新测试文件。测试不依赖 Compose，也不需要设备，任何环境都能跑。
   注意 `AndroidInput` 里那几个位值是**从安卓平台抄过来的**（决策(169)），
   如果哪天真机上某一类框忽然认不出来了，先回来核对这几个常量，
   `FieldRolesTest` 里有一条专门钉它们。
   `FieldRoles` 的表同样写成空白分隔的文本再在类加载时切开（同 `PublicSuffix`）。
   往正向表里加词的时候记得连负面表一起想一遍：这一步就是因为
   「地址」进了排除表，把「邮箱地址」这种最常见的账号框挡掉了（决策(168)）。
3u. M4-1a 没有动依赖，**没有动 `AndroidManifest.xml`**（自动填充要的
   `BIND_AUTOFILL_SERVICE` 是 M4-2a 那一步的事，这一步还没有服务组件；
   关于页那份权限清单眼下仍然只有 `USE_BIOMETRIC` 一条，**M4-2a 会打破它**，
   到那时关于页必须跟着改，不能让它继续说「只有一条」），
   没有加新图标，也没有加新组件。它加了四个新文件（`ui/autofill/`）和三个新测试文件，
   并改了**一个既有文件**：`ui/list/VaultIndex.kt` 多了一个公开的 `NAME_ORDER`
   （既有私有 `BY_NAME` 的读取器）。这是纯加法，没有改动任何一行既有代码，
   `VaultIndexTest` 一条都不用改。
   包放在 `ui/autofill/` 而不是 `core/autofill/`，是想过的：这个内核必须复用
   `VaultIndex.normalizeDomain`（决策㉝ 的硬要求），而 `VaultIndex` 在 `ui.list`，
   放进 `core` 就成了 core → ui 的反向依赖。这个工程里的 `ui/` 本来就是**按功能分的**
   （`ui/importer/CsvParser.kt` 同样是一个没有一行 Compose 的纯内核），
   所以 M4-2 的 `AutofillService` 也落在这个包里。
   三个测试文件都不依赖 Compose，也不需要设备，任何环境都能跑；
   `PublicSuffixTest` 里有两条用到 `java.net.IDN`（JDK 自带，安卓上 API 9 起就有）。
   注意 `PublicSuffix` 里的几张表是写成一段空白分隔的文本再在类加载时切开的，
   不是几百个字符串字面量——好读好改，往里加一条也不会因为漏个逗号而编译不过。
3w. M5-2a-1 没有动依赖，没有动 `AndroidManifest.xml`，没有加新图标，没有加新组件，
   **也没有改任何一个既有文件的任何一行**——它只加了两个新文件（`ui/importer/`）
   和两个新测试文件。两个测试都不依赖 Compose，也不需要设备，任何环境都能跑。
3v. M5-2b-2 是纯界面步骤，**没有加任何测试**——它的逻辑在 M5-2b-1 那 32 条里已经钉完了，
   这一步只做排版和接线。改了三个既有文件：`ui/nav/Routes.kt`（加一条路由常量）、
   `ui/nav/VaultNavHost.kt`（`UnlockedGraph` 里加 `rememberCoroutineScope`、
   一个图级的 `ImportController`、一条 `composable` 注册、`SettingsScreen` 多传一个回调）、
   `ui/settings/SettingsScreen.kt`（多一个 `onImport` 参数和一行）。
   `SettingsScreen` 的签名变了，调用点只有 `VaultNavHost` 一处，已经一起改了。
   没有动依赖、没有动 `AndroidManifest.xml`、**没有加任何权限**——
   关于页那份权限清单仍然只有 `USE_BIOMETRIC` 一条。
   注意 `ImportScreen.kt` 里那个 `arrayOf("*/*")` 字面量：它里面带着一个块注释的结束标记，
   所以那一段说明写在它上面的 KDoc 里而不是行内（同 `RestoreScreen` 的 `OPEN_ANY`）。
3z. M5-2b-1 是 M5 里**第一个改了既有文件的步骤**：`core/session/VaultSession.kt`
   加了一个方法 `importEntries`，既有的任何一行都没有动，也没有动依赖、
   没有动 `AndroidManifest.xml`、没有加图标。新文件两个
   （`ui/importer/ImportController.kt` + `ImportControllerTest.kt`）。
   `ImportControllerTest` 用到 `mutableStateOf`，所以它和 `RestoreControllerTest`
   一样需要 compose-runtime 在 JVM 测试类路径上；它跑真的库文件（`TemporaryFolder`
   + 真的加解密），KDF 用的是廉价参数，整份跑下来是秒级的。
   其中「五百条一次导入」那一条会做一次 500 条的真实加密写盘，比其余用例慢一点。
3y. M5-2a-2② 同样只加了两个新文件（`ui/importer/CsvImport.kt` + `CsvImportTest.kt`），
   既有文件一个字节没动。它 import 了 `EntryForm` 和 `VaultIndex`，两者都是纯 JVM 对象，
   所以测试仍然不依赖 Compose、不需要设备。
3x. M5-2a-2① 同样没有动依赖、没有动 `AndroidManifest.xml`、**也没有改任何一个既有文件的任何一行**，
   只加了 `ui/importer/CsvMapping.kt` 和 `CsvMappingTest.kt`。不依赖 Compose，不需要设备。
   注意 `CsvMapping.looksLikeData` 是 `internal`，靠 Kotlin Gradle 插件把单元测试源集
   当作 friend module 才看得见——正常配置下没问题。
   包名是 `ui.importer` 而不是 `ui.import`：`import` 是 Kotlin 关键字，
   写成 `ui.import` 每一处引用都得加反引号。
   `CsvText` 用到 `java.nio.charset`（`Charset` / `CharsetDecoder` / `CodingErrorAction`），
   都是 JDK 自带，安卓上从 API 1 就有；`Charset.forName("GBK")` 在安卓上一直可用，
   但仍然包了一层 `charsetOrNull`，拉不到时那条路自动让位给「不是文本文件」。
   注意 `CsvTextTest` 里有两条会各自分配 16 MiB 的用例（上限的两侧），
   解码时峰值内存约 64 MB——默认的单测 JVM 跑得动，
   万一你的 CI 把堆压得很小，看到的会是那两条 OOM 而不是断言失败。
   `CsvParser.MAX_ROWS` / `MAX_COLUMNS` / `MAX_CELL_CHARS` 三个上限都有「刚好到线」
   和「超一个」两条用例夹着，将来调数值时不用担心边界写反。

3y. M5-1b 没有动依赖，**没有动 `AndroidManifest.xml`**（`ACTION_OPEN_DOCUMENT`
   和导出侧的 `CreateDocument` 一样，一个权限都不要——关于页那份权限清单
   仍然只有 `USE_BIOMETRIC` 一条，「导出 + 恢复」这个换机闭环没给它添过一行），
   没有加新图标（「会怎样」那四条用的是现成的 `Glyph.Check`），也没有加新组件。
   它用到 `rememberLauncherForActivityResult` + `ActivityResultContracts.OpenDocument`
   （`activity-compose`，同导出页）、`BackHandler` 和 `imePadding`。
   它**没有改任何一个既有函数的签名**——`WelcomeScreen.onRestore` 从 M3-2a 起
   就一直在那儿，这一步只是把它从 `Stub` 改接到真页面。
   `VaultNavHost.kt` 里那个私有的 `Stub` 函数**已经删掉**，连同十二个只为它存在的
   import（`Column` / `Arrangement` / `Text` / `VaultScreen` / `DefaultSeal` / `Glyph` 等）。
   如果你的编辑器提示某处引用不到 `Stub`，那是预期内的——它已经没有调用点了。
   注意 `SafImportSource` 对读取设了 64 MiB 上限（先问 `SIZE` 列，再在流上兜底）：
   文件选择器里连一部两个 G 的电影都点得到，不设上限那一下不是「恢复失败」，
   是 `OutOfMemoryError`。超限报的是第七条「文件读不下来」，
   对一个错点了电影的人来说不够准；真要为它加第九条文案，位置在
   `RestoreModel.Failure` 里（M5-2 的 CSV 导入多半也要一条同类的），
   不该在这一步去动一个已经有 37 条用例钉着的内核。
   **这一步没有新增测试**：新加的两个文件一个是 `android.*` 外壳
   （`ContentResolver` / `Uri`，纯 JVM 测不了，同 `SafExportSink`），
   一个是 Compose 页面。这一页的可测部分在 M5-1a 就已经全部测完了
   （`RestoreModelTest` 37 条 + `RestoreControllerTest` 19 条），
   这也正是当初把内核和页面拆成两步的目的。

3z. M5-1a 没有动依赖，没有动 `AndroidManifest.xml`，没有加新图标，没有加新组件，
   也没有动任何一个界面文件。它加了两个新文件（`ui/restore/`）和两个新测试文件，
   并改了两个既有的核心文件：
   · `VaultFile.kt` —— `VaultFormatException` 加了 `open`，多出两个子类。
     **这是纯粹的加法**：抛出的对象仍然 `is VaultFormatException`，
     既有的 `catch (e: VaultFormatException)` 一处都不用改，`VaultFileTest` 也不用改。
   · `VaultRepository.kt` —— `restoreFrom` 换成 `restoreAndOpen`。旧方法**没有任何调用点**
     （M1 写下来就一直空着），如果你的编辑器提示引用不到它，那是预期内的。
   `RestoreController` 用到 `mutableStateOf`，所以 `RestoreControllerTest` 需要
   `compose-runtime` 在 JVM 测试类路径上（同 `DeleteVaultControllerTest`）；
   `RestoreModelTest` 不依赖 Compose，任何环境都能跑。
   这两个测试文件都不需要设备，也不需要 Argon2（用廉价 PBKDF2 参数）。

3a. M3-6c-3b 没有动依赖，没有动 `AndroidManifest.xml`，没有加新图标
   （清空页上只用到 `Glyph.Close` 和 `Glyph.Check`，都是现成的）。
   它加了两个新文件到 `ui/components/`：`HoldProgress.kt`（纯逻辑，没有一行
   `android.*` 和 Compose）和 `HoldButton.kt`。后者用到
   `androidx.compose.foundation.gestures` 里的 `awaitEachGesture` /
   `awaitFirstDown` / `waitForUpOrCancellation`，以及
   `androidx.compose.runtime.withFrameMillis`——都在 compose-bom 里，不新增依赖。
   它改了**一个既有函数的签名，而且刻意没给默认值**：`UnlockMasterScreen`
   多了 `onReset`，理由同 `SettingsScreen.onSecurity`（一个新长出来的、
   能点得动的出口，参数可省略的话某天会变成点了没反应的死行，而编译器一声不吭）。
   `UnlockMasterScreen` 全工程只有一个调用点（`VaultNavHost` 里），已经改好。
   `ResetVaultScreen` 有一处跨包引用 `ui.settings.QuickUnlockRemnants`，
   和 `ResetVaultController` 里那处是同一个理由（两页要清的残留是同一堆）。
   `HoldProgressTest` 不依赖 Compose，任何环境都能跑。
   注意 `PlainField` **没有** `enabled` 参数，这一页也没有为它去加一个——
   清空那两步只有几十毫秒，期间 `canArm` 已经是 false，改字改不出任何后果。

3b. M3-6c-3a 没有动依赖，没有动 `AndroidManifest.xml`，没有加新图标，没有加新组件，
   **也没有改任何一个既有文件的任何一行**——它只加了四个新文件。
   两处跨包引用是有意的，不是将就：`ResetVaultController` 用
   `ui.settings.VaultRemnants`（M3-6c-2 留下的接口，两页要清的残留是同一堆，
   为它再定义一个同形状的接口只会多出一个迟早会走样的副本），
   `ResetVaultModel.ERASURE_NOTE` 引用 `ui.settings.DeleteVaultModel.ERASURE_NOTE`
   （决策(131)，`const val` 之间的引用是编译期常量，不产生第二个字符串）。
   将来若觉得这两样长在 `ui.settings` 里别扭，搬去 `core` 是对的，
   但那会动三个已经测过的文件，不该混在这一步里做。
   `ResetVaultController` 用到 `mutableStateOf`，所以 `ResetVaultControllerTest`
   和 `DeleteVaultControllerTest` 一样需要 `compose-runtime` 在 JVM 测试类路径上；
   `ResetVaultModelTest` 不需要，任何环境都能跑。
4. M3-6c-2 没有动依赖，没有动 `AndroidManifest.xml`，没有加新图标
   （`Glyph.Close` / `Warn` / `Trash` 都是现成的），也没有加新组件文件。
   它用到 `BackHandler`、`imePadding`、`rememberCoroutineScope` + `Dispatchers.Default`
   （核对主密码要跑一遍 KDF，不能在主线程上）。
   它改了**一个既有函数的签名，而且刻意没给默认值**：`SettingsScreen` 多了 `onDelete`，
   理由同 M3-6b-1 的 `onSecurity`。
   `VaultSession` 多了一个方法 `onVaultDeleted()`——它是**唯一**能让相位从
   `Unlocked` 走到 `NoVault` 的路径，`lock()` 到不了那儿（终点是 `Locked`）。
   新增的 `VaultRemnants` 是个接口（同 `UnlockGuard` 的用意），线上实现
   `QuickUnlockRemnants` 单独一个文件，于是 `DeleteVaultController.kt` 和
   `DeleteVaultModel.kt` 里一行 Keystore / SharedPreferences 都不会出现。
   `DeleteVaultControllerTest` 跑的是真的库文件（同 `ChangeMasterControllerTest`），
   不需要设备也不需要 Argon2；其中「文件删不掉」那一条靠把临时目录设成只读来造，
   **在 root 环境下会自动 `Assume` 跳过**而不是给出假的通过——
   如果你的 CI 以 root 跑单测，看到那一条被跳过是预期内的。
4b. M3-6c-1 没有动依赖，没有动 `AndroidManifest.xml`，也没有加新图标，也没有加新组件文件
   （`MatchHint` 是从 `CreateMasterScreen` 里搬进 `Fields.kt` 的，不是新写的）。
   它用到 `BackHandler`、`imePadding`（`adjustResize` 在 M3-2a 就配好了）、
   `rememberCoroutineScope` + `Dispatchers.Default`（两次 KDF 派生都不能在主线程上跑）。
   它改了**四个既有函数/属性的签名**，其中一个**刻意没给默认值**：
   · `SettingsScreen` 多了 `onChangeMaster`（无默认值，理由同 `onSecurity`）；
   · `VaultRepository.changeMasterPassword` 的返回值从 `Unit` 变成 `VaultFile.Header`，
     并多了一个收裸 `ByteArray` 的重载（原来那个 `SecureBytes` 版本原样保留，转调新的）；
   · `VaultSession` 的 `headerKdfParams` 现在是 `_headerKdfParams.value` 的读取器，
     另外多了 `headerKdfParamsFlow: StateFlow`。**私有字段 `header` 已经删掉**——
     如果你的编辑器提示某处引用不到它，那是预期内的（它只被封条用过，现在只留档位）；
   · `PasswordStrength` 多了 `MASTER_MIN_LENGTH`。
   `VaultMeta` 多了一个带默认值的字段 `masterChangedAt`，
   `Json { ignoreUnknownKeys = true; encodeDefaults = false }` 两个方向都兼容：
   老库读进来是 0，新库在老版本上读也不会炸。**`.lvault` 格式版本号不变。**
   `ChangeMasterControllerTest` 跑的是真的库文件，需要 `VaultStorage` 能在
   `TemporaryFolder` 里读写（同 `VaultRepositoryTest`），不需要设备也不需要 Argon2。
   M3-6b-2 没有动依赖，没有动 `AndroidManifest.xml`，也没有加新图标，
   连新组件都没加——`PinBuffer` / `Keypad` / `PinDots` 是 M3-1 就备好的，
   `ToggleRow` 是 M3-5a 备好的，`VaultDialog` 是 M3-2a 备好的。
   它用到 `BackHandler`（`activity-compose`，M0 就在了）和
   `rememberCoroutineScope` + `Dispatchers.Default`（Argon2 派生不能在主线程上跑）。
   它改了**一个既有函数的签名，而且刻意没给默认值**：`SecuritySettingsScreen`
   多了一个 `onSetupPin`，理由同下面 M3-6b-1 那条 `onSecurity`。
   `Route.SETTINGS_PIN` 是**整个工程里第二个带参数的路由**（第一个是条目 id），
   参数是一个布尔值 `change`，Routes.kt 里写清了它凭什么可以进 Bundle。
   M3-6b-1 没有动依赖，没有动 `AndroidManifest.xml`，也没有加新图标。
   它改了**一个既有函数的签名，而且刻意没给默认值**：`SettingsScreen` 多了一个
   `onSecurity`。别处那些新增参数都带默认值（为了「既有调用一个字没改」），
   这一处反着来是有意的——设置主页上多出一行能点的入口，
   如果它的跳转参数可以被省略，某天有人复制一份调用忘了传，
   那一行就会变成点了没反应的死行，而编译器一声不吭。让它编译不过更省事。
   用到 `Settings.ACTION_BIOMETRIC_ENROLL`（API 30+，低版本自动退回
   `ACTION_SECURITY_SETTINGS`）——**这个 Intent 不需要任何权限**，
   关于页那份权限清单仍然只有 `USE_BIOMETRIC` 一条。
   另外用了 `Lifecycle` / `LifecycleEventObserver`（`lifecycle-runtime-ktx`，M0 就在了），
   从 `Context` 里剥出 `ComponentActivity` 来拿 lifecycle，
   刻意**没有**用 `LocalLifecycleOwner`：它在 compose-ui 和 lifecycle-runtime-compose
   两个包里各有一份，前者已被弃用、后者要多加一个依赖，而这里只需要 Activity 的 lifecycle。
   `Route.SETTINGS_SECURITY` 现在已经注册到导航图里了（M3-6a 时刻意没注册）。
   M3-6a 没有动依赖，没有动 `AndroidManifest.xml`，也没有加新图标。
   它用到 `FlowRow`（同 M3-3b/M3-5a，带着 `@OptIn(ExperimentalLayoutApi::class)`）
   和 `BuildConfig.VERSION_NAME`（`buildConfig = true` 在 M0 就打开了）。
   它改了三个既有函数的**签名**，三处都带默认值，既有调用一个字没改：
   `ClipboardBar` 多了 `autoClear`、`SealSlot` 多了 `clipboardAutoClear`、
   `Fmt.bytes` 多了一个 Long 重载。另外给 `VaultRepository` 加了 `fileSizeBytes()`。
   **`Route.SETTINGS_SECURITY` 目前没有注册到导航图里**（决策(96)），
   如果你的编辑器提示这个常量没人用，那是预期内的。
   M3-5b 没有动依赖，没有动 `AndroidManifest.xml`，也没有加新图标。
   它改了两个既有文件的**签名**，两处都带默认值，既有调用一个字没改：
   `PlainField` 多了 `fieldModifier`（挂 `FocusRequester` 用），
   `EntryFormFields` 多了 `visible` 和 `autoFocus`。
   用到 `androidx.compose.ui.focus.FocusRequester` / `focusRequester`（compose-ui 自带）
   和 `BackHandler`（`activity-compose`，M0 就在了）。
   M3-5a 没有动依赖，也没有动 `AndroidManifest.xml`。
   它给 `Glyphs.kt` 加了第 23 个 Canvas 手绘图标 `Glyph.Minus`，
   并新增了一个组件文件 `ui/components/Toggle.kt`（开关 / 步进器 / 预设片，M3-6 要复用）。
   用到 `FlowRow`（同 M3-3b，带着 `@OptIn(ExperimentalLayoutApi::class)`）、
   `animateFloatAsState` 和 `BackHandler`，都是已有依赖里的东西。
   **它删掉了 `Route.GENERATOR`** —— 如果你的编辑器提示某处引用不到它，那是预期内的。
   M3-4b 没有动依赖，也没有动 `AndroidManifest.xml`，也没有加新图标。
   它用到了 `androidx.activity.compose.BackHandler`（`activity-compose` 在 M0 就在了）
   和 `FlowRow`（和 M3-3b 一样带着 `@OptIn(ExperimentalLayoutApi::class)`，
   在已转正的 foundation 版本上只会是一条警告）。
   M3-4a 没有动依赖，也没有动 `AndroidManifest.xml`。
   它给 `Glyphs.kt` 加了第 22 个 Canvas 手绘图标 `Glyph.Pencil`。
   M3-3b 同样没有动依赖，也没有动 `AndroidManifest.xml`。
   `SearchScreen` 用到 `FlowRow`（`androidx.compose.foundation.layout`）画分类快捷键，
   它在 foundation 1.7 之后已经转正；文件里仍带着 `@OptIn(ExperimentalLayoutApi::class)`，
   在已转正的版本上只会是一条警告，不会编译失败。
   M3-3a 同样没有动依赖，也没有动 `AndroidManifest.xml`。
   M3-2c-2 之前也没有动过依赖；那一步加了 `androidx.fragment`（biometric 的传递依赖，
   显式声明而已）。`AndroidManifest.xml` 里**依然只有 `USE_BIOMETRIC` 一条权限**。
   `navigation-compose` / `biometric` 在 M0 就已经写进去了，M3 才开始真正用它们。
5. M2 中依赖 Keystore / BiometricPrompt / SharedPreferences 的部分（`KeystoreKeys`、
   `QuickUnlock`）无法在纯 JVM 单测，必须上真机验证。建议至少测三种机型：
   有 StrongBox 的、只有 TEE 的、以及没录入任何生物特征的。

## 启动图标

一把挂锁：钢制锁梁（`#C2D0D2`）+ 黄铜锁体（`VaultColors.Brass`）+ 挖空的锁孔，
压在一块纯色钢青机身（`#17272D`）上。

四个文件，全是矢量，**没有一张 PNG**：

| 文件 | 作用 |
|---|---|
| `res/mipmap-anydpi-v26/ic_launcher.xml` | 自适应图标定义（背景 / 前景 / 主题层） |
| `res/mipmap-anydpi-v26/ic_launcher_round.xml` | 同上，只为满足 Manifest 的 `roundIcon` |
| `res/drawable/ic_launcher_foreground.xml` | 前景：锁梁 + 锁体 + 锁孔 |
| `res/drawable/ic_launcher_monochrome.xml` | Android 13+ 主题化图标的单色剪影 |
| `res/values/ic_launcher_background.xml` | 背景色 `ic_launcher_background` |

几条不要随手改的：

1. **没有 PNG 兜底是有意的**，不是漏了。minSdk = 26 正好是自适应图标的起始 API，
   `mipmap-hdpi/xhdpi/...` 那一整套位图在这个工程里永远不会被读到。
2. **别放大图形**。整个锁画在 108 坐标系里，最远点（锁体底部圆角）距中心 32.0，
   安全区半径是 33 —— 已经贴着上限了。再大一点，圆形蒙版会先切掉锁体的两个下角，
   锁就变成一个方块。
3. **锁孔是 `fillType="evenOdd"` 挖出来的孔，不是填了个深色**。主题化图标那一层
   靠这个孔才能看出是锁；填实心的话它退化成「圆角方块 + 半圆」。
4. **改了前景层的几何，`ic_launcher_monochrome.xml` 要同步改**，两个文件里的
   `pathData` 目前逐字相同。否则同一个 App 在「主题化图标」开 / 关两种状态下不一样。
5. 上架用的 512×512 PNG 在 `brand/` 下，**刻意不放进 `res/`** ——
   商店素材是上传给控制台的，不该占 APK 体积。

图标是纯资源改动：没有动依赖，没有动 `AndroidManifest.xml`（`android:icon` /
`android:roundIcon` 本来就指着这两个名字），没有动任何 Kotlin 代码，
`Glyphs.kt` 里的 24 个手绘图标一个没加没减。

---

## M4-3c 修复：自动填充「保存」这一条链在真机上从来没走通过

**症状**（用户报告 + 真机复现）：在别人的 App 登录页上提交表单、系统底部弹出
「要在本地保险库中更新密码和用户名吗？」、按下「更新」——**保险库里没有任何变化**，
既不新增也不修改，而且没有任何提示、没有任何崩溃。

**断点不在写库那一段**（`AutofillSaveActivity.commit` / `VaultSession.addEntry` /
`updateEntry` 都是好的），而在链条的倒数第二步：**确认页根本没被拉起来**，
所以 `commit()` 一次都没有被执行过。

### 主因：从 `AutofillService` 自己 `startActivity`

`VaultAutofillService.handOff()` 原来是 `startActivity(AutofillSaveActivity.intent(...))`。
`onSaveRequest` 跑在一个**没有任何可见窗口**的进程里（用户正站在别人的登录页上），
这正是 Android 10（API 29）后台启动限制的正靶心：`AutofillService` 由
`system_server` 绑定，而 system_server 不是「可见应用」，那条豁免不成立。
本工程 `targetSdk = 36`，Android 14/15 又把这条收得更紧。

最要命的是**它不抛异常**，只在 `ActivityTaskManager` 上留一行
`Background activity launch blocked`。于是：

- `runCatching { startActivity(...) }.onFailure { ... }` 一次都不触发；
- 那句 `Log.w("拉不起确认页")` 从来没有打印过——查日志的人会以为这一步是成功的；
- **`SaveHandoff` 槽里那份刚读到的明文密码没人来取**，原地躺满 `TTL_MILLIS`（5 分钟）。
  三条纪律的第 2、3 条都长在确认页上，那一页没起来，一条都不会走。
  也就是说这个 bug 不只是「功能不生效」，它同时是一个明文驻留问题。

**改法**：走平台早就备好的那个重载 `SaveCallback.onSuccess(IntentSender)`（API 28）。
交出去的 `IntentSender` 由系统**从正在被填的那个 Activity 的上下文**启动，
因此压根不是一次后台启动。26/27 上没有这个重载，但那两版也没有这个限制，
旧路 `handOff()` 原样留着，只在 `SDK_INT < P` 时才走。

改完之后**这条链上三个页面没有一个是我们自己拉起来的**：解锁跳板和挑选页走
`setAuthentication` 的 `IntentSender`，确认页走 `onSuccess(IntentSender)`。

### 顺手修掉的第二个「同症状」缺陷

`AutofillSaveActivity` 是 `launchMode="singleTop"` + `taskAffinity=""`，
但**全工程没有一处 `onNewIntent`**。用户上一次被问「要存吗」时按了 Home——
这一页没有 `finish`，就活在一个 `excludeFromRecents`、他再也回不去的任务里。
下一次的票会被 `singleTop` 送到那个老实例上，`onCreate` 不再跑，
**这一次的票没有任何人去取**，表现同样是「按下更新什么都没发生」。

补上 `onNewIntent` + 抽出 `redeem(Intent?)`（`onCreate` 和它共用同一段取票逻辑）。
这里有一处读起来正合适、但**绝对不能写**的东西：`drop()`。它除了清手上那一份，
还会 `SaveHandoff.clear()` 一次——而槽里此刻装着的正是**这一次**的那一份
（服务先 `offer` 才拉页面）。调下去就是自己把刚送来的东西扔了，
然后 `take` 拿到 null、安静走人，又一次「按下更新什么都没发生」。
所以只动 `pending` 这一个字段，槽交给 `take` 去清（纪律第 1 条：取一次就清）。
另外 `committed` / `switchedTo` / `failure` / `sawUnlocked` 四个字段一起归零，
否则新的这一次会继承上一次的 `committed`（当场 `Leaving`，又是一次「什么都没发生」）。

`sawUnlocked` 归零还牵出一处：那个 `LaunchedEffect` 的 key 从 `phase.javaClass`
改成 `phase.javaClass, ctx`。上一次和这一次的相位**类型**可能一个字都没变
（都停在 `Confirming`），只拿类型当 key 的话这个块不会重跑，
于是新的这一次 `sawUnlocked` 全程是 false——库中途被锁上时 `drop()` 不触发，
一份明文留在进程里过夜（纪律第 3 条静默失效）。

### 第三处：`PendingIntent` 的 `FLAG_CANCEL_CURRENT`

`saveSender()` 里那个 flag 不是可选的。`PendingIntent` 按 (requestCode, Intent) 配对复用，
而**配对时 extras 不参与比较**——不加它，第二次保存请求会拿回第一次那个
`PendingIntent`，里面躺着的是**第一张票**，确认页 `take` 一张早就被取过的旧票、
拿到 null、`Leaving`、安静走人。症状和主因一模一样，但只在第二次之后发作。
`FLAG_ONE_SHOT` 是同一件事的另一半（这张票本来就只该被用一次）。

这一处和另外两个 sender 相反，用 `FLAG_IMMUTABLE`：那两个必须可变是因为
**系统要往它们的 `Intent` 里塞 `EXTRA_ASSIST_STRUCTURE`**；
这一处要的东西（一个数字）已经在 extras 里了，没有任何理由留一个可变的出去。
`REQ_SAVE = 0x10CC` 必须和 `REQ_UNLOCK`(0x10CA) / `REQ_PICK`(0x10CB) 都不同。

### 改动清单

| 文件 | 改了什么 |
| --- | --- |
| `ui/autofill/VaultAutofillService.kt` | `onSaveRequest` 改成「收票 → 28+ 交 `onSuccess(IntentSender)` / 26·27 走 `handOff`」；`capture()` 返回 `SaveHandoff.Ticket?`（只放票，不拉页面）；新增 `saveSender()` 和 `REQ_SAVE` |
| `ui/autofill/AutofillSaveActivity.kt` | 抽出 `redeem(Intent?)`；新增 `onNewIntent`；`LaunchedEffect` key 补上 `ctx` |
| `AndroidManifest.xml` | 注释更正（三页现在都由系统代发 `PendingIntent`；`singleTop` 要求代码侧有 `onNewIntent`） |

**没有动**：`SaveHandoff` / `SaveCapture` / `SaveShell` / `SavedFields` / `SavePlan` /
`AutofillSave` / `AutofillSaveFlow` / `AutofillSaveScreen` 一个字都没改，
所以那 75 + 28 + 30 条用例全部原样有效——这次的 bug 从头到尾长在
「没有用例的那一段平台代码」里，而这正是当初 `VaultAutofillService`
文件头那句「在这儿加一个 `if` 之前先停一下」担心的东西的反面：
错的不是多了一个 `if`，是那一段代码本身没有任何观察点。

### 复现与验收

`adb logcat -s AutofillSvc AutofillSave ActivityTaskManager`，按日志停在哪儿分档：

| 日志最后一行 | 断点 |
| --- | --- |
| `收值：Tally(...)` 之后本应用再无日志，`ActivityTaskManager` 有 background activity launch blocked | **就是这次修的主因**（修复后不该再出现） |
| `这一屏现在不值得看着了：XXX` | 提交那一刻页面变了，`SavePlan` 在保存侧判成 `Skipped` |
| `建不出确认页入口：XXX` | `saveSender` 挂了（修复后新增的这一档） |
| `读值：0 格有值` + `Tally(kept=0)` | 承载页走的是兼容模式（部分浏览器），`getAutofillValue()` 拿不到值 → `NothingCaptured` 拒绝页。**这一档会摆出一句实话，不是「什么都没发生」**，所以它和主因在现象上能区分开 |

回归必测四条：① 新站注册后保存（Create）；② 已有条目改密码后保存（Update）；
③ **连着两次保存**（验 `FLAG_CANCEL_CURRENT`）；④ 第一次保存框弹出后按 Home 不理它，
再登录一次触发第二次保存（验 `onNewIntent`）；⑤ 库锁着时保存 → 解锁 → 提交。

### 待办上还欠的一条

`SaveInfo` 那一支目前只挂给非兼容模式的宿主。兼容模式浏览器上
`onSaveRequest` 拿不到 `autofillValue`，走到的是 `NothingCaptured` 那句拒绝——
话说得是实话，但对用户没用。要么在 `SavePlan` 那一层就不给这类宿主挂 `SaveInfo`
（宁可不问），要么把那句话写得更具体。这一条不在本次修复范围内。

### M4-3c 补：那一段的观察点（真机日志复盘）

用户真机日志（vivo / SDK=34 / 宿主 `com.xingin.xhs`）三次保存请求的形态完全一致：

```
AssistShell  结构：2 窗口 → 2 个框，截断=false
AutofillSvc  收值：Tally(watched=2, kept=2, blank=0, tooLong=0, control=0, unreadable=0)
AutofillSvc  已断开
        ← 之后本应用一行日志都没有
```

- `kept=2` → 读值这条链是好的（不是兼容模式、也没把框判错）；
- 没有 `这一屏现在不值得看着了` → `SavePlan.decide` 在保存侧返回的是 `Hang`；
- 没有 `拉不起确认页` → `startActivity` **没抛异常**；
- `system_server` 里没有 `Displayed .../AutofillSaveActivity`，而同一份日志里
  别的每一次 Activity 启动都有那一行 → 确认页从来没起来。

**这份日志暴露的第二个问题：它分不出装的是哪个包。**
`收值 → 已断开` 这个形态在「旧包被 BAL 静默拦下」和「新包成功交给系统」上**一模一样**
（两边成功时都不打字，而旧包失败时也不打字）。这正是这个 bug 能藏住一个版本的原因——
不是逻辑写错了没被发现，是**那一段代码没有任何观察点**。

所以补四行日志，每一行都对应一个原来分不出来的岔口，且都只有档名（决策(144)）：

| 位置 | 那一行 | 它区分的是 |
| --- | --- | --- |
| `VaultAutofillService.onSaveRequest` | `确认页：交给系统拉（onSuccess(IntentSender)）` | 新包 vs 旧包；以及 28+ 分支有没有走到 |
| 同上（26/27 分支） | `确认页：自己拉（SDK<28）` | 版本分支选错 |
| `AutofillSaveActivity.redeem` | `确认页起来了，交接单已兑` | 「页面没起来」vs「起来了但相位不对」 |
| `AutofillSaveActivity` 相位 effect | `相位：Confirming(Update)` 等 | 起来了之后卡在哪一档（`Unlocking` / `Refused` / 当场 `Leaving`） |
| `AutofillSaveActivity.onNewIntent` | `确认页复用了上一次的实例（singleTop）` | 票走的是不是 `onCreate` 之外的路 |

改完之后一次成功的保存在 logcat 上是这样一串，**中间少哪一行就是断在哪儿**：

```
AutofillSvc   收值：Tally(watched=2, kept=2, ...)
AutofillSvc   确认页：交给系统拉（onSuccess(IntentSender)）
ActivityTaskManager  Displayed cn.localvault.app.debug/cn.localvault.app.ui.autofill.AutofillSaveActivity
AutofillSave  确认页起来了，交接单已兑
AutofillSave  相位：Unlocking          ← 库锁着时才有这一档
AutofillSave  相位：Confirming(Update)
AutofillSave  已存：Update
```

**OEM 那一层还欠一次验证**：vivo / OPPO / 小米都有一项独立于 AOSP 的「后台弹出界面」
开关（设置 → 应用管理 → 本应用 → 权限）。走 `onSuccess(IntentSender)` 之后这一次启动是
**系统代宿主 Activity 发起的**，按理不该受它管；但这一条只能在真机上确认，
所以它留在待办上，不算已验证。

### M4-3c 再复盘：`onSuccess(IntentSender)` 也被拦了，问题在「特权链」

第二份真机日志（新包）：

```
AutofillSave  读值：2 格有值
AutofillSvc   收值：Tally(watched=2, kept=2, ...)
AutofillSvc   确认页：交给系统拉（onSuccess(IntentSender)）
AutofillSvc   已断开
        ← 没有 Displayed .../AutofillSaveActivity，没有「确认页起来了」，没有「相位：…」
```

用户确认：全程没有自动锁定；这一次也没走填充，是手打的密码。所以不是相位、不是读值、
不是 `SavePlan`——**我们这一侧从解析到交出 `IntentSender` 全对，卡在系统那一步。**

**为什么交给系统也不行。** 系统拿到 `IntentSender` 之后不是自己启动，而是转交给
被填的那个应用：`AutofillManager.AutofillManagerClient.startIntentSender` 里那一句
`afm.mContext.startIntentSender(intentSender, null, 0, 0, 0)`——最后三个 0 的位置
本该是 `ActivityOptions`。而 BAL 现在判的是一条**特权链**：只有「创建者授权了、
且创建者当下满足某条 BAL 豁免」或「发送者贡献了、且发送者当下满足豁免」至少成立一条
才放行；都不成立就拦掉，**发送方拿不到异常、我们拿不到回调**，只有一行
`Background activity launch blocked!`（`callingPackage` = 创建者 = 我们，
`realCallingPackage` = 发送者 = 那个被填的应用）。

| 角色 | 是谁 | 授权了吗 |
| --- | --- | --- |
| Creator | 我们 | 没有：targetSdk 35+ 的创建者默认不再授出特权；而且那一刻我们是没有可见窗口的后台服务，本来也没有特权可授 |
| Sender | 被填的那个应用（框架代发） | 没有：targetSdk 34+ 的发送者默认不再贡献特权，而框架那一句没有传 `ActivityOptions` |

**已补**：`creatorBalOptions()`——创建 `PendingIntent` 时带上
`setPendingIntentCreatorBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)`（34+）。
它是这条链上**我们唯一够得着的一环**，但**必要不充分**：授权只是「愿不愿意借」，
能不能借还看我们此刻有没有那份特权，而后台服务一条豁免都不占。

**所以这条路上还剩三个方案，是一次产品级取舍，不是实现细节**（未决，等真机日志确认后再选）：

1. **SYSTEM_ALERT_WINDOW**（悬浮窗 / 在其他应用上层显示）。拿到它我们就实打实满足一条
   BAL 豁免，`startActivity` 和 `IntentSender` 两条路当场都通。代价直接顶在产品命门上：
   权限清单本身就是这个 App 的卖点（文件头那个框），多一项「特殊应用权限」要在关于页
   和商店说明里逐字解释清楚，而且它是可选授权——没授的用户仍然回到今天这个死局。
2. **通知中转**。保存请求到达时不拉页面，改为发一条通知（「有一条待存的密码」，
   **通知里一个字的凭据都不出现**），用户点通知再进确认页。系统发出的 `PendingIntent`
   是一条明确的 BAL 豁免，所以这条路一定通。代价：多一项 `POST_NOTIFICATIONS`（13+ 运行时权限，
   比悬浮窗轻），以及多一次点击；好处是库锁着那一档本来就要多走一步，体感差别不大。
3. **收到系统保存框的确认就直接落盘 + 事后可撤销**。理由是：系统那个框
   （「要在本地保险库中更新密码和用户名吗？ [更新]」）**本身已经是一次用户确认**，
   这一按不是静默写入。做法是只在「库已解锁 + 提案唯一且无歧义（`canCommit` 且没有
   `conflictingPasswords`、没有多个候选）」时直接写，然后发一条「已存入：<条目名>，点此查看/撤销」；
   有歧义或库锁着时退回方案 2。代价是放宽了 M4-3b 立下的那条硬规矩
   （「落盘前要让用户看见改的是哪一条、哪几个字段」），要在决策里明确写下这次放宽的边界。

**待办**：三选一之前先拿到那行系统日志确认是 BAL；另外验证一次填充侧的认证入口
（库锁着时点填充条上「先解锁」，看 `AutofillUnlockActivity` 起不起得来）——
那条路 AOSP 走的是 `autofillClientAuthenticate` 而不是 `startIntentSender`，
如果它通而保存不通，就精确证明差别只在那三个 0 上。

### M4-3c 定案：那个 `Intent` 上多了一个 `FLAG_ACTIVITY_NEW_TASK`（决策(222)）

**第三份真机日志（16:45:11，vivo / SDK=34，新包）：**

```
AutofillSave  读值：2 格有值
AutofillSvc   收值：Tally(watched=2, kept=2, blank=0, tooLong=0, control=0, unreadable=0)
AutofillSvc   确认页：交给系统拉（onSuccess(IntentSender)）   ← 28+ 分支，sender 建出来了
AutofillSvc   已断开
        ← 之后一行都没有
```

排除掉的：不是读值（`kept=2`）、不是 `SavePlan` 判错（没有「这一屏现在不值得看着了」）、
不是建不出 sender（没有「建不出确认页入口」）、不是相位（用户确认过没有自动锁定，
所以不是 `Unlocking`；也没有 `确认页起来了，交接单已兑`，说明根本没到相位这一层）。

**两条新证据：**

1. `16:45:11.444 ImeFocusController onWindowFocus: DecorView[IndexActivityV2]` ——
   保存请求到达时，**被填的那个 Activity（`LoginActivity`）已经没了**，
   前台是小红书自己的首页。也就是说框架要「从被填的那个 Activity 的上下文」发起启动时，
   那个上下文已经是一个销毁掉的 Activity。
2. 这份日志里 `system_server` 的 W 级别是打得出来的（`BatteryStatsService W`），
   却**没有** `Background activity launch blocked!`。所以「纯 BAL」这个解释是站不住的，
   至少不是全部。

**真正的差别在 `Intent` 上。** `onSuccess(IntentSender)` 的设计是：这一次启动由被填的那个
Activity 的上下文发起，**因而成为那个 Activity 任务栈的一部分**（平台文档原话）。
而 `AutofillSaveActivity.intent` 上一直带着 `FLAG_ACTIVITY_NEW_TASK`——那是从更早那条
`startActivity` 老路上带过来的残留（从 `Service` 上下文启动 Activity 时它是硬性要求）。
带着它，再叠上清单里的 `taskAffinity=""`，系统要做的就不是「往前台那个任务上压一页」，
而是**新建一个任务**；而后台新建任务正是 BAL 拦得最死的那一种，同样不抛异常、同样没有回调。

对照组一直摆在隔壁：解锁跳板和挑选页走的是同一套「系统代发我们的 `PendingIntent`」，
而 `AutofillResponses.unlockSender` / `pickSender` 那两个 `Intent` **一个旗子都没加**。
三个页面里唯一起不来的那一页，也是唯一多带一个旗子的那一页。

**改动**

| 文件 | 改了什么 |
| --- | --- |
| `AutofillSaveActivity.kt` | `intent()` 去掉 `FLAG_ACTIVITY_NEW_TASK`，和另外两页逐条一致 |
| `VaultAutofillService.kt` | `handOff()`（26/27 兜底，唯一真的从 `Service` 启动的路）自己补上那个旗子 |
| `VaultAutofillService.kt` | 新增 `watchLanding()` + `WATCH_MILLIS = 3s`：交出 sender 之后守一下，票还在就记一行并清掉 |
| `SaveHandoff.kt` | 新增 `dropIfPending(t, now)`：按票、在同一把锁里判并清（`clear()` 会误伤中途来的新一份） |
| `AndroidManifest.xml` | 只改注释：`taskAffinity=""` 现在只对 26/27 那条兜底路生效 |

`creatorBalOptions()` 保留：它仍然是必要的那一半（宿主不可见时照样要用），只是不再是主线。

**这次的观察点。** 之前两版各藏了一整轮，根子是 `onSuccess(sender)` 之后**没有任何回音**：
成功和石沉大海在 logcat 上一模一样。`watchLanding` 把这一段变成可观测的：

- 有 `确认页起来了，交接单已兑` → 修好了，往下看相位；
- 有 `确认页没起来：3000ms 后票还在槽里，已就地清掉` → 这一条也不是主因，
  直接进「未决三方案」的方案 2（通知中转），那条路不依赖任何 OEM 的善意。

顺带一个安全上的净收益：判定成立时那份明文原本要躺满 `TTL_MILLIS`（5 分钟）——
`SaveHandoff` 三条纪律的第 2、3 条都长在确认页上，那一页没起来一条都不触发。现在压到 3 秒。

**回归项**（在 M4-3c 那五条之外再加两条）：
6. 连着保存两次，第二次的确认页要照常起来（验 `FLAG_CANCEL_CURRENT` 那条没被这次改动影响）；
7. 确认完之后按返回，应该回到宿主应用（现在这一页压在它的任务上），而不是回到桌面。

### M4-3d 定案：确认页改由通知拉起（决策(223)）

**去掉 `FLAG_ACTIVITY_NEW_TASK` 之后的真机日志（17:33，vivo / SDK=34）：**

```
17:32:53.132  AutofillSvc   已断开                              ← 登录成功，LoginActivity 销毁
17:33:07.006  AutofillSvc   已连接                              ← 14 秒后，用户按下「更新」
17:33:07.033  AutofillSvc   收值：Tally(watched=2, kept=2, ...)
17:33:07.040  AutofillSvc   确认页：交给系统拉（onSuccess(IntentSender)）
17:33:07.047  AutofillSvc   已断开
17:33:10.047  AutofillSvc   确认页没起来：3000ms 后票还在槽里    ← 守望命中
```

**所以决策(222) 不是主因**，而且这一次的日志把 BAL 也排除了：这份抓包**带着
`ActivityTaskManager` 这个 tag**（`-s AutofillSvc AutofillSave AutofillDbg AssistShell
ActivityTaskManager`），它把小红书的每一次 Activity 启动都打出来了，
却既没有 `Displayed …AutofillSaveActivity`，也没有 `Background activity launch blocked!`。
**不是被拦下的，是那一句压根没执行。**

对上 AOSP 就是那个弱引用：`AutofillManagerClient.startIntentSender` 先
`mAfm.get()`，取不到就**什么都不做**。而 `mAfm` 指向的是**被填的那个 Activity** 的
`AutofillManager`——系统的保存框恰恰是在那个 Activity 销毁之后才弹出来的
（这次隔了 14 秒，中间小红书跑了好几轮 GC）。取不到、不抛异常、不回调、系统日志上也不留一行。

三条路的共同点因此浮出来了：**它们都要求别人替我们把一个页面拉起来。**

| 路 | 谁去拉 | 结果 |
| --- | --- | --- |
| `startActivity`（M4-3b） | 我们自己，从没有可见窗口的服务里 | BAL 静默拦下 |
| `onSuccess(IntentSender)`（M4-3c） | 框架转交给被填的那个 Activity | 那个 Activity 已经销毁，静默作废 |
| **通知（M4-3d）** | **用户自己点，系统代发** | **明确的 BAL 豁免，一定通** |

**改动**

| 文件 | 改了什么 |
| --- | --- |
| `SaveNotice.kt`（新） | 通道、发、收；通知里不出现用户名/密码/应用名，锁屏可见性 SECRET，`setTimeoutAfter` = `SaveHandoff.TTL_MILLIS` |
| `VaultAutofillService.kt` | `watchLanding` 3 秒后不再直接清票，改为 `SaveNotice.post`；连通知都发不出去才按票清 |
| `AutofillSaveActivity.kt` | `redeem()` 开头、`drop()` 里各收一次通知（第二条入口不能留在抽屉里） |
| `SaveHandoff.kt` | 新增 `isPending(t, now)`（判）与 `dropIfPending(t, now)`（判+清，同一把锁） |
| `AndroidManifest.xml` | 新增 `POST_NOTIFICATIONS`，附一段说明它为什么不破坏权限清单那个卖点 |
| `AutofillSettingsScreen.kt` | 缺权限时才出现的一格：说明 + 「允许通知」；拒过一次之后按钮改成「去系统设置打开通知」 |
| `AutofillAvailability.kt` | 新增 `openAppNotificationSettings()`（三级兜底） |

`onSuccess(IntentSender)` 那条路**保留**：它免费、而且在宿主 Activity 还活着的场景
（多步登录、网页表单）上是更快的一条。通知只在守望命中时才发，两条路互不干扰——
页面从哪条路起来，`redeem()` 都会把通知收掉。

**为什么系统那张请求框只弹一次要单独处理**：`POST_NOTIFICATIONS` 被拒之后再调
`launch` 会当场返回「未授予」而不弹任何东西，表现就是「按钮按下去没反应」——
和这一版在修的症状同一种。所以设置页记住 `refused`，之后换成跳系统设置那条路。

**回归项**（在前面那七条之外）：
8. 没给通知权限时保存：设置页那一格要出现，logcat 要有「发不出通知：没有通知权限」+「这一份没有入口了，已就地清掉」；
9. 给了权限之后保存：3 秒后出现通知 → 点进去 → 确认页 → `已存：Update`，通知同时消失；
10. 发了通知但用户不点：5 分钟后通知自己消失，票也已过期，点不出空页面；
11. 连着两次保存：抽屉里只有一条通知，点进去是**第二**次那份（验 `FLAG_CANCEL_CURRENT`）；
12. 库锁着时点通知：先解锁屏，解完锁是确认单，通知在 `redeem` 那一步就已经收掉了。

### M4-3d 后续：通知这条路通了，但提案落成了「新增」（决策(224)）

**真机日志（17:46:31）**：`Displayed cn.localvault.app.debug/…AutofillSaveActivity`
——**确认页第一次真的起来了**，通知那条路成立。剩下的是提案内容错了：
库里已经有一条小红书，按下确认之后新增了一条，而不是改那一条。

**成因在 `AutofillSave.chooseTarget`。** 它认目标只有两条规则：账号逐字相同，
或者这一屏根本没读到账号（`matches.singleOrNull()`）。于是「库里那一条账号是空的」
这种处境落进了缝里——账号相同永远不成立，`user != null` 又走不到 `singleOrNull`
那一支，只能新建。

这条缝是自己长出来的，因为下游早就把这一档写好了：

- `changesFor` 明写「账号：只在原来是空的时候补上（`How.Add`）」；
- `proposeUpdate` 的护栏二只挡「账号**非空**且对不上」。

也就是说 `proposeUpdate` 一直能正确处理一个空账号的 target，而 `chooseTarget`
永远造不出这样的 target。用户那一侧的症状是：先在列表页手记一条「小红书 + 密码」
（新建时账号本来就可以留空），之后每登录一次就多一条。

**改动**：`chooseTarget` 增加第 3 条——账号一条都对不上时，如果这个站在库里
**恰好一条、而且那一条的账号是空的**，认它。`singleOrNull` 是有意收紧的：
一条空账号 + 一条别人的账号时不猜，猜错的代价是另一个账号的密码没了。
这一档不会让任何东西凭空消失（账号是 `Add`，只有密码是 `Replace`，逐条写在确认页上）。

新增两条用例：`这个站只有一条、而且它还没有账号…`、`空账号那一条只在这个站独苗时才认…`。
既有用例不受影响——测试里的 `entry()` 默认账号是 `"ann"`，没有一条空账号的单条库。

**顺带补的观察点**：`AutofillSaveActivity` 算完提案打一行
`提案：Proposal(…)｜同站可改 N 条｜这一屏读到账号=true/false`。
`Create` 有三种成因（这个站库里一条都没有 / 有但不够格被改 / 够格但账号对不上），
三种的修法完全不同，而原来在日志上分不出来——同这一轮反复踩到的那个坑。
这一行只有两个数字和一个布尔（决策(144)）。

### M4-3d 收尾：账号比较要认得出手机号的排版（决策(225)）

**现场**：库里那一条存的是 `18623456789`，小红书登录框读回来的是 `186 2345 6789`
——同一个号码，中间多了两个空格（那个输入框在用户打字时自己插的分节）。
`chooseTarget` 里那一句 `it.username == user` 于是永远不成立，
每登录一次就在库里新长出一条，两条指着同一个账号。

**为什么不改成按名称或按包名匹配**（当时最自然的两个念头，两个都不能要）：

- **包名其实已经在用了，而且它是前一道筛子不是这一道。** `updatable()` 先按归属把
  「这个站的条目」筛出来，剩下的问题恰恰是「这个站的好几条里改哪一条」。
  用包名当最终判据 = 「同一个应用 → 覆盖手上这一条」：一个人有主号和小号时，
  小号那次登录会把主号那条的密码盖掉，而他要到下次登录主号才发现，
  且这个 App 没有条目级历史、没有撤销。
- **名称比包名更糟。** 它是用户能随手改的自由文本，而新建时的建议名又是从应用名推出来的
  ——一个人的两个小红书账号默认都叫「小红书」。拿它当判据是把两条同名条目直接判成同一条。

**所以判据仍然是账号，改的是比较方式。** 新增 `AccountName`（纯 Kotlin，无 `android.*`）：

- `same(a, b)`：逐字相同一律相等；**只有当两边去掉分隔符之后都是电话号码的形状时**
  才允许「抹掉排版后相等」。其余一概逐字比较——「去掉空格再比」用在所有账号上会把
  `a b` 和 `ab` 判成同一个账号，而那是两个人。
- 分隔符只认排版会用的那几种（空格类、`-`、`.`、括号），不认字母、不认 `@`/`_`。
- 电话号码的形状：可选一个前导 `+`，其余全数字，6..15 位（E.164 上限）。
  **`+86 186…` 和 `186…` 不算同一个**——「补上区号算不算同一个号」没有一个在全世界都对的答案，
  这一层只做一件确定的事：把排版抹掉，不做任何猜测。
- `tidy(raw)`：存进库的那一份抹掉手机号的排版。存下带空格的那一份不只是难看——
  下次自动填充会把 `186 2345 6789` 原样打进另一个不接受空格的登录框，
  而用户会以为是密码错了。抹掉的只有分隔符，一个数字没动，而且结果会逐字画在确认页上。

**落点四处**：`chooseTarget` 的账号筛选、`proposeUpdate` 护栏二（同一个号不该再被挡）、
`proposeCreate`、`changesFor`/`applyTo` 里往空账号格补值那一路。
**账号非空时仍然一个字都不动**（决策(201) 不变）：库里那条 `18623456789` 不会被换成带空格的写法。

新增用例：`AccountNameTest`（12 条，一半在钉「不许放宽什么」）+ `AutofillSaveTest` 两条
（`登录框把手机号排版过，照样认得出是同一条`、`新建时存下的手机号不带排版`）。

**遗留**：这次之前已经误建出来的那条重复记录要用户自己删。工程里不做自动合并——
合并两条凭据是一次不可撤销的写入，而这个 App 没有撤销。

### M4-3d 再收尾：填充成功之后不该再问一次（决策(226)）

**现场**：用自动填充登录小红书成功，系统那个保存框**照样弹**。点通知进去，
确认页写着「这一份库里已经有了，不用存」——为一件确定不用做的事打扰了三次
（系统框 → 通知 → 一页写着不用存）。

**为什么系统会问**：手机号那个输入框收到我们填进去的 `18623456789` 之后
自己排版成了 `186 2345 6789`。框架比对「填进去的值」和「提交时的值」发现不一样，
判定用户改过，于是问一次。**别人的输入框我们管不着**，能管的是后面那一串。

**改动**：`VaultAutofillService` 新增 `notWorthAsking()`，在 `capture()` 里、
`SaveHandoff.offer` **之前**调一次。库当下解锁着时就地跑一遍 `AutofillSave.outcome`，
拿到 `Outcome.Silent` 就 `return null`——不放票、不交 `IntentSender`、不发通知、
不起确认页，`onSuccess()` 安静收下这次请求。三次打扰一次都不发生。

`AutofillSave.outcome` 本来就能算出 `AlreadyStored` 这一档，只是原来算得太晚
（在确认页上，那时候三次打扰已经发生了两次）。

**两条边界照旧**：

- **只读，算完就丢。** 真正的提案仍由确认页拿**那一刻**的库内容重算（决策(152)）：
  从这儿到用户按下确认中间隔着一次通知、可能还隔着一次解锁，库完全可能已经变了。
  这儿算出来的东西只配回答一个问题——「要不要往下走」。
  `onSaveRequest` 的文件头相应改了措辞：不再说「`VaultSession` 一次都没被碰过」，
  而是点名 `notWorthAsking` 是唯一一处、且只读。
- **锁着一律返回 false。** 锁着就是数不出库里有什么，而宁可让用户白走一趟，
  也不能因为库锁着就把他刚打的密码丢掉（决策(197)，一字不改）。

副作用（有意的）：解锁状态下 `AutofillSaveFlow.Refused` 那一屏基本不再出现——
`OwnUi` / `NothingCaptured` / `CannotTellPassword` / `CannotTellEntry` 这几档也一起被提前挡掉了。
经通知才进得来的一页拒绝屏是纯噪音，那几档的解释本来就归 M4-4 关于页
（`AutofillSave.note`）。锁着时那一屏仍然可达。

新增日志：`这一次不打扰：AlreadyStored`——只有档名，没有包名、没有条目名（决策(144)）。
### M4-3d 回退：账号逐字比较，排版不同就是两个账号（决策(227)，撤回决策(225)）

**用户的判断**：只要账号名不一样就算不同的一条，**不管差的是不是几个没意义的空格**。
误建出来的那条留着当正式记录，自己早先手记的那条自己删掉。

**为什么撤回**：决策(225) 那一层（`AccountName`）没有算错，撤它是因为**两个方向判错的代价不对称**：

| 判错方向 | 结果 | 能不能挽回 |
| --- | --- | --- |
| 该更新的落成新增 | 库里多一条 | 能，用户看得见、删得掉 |
| 该新增的落成更新 | 一条密码被盖掉 | **不能**，没有条目级历史、没有撤销 |

「只是几个没意义的空格」这个前提要成立，得先认定那串字符是个电话号码、
且分隔符不携带信息——两层猜测叠在一起，猜错一次的代价落在不可挽回那一栏。
决策(201)（绝不改写非空账号）本来就是同一条账，这次只是把它贯彻到底。

**改动**：

- 删掉 `AccountName.kt` 与 `AccountNameTest.kt`；
- `chooseTarget` 的账号筛选改回 `it.username == user`；
- `proposeUpdate` 护栏二改回 `target.username != user`；
- `proposeCreate` / `changesFor` / `applyTo` 不再 `tidy()`——**屏幕上读到什么就存什么**。
  这一条不只是「少做一件事」：抹掉分节空格之后，存下的那一份和下次从同一个输入框
  读回来的那一份对不上，逐字比较又落成新增，反倒把 bug 变成了每次都犯。
  存原样则第二次登录起就能逐字对上。

`AutofillSaveTest` 那两条用例改写成钉新行为：
`排版不同的手机号算两个账号——新增，不覆盖`、`新建时账号原样存下，一个字都不改`。

**决策(224) 不变**（这个站只有一条、且账号是空的 → 认它）：它跟账号怎么比无关，
补的是「用户先手记了一条没账号的，后来才在应用里登录」那个缺口。

**决策(226) 不变**（`notWorthAsking` 提前静默）：它和账号怎么比是两件事。
回退之后第一次登录会照常发通知、确认页写「新增」；把旧的删掉之后，
第二次起账号逐字对得上、密码也没变，仍然一次都不打扰。

### M4-3d 收尾：通知权限进页面就自动请求（决策(228)）

**现场**：用户问「那个允许通知能不能自动打开，不让用户再点一次」。

**能省的和不能省的**：`POST_NOTIFICATIONS` 是运行时权限，
**只有用户能授予**——应用没有任何 API 能替他按下系统那张框上的「允许」，
这一下省不掉。能省的是**我们自己那一次点击**：原来是
「看到卡片 → 点『允许通知』→ 系统弹框 → 点『允许』」，现在进页面直接弹系统框。

**改动**（`AutofillSettingsScreen`）：`needsNotice` 为真时用 `LaunchedEffect` 自动
`launch(POST_NOTIFICATIONS)`，一次进入只发一次（`asked` 闸；不设它就是自旋，
因为回调里 `revision++` 而 `needsNotice` 跟着 `revision` 重算）。
`asked` 用 `remember` 不用 `rememberSaveable`——转屏后再问一次不是代价
（系统框只弹一次，之后 `launch` 立刻返回），而 `rememberSaveable` 要往
`savedInstanceState` 里写东西（同 `EntryFormFields` / `VaultListScreen` 的取舍）。

那张卡片**从主路降为兜底**：它现在出现只说明自动那一次没成（用户划掉了系统框，
或者更早之前拒过一次、那张框已经不会再弹）。按钮文案相应改成
「再问我一次」/「去系统设置打开通知」，被拒那一档多一句解释系统框为什么不再弹。
权限已给、或系统 < 13 时 `needsNotice` 为 false，这段一次都不跑。
### M4-3d 事故与修补：安全键盘的一串圆点被当成密码存了进去（决策(229)）

**现场**：`com.sgcc.wsgw.cn`（网上国网）。填充填不进去（见下一段），用户手打密码登录成功，
系统弹保存框，按「更新」—— 库里那条**正确的密码被换成了一串圆点**，
点开小眼睛看到的还是圆点。这个 App 没有条目级历史、也没有撤销，那一份密码就是没了。

**根因**：那个密码框接的是一套**安全键盘 SDK**。真值全程在 SDK 自己的缓冲里
（往往按键那一刻就加密），摆在 `EditText` 里的**就是一串 `•`**。
所以 `getAutofillValue()` 读回来的也是那串圆点，而它一路畅通地走完了整条链：
`SavedFields` 收下 → 账号对得上 → `How.Replace` → 落盘。

链上每一环都按自己的规矩做了正确的事，没有一环负责问「读到的这个东西**像不像**一个密码」。

**同一个根因的另一半**（用户先报的症状）：填充侧看得见填进去了、明文也对，
但应用提示「请输入密码」；在密码框末尾敲一个字符，前面的内容**全部消失，只剩新敲的那个**——
那是 SDK 拿自己的空缓冲重画了整格。日志里那个框是
`ifa=2`（`importantForAutofill=NO`，应用明说别填）+ `hints=passwordAuto`，
我们的默认策略是覆盖这条声明（`⟨应用声明别填，已放行⟩`）。这一次应用说对了。
走标准 `AutofillValue` 的密码管家在这种框上一律无效；要填进去只有无障碍那条路，
和这个 App 的权限前提冲突，不做。

**改动**：`SavedFields` 新增 `Rejected.Masked`，**整格拒收**（不是「洗掉圆点」——
文件头那条死规矩不变：要么原样收下，要么整格拒收，绝不改写一个字符）。
拒收之后这一格根本不进 `SaveContext`，`changes` 里就没有密码这一项，也就不会有任何覆盖。

判据故意收得很死：**整格每一个字符都来自 `MASK_CHARS` 才算**（`•·●○∙⦁⬤◦⚫﹡＊･․‧` 加 ASCII 的 `*` 和 `.`）。
`a•b` 这种真密码不受影响。代价不对称是这条判据的全部依据：
误伤一个通篇只有圆点的真密码 = 这一次没存上，用户自己在库里改；
放行一次 = 一条存在的密码被换掉、且找不回来。

只对 `Password` / `NewPassword` 两档判：账号框不会被掩码，
而一个由圆点组成的**用户名**虽然古怪却是他自己打的，拒收它没有任何东西可保护。

`SaveCapture.Tally` 加 `masked` 计数（并进 `rejected`），日志上现在是
`收值：Tally(watched=2, kept=1, ..., masked=1, ...)` —— 这是这一档唯一的观察点。

**落到用户身上的效果**：这种应用上按「更新」之后，密码那一格被拒收，
账号和网址又都已经对得上 → `changes` 为空 → `isNoop` → `Outcome.Silent(AlreadyStored)`，
经决策(226) 那道提前判断，**一次都不打扰**。库里那条保持原样。

新增用例四条（`SaveCaptureTest`）：整串圆点被拒、星号/句点也算、夹在密码中间的圆点不算、
一串圆点的用户名照样收下。`收下的加丢掉的正好等于看着的那几格`那条加上了 `masked`。

**遗留**：已经被改坏的那一条要用户自己在库里改回来 —— 没有历史可回滚，这一点没变。
### M4-3d 补决策(229)：读到掩码时说一句实话，而不是新建一条空壳（决策(230)）

**现场**（用户装上决策(229) 那一版之后报的两条）：

1. 库里**有**网上国网那条 → 登录成功后，**系统那个保存框根本不弹**（无论手打的密码和库里那条一不一样）；
2. 库里**没有** → 系统框照弹，按「更新」→ 新增一条，**密码字段是空的**。

第 2 条是我们自己的缺口：密码那格被拒收之后，账号还读得到，
于是 `refuse` 放行 → `proposeCreate` 拿着账号新建了一条。那条空壳**永远补不上**——
下一次登录密码照样读不到，`changes` 依旧为空，`AlreadyStored` 安静收场。
而用户按下「更新」时以为自己刚存了一份密码。

**改动**：`SaveContext` 加一位 `maskedPassword`（`SaveCapture` 在 `masked > 0` 时置上）；
`AutofillSave` 新增 `Reason.MaskedPassword`，在 `refuse` 里排在 `NothingCaptured` **之前**——
这一屏多半还读到了账号，走不到那一档，而「读到的是掩码」是一句比「什么都没读到」准确得多的话。

判据是 `maskedPassword && effectivePassword == null`，不是只看那一位：
改密码页上旧密码那格是安全键盘、新密码那格读得出时，后者才是该存的东西，不该被这一档挡掉。

拒收（决策(229)）保住的是库，这一档保住的是**这一页说的话**：
与其留一条骗人的记录，不如摆一句「这个应用的密码框用的是它自己的安全键盘……这一类应用的密码要自己在保险库里记一条」。

**第 1 条不是我们做的**：我们的代码全部跑在用户按下那个框**之后**（`onSaveRequest`），
没有任何机会阻止它出现。那条框由填充那一次交上去的 `SaveInfo` 加框架自己的门决定，
而框架的门是「必填框都非空 **且** 至少有一格和它自己填进去的值不一样」。
库里有记录时我们会摆填充条，用了它账号那格就和框架填进去的值逐字相同；
密码那格无论打什么，安全键盘摆在文本层的都是同一串圆点——两格都"没变过"，门就不开。
**待验**：在有记录的情况下完全不碰填充条、两格全手打再登录一次，如果这时候弹了，就坐实是这条。
日志上的判据是有没有 `收值：Tally(...)` 这一行：没有这一行，说明我们压根没被调到。

新增用例：`密码框读到的是掩码时，不新建一条空壳条目`、
`同屏还有一个读得出的新密码框时，掩码这一档不挡`；
`四条拒绝一次都不用碰库`（`AutofillSaveTest`）与
`不需要碰库的那四条拒绝排在库状态之前`（`AutofillSaveFlowTest`）各加一档。
### M4-3d 补：「按了保存框什么都没发生」要有个地方能查到（决策(231)）

**现场**：决策(230) 装上之后，库里**没有**网上国网那条时按系统的保存框 ——
不新增记录（对），但也**没有通知、没有确认页**，屏幕上什么都没有。

**这是设计内的，不是新 bug。** 链路：`refuse` 给出 `MaskedPassword` →
`Outcome.Silent` → 决策(226) 那道 `notWorthAsking` 在 `SaveHandoff.offer` 之前就 `return null`，
票不放、通知不发、页面不起。决策(226) 当时就写明了这个副作用：
「经通知才进得来的一页拒绝屏是纯噪音」。

**但这一档和别的档不一样，值得单独记一笔。** `AlreadyStored` 静默是安全的——
用户要的那条记录**确实在库里**。`MaskedPassword` 静默有一个真实代价：
他按了「保存」，什么都没发生，而他很可能**以为存上了**，
以后在需要那条密码的时候才发现库里没有。

**这一版的取法**：保持静默，但把这件事写进「它为什么有时候不出现」那份清单
（`AutofillSettingsModel.WHY_NOT_SHOWING`，那份清单存在的全部理由就是
「一个功能在克制和一个功能坏了，在屏幕上长得一模一样」）。加两条：

- 「填进去了，可它说没输入密码」→ 安全键盘：框里那串圆点只是占位，
  真正的密码从不经过系统，任何密码管理器都填不进去；补敲一个字符也没用，
  它会把整格重画成刚敲的那一个。
- 「按了系统的保存框，什么都没发生」→ 要么库里已经一模一样，
  要么这一屏只读到一串圆点。宁可一个字都不存，也不能拿圆点把正确的密码换掉。

`WHY_SUMMARY` 的条数是算出来的，不用改。`WHY_TAIL` 的 KDoc 原来写死「前面七条里有五条」，
改成不写死条数——这份清单是会长的。

**没有选的两条路**（如果以后要改，理由在这儿）：

- **这一档不静默**（照发通知、进确认页说那句话）：最诚实，但网上国网这类应用
  **每登录一次就是一条通知**，而那句话第二次之后就是纯骚扰。
- **按应用只解释一次**：要在 `SharedPreferences` 里记下包名，
  也就是在库文件之外落下一份「这个人用哪些应用」的明文记录。
  这个应用的整个前提是别处不留东西，为一句提示破这个例不划算。
### M4-3d 补：掩码这一档，库里没有对应条目时不许静默（决策(232)，修正决策(231)）

**现场**（真机日志，删掉库里那条之后再登录一次）：

```
08:40:31  收值：Tally(watched=2, kept=1, blank=0, tooLong=0, control=0, masked=1, unreadable=0)
08:40:31  这一次不打扰：MaskedPassword
```

链路和决策(231) 写的一字不差，**但那一版选择忍受的那个代价这次落到了用户身上**：
他按下系统的保存框，屏幕上什么都没有，而库里一条都没有。他会以为存上了。

**这一版的取法**：静默的前提是「他要的那份东西确实已经在库里」。
`AlreadyStored` 天然满足；`MaskedPassword` 要看库：

- 这个站库里**有**一条 → 那条还在、还是对的，这一次只是没跟上 → 静默（原样）；
- 库里**一条都没有** → 照常往下走，确认页把 `AutofillSave.note` 那句实话说出来。

这条打扰是**自终止**的：用户照着那句话自己在库里记一条之后，同一屏从此回到静默。
决策(231) 当时担心的「每登录一次一条通知」因此不成立——它至多发生到用户建好那条为止。

**判据是纯的**：`AutofillSave.safeToStaySilent(reason, hasStoredMatch)`，
`hasStoredMatch` 由调用方用 `updatable()` 数（够格被这一次保存改动的那些条目，
不是「库里有没有条目」）。服务那一侧只多了一次计数和一行日志：
`这一档本该静默，但库里一条都没有，照样问：MaskedPassword`。

新增四条用例，其中 `除掩码之外没有第二档要看库` 是护栏：
以后往 `Reason` 里加档时，需要看库的那一档必须显式写一行，不许靠 `else` 兜底默默静默。

**顺带**：保存请求补了抬头 `════ 保存请求 ════`（`AutofillDebug.saveRequestStart`）。
原来它在 logcat 上只有一行光秃秃的 `── 请求来自：…`，和又一次填充请求分不开，
唯一的区别是后面隔着几行才出现的 `收值：Tally(...)`——而整条保存链的故障
全靠这份日志分档，第一步就是先认出这是哪一种请求。

`AutofillSettingsModel` 那条「按了系统的保存框，什么都没发生」跟着改：
现在它只在「库里已经有一条」时才成立，文案要说清楚这一点，否则那份清单就在骗人。
### M4-3d 补：空密码的条目不算「库里有」（决策(233)，收紧决策(232)）

**现场**：库里有网上国网那条、但密码字段**是空的**（决策(230) 之前那一版留下的空壳，
或者用户自己建了一半的那一条）。登录成功 → 系统保存框弹出 → 按下保存 → **一点反应都没有**。

**决策(232) 的判据在这一种库状态上是错的。** 它数的是「有没有够格被这一次保存改动的条目」
（`updatable().isNotEmpty()`），而静默真正的前提是「用户要的那份东西已经在库里、
**而且是一份能用的东西**」。一条密码为空的条目满足前者、不满足后者：它存在，
但用户要密码的时候那一格照样是空的，我们却因为「有一条」而安静收场了——
**决策(232) 要修的那个误解，原样又发生了一次**。

**改动**：`safeToStaySilent(reason, hasStoredPassword)`，入参由
`AutofillSave.storedPasswordExists(origin, entries, trust)` 算——
够格的那些条目里有没有哪一条的**密码非空**。空壳一律不算数。
服务那侧的日志跟着改：`这一档本该静默，但库里没有一份能用的密码，照样问：MaskedPassword`。

数错方向的代价在这一档上是单向的：少数一条只是多打扰一次，多数一条是又一次「按了没反应」。

新增用例 `密码为空的条目不算一份能用的密码`；原来那两条的参数名跟着改。
`AutofillSettingsModel` 那条症状的说法也跟着改口（「已经存过一条**带密码的**」）。
### M4-3d 定稿：掩码这一档一律不静默（决策(234)，撤回决策(232)/(233) 的看库判据）

**现场**：库里那条密码**非空**，用户随便填一个非空的假密码，登录成功 → 系统保存框弹出
→ 按下保存 → 一点反应都没有，库里那条也没动。**和密码空不空无关。**

**两版弯路，错在同一个地方。** 决策(232) 数「有没有能被这次保存改动的条目」，
决策(233) 收紧成「有没有一份非空的密码」——都是拿**库里有什么**，
去替代一件我们根本读不到的事实。库里那份密码非空，不等于它**是对的**：
用户改过密码、库里那条早已过期，这一次他正是想把它更新过来才按下保存的，
而我们安静收场，他于是带着一条错的记录走了。

**只要密码是掩码，「库里那条对不对」就是不可知的**，而在不可知的时候保持沉默，
等于替用户做了「不用管」这个判断。所以判据里那个入参整个删掉：

```kotlin
fun safeToStaySilent(reason: Reason): Boolean = reason != Reason.MaskedPassword
```

服务那侧连库都不数了（`storedPasswordExists` 随决策(233) 一起删）。
`AlreadyStored` 照旧静默——那一档的成立方式就是把读到的值和库里那条逐字比过一遍；
`CannotTellEntry` 照旧——它成立的前提是这个站库里有不止一条，用户手上有东西可查。

**决策(231) 当初担心的骚扰不成立**：那时候设想的是「每登录一次就是一条通知」。
实际链路不是这样——这一页只在用户**自己按下系统那个保存框**之后才出现，
它是对一次明确请求的回答，不是主动打扰。答不上来时说一句「这次没存、原因是这个」，
是这条链上最起码的诚实；什么都不做才是那个需要辩解的选项。

用例：`掩码这一档一律不许静默` + 护栏 `其余几档照旧静默`（以后加档时不该静默的必须显式写出来）。
## 1.0.0 定版

`versionName` 从 `0.1.0` 提到 `1.0.0`，`versionCode` 保持 1（第一次发布）。

**版里有什么**：主密码（Argon2id 64 MiB / t=3）与快速解锁、条目增删改查与搜索、
密码生成器、加密备份与恢复、CSV 导入、自动填充四条路
（填充 / 保存 / 开关与交代 / 内联建议）。全部功能不需要网络，进程没有 `INTERNET` 权限。

**刻意不在这一版里的**：M5-3 kdbx 导入、M5-4 CXF 格式对接；明文 CSV 导出永不提供。
这两条都是**加法**，不影响已有任何一条路，所以不构成定版的阻碍。

**已知天花板，写在这儿免得以后当成 bug 重查**：

- **安全键盘类应用**（网上国网是样本，银行政企类居多）：填不进去，也存不下来。
  密码框里那串圆点只是占位，真值在 SDK 自己的缓冲里，不经过系统的任何接口。
  这一版能做的是**不骗人**——不拿圆点覆盖已有密码（决策(229)）、
  不留假装存过的空壳（决策(230)）、每次都说清楚为什么（决策(234)）。
- **兼容模式浏览器**：`onSaveRequest` 拿不到 `autofillValue`，走到 `NothingCaptured`。
  话是实话但对用户没用（见「待办上还欠的一条」）。填充侧不受影响。
- 这两条都只在各自的处境里生效，通用路径上没有为它们加过任何判断——
  掩码那个布尔全工程只有一个消费点（`AutofillSave.refuse`），
  且带 `effectivePassword == null` 守卫。

**顺带清掉一条待办**：M4-3c 留下的「OEM 后台弹出界面开关还欠一次真机验证」，
今天这轮 vivo 真机日志已经证实——`确认页：交给系统拉（onSuccess(IntentSender)）`
之后确认页正常起来、新增与更新各走通一次。那一条不再是待办。

**定版前必须在有工具链的机器上跑一次**（这两步不在本次改动范围内，也没人替你跑过）：

```
./gradlew test          # 全部纯 JVM 用例
./gradlew assembleRelease
```
