# better-timelapse 规划书（v1）

| 项目 | 值 |
|---|---|
| 目标 | 一个装在索尼相机上、能在三脚架上连续工作数小时、不需要电脑的延时摄影 app |
| 参照 | 老 app：`TimeLapse/`（痛点见 [PAIN-POINTS.md](PAIN-POINTS.md)）；工程方法：`recipe-lab-sony-pmca/` |
| 平台 | PlayMemories Camera Apps（PMCA），Android 2.3.7 / API 10，armeabi，无触摸屏，单横屏 |
| 本机状态 | 未接相机；`docker`、旧 SDK/NDK、JDK 17 均缺失（见 §9 环境前置） |
| 本文档的用途 | 这份文档是**施工蓝图**：架构、界面规格、里程碑与验收条件。里程碑 M0 之后每一步都必须有真机证据 |

---

## 1. 设计目标与不做的事

### 1.1 目标（按优先级排序）

| # | 目标 | 为什么排这个位置 |
|---|---|---|
| G1 | **一次拍摄能安全地跑到最后**：可暂停、可停止、被打断后能继续、掉电/崩溃后能知道发生过什么 | 老 app 的头号痛点是「无法停止」与「任何打断都终止」（PAIN-POINTS U-01/U-02），这是「敢不敢用」的问题，先于一切功能 |
| G2 | **说过的话要算数**：张数就是存储卡上的文件数，故障必须说出来 | U-06（虚报张数）、U-09（静默忽略不支持的能力） |
| G3 | **相机自己能记住**：计划写进相机设置存储，app 关掉、关机再开机依然生效 | U-03，也是 PMCA app 相对手机的唯二优势（另一次是「不用电脑」） |
| G4 | **零学习成本的中文界面**：每个开关写明后果，值有单位、有精度、可预期 | U-05、U-08、U-11 |
| G5 | **相机之外可以验证逻辑**：时序数学、格式化、按键映射都有单元测试；七步脚本一条命令构建 | D-01..D-04 |
| G6 | **写坏相机的概率尽量低**：只写已知含义、已知原值的单字节槽位；写前查只读位；不碰固件 | skill 的 tripwire 与 recipe-lab 的安全规则 |

### 1.2 明确不做的事（非目标）

| 不做 | 理由 |
|---|---|
| 手机 App / WiFi 遥控 | 这是另一类产品（Sony 官方 Imaging Edge 的地盘）。本 app 的价值是「不需要第二台设备」 |
| 机内合成视频 / 生成 MP4 | API 10 上做视频编码不现实；app 只负责拍，合成交给电脑。屏上只做**预估**并明确标注是预估 |
| 支持 2016 年底之后发布的机身 | 固件签名 + 菜单里没有 Application，任何工具都装不进去（skill §1.1）。不做固件刷写 |
| 支持 OpenMemories-Tweak 之外的越狱 | 不碰固件、不碰系统分区 |
| Gradle / Android Studio 项目结构 | 工具链要求（JDK 17 + NDK r16b + armeabi）与 Gradle 的现代版本互相冲突；recipe-lab 已经证明七步脚本更稳 |
| 用机型表猜能力 | 没有一张机型表能被维护到可信（skill、recipe-lab 都是这个结论）。一律**问相机** |

---

## 2. 从老 app 与 recipe-lab 各自拿什么

| 从老 app 继承（设计是对的） | 从 recipe-lab 拿来（新时代经验） |
|---|---|
| 拍摄循环跑在 Activity 的 `Handler` 上，不自建线程 | 无 Gradle 的七步 `build.sh` + 钉死的工具链 + `docker/` 兜底 |
| 用 `ShutterListener` 而不是盲等固定时间 | `AndroidManifest.xml` 是版本号唯一来源 + 版本码公式 |
| 关屏（DOFF）作为长拍摄的省电选项 | 纯逻辑类不得 import `android.*`（`tools/test.sh` 不提供 `android.jar`） |
| 退出时恢复 `setAutoPowerOffMode(true)` | Sony 类一律反射 + 相机不在场时返回 null 而不是抛异常 |
| 「先在相机里设好参数，再用 app」的分工 | 按键探测（不维护机型表）+ 图例只画机身真有的键 |
| 诚实写出已知问题 | Java 文本表 + 打包裁剪 CJK 字体 + `LangTest` 查字形覆盖 |
| | 自绘 Canvas UI（无控件焦点歧义） |
| | 应用内开发者菜单（快照/差分/只读检查/按键日志） |
| | 约定式提交 + 语义化版本 + CI 门禁 + `CLAUDE.md` 给 agent 的规则 |

---

## 3. 架构

### 3.1 分层与模块

原则：**能不用相机决定的逻辑，全部放进纯类**。纯类是 `src/` 下不 import `android.*` 的类，被 `tools/test.sh` 用裸 JDK 编译，每一个都配测试。相机、UI、JNI 是外壳。

