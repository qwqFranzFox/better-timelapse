# 老 APP 痛点报告 —— TimeLapse (jonasjuffinger/TimeLapse) 复盘

| 项目 | 值 |
|---|---|
| 分析对象 | `TimeLapse/`（`com.jonasjuffinger.timelapse`，最后一个 tag `v.1.4.0`，最后提交 2020-06-03） |
| 代码规模 | 9 个自有 Java 类（1173 行）+ 2 个内嵌框架类（279 行），共 1452 行 Java；2 个 layout（404 行 XML） |
| 提交历史 | 33 个提交，跨 2017-02 → 2020-06，之后停更 5 年 |
| 测试 | **0 个**（`find` 全仓无任何 test/spec 文件） |
| 证据等级 | 本文每条结论都给出 `文件:行号`。「已读源码」= 我直接读过；「社区报告」= GitHub issue 标题/讨论；「推断」= 明确标注 |
| 分析方式 | 通读全部源码与资源，对照 `recipe-lab-sony-pmca`（同类 PMCA app，2025 年仍在维护）与 `pmca-app` skill 的平台约束 |

**一句话结论：** TimeLapse 是一个「能跑但不敢用」的 app —— 真机上长时间拍摄时无法安全停止、任何一次系统打断都会静默终止拍摄、设置界面有 5 个只有老用户才懂的缩写开关、拍完之后的收尾界面是捐款信息；开发侧则是一个 Gradle 5.4.1 + jcenter + 快照依赖的 2020 年时间胶囊，没有任何测试和构建脚本，新人（或 AI）无法在相机之外验证任何一行逻辑。

---

## 1. 这个 app 现在做什么

两个 Activity，一个 `Settings` 数据袋，设置通过 Intent 传递。

| 功能 | 用户看到的标签 | 代码位置 | 行为 |
|---|---|---|---|
| 间隔 | `Interval` | `SettingsActivity.java:96-99,157-214` | 一条 120 档的分段刻度条：第 1 档是 `burst`，之后 0.5s 一档到 20s，1s 一档到 40s，5s 一档到 100s，30s 一档到 11min，1min 一档到 15h+ |
| 张数 | `Shots` / `Dur. (s)` | `SettingsActivity.java:101-104,215-254` | 单条 131 档刻度条；burst 模式下标签变成 `Dur. (s)`（变成了秒数，见痛点 U-07） |
| 初始延时 | `Delay` | `SettingsActivity.java:106-109,255-289` | 0-4min 整数档，5-55min 步进 5min，1-24h 步进 1h |
| 帧率 | `fps` | `SettingsActivity.java:291-303`，`arrays.xml:2-10` | 只是一个**算式参数**，用来预估成片时长；app 不生成视频（README 也这么说） |
| 静音快门 | `SS` | `layout/activity_settings.xml:171-179` | 写 `modifier.setSilentShutterMode()` |
| 手动对焦 | `MF` | `layout/activity_settings.xml:181-189` | 拍前把对焦模式设为 manual |
| 曝光锁 | `AEL` | `layout/activity_settings.xml:191-199` | 拍前 AE lock on |
| 包围曝光 | `BRC3` | `layout/activity_settings.xml:201-209` | 假定相机已在菜单里设好 3 张包围，app 每次拍 3 张 |
| 关屏 | `DOFF` | `layout/activity_settings.xml:211-219` | 每张之间关闭 LCD |
| 开始 / 关闭 | `Start` / `Close` | `SettingsActivity.java:140-155` | `Start` 保存设置并跳转 `ShootActivity` |
| 停止 | — 无 | `ShootActivity.java:241-244` | **没有任何停止按钮**；MENU 抬起 = `onBackPressed()` 直接退出 |
| 拍完 | `Thanks for using this app!` | `ShootActivity.java:95-100`，`activity_shoot.xml:56-103` | 显示「Press MENU to exit」+ 作者邮箱 + Instagram 账号 |

拍摄主循环（`ShootActivity.java:57-108` 与 `319-396`）是「Handler 自重排 + ShutterListener 回调」两套定时器互相接力：`shootRunnable` 负责等到时间点按快门，`onShutter()` 回调负责取消拍摄、决定是否显示回放、再 `postDelayed` 回 `shootRunnable`。

---

## 2. 使用侧痛点（摄影师站在三脚架后面会遇到什么）

### 2.1 严重度总表