```
AndroidManifest.xml            包名 com.voxivoid.bettertimelapse（待定，见 §3.4）
jni/jni.cpp                    四个 JNI 函数：read / write / attr / sync（settings store）
jni/platform/                  git 子模块：ma1co/OpenMemories-Platform

src/com/voxivoid/bettertimelapse/
  ── 纯逻辑（可离机单测，禁止 android.*） ──────────────────────────────
  Plan.java          拍摄计划的模型：字段、默认值、不变量、序列化/反序列化、单位换算
  Steps.java         间隔与张数的档位表：值 ⇄ 档位双向映射，含「下一格增量」
  Engine.java        拍摄状态机（纯）：给定 now 与事件，返回「下一步该做什么」
  Schedule.java      时间数学（纯）：**绝对时刻表** `t(n) = start + n × interval`、剩余时间、已用时间、
                     实测平均间隔与漂移、预计结束时刻。输入只有 long 与 Plan，因此能被毫秒级单测钉死
  Frames.java        张数核算：已发送快门数 vs 已落盘文件数、故障判定（卡满/写失败）
  Session.java       会话日志的读写格式：启动、每张、暂停、停止、故障、恢复点
  Preflight.java     预检规则：相机状态 + 能力 + 存储卡的告警清单（纯判定）
  Hud.java           状态行、进度条、倒计时、完成摘要的文本（纯）
  Keys.java          扫描码、按下/长按手势、图例内容（纯）
  KeyProbe.java      问相机有哪些键 / 机型 / 平台（反射，离机全返回 null）
  Lang.java          语言选择与查表入口；TextEn / TextZhHans / TextZhHant 三张表
  DevTools.java      应用菜单与开发者菜单的行定义（纯）
  Store.java         设置存储的槽位表 + 编码/解码 + 只读检查（纯，写操作走 NativeStore）
  ── 外壳（需要相机或 Android） ──────────────────────────────────────
  MainActivity.java  唯一的 Activity：生命周期、把按键转成 Engine 事件、相机持有、写存储
  Camera.java        所有 Sony 反射的唯一入口（CameraEx / ParametersModifier / ScalarInput / ScalarProperties）
  NativeStore.java   JNI 声明
  NativeException.java
  PanelView.java     Canvas 自绘主面板（行、值、选中高亮、状态、进度条）
  MenuView.java      Canvas 自绘全屏菜单（应用菜单 / 开发者菜单 / About / 按键日志）
  PromptView.java    确认对话框（默认落在 Cancel）
  Legend.java        按键图例（只画机身真有的键）
  UiFont.java        各语言字体选择
  Display.java       屏幕开关（反射 DisplayManager），带 null 安全检查
res/                 layout（只有一个）、drawable、launcher icon
assets/fonts/        裁过的 Noto Sans CJK SC/TC（OFL）
res/raw/ids.txt      快照/差分工具用的槽位清单
test/                JUnit 5 测试 + Sony 类的 test double
tools/test.sh        单元测试（只需 JDK 17）
tools/version.sh     版本码计算    tools/check-version.sh  版本唯一性门禁
build.sh / build.cmd 七步构建（两个文件必须同步修改）
docs/                本目录：痛点、规划、平台事实、开发手册
```

**模块深度要求**（什么时候算合格）：每个纯类的公开接口应当能用一句话说明，并且**包含它自己的全部复杂度**。反例是 `Engine` 变成「把 Activity 里的 if 搬了个地方」——那没有价值。正例是：

```java
// Engine 的接口形状（示意）
Step next(State s, Event e, long nowMs);   // 纯函数：状态 + 事件 + 时刻 → 下一步动作
// Step 是 Idle | Wait(untilMs) | Release | Expose(frameIndex) | Settle | Finish(outcome) | Fault(cause)
```

有了这个接口，`EngineTest` 可以在毫秒级验证「间隔 2s、曝光 1.5s、第 3 张时卡满」这类场景，而**不需要相机**。

### 3.2 相机接口：反射封装在一处

构建环境没有索尼 stub，因此所有 Sony 调用只允许出现在 `Camera.java` 里，用反射：

| 需要的能力 | 反射目标 | 失败时的降级 |
|---|---|---|
| 打开相机 | `CameraEx.open(0, null)` → `getNormalCamera()` | 显示「相机不可用」+ 原因，不崩溃；`onPause` 里 `release()` |
| 快门 | `CameraEx.burstableTakePicture()` / `cancelTakePicture()` / `setShutterListener` | 无快门 = 不可拍摄，直接进故障态 |
| 拍摄参数 | `Camera.Parameters` 的索尼字符串键（`color-mode` 等）与 `createParametersModifier` | 逐项 try，失败的项记进告警清单 |
| 静音快门 | `ParametersModifier.setSilentShutterMode` + `isSupportedSilentShutterMode` | 不支持则该开关标记为「本机身不可用」 |
| 驱动模式 | `setDriveMode` / `setBurstDriveSpeed` / `setBurstDriveButtonReleaseBehave` | 不支持则 burst 模式标记不可用 |
| 曝光锁 | `setAutoExposureLock` | 不支持则 AEL 行标记不可用 |
| 按键 | `ScalarInput.getKeyStatus(scan).valid` / `.status` | 探测不到 = 该键不存在，图例不画 |
| 机型 | `ScalarProperties.getString("model.name")` / `"version.platform"` | 显示为 `unknown`（**不要**用 `Build.MODEL`，它在所有机身上都是 `ScalarA`） |
| 屏幕 | `DisplayManager.switchDisplayOutputTo(...)` | 关屏不可用 = 该选项标记不可用，不影响拍摄 |
| 自动关机 | 广播 `com.android.server.DAConnectionManagerService.apo`，`APO/NO` / `APO/NORMAL` | 与老 app 相同，失败只记日志 |

**规则：** 每一次反射调用都包在 `catch (Throwable)` 里，失败是一个**正常分支**，必须让用户看得见（告警清单），而不是静默假装成功（老 app 的 D-05）。

### 3.3 设置存储：app 的「持久化大脑」

`Store.java` 负责槽位表与编码（纯、可测），`NativeStore.java` + `jni/jni.cpp` 负责真正的读写：

| 内容 | 编码 | 说明 |
|---|---|---|
| 计划（间隔、张数、延时、选项） | 待测：需要发现/确认槽位 ID | **M2 的现场测量任务**：用快照/差分工具找出存放 app 计划的位置 |
| 已有相机设置（静音快门、驱动模式、AEL 等） | 复用 recipe-lab 已确认的槽位表 | 这些**不属于 app 的私有数据**，改动它们就是改动相机，必须在退出时遵守「退出规则」 |

**内存放计划有三种方案，M2 现场测量后择优：**

| 方案 | 做法 | 优点 | 风险 |
|---|---|---|---|
| A. 自有槽位 | 用快照/差分找出一个未被相机使用的槽位，把计划编码成若干字节 | 真正的「相机自己记住」，关机再开机仍在 | 槽位可能与别的设置冲突；需要真机验证不会被相机覆写 |
| B. 相机目录文件 | 把计划写成 `getFilesDir()` 下的文本文件 | 简单、容量大、无冲突 | 卸载即丢失；不是「相机记住」 |
| C. 混合 | 计划存文件，同时把「要用的相机参数」写进设置存储 | 兼顾持久与安全 | 需要设计两处的一致性 |

**默认选 C**：计划本身（app 的数据）存文件，只有「相机该怎么拍」那部分（静音快门/驱动/AEL 等）写设置存储。理由：方案 A 需要占用相机槽位，风险与工作量都最大，而用户真正抱怨的「相机自己记住」指的是**相机的拍摄设置**（老 app 的 U-03），不是 app 的 UI 状态。M2 会实测方案 A 是否可行，可行再升级。

**写存储的安全规则（照抄 recipe-lab，不可协商）：**

1. 写前对**本次写入将触及的每一个槽位**调 `Backup_get_attribute`，只要有一个带只读位就**全部不写**（半写会留下半个值）；相机答不上来（`attr < 0`）的槽位按「可写」处理，因为真正的拒绝会在写路径上报出来，探测失败不该阻止功能。
2. 只写单字节、含义已知、原值已知的槽位。
3. 失败信息要写明「哪个槽位 + 十六进制 ID + 已经写进去多少字节」。
4. **至少写进去 1 个字节才调 `Backup_sync_all()`**（空写入不需要同步）。
5. 写完后**重新读回并重新应用到实时预览**，这样屏上显示的就不能是假的（recipe-lab 因此废掉了会撒谎的 `PROTECTED` 徽章）。
6. 退出时把实时参数设为**已存储**的值，而不是 app 启动时的快照（否则相机会把自己写回存储的值撤销）。
7. 不碰固件。

### 3.4 命名与环境

| 项目 | 值 |
|---|---|
| 包名 | `com.voxivoid.bettertimelapse`（待确认：也可以用 `com.bettertimelapse`；首次真机安装后**不可更改**，因为证书/包名决定能否覆盖安装） |
| 应用显示名 | 待定（`Better Timelapse` / `延时摄影`；`AndroidManifest` 里 `@string/app_name`） |
| minSdk / targetSdk | 10 / 10（安装器在 >10 时警告甚至失败） |
| ABI | `armeabi` only |
| 签名 | 只用 v1（`--v1-signing-enabled true --v2/v3 false --min-sdk-version 10`）；构建最后一步必须 `apksigner verify` 自己刚产出的 APK + 写 `.sha256`（老 app 曾经发布过一个只有 v2 签名、因而装不上的 APK：见 [PLATFORM-FACTS](PLATFORM-FACTS.md) §3） |
| 版本码 | `MAJOR*10_000_000 + MINOR*100_000 + PATCH*1_000 + P`；正式版 `P=999` |
| 版本号唯一来源 | `AndroidManifest.xml` 的 `versionName`；构建写副本到 `out/`，**不改仓库里的清单** |

---

## 4. 界面规格：一个 Activity，两块屏

相机没有触摸屏，所有输入来自按键；机型之间的键位差异（Fn/AEL/C1/DISP 时有时无）必须在设计里被吸收，而不是靠机型表。

### 4.1 按键模型

直接继承 recipe-lab 已经验证过的手势模型（`Keys.Hold`：`IGNORE / ARM / SHORT / NONE / HOLD` 五态，`oneShot()` 丢弃重复事件，`trashHoldActs()` 处理「机身丢失松键」）。**与 recipe-lab 保持「方向键 = 换行、左右 = 改值」的一致语义**，这样两个 app 的操作记忆可以互用。

| 功能 | 所有机身都有 | 快捷键（探测到才画进图例） |
|---|---|---|
| 移动到上一行 / 下一行 | 上、下 | 顶部转盘 |
| 调整当前行的值（**小格**） | 左、右 | — |
| 调整当前行的值（**大格**，用于快进） | 滚轮 | — |
| 开始 / 确认 / 停屏 | 中心（抬起时执行） | 快门 |
| 暂停 / 继续拍摄 | TRASH | — |
| 停止拍摄（**先确认，默认 Cancel**） | hold TRASH（600 ms） | — |
| 打开菜单（重拍 / 预设 / 语言 / 预检 / 关于 / 开发者） | hold MENU（600 ms） | Fn |
| 退出 app（拍摄中先守卫） | MENU（抬起） | — |
| 记录「这张重拍」（打标记） | hold 中心 | — |

**手势细节（照抄 recipe-lab 的实现，不重新发明）：**

1. `HOLD_MS = 600`；长按用 `Handler.postDelayed` 在按下时布置、松键时取消，**不依赖固件的按键重复事件**。
2. `getRepeatCount() > 0` 时，中心 / MENU / TRASH / Fn 的重复事件直接丢弃（否则「长按 Fn」会先开列表再关列表）；四向与转盘保留重复（按住就是连续移动）。
3. 短按动作在**抬起**时执行；已经触发过长按的那一次抬起被吞掉。
4. `onPause` 清空全部长按状态，避免「按键卡住」。
5. 「松键丢失」的机身：`ScalarInput.getKeyStatus(scan).status` 报告键已经抬起时，已触发的长按按短按处理；相机答不上来时就当长按（最坏情况是一个默认落在 Cancel 的确认框）。
6. **长按不写进图例**：它是给需要的人准备的，不是邀请。