| # | 痛点 | 严重度 | 一句话后果 |
|---|---|---|---|
| U-01 | 没有「停止」，MENU = 直接退出 app | **阻断** | 想停就得丢掉整个会话，没有暂停、没有确认 |
| U-02 | 任何一次 `onPause` 都静默终止拍摄 | **阻断** | 低电量弹窗、USB 插入、相机自身对话框都会让拍摄无声无息地结束 |
| U-03 | 退出后设置不生效 / 不持久；区间内的临时参数一关 app 就回滚 | **阻断** | 相机本身不知道你想拍什么，重开 app 才能再拍一次 |
| U-04 | 拍完的收尾页是捐款 + 邮箱 + Instagram | 主要 | 唯一的「拍摄完成」反馈被广告占满，且**无法从这里重拍** |
| U-05 | 5 个缩写开关（SS/MF/AEL/BRC3/DOFF）没有任何屏上说明 | 主要 | 只能先读 README 才知道 BRC3 是「相机要先设好 3 张包围」 |
| U-06 | 拍摄中没有任何故障面：SD 卡满 / 写失败照旧计数 | 主要 | 回家才发现只拍到 300 张，不是 3000 张 |
| U-07 | burst 模式语义翻转（张数变秒数），进度显示混乱 | 主要 | `Shots=DUR`，`tvCount` 被写成剩余时间（`ShootActivity.java:332`） |
| U-08 | 间隔刻度条是分段跳档，没有精确输入 | 主要 | 想要 7s 得先知道 7s 落在哪一档；调 1 格可能跳 30s |
| U-09 | 无能力探测：silent shutter 不支持时静默忽略 | 主要 | `catch(NoSuchMethodError ignored){}`（`ShootActivity.java:184-188`），用户以为开了 |
| U-10 | 剩余时间 / 进度显示算错或自相矛盾 | 中等 | 进度条说 "30min"，实际刚拍完 3 张 |
| U-11 | 全英文界面，字符串硬编码在 layout 里 | 中等 | 中文用户看到 `Dur. (s)`、`DOFF` |
| U-12 | 对焦 / 刻度条焦点不明确，用户不知道自己正在调哪一行 | 中等 | `AdvancedSeekBar.dialChanged` 只在 `isFocused()` 时生效，屏幕上无焦点高亮 |
| U-13 | 退出 / 卸载即失去一切（无会话记忆） | 中等 | 拍一半退出，下次要重新输所有参数 |
| U-14 | 已发布 APK 用旧证书签进 git，新版本必须卸载重装 | 中等 | 卸载会清掉所有 SharedPreferences 设置（`Settings.java:86-114`） |
| U-15 | 没有「先试一张」的预检 | 中等 | 用户按 Start 就进入正式计时，第一张对不对只能靠运气 |
| U-16 | LENR / Picture Profile / MR 档位等已知会取消设置的状态没有提示 | 中等 | README 只能用文字警告（README「Known Issues」），app 内不提示 |
| U-17 | **全新安装时 MF + AEL + BRC3 默认全开**（装完第一次按 Start 就是「手动对焦 + 曝光锁死 + 三倍张数」） | **阻断** | `Settings.java:44-48`：`silentShutter = true; ael = true; brs = true; mf = true;` |
| U-18 | **间隔 > 2s 时每张提前 150ms 并逐张累积**（`remaining-150` 补偿项），且全程用墙上时钟；与快门无关 | **主要** | `ShootActivity.java:82-88`、`:215`（回放写死 2s）、6 处 `currentTimeMillis`；200 张实测：2.5s 请求变 2.35s、累计 −29.9s |
| U-19 | versionCode 三个版本共用 7（1.0.0 / 1.3.0 / 1.4.0），安装器与任何商店都分不清 | 中等 | `app/build.gradle:10`；提交 `1f23085` 升到 8 又被 `ab316cc` 退回 7 |
| U-20 | **仓库里提交的 7 个 APK 中，`TimeLapse-release-0.3.1.apk` 只有 v2 签名块、没有 v1** | **阻断（对那个版本而言）** | 解析 APK Signing Block：7 个都含 v2（`0x7109871a`），但 0.3.1 的 `META-INF/` 只有 87 字节的 `MANIFEST.MF`，没有 `.SF`/`.RSA`。相机只认 v1 → **那个 APK 装不上**，与 [Issue #11「A7 II 安装失败」](https://github.com/jonasjuffinger/TimeLapse/issues/11) 的现象一致 |

### 2.2 逐条证据

#### U-01 没有停止，MENU / TRASH 都会立刻终止会话（阻断）

```java
// ShootActivity.java:240-244
@Override
protected boolean onMenuKeyUp() {
    onBackPressed();
    return true;
}
```

源码里没有 `stopShooting()`、没有 `abort()`、没有确认对话框，也没有把当前进度写进任何持久化位置。`TRASH` 键更糟 —— 来自内嵌框架的默认实现：

```java
// BaseActivity.java:198-204
protected boolean onDeleteKeyDown() { return true; }
protected boolean onDeleteKeyUp() { onBackPressed(); return true; }
```

`ShootActivity` 没有覆盖它们，所以**按 TRASH 也会终止拍摄**。这不是「没有停止功能」，而是「两个最顺手的键都会立刻毁掉会话」。社区报告与之吻合：[Issue #49 「Unable to stop or shut off the camera while shooting (Sony RX100 III)」](https://github.com/jonasjuffinger/TimeLapse/issues/49)——用户连相机都关不掉。

**三处比「不能停止」更细、也更坏的地方：**

1. **MENU 并不是「退出 app」，而是「退到设置页」。** `SettingsActivity.java:143-146` 用 `startActivity()` 启动拍摄页却**没有 `finish()`**，所以设置页一直在返回栈里。按 MENU = `onBackPressed()` = 回到设置页，**拍摄被丢弃**。而结束屏上写的却是 `"Press MENU to exit"`（`activity_shoot.xml:70`）—— 文案与行为不一致。
2. **MENU 的「按下」没有被消费。** `BaseActivity.java:178` 的 `onMenuKeyDown()` 默认返回 `false`，两个 Activity 都没有覆盖它，所以固件**也**收到这次 MENU。而 `BaseActivity.java:233-235` 里注册 `pullingback_key` / `resume_key` 的两行是注释掉的 —— 也就是说相机自己的菜单逻辑仍然有效，**可能在拍摄中途把 app 顶到后台**（正好触发 U-02）。（这一条是推断，需要在真机上用按键日志确认。）
3. **全屏快门也会漏给固件。** `BaseActivity.java:76-77` 的 `ISV_KEY_S2`（518）默认返回 `false`，`ShootActivity` 没覆盖 —— 拍摄中按快门，固件会自己拍一张。

**新 app 的要求：** `TRASH` 抬起 = 暂停/继续，`hold TRASH` = 停止并确认，`MENU` = 退出但有守卫（拍摄中先问，不静默丢）；**按下与抬起两个事件都要消费掉**，绝不让固件看到 MENU / TRASH / 快门；任何终止路径都必须把会话日志落盘。

#### U-02 一切 `onPause` 都静默终止（阻断）

```java
// ShootActivity.java:247-281
protected void onPause() {
    display.on();                            // ① display 可能是 null
    display.turnAutoOff(Display.NO_AUTO_OFF);
    super.onPause();
    shootRunnableHandler.removeCallbacks(shootRunnable);   // ② 定时器被取消 = 拍摄结束
    ...
    setAutoPowerOffMode(true);
}
```

`onPause()` 无条件 `removeCallbacks`，而恢复路径只在 `onResume()` 里重新 `postDelayed` 一个**完整的新延时**（`ShootActivity.java:219-225`），也就是说：打断 1 秒 = 丢掉一个完整间隔的相位，打断后 app 回到「从零开始」。更严重的是这一行：

```java
// ShootActivity.java:248
display.on();   // display 在 onResume():227 才赋值，onPause 里没有 null 检查
```

`onResume()` 如果提前 `return`（例如 `CameraEx.open()` 抛异常、或框架 `isInTest()` 分支），`display` 就是 `null`，`onPause()` 会 NPE 崩溃。而 `onPause` 里的异常不会被 `CustomExceptionHandler` 之外的东西兜住，用户看到的是相机黑屏。

**新 app 的要求：** 会话状态（计划 + 已拍张数 + 下一张到期时刻）必须与 Activity 生命周期解耦并落盘；`onPause` 只做「释放相机资源」，不做「放弃拍摄」；恢复时按**剩余时间**继续，而不是重新计时。

#### U-03 设置不进设置存储，退出即失效（阻断）

整个 app 从不调用 `Backup_write`/`Backup_sync_all`（全仓 grep 无匹配），也没有 JNI 层。它只在 `onResume` 里改**实时参数**：

```java
// ShootActivity.java:156-213
final Camera.Parameters params = cameraEx.getNormalCamera().getParameters();
...
modifier.setSilentShutterMode(settings.silentShutter);
modifier.setAutoExposureLock(...);
modifier.setDriveMode(...);
cameraEx.getNormalCamera().setParameters(params);
```

按 `pmca-app` skill 与 recipe-lab 的实测（`docs/DEVELOPMENT.md:194-198`「Exit rule」）：**实时参数在 app 关闭后失效**，只有设置存储里的值在所有模式下、app 关闭时、关机再开机后都生效。后果是：

- 拍摄途中相机自己（或用户）动了任何设置，app 不会纠正；
- app 一退出，SS/MF/AEL/BRC3/DOFF 全部回滚；
- 相机在别的模式（例如视频、或者用户按了 Fn）下完全不知道你的意图。

对照 recipe-lab 的做法：选中即 `Backup_write` + `Backup_sync_all`，用户可以在 app 关闭后一直拍（`README.md`「What it changes」）。这正是新时代 PMCA app 最大的体验跃迁。

**新 app 的要求：** 把「拍摄计划」持久化到相机设置存储（自有槽位，先 `Backup_get_attribute` 检查只读再写、单字节、已知原值），并遵守退出规则（退出时把实时参数设成**已存储**值，而不是启动快照）。

#### U-04 收尾页是捐款广告（主要）

```xml
<!-- res/layout/activity_shoot.xml:66-101 —— 整个「结束屏」 -->
<TextView android:text="Press MENU to exit" />                                          <!-- :70 -->
<TextView android:text="Please think about a small donation if you like it :)" />        <!-- :78 -->
<TextView android:text="PayPal: mail@jonasjuffinger.com" />                             <!-- :85 -->
<TextView android:text="You can also follow me on Instagram..." android:textSize="20dp" /> <!-- :93-94 -->
<TextView android:text="@jonas.juffinger" />                                             <!-- :100 -->
```

外加 `ShootActivity.java:96` 的 `tvCount.setText("Thanks for using this app!")`。这一屏同时是「拍摄完成」的唯一反馈、唯一的错误反馈位、也是唯一的重拍入口 —— 而它被三行自我推广占满，且只能按 MENU 退出。注意 `:94` 的 `android:textSize="20dp"` 是**单位用错**（应为 sp，同一屏的 `:86` 却是 `22sp`），在相机的固定密度屏上是不可控字号。

**新 app 的要求：** 完成屏 = 结果摘要（拍了 N 张、用了多久、平均间隔、落在哪个目录、有没有故障）+ 明确的三个下一步（重拍 / 改参数 / 退出）。推广只允许出现在 About 页。

#### U-05 五个缩写开关（主要）

layout 里只有 `SS` `MF` `AEL` `BRC3` `DOFF` 五个字母（`activity_settings.xml:179,189,199,209,219`），含义只在 README 里：`BRC3` 甚至要求用户「先在相机菜单里设好三张包围曝光」，app 不检查也不设置相机菜单状态。

**新 app 的要求：** 每个开关都必须有全名 + 一行后果说明 + 当前是否生效（能力探测结果）。相机上没有 hover，所以说明要常驻在选中行的下方，或用两行行高。

#### U-06 没有任何故障面（主要）

`tvBattery` 的充电状态只用一个 `"c "` 前缀静默表示（`ShootActivity.java:413-417`：`if(isCharging) s = "c "; return s + pct + "%"`）；整仓没有任何判断 SD 卡剩余空间、文件是否真的写入、写卡是否失败的代码。`shotCount++` 在 `cameraEx.burstableTakePicture()` 之后**无条件**执行：

```java
// ShootActivity.java:293-311
private void shoot() {
    if(takingPicture) return;        // takingPicture 从未被置 true（只在 118-130 初始化）——见 D-10
    shootTime = System.currentTimeMillis();
    cameraEx.burstableTakePicture(); // 返回值/异常都不看
    shotCount++;                     // 拍没拍成、写没写卡，都算一张
    ...
}
```

**新 app 的要求：** 张数必须由「文件真的出现在存储卡上」来确认（记录起始文件号 → 每次比对目录内新增文件），并区分 `已发送快门` / `已落盘` 两个计数；写失败或目录不再增长 = 立即进入故障态并告知用户。

#### U-07 burst 语义翻转（主要）

```java
// ShootActivity.java:130
burstShooting = settings.interval == 0;
// 结束条件用「秒」而不是「张」：
// ShootActivity.java:339
if (System.currentTimeMillis() >= shootStartTime + settings.shotCount * 1000) {
```

interval=0 时 `shotCount` 被当**秒数**用；而 UI 在同一条刻度条上把标签从 `Shots` 改成 `Dur. (s)`（`SettingsActivity.java:202-205`）。进度显示更乱：burst 模式下 `onShutter()` 把剩余时间写进 `tvCount`，把 `tvRemaining` 清空（`ShootActivity.java:328-336`）—— 即「张数」位置显示的是秒数。

**新 app 的要求：** 两种模式用两个不同的字段名与单位（`frames` vs `burstSeconds`），UI 上分开成两个独立行，不复用同一个刻度条。

#### U-08 间隔刻度条不可预期（主要）

```java
// SettingsActivity.java:173-198（分段跳档，共 5 种斜率）
else if(i < 41)       { settings.interval = i * 0.5; }
else if(i < 60)       { settings.interval = (i-40) + 20; }
else if(i < 76)       { settings.interval = (i-60) * 5 + 40; }
else if(i < 93)       { settings.interval = (i-76) * 30 + 120; }
else                     { settings.interval = (i-93) * 60 + 660; }
```

反向映射不存在，所以「输入 7 秒」这件事无法一次完成；档位跳变处一格 = 30s / 60s。而且这里算出的 `interval` 是**浮点秒**，主循环却把它乘回 1000 与 `System.currentTimeMillis()` 比较（`ShootActivity.java:75`），精度和相位漂移都没有处理。

**新 app 的要求：** 双键输入（粗调 / 细调各一键）+ 明确的档位表，屏上必须同时显示 `值`、`单位`、`下一格的增量`；不允许出现「按一下跳 30 秒」而用户不知情。

#### U-09 能力不做探测（主要）

```java
// ShootActivity.java:183-188
// setSilentShutterMode doesn't exist on all cameras
try { modifier.setSilentShutterMode(settings.silentShutter); }
catch(NoSuchMethodError ignored) {}
```

用户勾了 `SS`，相机不支持，app 一声不响。同样的静默吞异常遍布全文件（见第 3 节 D-09）。README 只能用文字告知：「The silent shutter option is functionless on cameras without silent shutter mode.」

app 里其实**已经有**探测代码，只是被注释掉了：

```java
// SettingsActivity.java:130-137
//try {
    //CameraEx cameraEx = CameraEx.open(0, null);
    //if(modifier.isSupportedSilentShutterMode())
    //    cbSilentShutter.setVisibility(View.VISIBLE);
/*} catch(Exception ignored) {}*/
```

**新 app 的要求：** recipe-lab 的做法照抄 —— 「不要维护机型表，问相机」（`docs/DEVELOPMENT.md:249-253`：`KeyProbe` 逐键问 `ScalarInput.getKeyStatus(scan).valid`）。支持性探测放在开机预检里，不支持就把该行显示为不可用并说明原因，而不是静默忽略。

#### U-10 剩余时间自相矛盾（中等）

```java
// ShootActivity.java:420-425
private String getRemainingTime() {
    if(burstShooting)
        return "" + Math.round((settings.shotCount * 1000 - System.currentTimeMillis() + shootStartTime) / 1000) + "s";
    else
        return "" + Math.round((settings.shotCount * getcnt() - shotCount) * settings.interval / 60) + "min";
}
```

非 burst 分支用「剩余张数 × 间隔」估算，忽略正在进行的曝光与回放耗时；burst 分支在**还未开始**（`shootStartTime` 是未来）时就已经在倒数。配合 `Activity_settings.updateTimes()` 用整数除法算时长（`SettingsActivity.java:361-381`），屏上会出现「预计 0min」这类无意义值。

**新 app 的要求：** 所有时间量都从**单调时钟**出发，由纯函数计算并单元测试（倒数秒数、已用时间、预计结束时刻、平均实际间隔、漂移）。

#### U-11 全英文、字符串硬编码（中等）

`res/values/strings.xml` 只有 3 行（app_name 与一个 title），其余全部是 layout 内联英文：`Interval`、`Shots`、`Dur. (s)`、`min`、`h`、`inf`、`Start`、`Close`、`Press MENU to exit`，再加上 `ShootActivity.java:96` 的 `"Thanks for using this app!"` 与 413-417 的充电前缀 `"c "`。没有任何 `values-zh`。

**新 app 的要求：** recipe-lab 在 API 10 上的做法（`docs/LOCALIZATION.md`）：Java 文本表（`TextEn` / `TextZhHans` / `TextZhHant`）+ 打包裁剪过的 CJK 字体（`assets/fonts`，OFL）+ 一个 `Lang` 查表入口 + `LangTest` 保证「每个 key 都有翻译、每个字符都有字形」。不依赖 Android 资源系统，因为 API 10 的字体与 locale 机制不可靠。

#### U-12 焦点不可见（中等）

```java
// AdvancedSeekBar.java:24-29
public void dialChanged(int value) {
    if(!isFocused()) return;
    setProgress(getProgress() + value);
}
```

刻度条只在获得焦点时响应滚轮，但屏幕上看不出哪一行有焦点，而且这是**唯一**的调节手段 —— 用户只能用四向键在不同行之间摸索。更糟的是 `SettingsActivity` 没有覆盖四向键/中心键（整仓 grep 无 `onUpKeyDown` 等），完全依赖 Android 2.3 的 View focus 机制在 `Button`/`SeekBar`/`CheckBox`/`Spinner` 之间跳转（focusable 顺序未定义），这条路径从没被测试过。

**新 app 的要求：** 自绘 UI（Canvas）而不是 Android 控件，自己持有「当前选中行」状态，滚轮/四向键的行为完全确定；这正是 recipe-lab `MenuView` / `PickerView` 的路子。

#### U-13 / U-14 会话与升级都丢状态（中等）

设置只存在 `SharedPreferences`（`Settings.java:86-114`），并且是**原始刻度位置**（`rawInterval`/`rawShotCount`/`rawDelay`）而不是值 —— 一旦刻度表改了（历史提交里确实改过），旧存档就会指向不同的间隔。仓库里还提交了 7 个 release APK（`app/release/*.apk`，v0.3 → v1.4.0），它们都用旧证书签名，因此新版本必须卸载重装，而卸载会清掉 SharedPreferences。

**新 app 的要求：** 计划存**值 + 单位**（不存 UI 刻度位置）并带 schema 版本；版本用清单里唯一数字公式；APK 不进仓库；发布用固定 keystore（CI secret）以便覆盖安装。

#### U-15 / U-16 没有预检、没有已知状态提示（中等）

没有「试拍一张」；没有检查 Picture Profile 是否打开、模式拨盘是否在 MR/1/2；这些都是 skill 与 recipe-lab 明文记录的「会让已存储值失效」的相机状态（`docs/PMCA-APP-DEVELOPMENT.md` 第 6.4 节、skill 的「Two behaviours」）。README 只能用文字警告它们（「Long exposure noise reduction should be turned off if the interval is less than double the shutter speed」）。

**新 app 的要求：** 开机预检 = 一次性检查相机状态 + 存储卡 + 能力，并把结果变成**可操作的告警列表**（哪一项、为什么、怎么改）。预检还要包含一次真实试拍（快门 + 落盘各验一次），因为「关掉相机的检查永远不是证据」。

#### U-17 全新安装的默认值是「危险三连」（阻断）

```java
// Settings.java:44-48 —— Settings() 的字段默认值
displayOff = false;
silentShutter = true;
ael = true;
fps = 0;
brs = true;
mf = true;
```

`Settings.load()` 用这些字段当 `SharedPreferences` 的默认值（`Settings.java:102-113`），而 layout 里那些 `android:checked` 全都会被 `setChecked(settings.…)` 覆盖（`SettingsActivity.java:114-128`）。所以**全新安装的第一次运行**就是：手动对焦（画面不会合焦）+ 曝光锁在第一帧之前就锁死 + 包围曝光模式（每次 3 张，跑三倍时间）+ 静音快门。README 在第 24、27、30 行分别警告了这三件事——但都是在用户已经踩过之后。

这一条被两轮独立分析同时判为**最严重**：它不需要用户犯任何错误，装完按一次 Start 就会毁掉一次拍摄。

**新 app 的要求：** 默认值必须是「无害且有意义」的一组（单张、自动对焦、不锁曝光、不包围），任何会改变相机行为的选项在开启前都要说清后果；并且**不许用 layout 里的 `android:checked` 假装默认值**（那一刻的真相只有代码知道）。

#### U-18 间隔在 2s 以上会系统性偏短，原因**不是快门**（主要）

这一条我最初写错了原因，值得留下更正记录：**不是「曝光+写卡被累加进间隔」**。把老 app 的计时逻辑逐行移植成脚本、跑 200 张实测之后，真实原因是两个互相独立的东西，**都与快门速度无关**——这也解释了为什么这个 bug 长期「原因不明」。

**原因 1：`remaining - 150` 让每张提前 150ms，但只在间隔 > 2s 时生效。**

```java
// ShootActivity.java:82-88
if (remainingTime <= 150) { // 300ms is vaguely the time this postDelayed is to slow
    shoot();
} else {
    shootRunnableHandler.postDelayed(this, remainingTime-150);   // ← 比剩余时间少 150ms
}
```

那 150ms 本意是补偿 `postDelayed` 的调度粗粒度，但**下一张的周期是从上一张的时刻算起**（`shootTime + interval`），少等的那 150ms 直接变成「每张提前 150ms」，而且每张都重新少等一次，于是逐张累积。

为什么只在间隔 > 2s 时出现，是这份代码里最隐蔽的一条：

```java
// ShootActivity.java:215 —— 回放时长写死 2 秒，取真实值的调用被注释掉了
pictureReviewTime = 2; //autoReviewControl.getPictureReviewTime();
// ShootActivity.java:385 —— 回放取 min(剩余时间, 2s)
long previewPictureShowTime = Math.round(Math.min(remainingTime, pictureReviewTime * 1000));
```

- **间隔 ≤ 2s**：回放被 `min` 截到「剩余时间」，回放结束后 `remaining == 0`，走 `remaining <= 150` 分支立刻拍 → 周期精确。
- **间隔 > 2s**：回放只走 2s，之后 `remaining` 仍剩 `interval − 2000`，走 `remaining − 150` 分支 → **每张早 150ms**。

实测（200 张，把老 app 的计时逻辑逐行移植成脚本跑出来的）：

| 请求间隔 | 实际平均 | 每张偏置 | 200 张累计 |
|---|---|---|---|
| 0.5 / 1 / 1.5 / **2.0s** | 精确 | **+0.0ms** | 0 |
| 2.1s | 2.000s | −100ms | — |
| **2.5s** | **2.350s** | **−150ms** | **−29.9s（−6.0%）** |
| 5s | 4.850s | −150ms | −29.9s（−3.0%） |
| 30s | 29.850s | −150ms | −29.9s（−0.5%） |

想拍 2.5s 的人拿到 2.35s；一晚上少 30 秒素材。间隔越长百分比越小，所以**短间隔的延时摄影受害最重**，而 2s 及以下完全正常——这正是它看起来像「随机漂移」的原因。

**原因 2：用的是墙上时钟，不是单调时钟。**

`ShootActivity.java` 里有 **6 处 `System.currentTimeMillis()`**、**0 处 `nanoTime()` / `elapsedRealtime()`**。相机在拍摄期间校时（开机联网校时，或用户改过时间），墙钟跳一下，`remaining` 就整体错位。实测：运行中途墙钟前跳 30s，平均间隔从 2000ms 变成 2151ms。一小时的拍摄里被校时一次，就是几秒到几十秒的偏移。

**还有一条次要的：** `pictureReviewTime` 写死 2，取真实回放时长的调用被注释掉了（`:215`）。那个 2s 是猜的，换台机身回放时长不同，**转折点就跟着移到别的间隔上**——同一份代码在不同机身上表现不同，这也解释了为什么有人报漂移、有人不报。

**新 app 的做法：** 绝对时刻表 `t(n) = start + n × interval`，只用单调时钟，且没有任何常数补偿项——需要提前量就直接算，不靠「减一点」。见 `Schedule.java`（已实现：5 张 × 2s 实测恰好 8.000s、漂移 0ms）。


## 3. 开发侧痛点（改一行会发生什么）

### 3.1 严重度总表

| # | 痛点 | 严重度 | 证据 |
|---|---|---|---|
| D-01 | 构建链断裂：jcenter 已关闭 + `-SNAPSHOT` 依赖 + Gradle 5.4.1/AGP 3.5.0 | **阻断** | `build.gradle:3,13`、`app/build.gradle:36-44` |
| D-02 | 直接 `import com.sony.scalar.*`，没有编译期 stub 就无法编译 | **阻断** | `ShootActivity.java:17`、`Display.java:5`、`BaseActivity.java:7-9,225` |
| D-03 | 零测试、零构建脚本、零开发者文档、零 CI | **阻断** | 全仓无 test/spec；无 build.sh；无 `.github/` |
| D-04 | 所有逻辑都在两个 Activity 里，业务逻辑不可测 | **阻断** | `ShootActivity` 465 行 / `SettingsActivity` 418 行，含时长换算、状态机、格式化的全部逻辑 |
| D-05 | 反射缺失 / 空 catch 遍布，失败路径全被吞掉 | 主要 | 全文件 11 处 `catch(... ignored){}` |
| D-06 | 定时器 + 回调的双定时器结构，相位与竞态不可推理 | 主要 | `ShootActivity.java:57-116,319-396` |
| D-07 | 版本号有 3 个来源且互相矛盾 | 主要 | 见下方 |
| D-08 | 死代码与注释掉的代码被留在仓库 | 中等 | `SonyDrawables.java`（23 行常量从未使用）、`SettingsActivity.java:130-137`、`ShootActivity.java:153,215,450-464` |
| D-09 | 内嵌了一份 247 行的框架 `BaseActivity`，与上游漂移 | 中等 | `com/github/ma1co/pmcademo/app/BaseActivity.java` |
| D-10 | 无效状态标志 / 空实现 / 赋值后不用 | 中等 | `takingPicture` 恒为 false；`Display.on(boolean)` 的 `autoOff` 参数；`onShutter(int i, ...)` 的 `i` |
| D-11 | 输入分发把同一个事件喂给三个控件 | 主要 | `SettingsActivity.java:384-410` |
| D-12 | 未做生命周期空检查；异常处理器只写日志 | 中等 | `ShootActivity.java:248` vs `:227`；`CustomExceptionHandler.java:16-24` |
| D-13 | 日志写到存储卡根目录，没有大小/轮转 | 次要 | `Logger.java:12-24` |
| D-14 | 权限申请与功能无关（INTERNET / WiFi） | 次要 | `AndroidManifest.xml:5-7` |
| D-15 | 机型覆盖只有作者一台 a6300；「改一行」的回归只能靠用户报 bug | 主要 | `README.md`「I have only a a6300 to test it」 |
| D-16 | `.gitignore` 拼接损坏；`local.properties` 提交了绝对路径 | 次要 | `.gitignore:6`、`local.properties:11`（作者本人的 `/home/jonas/Android/Sdk`，而它同时又在 `.gitignore:4` 里） |
| D-17 | **一次 `onResume` 做完全部事情，且拍摄计数被重置** | **阻断** | `ShootActivity.java:219-220`：`shotCount = 0;` 之后才 `postDelayed(...)` —— 任何打断都变成「从头再拍一遍」，见 U-02 |
| D-18 | **两个屏幕的预计时长算法不一致（差 3 倍）** | 主要 | `SettingsActivity.java:357` 不含 `getcnt()`；`ShootActivity.java:424` 含 —— BRC3 下设置页说 10min、拍摄页说 30min |
| D-19 | **版本码在三个发布之间冻结为 7** | 主要 | `app/build.gradle:10`（`versionCode 7` / `1.4.0`）；对比已提交 APK 的二进制清单：0.3.1→4、0.3.2→5、0.3.3→6，然后 **1.0.0→7 / 1.3.0→7 / 1.4.0→7**；git 里 `1f23085` 把它升到 8（1.1.0），`ab316cc` 又退回 7 |
| D-20 | **Release 构建被 ProGuard 混淆，但 `mapping.txt` 从不提交** | 次要 | `app/build.gradle:15-16`（`minifyEnabled true`）、`proguard-rules.pro:41-48`（`-printmapping mapping.txt` / `-repackageclasses 'fb'`）；已提交 APK 的 dex 里确实是 `Lfb/a;`…`Lfb/f;` —— 而栈追踪是这个 app 唯一的诊断通道（D-12） |

### 3.2 逐条证据

#### D-01 构建链断裂（阻断）

```groovy
// build.gradle:3-8
repositories { jcenter(); google() }
dependencies { classpath 'com.android.tools.build:gradle:3.5.0' }
// app/build.gradle:36-44
repositories { jcenter(); maven { url "https://jitpack.io" } }
dependencies {
    compileOnly 'com.github.ma1co.OpenMemories-Framework:stubs:-SNAPSHOT'
    implementation 'com.github.ma1co.OpenMemories-Framework:framework:-SNAPSHOT'
}
```

- `jcenter()` 已于 2021 年停止服务 → 依赖解析直接失败。
- `-SNAPSHOT` 表示每次构建可能拿到不同的包，没有锁文件；`package-lock.json` 那种可复现性在这里不存在。
- `gradle-wrapper.properties` 指向 Gradle 5.4.1（2019 年），它不支持本机的 JDK 17/21。本机实测：`java -version` = Zulu 21.0.12，仓库里没有任何构建脚本可以绕开 Gradle。
- `local.properties:11` 还写着作者的 `sdk.dir=/home/jonas/Android/Sdk`。

也就是说，**今天在这台机器上无法复现任何一次构建**。对照 recipe-lab：不用 Gradle，`build.sh` 七步（ndk-build → aapt → javac → d8 → aapt package → zipalign → apksigner），只用一套钉死的工具链（JDK 17 + build-tools 30.0.3 + platform 28 + NDK r16b），并且有 `docker/` 作为宿主机装不了老工具链时的替代路径。

#### D-02 直接 import 索尼类（阻断）

```java
// ShootActivity.java:17
import com.sony.scalar.hardware.CameraEx;
// Display.java:5
import com.sony.scalar.hardware.avio.DisplayManager;
// BaseActivity.java:7-9, 225
import com.sony.scalar.hardware.avio.DisplayManager;
import com.sony.scalar.sysutil.ScalarInput;
import com.sony.scalar.sysutil.didep.Gpelibrary;
```

`ss` 类的 API 在真机上存在，但**SDK 里没有**（stub 只由上面那个已死的 `-SNAPSHOT` 依赖提供）。`pmca-app` skill 的规则是「构建环境里没有索尼 stub jar，所以直接 import 编译不过」，必须走反射：

```java
Class<?> cx = Class.forName("com.sony.scalar.hardware.CameraEx");
Object cameraEx = cx.getMethod("open", int.class,
        Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions")).invoke(null, 0, null);
Camera camera = (Camera) cx.getMethod("getNormalCamera").invoke(cameraEx);
```

代价是每次调用都要过反射，但收益是：**代码在相机之外可以编译、可以单元测试、缺能力时是一个正常分支而不是崩溃**。recipe-lab 就是这么做的（`KeyProbe.java` 每次反射；`KeyProbeTest` / `KeyProbeCameraTest` 用 test double 验证「相机不在场时答案必须是 null，而不是抛异常」）。

#### D-03 零测试、零脚本、零文档（阻断）

`find` 全仓（排除 `.git`）没有任何 test/spec 文件；没有 `build.sh`、没有 CI 配置、没有 `docs/`。唯一的「开发文档」是 README 的 4 段用户说明。这意味着：

- 时长换算（`SettingsActivity.java:340-382`）、刻度→值的分段映射（`:157-289`）、burst 的结束条件（`ShootActivity.java:339`）这些**纯算术**从来没有被验证过，而它们恰恰是最容易出错的（U-07、U-08、U-10 都出在这里）。
- 新人（或 AI）想改一行，唯一能得到的反馈是「装到相机上看炸不炸」。

对照 recipe-lab 的量化差距（实测）：**`test/` 目录 2309 行（16 个测试类 + 3 个 Sony test double）对 `src/` 3818 行**，比值 0.60；其中 **12 个类 2193 行（57.4%）不含任何 `android.*` import**，因此可以在相机之外编译与测试。`tools/test.sh` 只需要 JDK 17，不需要 SDK、不需要 NDK、不需要相机。

#### D-04 逻辑与生命周期缠在一起（阻断）

`ShootActivity` 同时负责：相机生命周期、`SurfaceHolder` 回调、`ShutterListener` 回调、定时器、计数、时间格式化、电量读取、UI 更新、结束判定。结果是任何一条规则都无法被单独验证。举例，要单测「burst 模式在该停下的时候停下」，需要构造一个真实的 `CameraEx`。

**分工规则（recipe-lab 的）：** 不需要相机就能决定的逻辑放进单元类并配测试；同一个类不得出现在 Activity 里；`tools/test.sh` 编译这些类时**不提供 `android.jar`**，所以一个 `android.*` import 就会让测试任务先失败。

#### D-05 静默吞异常（主要）

全仓共 9 处 `catch`，其中 **5 处吞掉异常且不告诉用户**（`ShootActivity.java:176`、`:196`、`:208`、`:290`，`Logger.java:23`），另有 1 处在注释块里（`SettingsActivity.java:136`）：

```java
// ShootActivity.java:164-165, 176-177, 187-188, 196-198, 208-210
catch(Exception ignored) {}
catch (Exception ignored) { }
catch(NoSuchMethodError ignored) {}
catch (Exception e){ /*do nothing */ }
catch (Exception e){ /*do nothing */ }
// SettingsActivity.java:130-137（整段被注释掉：能力探测代码写了但被停用）
//try {
    //if(modifier.isSupportedSilentShutterMode())
    //    cbSilentShutter.setVisibility(View.VISIBLE);
/*} catch(Exception ignored) {}*/
// ShootActivity.java:290
catch (IOException e) {}
```

两个把变量命名为 `ignored` 的 `catch` 直接说明作者的意图。唯一的例外是 `Display.off()`（`Display.java:45-54`），它在关屏失败时回退到 `DEVICE_ID_PANEL` 并记日志 —— 这是全仓唯一一处「失败时降级并记录」的写法。

用户看到的现象与代码里的原因之间没有任何可见的联系（U-06、U-09 都是它的直接后果）。skill 的要求是「用 `catch (Throwable)` 包住每一次反射调用并**降级**：反射失败是正常分支，不是崩溃」—— 降级意味着要告诉用户，而不是假装成功。

#### D-06 双定时器互相接力（主要）

`shootRunnable`（Handler 自重排）与 `onShutter()`（相机回调）互相 `post`/`postDelayed`，共享 3 个可变字段（`stopPicturePreview`、`burstShooting`、`shotCount`）与一个 `AtomicInteger brck`；`brck` 还会被赋值为全新对象（`ShootActivity.java:321-326`），这在内存模型上没有意义。时间判断里还写死了两个魔数：

```java
// ShootActivity.java:82-83
if (remainingTime <= 150) { // 300ms is vaguely the time this postDelayed is to slow
    ...
} else {
    shootRunnableHandler.postDelayed(this, remainingTime-150);
}
```

注释里的 300 和代码里的 150 不一致，且注释本身用「vaguely」描述时序假设。对照 recipe-lab 的做法：拍摄循环放在 Activity 的 `Handler` 上（而不是线程），但**状态机与时间数学**是纯函数并配测试，循环只负责「问纯函数下一步做什么、设一个定时器」。

#### D-07 版本号三个来源互相矛盾（主要）

| 位置 | versionCode | versionName |
|---|---|---|
| `app/build.gradle:10-11` | 7 | 1.4.0 |
| `app/release/output.json` | 7 | 1.3.0（且文件名是 `TimeLapse-release-1.3.0.apk`） |
| 已提交的 APK 文件名 | — | 最高 `1.4.0` |
| git tag | — | `v.1.4.0`（注意是 `v.` 不是 `v`） |
| `app/build.gradle.new`（连文件名都还是 `.new`，却停在旧值） | 4 | 0.3.1 |

recipe-lab 的规则正好相反：`AndroidManifest.xml` 的 `versionName` 是**唯一**来源，`versionCode = MAJOR*10_000_000 + MINOR*100_000 + PATCH*1_000 + P`（正式版 P=999），构建写一份清单副本到 `out/` 而**从不改动仓库里的清单**，`tools/check-version.sh` 会在任何地方冒出第二个版本号时让 CI 失败。

#### D-09 内嵌框架副本（中等）

`app/src/main/java/com/github/ma1co/pmcademo/app/BaseActivity.java`（247 行）与 `AppNotificationManager.java`（32 行）是 PMCADemo 的拷贝，不是依赖。它带来两个问题：一是与上游漂移（上游后来加了按键探测、`Hold` 超时等）；二是它把 **TRASH = 退出** 这个默认行为带进了 app（U-01 的一半原因）。recipe-lab 的选择是：不拷贝框架，自己写 `Keys.java` 定义扫描码与手势（`Keys.HOLD_MS = 600`），并**吞掉**会影响固件行为的键。

#### D-10 无效标志与空实现（中等）

```java
// ShootActivity.java:129 —— 初始化
takingPicture = false;
// ShootActivity.java:294-295 —— 唯一的读取处
if(takingPicture) return;     // 永远不成立：全仓没有 takingPicture = true
```

```java
// Display.java:61-68 —— on(boolean autoOff) 的 autoOff 参数从未被调用方使用
public void on(boolean autoOff) { ... if(autoOff && autoOffDelay > 0) {...} }
// 调用处：ShootActivity.java:85, 91, 248 全部是 display.on()
```

```java
// ShootActivity.java:319 —— 参数 i 从未使用
public void onShutter(int i, CameraEx cameraEx) {
```

#### D-11 一个事件喂三个控件（主要）

```java
// SettingsActivity.java:384-410 —— 四种拨盘，每一种都把增量同时送给三条刻度条
protected boolean onUpperDialChanged(int value) {
    sbInterval.dialChanged(value);
    sbShots.dialChanged(value);
    sbDelay.dialChanged(value);
    return true;
}
```

「哪一个会真的变」取决于 Android 的焦点状态，而焦点不可见（U-12）。这不是风格问题：**在真机上转一下拨盘，用户可能同时改掉间隔、张数、延时三个值**，而屏幕只显示其中一个的更新结果（另外两个也会重算 `updateTimes()`，但用户没有理由怀疑它们变了）。

#### D-12 生命周期与崩溃处理（中等）

见 U-02 的 `display.on()`（`ShootActivity.java:248` vs 赋值处 `:227`）。另外：

```java
// CustomExceptionHandler.java:16-24
public void uncaughtException(Thread t, Throwable e) { ...Logger.error(stacktrace); defaultUEH.uncaughtException(t, e); }
```

它只把栈写进 `TIMELPS/LOG.TXT` 就转交默认处理器 —— 用户不会看到任何提示，而日志在存储卡根目录（`Logger.java:12-13`），需要拔卡用电脑读。对比 recipe-lab 的 `getFilesDir()` 内文件 + 屏上 key logger（`docs/DEVELOPMENT.md:119`：每个按键的扫描码/按下抬起/repeat/Sony 逻辑码都画在屏上并追加进 `keys.txt`）。

#### D-15 回归只能靠用户（主要）

`README.md` 开头：「I have only a a6300 to test it, if you have another camera I would be happy to receive bug reports.」加上「Known Issues」里列出的 RX100 M4 崩溃/黑屏报告，说明这个项目的验证闭环是**用户报 bug → 作者猜 → 再发版**。而 recipe-lab 把这件事变成了基础设施：按键探测问相机（不维护机型表）、`KeyProbe` 相机测试用 double、兼容性报告是一个 issue 模板、README 里有 ✅/❔ 兼容表（A6000/A6500/A5100/A7 II/A7/A6300/…/RX100 V 共 15 台已确认）。这些做法可以整体搬到新 app。

---

## 4. 关键输入模型对照表（老 app 实际消费的键）

| 键 | 扫描码 / 来源 | 老 app 的行为 | 问题 |
|---|---|---|---|
| 滚轮 / 上/下/左/右 | `BaseActivity.java:55-62,89-100`（`ScalarInput.ISV_*`） | 交给 Android focus 系统；`SettingsActivity` 不覆盖 | 焦点不可见（U-12），拨盘一次改三行（D-11） |
| 中心（ENTER） | `BaseActivity.java:63-64,121-122` | 不覆盖 → 默认点击行为 | 依赖 focus 落在哪个控件上 |
| MENU | `BaseActivity.java:69-71,127-129` | `ShootActivity.java:241-244`：抬起 = `onBackPressed()` | **直接退出，不能暂停**（U-01） |
| TRASH（DELETE/SK2） | `BaseActivity.java:84-86,198-204` | 未覆盖 → 抬起 = `onBackPressed()` | **同样直接退出**（U-01） |
| 快门 S2 | `BaseActivity.java:76-77,134-135` | 未覆盖 | 拍摄中按快门会被固件消费，app 不知道 |
| Fn / AEL / C1 / DISP / PLAY / MOVIE | `BaseActivity.java:65-82` | 未覆盖，全部返回 false → 交给固件 | 相机可能弹出自己的 UI，把 app 顶到后台 → 触发 U-02 |
| 拨盘 1/2/3/KURU | `BaseActivity.java:89-100` | `SettingsActivity` 覆盖 4 种；`ShootActivity` 不覆盖 | 拍摄界面里转拨盘会打到固件 |

**规则（来自 skill 与 recipe-lab）：** 每个功能都必须落在**所有机身都有**的键上（滚轮、四向、中心、MENU、TRASH、快门）；Fn 只作为快捷键，并且**只在探测报告存在时**才画进图例；会影响固件的键要主动吞掉。

---

## 5. 老 app 值得保留的部分

不是所有东西都要重写。以下设计是对的，新 app 应该继承：

| 保留 | 理由 | 证据 |
|---|---|---|
| 主循环用 Activity 的 `Handler` 而不是自建线程 | 相机 API 与 UI 都在主线程，避开了同步问题 | `ShootActivity.java:57,110`；recipe-lab 也是这个选择（`docs/DEVELOPMENT.md:166-167`） |
| 用 `ShutterListener` 而不是盲等固定时间 | 相机自己知道什么时候真的拍了 | `ShootActivity.java:150,319` |
| 关屏（DOFF）作为省电/护屏选项 | 长延时拍摄确实需要 | `Display.java:45-54` |
| 停止时把 auto power off 恢复 | 相机设置不能留在 app 状态里 | `ShootActivity.java:233,280` |
| LENR / 崩溃场景写进 README | 作者诚实记录了已知问题 | `README.md`「Known Issues」 |
| 把「先设好相机，再用 app」讲清楚 | 这是 PMCA app 的正确分工 | `README.md`「Usage」 |

---

## 6. 结论：新 app 必须解决的 11 件事

| # | 目标 | 对应痛点 | 验收方式（真机） |
|---|---|---|---|
| 1 | 拍摄**可暂停 / 可停止**，任何终止都有确认，且不丢会话 | U-01, U-13 | 拍摄中按 TRASH 暂停、继续、停止，全程不退出 app；停止后有会话日志 |
| 2 | 会话与计划**落盘**，`onPause` 不再等于终止；恢复时**按剩余时间继续**而不是从头再来 | U-02, U-13, D-17 | 拍摄中触发一次相机弹窗/插 USB，回来后拍摄继续且相位正确、张数不归零 |
| 3 | 计划与「相机该怎么拍」写进**相机设置存储**，app 关闭后依然生效 | U-03 | 设置 → 退出 app → 关机再开机 → 值仍在（skill 第 5 阶段红测） |
| 4 | **绝对时刻表**：周期不随拍摄耗时漂移，「来不及」是一件可见、可记录的事 | U-18 | 拍 100 张 × 2s：平均间隔与漂移显示吻合实测；人为拖慢相机时屏上出现告警而不是静默背靠背 |
| 5 | 张数由**存储卡上的文件**确认，故障可见、有重试预算 | U-06 | 拔卡/塞满卡拍摄，app 在 N 秒内报故障并停止，不虚报张数；单张失败先重试，连续 3 次才停 |
| 6 | 完成屏 = 结果 + 下一步，不是捐款页 | U-04 | 完成屏显示张数/耗时/平均间隔/目录/告警，并提供重拍入口 |
| 7 | 每个开关都有全名 + 后果 + 生效状态（能力探测），默认值无害 | U-05, U-09, U-17 | 在不支持 silent shutter 的机身上该行显示为不可用并写明原因；全新安装的默认值不会改变相机行为 |
| 8 | 全中文界面（含 CJK 字体），新增语言只需加一张表 | U-11 | 切到简体/繁体，全屏无缺字（含 `LangTest` 字形覆盖） |
| 9 | 自绘 UI + 确定的按键模型，覆盖所有机身，且**吞掉会让固件动手的键** | U-12, D-11, U-01 的三点 | 键盘走查：滚轮/左右调值、上下换行、中心执行、TRASH 暂停、hold TRASH 停、hold MENU 菜单；固件不会同时收到 MENU / TRASH / 快门 |
| 10 | 相机外可测试：纯逻辑单元测试 + 七步构建脚本，无 Gradle | D-01..D-04, D-05 | `./build.sh` 输出 `BUILD OK`；`./tools/test.sh` 全绿；改动不碰相机即可验证时序数学 |
| 11 | 发布可维护：版本码唯一来源 + 固定证书 + APK 不进仓库 | D-19, D-20, U-14 | 从 dev 版覆盖安装到 release 版成功（不卸载）；`tools/check-version.sh` 能在版本号冒出第二处时失败 |

---

## 附录 A：证据等级与未能验证的事项

**已读源码（第一手）：** `TimeLapse/` 下全部 9 个自有 Java 类、2 个内嵌框架类、2 个 layout、全部 `values/`、`AndroidManifest.xml`、`build.gradle`（含 `.new`）、`settings.gradle`、`local.properties`、`proguard-rules.pro`、`gradle-wrapper.properties`、`app/release/output.json`、`.gitignore`、git 历史与 tag。

**已读对照源码：** `recipe-lab-sony-pmca/` 的 `docs/DEVELOPMENT.md`、`docs/PMCA-APP-DEVELOPMENT.md`、`CLAUDE.md`、`AndroidManifest.xml`、`README.md`（兼容性与用法）、`jni/jni.cpp`、`jni/Android.mk`、`jni/Application.mk`；`Sony-PMCA-RE/pmca/platform/__init__.py`（`bk r/w/s/lock/unlock` 命令）。

**社区报告（未在真机复现，只引用标题/讨论）：** [Issue #49 无法停止或关机](https://github.com/jonasjuffinger/TimeLapse/issues/49)、[Issue #41 BRC3 计数错误](https://github.com/jonasjuffinger/TimeLapse/issues/41)、[Issue #43 运行时崩溃](https://github.com/jonasjuffinger/TimeLapse/issues/43)、[Issue #11 A7 II 无法安装](https://github.com/jonasjuffinger/TimeLapse/issues/11)、[dpreview: a6000 间隔时间问题](https://www.dpreview.com/forums/threads/time-lapse-app-interval-time-problem-on-a6000.4419262/)。

**本轮未能验证（需要真机，见 PLAN.md 的环境前置）：**
- 本机没有连接相机（`lsusb` 无 Sony 设备，`adb devices` 为空），所有相机行为结论都来自 skill 与 recipe-lab 的实测记录，不是我在本轮测的。
- 本机没有 `docker`，`/opt/android-sdk` 下没有 build-tools / platform / NDK，`java` 是 21 而不是 17；`Sony-PMCA-RE` 缺 `pycryptodomex`（`pmca-console.py` 因此无法启动）。构建与安装链路本身尚未在本机跑通，这是下一阶段（M0）的第一件事。