**吞掉（不让固件处理）的键：** AEL、C1、DISP、PLAY —— recipe-lab 的结论是这些键在机身上差异太大（A5100/A5000 没有、A7S II 上 AEL 无效、DISP 在部分机身会以 608 出现并偷走「滚轮上」，而在 wheel-top 机身上「滚轮上」其实是 `K_UP`），所以一律吞掉，只在按键日志里显示。MOVIE 与变焦杆放行（不干扰本 app）。

### 4.2 主屏（拍摄屏）

一屏画完，无滚动；自绘，行高固定，选中行反色 + 左侧箭头。纵向布局：

```
┌─────────────────────────────────────────────────────────────────┐
│ 状态   WAITING 00:03:12        BAT 78%     卡: 1.2k 张余量        │  ← 状态条：当前状态 + 到下一张的倒计时 + 电量 + 存储卡余量
├─────────────────────────────────────────────────────────────────┤
│ 间隔      00:02.0 s     滚轮 ±0.5s  ←→ ±0.1s   ▸                │  ← 选中行：名称 + 值 + 单位 + 两档增量
│ 张数        300 张      滚轮 ±10    ←→ ±1       │                │  ← 未选中行：只显示值
│ 初始延时    00:00:00    滚轮 ±1min  ←→ ±10s     │                │
│ 稳定延时    1.2 s       左/右 循环 0.8/1.2/2.0/3.0/5.0 s         │
│ 静音快门    ON          （已探测：支持）        │                │
│ 驱动模式    SINGLE      不支持的取值不进循环    │                │
│ AEL         OFF                                 │                │
│ 关屏        ON          每张之后关屏            │                │
├─────────────────────────────────────────────────────────────────┤
│ 进度  ████████░░░░░░░░░░░░  112 / 300 张   已用 00:04:31          │  ← 拍摄中才有：真实落盘张数 / 计划张数
│ 平均间隔 2.04 s   漂移 +0.04 s/张   预计 00:06:12 后结束          │  ← 实测数据，不是理论值
├─────────────────────────────────────────────────────────────────┤
│ 滚轮 大格调值 · ←→ 小格调值 · ↑↓ 换行 · 中心 开始 · TRASH 暂停 · hold TRASH 停 · MENU 退出 │
└─────────────────────────────────────────────────────────────────┘
```

**行的含义（每行都要能被单独解释，不留缩写）：**

| 行 | 值域与步进 | 说明 |
|---|---|---|
| 间隔 | 0.1s ~ 24h | 两次快门之间的目标时间 |
| 张数 | 1 ~ 9999，或 `不限` | `不限` = 拍到停止或卡满才结束 |
| 初始延时 | 0 ~ 24h | 按开始到第一张的时间 |
| 稳定延时 | 0.8 / 1.2 / 2.0 / 3.0 / 5.0 s | 每张之后留给相机把参数与预览跟上的时间。recipe-lab 用同一组值（`DevTools.SETTLE_MS`），并明确写「正确的值是**本机身的属性**，不是这张表的属性」—— 我们的默认值也必须靠真机测量确定，不能照抄 |
| 静音快门 | 开 / 关 | 探测不到 `setSilentShutterMode` 时整行灰掉并写明原因 |
| 驱动模式 | SINGLE / BURST / BRACKET3 | 只列出**探测到支持**的取值；`BURST` 用于「间隔 = 0 的连拍」，`BRACKET3` 用于包围曝光 |
| AEL | 开 / 关 | 第一张之后锁定曝光（长片的明暗一致性） |
| 关屏 | 开 / 关 | 每张之间关闭 LCD（省电、护屏、避免屏幕光污染画面） |

**关键交互决定：**

1. **值必须可预期。** 每个值行显示三样东西：值 + 单位 + 「格增量」。左/右走**小格**（该量程下的最细步进），滚轮走**大格**（`Steps.java` 里的档位表，双向映射，被 `StepsTest` 钉死）。老 app 的「转一格跳 30 秒」在这里不可能发生。
2. **不支持的功能要写原因。** 静音快门 / 驱动模式 / AEL 行如果探测失败，行文字变成 `静音快门 不可用（本机身未报告该能力）`，并且**不能被选中修改**。老 app 是静默忽略（U-09）。
3. **进度用真实落盘张数。** 计划张数旁边显示 `已落盘`，而不是「已按快门」。两者不一致时把差值标黄，并在故障条里写原因（U-06）。
4. **重试预算。** 单张失败先重试 1 次；**连续 3 次**仍失败才停止拍摄并写清单，中间成功一次就清零。recipe-lab 的连拍是「一次失败就结束整轮」，对一晚的拍摄太脆；但也不能无限重试 —— 真正的坏链路必须在几十秒内被发现而不是耗到早上。
4. **倒计时用单调时钟，时刻表是绝对的。** `Schedule.java` 从 `System.nanoTime()` 出发，第 n 张的目标时刻 = `start + n × interval`（**不是**「拍完之后再等 interval」）。老 app 是后者，于是曝光 + 写卡 + 回放的耗时被累加进每一个间隔，几百张后相位大幅偏移，而且相机比间隔慢时它会静默改成背靠背连拍（U-18）。漂移不累积之后，「这一张来不来得及」就变成一个可以显示、可以记录、可以让用户改参数的事件。
5. **状态词固定七个：** `IDLE / ARMED / WAITING / EXPOSING / PAUSED / DONE / FAULT`，每个状态在状态条上有一句人话解释。
6. **拍摄开始前给 3 秒倒计时**，让站在相机旁的人有时间离开画面（老 app 从按中心到第一张之间没有缓冲，只有分钟级的 `Delay`）。
7. **面板可见性三态**（同 recipe-lab）：`Full / Label / Hidden`，TRASH 单击循环切换 —— 长曝光时可以把面板缩到只剩一行，避免屏幕光污染画面。

### 4.3 菜单屏（hold MENU）

| 行 | 行为 |
|---|---|
| **继续 / 重拍** | 用当前计划再拍一次（拍摄中显示为「回到拍摄屏」） |
| **保存为预设 / 载入预设** | 3 个预设位（例如「星空 20s×300」「云 3s×1000」），存文件 |
| **语言** | `Auto / English / 简体中文 / 繁體中文`，当场重绘，存 app 偏好 |
| **面板可见性** | `Full / Label / Hidden`（同 recipe-lab：TRASH 单击循环） |
| **预检** | 跑一次完整预检（相机能力 + 卡 + 相机状态 + 试拍一张），结果可滚 |
| **关于** | 版本（从 `PackageManager` 读，源码里不写版本字符串）、`model.name`、`version.platform`、源码链接 |
| **开发者 >** | 见 4.4 |

### 4.4 开发者菜单（这是「不用电脑」的关键）

| 行 | 行为 |
|---|---|
| **设置快照 / 设置差分** | 照抄 recipe-lab：第一次按写 `snapshot.bin`（读 `res/raw/ids.txt` 里每个 ID），去相机菜单**只改一项**，回来第二次按得到 `id:old>new` 并写 `diff.txt` —— 这是发现槽位 ID 的唯一可靠方法 |
| **只读检查 N 个槽位** | 对每个将要写的槽位读 `Backup_get_attribute`，把 `01070175 STYLE attr=0` 这类行写进 `locks.txt` |
| **相机能力清单** | 逐项打印探测结果：机型、平台、每个键是否 `valid`、静音快门/驱动/曝光锁/关屏是否支持。**这张清单就是兼容性报告要引用的东西** |
| **试拍一张（含落盘校验）** | 按一次快门，等在存储卡目录里看到新文件，报告耗时；失败则报告失败在哪一步 |
| **软件自检** | 检查：JNI 库能加载、`getParameters()` 不抛、`getFilesDir()` 可写、能关屏与恢复、自动关机广播发出去了 |
| **按键日志** | 屏上倒序列出每个按键：扫描码、按下/抬起、repeat、Sony 逻辑码；同时追加进 `keys.txt`。这是填补「各机身键位差异」的唯一手段 |
| **会话日志** | 查看最近一次拍摄的日志（见 §5.2）与恢复点 |

### 4.5 完成屏与故障屏

**完成（`DONE`）：** 一句结论 + 数据 + 三个选项，不再出现捐款/邮箱/Instagram（U-04）：

```
拍摄完成    300 / 300 张
用时 00:10:12      平均间隔 2.04 s      漂移 +0.04 s/张
目录 DCIM/100MSDCF      卡上剩余 1.2k 张
[ 中心 再拍一次 ]   [ 左 改参数 ]   [ MENU 退出 ]
```

**故障（`FAULT`）：** 第一行是人话结论（「存储卡写满，已拍 214 张，最后一张在 00:07:03」），第二行给出原因与建议，第三行说明已经落盘的东西在哪。故障也写进会话日志。

### 4.6 长拍摄的生存策略

| 威胁 | 对策 |
|---|---|
| 相机自动关机 | 拍摄期间广播 `APO/NO`；退出/结束时恢复 `APO/NORMAL`（老 app 已有，保留） |
| 屏幕耗电/烧屏 | `关屏 ON` 时每张之间关屏，任何按键先亮屏（老 app 已有，保留） |
| 系统把 app 顶到后台（弹窗、插 USB、低电量） | `onPause` **不再取消拍摄**：把会话状态（计划、已落盘张数、下一张到期时刻、日志路径）刷盘，释放相机；`onResume` 读回状态，**按剩余时间**续拍并在状态条上写明「被打断过 N 次」 |
| 进程被杀 / 相机断电 | 会话日志是带时间戳的追加文件（§5.2），重开 app 时如果发现日志尾部没有 `END` 行，就在主屏显示「上次拍摄在 HH:MM 中断，拍了 N 张，要重拍吗？」 |
| 曝光时间比间隔长 | 预检阶段就能算出来：如果最近一次曝光的实际耗时 ≥ 间隔，状态条显示「间隔小于曝光+写卡耗时」并建议改值；这是老 app 只能在 README 里警告的事（U-16） |
| 相机自己改写设置 | 预检检查 Picture Profile 是否开启、模式拨盘是否在 MR/1/2（这两者会让存储的值失效） |

---

## 5. 数据与持久化

### 5.1 存什么、存哪里

| 数据 | 位置 | 生命周期 |
|---|---|---|
| 计划（间隔/张数/延时/选项/预设） | `getFilesDir()/plan.txt`（键值文本，带 `schema=` 版本号） | app 装着就在；卸载即失 |
| app 偏好（语言、面板可见性、上次的目录名） | `getPreferences(MODE_PRIVATE)` | 同上 |
| 「相机该怎么拍」的那部分 | 相机设置存储（`Backup_write` + `Backup_sync_all`） | 关机再开机仍在；这是 G3 的核心 |
| 会话日志 | `getFilesDir()/sessions/<起始时间>.log` | 追加写；用于恢复与事后复盘 |
| 开发者产物 | `snapshot.bin` / `diff.txt` / `locks.txt` / `keys.txt` / `caps.txt` | 同上；一律**英文**，方便贴进 issue |

**不存 UI 刻度位置。** 老 app 存的是 `rawInterval` 这类刻度下标（`Settings.java:90-92`），刻度表一改，旧存档就指向别的值（PAIN-POINTS U-13）。这里存值 + 单位。

### 5.2 会话日志格式（恢复的基础）

```
# better-timelapse session
schema=1
model=ILCE-6000 platform=...
plan interval=2000ms frames=300 delay=0s settle=1200ms drive=single silent=on ael=on paneloff=on
start 2026-10-07T21:14:03Z dir=DCIM/100MSDCF firstfile=DSC01234.JPG
shot 1 at=+0.000s file=DSC01235.JPG wrote=1180ms bat=78% temp=31C
shot 2 at=+2.041s file=DSC01236.JPG wrote=1130ms bat=78% temp=31C
pause at=+120.500s reason=onPause bat=77%
resume at=+131.200s
shot 3 at=+131.900s file=DSC01237.JPG wrote=1210ms bat=77% temp=32C
retry at=+200.100s frame=45 consecutive=1 reason=no_file_after_4s
fault at=+423.100s cause=card_full frames_landed=214
END at=+423.100s outcome=fault
```

每行都是**追加**写，所以断电最多丢最后一行。恢复时读最后一行判断结果。`shot` 行的 `at` 是相对 `start` 的毫秒数（单调时钟），`wrote` 是从快门到文件出现的实测耗时 —— 这个数据同时也是「间隔该设多大」的现场依据。`bat`/`temp` 是每帧的电量与温度（若平台给得出），因为**热与电的行为没有人替我们测过**，只能自己记录（见 §6 的 M7 注）。

### 5.3 隐私与权限

`AndroidManifest` 只申请真的需要的权限：`CAMERA` + `WRITE_EXTERNAL_STORAGE`（读目录数文件）。**不要** INTERNET / ACCESS_WIFI_STATE / CHANGE_WIFI_STATE（老 app 带了，D-14）。app 不联网。

> **`WAKE_LOCK` 要不要加？（开放问题，M7 用测量决定）** recipe-lab 没有这个权限，也**完全没有**任何长时运行机制（§6 M7 注）。我们面对的是「数小时」而不是「2 分钟」，所以必须实测两件事：相机在 app 处于前台但屏幕关闭时会不会杀掉进程；以及拿住 `WAKE_LOCK` 是否真的延长了存活时间。**在测出来之前不加** —— 加了不用是噪音，加了乱用会加快耗电，而这类 app 最怕的就是「以为加了就安全」。

---

## 6. 里程碑与验收

每一个里程碑都以**红测**结束；红测不过就不进入下一个。规则来自 `pmca-app` skill：构建绿、测试绿都**不是**证据。

| # | 里程碑 | 产出 | 红测（必须真机） |
|---|---|---|---|
| **M0** | 环境就位 | JDK 17、build-tools 30.0.3、platform 28、NDK r16b、`pycryptodomex`；`docker/` 兜底路线 | `build.sh`（拿 recipe-lab 的脚本改包名）输出 `BUILD OK`；`pmca-console.py info` 能列出机身 |
| **M1** | 骨架上机 | `build.sh` / `build.cmd` / `AndroidManifest.xml` / `jni/` / 一个只画一行字的 Activity | 四连红测全过：`BUILD OK` → 安装成功 → `MENU → Application → Application List` 里有它 → 打开后屏幕有字 |
| **M2** | 相机与存储 | `Camera.java`（反射）、`NativeStore.java` + `jni/jni.cpp`、开发者菜单的「能力清单」+「只读检查」+「快照/差分」 | `getNormalCamera()` 非空、`getParameters()` 不抛、一次 `set()` 让实时画面变化；一个槽位写入后**关机再开机仍在** |
| **M3** | 纯核心 + 测试 | `Plan` `Steps` `Engine` `Schedule` `Frames` `Hud` `Keys` `Lang` + 全部测试 | `./tools/test.sh` 全绿（JDK 17，无 SDK/NDK/相机）；故意在纯类里 import `android.*` 应让测试任务失败 |
| **M4** | 主屏 UI | `PanelView` / `MenuView` / `Legend` / `PromptView` + 按键分发 | 键盘走查：滚轮调值、上下细调、左右换行、中心执行、TRASH 暂停、hold TRASH 停止（有确认）、hold MENU 菜单；不与固件抢键 |
| **M5** | 拍摄闭环 | Engine 接到真实 Handler + ShutterListener；状态条与进度；暂停/继续/停止 | 拍 30 张 × 2s：张数正确、暂停后继续不重排相位、停止后 app 不退出；**关机再开机计划仍在** |
| **M6** | 真实张数与故障 | `Frames` 接文件系统观测；故障屏；`DONE` 摘要 | 拍摄中把卡拔掉/写满：app 在 N 秒内进 `FAULT` 并给出已落盘张数，不虚报；恢复后能继续 |
| **M7** | 长拍摄生存 | 会话日志、`APO/NO`、关屏、`onPause` 续拍与原位恢复 | 4 小时以上连拍；中途插拔 USB / 触发相机弹窗 / 关屏，拍摄能续；强制杀进程后重开能看到中断记录 |

> **M7 是全新的、没有先例可抄的部分。** Recipe Lab 的最佳实践里**完全没有任何长时运行机制**：没有 `WAKE_LOCK`、没有 Service、没有 `setKeepScreenOn`，而且它的示例连拍循环刻意在 `onPause` 里结束（`docs/DEVELOPMENT.md:176-177`「a run cannot outlive the camera it shoots with」），它最长的一次连拍是 77 张，并且有一台 **A5000 在约 8 张之后死机、只能拔电池**（其 `README.md` 兼容表注 #58）。也就是说：**「连拍/快门/数小时运行」这条路没有被任何人验证过**。M7 的每一件事都要当新测量做，并且必须准备降级方案：
>
> 1. 先测「Activity 能活多久」：连续拍 30 分钟、2 小时、4 小时，记录是否被系统杀掉、是否触发了相机自身的保护。
> 2. 加「连续 N 次失败就停」的保护，并且每张都写带时间戳的日志（这样死机也能定位到第几张）。
> 3. 恢复靠清单文件（manifest）而不是内存状态。
> 4. **降级方案（值得认真评估）：不自己驱动快门，而是把「间隔 + 张数」配给相机自己的间隔拍摄功能（若该机身有），app 只负责写设置存储。** 这条路让整个 §3.3 的安全模型直接适用，风险低得多 —— 代价是功能受相机固件限制。这条要在 M2 的现场测量里一并确认（Q-新增：本机身是否自带间隔拍摄）。
| **M8** | 中文与预检 | `TextZhHans` / `TextZhHant` + 裁剪字体 + `LangTest`；`Preflight` 告警清单；试拍 | 切简体/繁体无缺字；预检能在真机上指出 Picture Profile / MR 档位 / 卡余量问题 |
| **M9** | 发布 | `tools/version.sh` + `check-version.sh`、CI（dev-build / release）、`docker/` 一行构建、README 兼容性表 | 全新机器按 README 从零构建成功；release APK v1 签名；一台相机从 dev 版覆盖安装到 release 版成功 |

**M0–M2 是「环境与测量」，M3–M8 是「产品」，M9 是「可交付」。** 当前进度：M0 之前（本机缺工具链、未接相机、未建立仓库骨架）。

---

## 7. 测试策略

| 层 | 做法 | 能证明什么 |
|---|---|---|
| 单元测试（`tools/test.sh`） | 纯类用裸 JDK 编译（**不给 `android.jar`**），JUnit 5，无 mock 框架；Sony 类的 test double 放在 `test/com/sony/scalar/sysutil/`，未设定的调用一律抛异常 | 「app 发出了什么字节/算了什么时刻/画了什么字」 |
| 关键机制 | `tools/test.sh` 里的 `UNITS` 是**显式类名清单**，这些类在**没有 `android.jar` 的类路径下**编译。于是「纯类里不许有 `android.*` import」从一条约定变成**编译错误** —— 这是这套架构能守住的关键（recipe-lab 实测：20 个类里 10 个是纯核心，2193 行；整套测试 203 个、255 ms 跑完） | 「架构不会被慢慢腐蚀」 |
| 键探测测试 | 对着 double 验证反射签名、`valid==1` 才算有键、`status==1` 才算按下；相机不在场时答案必须是 null，不是异常 | 「换一台机身不会因为缺键而崩」 |
| 语言测试 | 三张表 key 集合一致、占位符一致、代码用到的 key 都存在；**每个字体文件对每张表用到的每个字符都有字形**（读字体自己的 cmap） | 「不会出现豆腐块」 |
| 版本测试 | 清单里的版本可解析、版本码符合公式、仓库里没有第二个版本号 | 「不会装不上/被拒为降级」 |
| 真机验证（skill 第 5 阶段） | 安装 → 操作 → **关机再开机** → 再读值 | 「值真的存进去了」 |
| 红测清单（每次改动触及时跑） | 见 §6 每个里程碑 | 「相机真的这么做了」 |

**明确写出不能证明的事**（照 recipe-lab 的诚实做法）：单元测试不能证明相机如何理解那些字节；不能证明间隔在真机上准时；不能证明关屏、自动关机、长按在具体机身上的行为。这些只能靠真机 + 兼容性报告。

---

## 8. 开发流程（照搬 recipe-lab 的约定）

| 项 | 规则 |
|---|---|
| 分支 | 只有 `main` 一条长期分支；工作分支 `feat/ fix/ docs/ refactor/ chore/`；不让 agent 自己开 PR，也不自己合并 |
| 提交 | `type(scope): subject`，type ∈ `feat fix docs refactor perf test build ci chore revert`，subject ≤72 字符、祈使句、无句号；PR 标题决定版本（`feat:`→minor，`fix:`→patch，`!`→major，`chore:`/`docs:`→不发版） |
| 版本 | `AndroidManifest.xml` 唯一来源；构建派生根副本，不改仓库清单 |
| 不提交 | APK、keystore、`out/`、`jni/platform/errno.h.updater_only` |
| 构建 | `build.sh` 与 `build.cmd` 必须同步改；`errno.h` 移开必须用 `EXIT` trap 还原（子模块脏就是 bug） |
| 构建脚本的自检 | `build.sh` 开头做**六条路径前置检查**，缺什么就打印 `missing: <path>` 并退出（不要等到第 4 步才失败）；`javac` 加 `--release 8`；`d8` 用 `java -cp d8.jar` 调用；结尾打印 `BUILD OK: <apk> <sha256>` |
| CI | `test`（`tools/test.sh`）在每次 dev-build 与 release 前跑；`check-version` 守版本唯一性；**再加一条：构建完成后断言 `git diff --exit-code`，用来抓「`errno.h` 忘了还原」** |
| CI 不是合并门禁 | recipe-lab 明确写了「`main` 不带必需状态检查」以及理由。我们沿用同样的取舍，但把理由写进 `docs/CONTRIBUTING.md`，避免下一个人以为是漏配置 |
| 给 agent 的规则 | 仓库根放 `CLAUDE.md`（分支/提交/版本/构建/不提交/发布规则）；`docs/` 写给人和 agent 看。**不要照抄 recipe-lab 的 `.claude/skills/test-feature`** —— 它已经过期（声称仓库里没有测试框架，实际已有 `tools/test.sh` + JUnit 5） |
| 文档 | `docs/PAIN-POINTS.md`（为什么做）、`docs/PLAN.md`（做什么）、`docs/PLATFORM-FACTS.md`（平台硬事实）、`docs/DECISIONS.md`（已定/开放）、`docs/DEVELOPMENT.md`（怎么改）、`docs/CONTRIBUTING.md`（流程） |
| 用户的成功反馈要包含「关机再开机」 | recipe-lab 的成功提示本身就告诉用户去断电重启 —— 因为我们能证明的只是「写了字节」，不是「相机真的记住了」。新 app 的成功路径也要这么写 |

---

## 9. 环境前置（M0 的具体清单）

本机当前实测状态与需要补齐的东西：

| 需要 | 现状 | 动作 |
|---|---|---|
| JDK 17 | 系统 `java` 是 Zulu **21**；`/usr/lib/jvm` 下有 `java-17-openjdk` | 指向 17（`JAVA_HOME=/usr/lib/jvm/java-17-openjdk`），写进构建脚本的检查 |
| Android build-tools 30.0.3 | `/opt/android-sdk` 下只有 `cmdline-tools` | `sdkmanager "build-tools;30.0.3" "platforms;android-28"` |
| NDK r16b | 完全没有（无 `ndk/` 目录） | `sdkmanager "ndk;16.1.4479499"`，约 3 GB |
| Docker（兜底） | **未安装** | 若宿主机装不上老工具链，用 `recipe-lab-sony-pmca/docker/`（`Dockerfile` + `pmca-build.sh`）在容器里构建；或用它作为参考自建 |
| Sony-PMCA-RE 可用 | `pmca-console.py` 因缺 `pycryptodomex` 无法启动 | `pip install -r requirements.txt`（或 `pycryptodomex` + `pyusb` + `tlslite-ng` + `asn1crypto` + `axmlparserpy`） |
| 相机 | 未连接（`lsusb` 无 Sony；`adb devices` 为空） | 相机开机 → `Setup → USB Connection` = Mass Storage → USB 连接；`pmca-console.py info` 应列出机身 |
| 相机机型与固件 | 未知 | **这是必须记录的第一条事实**：机型 + 固件版本写进 `docs/PLATFORM-FACTS.md` |
| `Application` 菜单项 | 未知 | 相机 `MENU → Application` 存在才能装；不存在则项目停止（不是换工具能解决的） |

---

## 10. 风险登记

| 风险 | 影响 | 应对 |
|---|---|---|
| 相机不是 app-capable（2016 年后机身） | 项目无法交付 | M0 第一步就验证 `MENU → Application`；不刷固件 |
| 宿主机构建不出 armeabi（NDK r16b 装不上 / JDK 冲突） | 无法产出 APK | 走容器路线；容器只负责**构建**，安装始终从宿主机走 USB（skill 明确要求） |
| 占用相机槽位存计划导致设置冲突 | 可能改坏相机设置 | 默认方案 C（计划存文件）；方案 A 只在 M2 实测通过后启用，且单字节、写前查只读 |
| 长拍摄中途进程被杀 | 丢一次拍摄 | 会话日志 + 恢复提示；把「被打断」变成可解释的事件而不是静默失败 |
| 各机身键位/能力差异 | 某些机型上功能不可用 | 一律探测、一律降级、图例只画真有的键；开发者菜单的能力清单 + 按键日志用于收集兼容性报告 |
| **长时运行没有任何先例**（Recipe Lab 无 wake lock / 无 Service / 最长 77 张；一台 A5000 约 8 张后死机需拔电池） | 「数小时拍摄」这个核心承诺可能做不到 | M7 当全新测量做；准备「连续 N 次失败即停 + 每张带时间戳日志 + 清单文件恢复」；认真评估「配置相机自带间隔拍摄功能」的降级方案（见 M7 注） |
| 相机在长时间连拍中死机（需要拔电池） | 用户数据/信任受损 | 用户可见的「上次拍摄异常结束」提示 + 会话日志；在兼容性文档里明确写出哪些机身被验证到多少张 |
| 间隔在真机上不准 | 延时片抖动 | 记录每张的实测 `at` 与 `wrote`；用实测平均间隔与漂移显示给用户，而不是假装精确 |
| 单元测试给人虚假信心 | 真机问题漏过 | 文档与 PR 模板里明写「构建绿/测试绿不是证据」；每个里程碑都有真机红测 |
| 包名/证书一旦发布不可更改 | 用户无法覆盖安装 | M9 之前确定包名；release 用固定 keystore（CI secret），APK 不进仓库 |

---

## 11. 下一步（本轮之后的第一件事）

按顺序做，不要跳：

1. **接上相机**，记录机型、固件版本、`MENU → Application` 是否存在。
2. 从 `recipe-lab-sony-pmca` 复制 `build.sh` / `build.cmd` / `tools/` / `jni/{Android.mk,Application.mk,jni.cpp}` / `.editorconfig` / CI 工作流，改成新包名与模块名，接上 `jni/platform` 子模块。
3. 跑通 M0 的红测：`BUILD OK` + `pmca-console.py info`。
4. 做 M1 的四连红测（骨架 APK 上机）。
5. 之后按 §6 逐个里程碑推进；每完成一个里程碑，把真机测到的结论追加进 `docs/PLATFORM-FACTS.md`，并在 PR 描述里写明**哪条结论是实测的、哪条是从源码读来的**。
