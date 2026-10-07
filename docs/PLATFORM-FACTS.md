# 平台硬事实（PMCA）

这份文件只放**做决定时必须知道、否则一定会踩坑**的平台事实。每条都标了来源，并区分「实测」「上游源码」「未验证」。

**来源等级：**

| 标记 | 含义 |
|---|---|
| 【实测·recipe-lab】 | Recipe Lab 项目在真实机身上测过并写进了它自己的文档 |
| 【实测·skill】 | `pmca-app` skill 的流程与结论（从 Sony-PMCA-RE / OpenMemories 项目提炼） |
| 【上游源码】 | ma1co 的 Sony-PMCA-RE / OpenMemories-Platform 源码 |
| 【未验证】 | 读到的说法，但本项目还没在相机上确认 |

> **本轮状态提醒：** 本机**没有连接相机**（`lsusb` 无 Sony 设备），所以本文件里没有任何一条是「better-timelapse 自己测出来的」。第一次接上相机后，必须把机型、固件版本、以及逐条复测结果补进来。

---

## 1. 能力边界

| 事实 | 来源 |
|---|---|
| 索尼最后两台支持安装 app 的机身是 **A6500** 与 **A99 II**（2016 年底）。此后的机身固件经过签名，菜单里没有 `Application` 项，任何工具都装不进去 | 【实测·skill】+【实测·recipe-lab】 |
| 索尼官方 app 商店已于 2021 年关闭 | 【实测·recipe-lab】 |
| 验证方法：相机 `MENU → Application` 存在否。不存在就停止项目 | 【实测·skill】 |
| 不要刷固件绕过。刷写固件有损坏相机的风险 | 【实测·skill】 |
| 已确认能跑 PMCA app 的机身（15 台）：A6000 / A6500 / A5100 / A6300 / A7 / A7 II / A7R / A7R II / A7S II / RX100 III / RX100 V / RX1R II / HX60 / HX90 | 【实测·recipe-lab 兼容表】 |
| A5100 与 A5000 **没有 Fn 键**；A7S II 的 AEL 在两次报告里都没反应；HX400 / WX500 等紧凑机没有控制滚轮 | 【实测·recipe-lab】 |
| NEX 代的菜单与 A6000 代差别很大，槽位最不可能在同一位置 | 【实测·recipe-lab】 |

---

## 2. 目标平台约束（每一个设计决定都要遵守）

| 项目 | 值 | 对开发的约束 | 来源 |
|---|---|---|---|
| 操作系统 | Android 2.3.7，API 10 | 不能用 minSdk 之上的 API；没有 `RecyclerView`、没有 `Fragment` 的现代用法 | 【实测·skill】 |
| `minSdkVersion` | 10 | 大于 10 时安装器警告，安装可能失败 | 【上游做法·skill】 |
| 处理器 | ARMv7，**只有 armeabi** | 原生代码只编译 armeabi；`jni/Application.mk` 钉 `APP_ABI := armeabi`、`APP_STL := stlport_static`、`APP_PLATFORM := android-14`、`NDK_TOOLCHAIN_VERSION := 4.9` | 【实测·recipe-lab】 |
| NDK | **r16b**（`16.1.4479499`） | 最后一个带 GCC 4.9 的 NDK；更晚的 NDK 都编不出这个目标 | 【实测·recipe-lab】 |
| JDK | 17 | `tools/test.sh` 与构建脚本都按 17 写；本机现在是 21，必须显式切 17 | 【实测·recipe-lab】 |
| build-tools | 30.0.3 | 提供 aapt / d8 / zipalign / apksigner | 【实测·recipe-lab】 |
| platform jar | API 28 | javac 与 d8 的编译类路径 | 【实测·recipe-lab】 |
| 触摸屏 | 无 | 全部输入走按键 | 【实测·skill】 |
| 屏幕 | 单个横向屏 | 单个 Activity；`android:screenOrientation="landscape"` | 【实测·recipe-lab】 |
| 相机接口 | `android.hardware.Camera`（旧版） | 用 `Camera.Parameters`，不是 Camera2 | 【实测·skill】 |
| 无 Gradle | 构建是七步脚本 | ndk-build → aapt → javac → d8 → aapt package → zipalign → apksigner | 【实测·recipe-lab】 |

---

## 3. 签名、包名、版本

| 事实 | 来源 |
|---|---|
| 相机只认 **v1 签名**，忽略 v2/v3。参数：`--v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false --min-sdk-version 10` | 【实测·recipe-lab】 |
| **只做 v2 的 APK 装不上** —— 这不是理论：老 app 提交的 `TimeLapse-release-0.3.1.apk` 里 7 个 APK 都带 v2 签名块（`0x7109871a`），但只有它没有 `META-INF/*.SF` / `*.RSA`（只剩 87 字节的 `MANIFEST.MF`）。用 `apksigner verify` 或解析 APK Signing Block 都能一眼看出，发布前必须验 | 【实测·本仓分析】 |
| 相机接受自签名证书，调试证书即可 | 【实测·skill】 |
| **不同证书的 APK 不能覆盖安装**到已有 app 之上，必须先卸载。因此发布必须用固定的 keystore（CI secret），APK 不能进仓库 | 【实测·skill】+【实测·recipe-lab】 |
| `versionCode = MAJOR*10_000_000 + MINOR*100_000 + PATCH*1_000 + P`；`P=999` 正式版，`P=N` 为自上个 tag 起的提交数。`999` 让正式版高于它之前的每个预发布版，避免被当成降级 | 【实测·recipe-lab】 |
| `AndroidManifest.xml` 的 `versionName` 是**唯一**版本来源；构建写一份清单副本进 `out/` 让 aapt 读，**从不修改仓库里的清单** | 【实测·recipe-lab】 |

---

## 4. 从应用操作相机（反射）

构建环境里没有索尼 stub jar，因此**直接 import 编译不过**，必须反射：

```java
Class<?> cx = Class.forName("com.sony.scalar.hardware.CameraEx");
Object cameraEx = cx.getMethod("open", int.class,
        Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions")).invoke(null, 0, null);
Camera camera = (Camera) cx.getMethod("getNormalCamera").invoke(cameraEx);
```

| 事实 | 来源 |
|---|---|
| `CameraEx.open(0, null)` → `getNormalCamera()`；`onPause` 里对 CameraEx 对象调 `release()` | 【实测·skill】 |
| 用 `catch (Throwable)` 包住**每一次**反射调用并降级：反射失败是**正常分支**，不是崩溃 | 【实测·skill】 |
| `Build.MODEL` 在所有机身上都返回 `ScalarA`，**不要用它**判断机型 | 【实测·skill】 |
| 机型与平台：`ScalarProperties.getString("model.name")` / `getString("version.platform")` | 【实测·skill】 |
| 某个键是否存在：`ScalarInput.getKeyStatus(scan).valid`；是否按下：同一次调用的 `.status` | 【实测·skill】（标注：skill 说「尚未在相机上验证」） |
| 支持性探测：`ParametersModifier.isSupportedSilentShutterMode()`；否则 `setSilentShutterMode()` 会 `NoSuchMethodError` | 【上游源码；recipe-lab 记录 `setSilentShutterMode` 不是所有机身都有】 |
| **拍摄是「按下 / 放开」一对，而且必须有活跃预览流**：`Camera.takePicture(null,null,null)`（按下）→ 约 **2500ms** → `CameraEx.cancelTakePicture()` + `Camera.startPreview()`（放开）。没有预览流时 `CameraEx.burstableTakePicture()` 会被拒，真机报 **`BurstableTakePicture failed`**（第一张之后必现） | 【实测·本机】+【recipe-lab 实测：`DevTools.SHUTTER_MS = 2500`，sample run 在 ILCE-6001 / 固件 v3.21 上拍成 77 张；`MainActivity.java:88` 的 `runNext = cancelCapture(); resumePreview();`，`:201` 的 `surfaceCreated = setPreviewDisplay + startPreview`】 |
| 因此**这台机身的每张最小周期 ≈ 2.5s（按住窗口）+ 0.4s（放开到下一次按下的余量）= 2.9s**：「间隔 2s」在这台机身上物理做不到，不是排程写法问题 | 【实测·本机（2s 间隔下必被拒）】+【recipe-lab 的常量】 |
| **这台机身当前写的是 RAW**：面板的「最新」行实测到 `DCIM/10561007/DSC00507.ARW`（`0s前`）。含义有两条：一次快门一个文件、但一个文件很大，所以「每张耗时」与「还能拍几张」都要按 RAW 的量级读；相机的目录名不一定是 `100MSDCF` 那种 roll 名（`10561007` 是机身按日期建的目录），所以递归走整棵 `DCIM` 是必须的（A18） | 【实测·本机，v0.1.0-dev.g37016620】 |
| **app 可以往卡根写** —— 但**本 app 现在写不进去**，原因待定：卡根上除了 `APPSTORE.log`、`TWEAKLOG.txt`，还有 **`TIMELPS/`** —— 那是老 TimeLapse 自己的日志目录，由 `new File(Environment.getExternalStorageDirectory(), "TIMELPS/LOG.TXT")` + `getParentFile().mkdirs()` 创建（`Logger.java:13-19`），而它的 manifest 与本 app **声明完全相同的权限**（`CAMERA` + `WRITE_EXTERNAL_STORAGE`）。所以「这台机身不让 app 写卡」这个结论**是错的**：路径、权限、写法都不是原因，剩下的变量是**时机**（老 app 是在拍摄过程中写的，本 app 在启动后约 2 秒写）与**安装时权限是否真的授予** | 【实测·本机：卡上文件列表】+【上游源码：TimeLapse `Logger.java` / `AndroidManifest.xml`】 |
| **老 app 在本机上确实写得进卡**（用户确认 `TIMELPS/` 里有它写的日志）→ 所以本 app 的 ENOENT **不是平台策略，而是「谁在问」或「怎么问」**。下一步区分：进程的 **uid 与附加组**（存储层就是按这个判的；`sdcard_rw`=1015 / `media_rw`=1023 在不在组里，Java API 看不到，只能读 `/proc/self/status`），以及**每条路径各自的 errno**（此前只汇总了原因，没报路径） | 【实测·本机：用户确认日志存在】+【本项目的诊断设计】 |
| **写卡的失败原因是 `No such file or directory`（ENOENT），六条路径全一样（实测）**：包括**父目录确实存在且可列出**的 `DCIM/` 与老 app 用过的 `TIMELPS/`。所以**这不是权限问题**（权限=有、状态=mounted），也不像挂载只读（那会是 `EROFS`）——更像存储层对「创建文件」这件事直接回答「没这个路径」。下一步要问的是**规范路径**（`getCanonicalPath`，看 `/storage/sdcard0` 是否只是个别处解析不了的拼法）与**追加模式**（老 app 的 `LOG.TXT` 是 `FileWriter(file, true)` 写的，而「创建」与「打开已存在文件」在有些存储层上不是同一个操作） | 【实测·本机，v0.1.0-dev.gb60fefc】 |
| **`canWrite()` 在这台机身上会说谎（实测）**：面板实测 `权限 有  状态 mounted  /mnt/sdcard可写 true  ext可写 true  TIMELPS可写 true` —— 五项全绿，**而写文件依然全部失败**。结论：这台机身的存储层不是用普通 Unix 权限表达的，`File.canWrite()` 给不出可用信号；**只有真正写一次才知道**，而写失败的原因字符串（`Permission denied` / `Read-only file system`）才是唯一证据 | 【实测·本机，v0.1.0-dev.gb60fefc 前】 |
| **实时参数共 298 个**，按 `shutter/exposure/speed/iso/aperture/silent/drive/burst/bracket/focus/review` 过滤命中 **85 个** —— 一屏放不下，而清单文件又取不出来，所以面板改成只列**曝光家族**（键名 + 值 + 总数）。已看到的名字里有 `exposure-bracket-mode-values`、`pixel-control-exposure-shift-supported`、`iso-auto-…` | 【实测·本机，同上】 |
| 设备清单的 7 个位置里只有机身内部写得进去（卡上六处全被拒），且**机身内部取不出来**（无 adb，`pmca-console` 无取 app 文件的命令）。新面板行会给出：被拒原因汇总 + `权限 有/无` + `状态 mounted/…` + `/mnt/sdcard 可写 true/false` + `ext 可写 …`，并在「首次读卡后」与「开拍时」再试（老 app 正是在拍摄中写成的） | 【实测·本机，v0.1.0-dev.g729c1736；诊断行为 v0.1.0-dev.g…】 |
| **实时参数里没有快门速度键**（初步）：面板上过滤出的候选键是 `silent-burst-picture`、`direct-shutter-supported`、`exposure-bracket-mode-values`、`zoom-drive-type`、`focus-area-type`、`cvf-preview` 一类 —— **没有曝光时间/快门速度**。注意这次过滤**漏了 `exposure` 这个关键词**（`exposure-compensation` 因此没被列出），所以这条只能算初步；下一次会把 `exposure/speed/iso/aperture` 一起过滤，并给出键的总数 | 【实测·本机，同上】**待补全** |
| **`direct-shutter-supported` 这个键值得追**：名字暗示平台区分「直接快门」与其它路径（与 A20 的 `takePicture`/`cancelTakePicture` 一对有关） | 【实测·本机，同上】**未验证** |
| **曝光值目前读不到——但还没问过相机**：已知的实时参数表（§4.1）与已知槽位表（§5.1）里都没有快门速度，这两张表都来自「recipe-lab 需要什么」而不是「机身给什么」。所以从 v0.1.0-dev.gb5e7a81e 起，app 每次开机会把**未过滤的全量参数、过滤后的候选键、CameraEx 与 modifier 方法表**写到卡上 `bettertimelapse/device.txt`。**下一步就是把那个文件读一遍**，答案在那里而不在推理里 | 【本项目的设计（[DECISIONS](DECISIONS.md) A30）】**待数据** |
| **间隔 3s 在这台机身上会丢帧（实测）**：一次运行 `落盘 17 核实 6 未核实 11`，同一时段的卡计数只涨了约 6 —— **17 张里约 6 张真的到了卡上**。recipe-lab 在同一机身跑通 77 张的循环是 `按住 2500ms + 稳定延时 1200ms = 3700ms`，与这个观察一致：**这台机身的实际下限接近 3.7s，不是 2.9s**（2.9s 只是快门按住窗口推出来的理论值） | 【实测·本机，v0.1.0-dev.g37016620 前后】 |
| **快门回调延迟不是曝光时长**：同一张照片上 `回执 5/17 +916ms` —— 回调只到了 5 次，平均 916ms。916ms 远大于当时的曝光（手持室内），所以它量的是**平台的报告延迟**，不能当曝光代理（A27 里把它当曝光提示，因此在这样的机身上不生效，只会退回常数——设计上安全但无用） | 【实测·本机，同一版】 |
| **走一次卡目录约 858ms**（`读数 858ms`）：这正是它必须待在工作线程、每帧只走一次的原因（A19） | 【实测·本机，同一版】 |
| **机身周期只能靠「被拒」学**：没有曝光时间可读、没有 busy 标志可问。因此新面板读数 `机身 >X.Xs` 是**下界**（一次被拒证明了机身至少忙了这么久），并且被拒时 evidence 行直接写「快门被拒：每张至少 N，间隔太短」 | 【本项目的设计（[DECISIONS](DECISIONS.md) A27）】**未在真机上验证**：还没人为地设过一个长曝光来看它是否真的被拒出更大的下界 |
| **静音快门与驱动模式都能读回**：面板的诊断行实测到 `silent-shutter-mode=false  drive-mode=single`，与面板上两行的值一致 —— 这是 A25「两个方向都下发 + 从机身取值打底」在真机上的第一次确认 | 【实测·本机，同一版】 |
| **已用本 app 自己证实**：间隔 **5s** 连拍成功、**卡上文件与张数完全一致**（`卡增`/`核实` 逐帧对上）；间隔 **2s** 则被拒（`BurstableTakePicture failed`）。即 A20 推出的 2.9s 下限在真机上成立 | 【实测·本机，better-timelapse v0.1.0-dev.g88fa8dc0】 |
| `ParametersModifier` 被调用的方法名（`setFocusMode` / `setDriveMode` / `setBurstDriveSpeed` / `setBurstDriveButtonReleaseBehave` / `setSilentShutterMode` / `setAutoExposureLock` / `setBracketMode` / `setExposureBracketMode` / `setExposureBracketPeriod` / `setNumOfBracketPicture`，以及常量 `DRIVE_MODE_*` / `AE_LOCK_ON` / `BRACKET_MODE_EXPOSURE` / `EXPOSURE_BRACKET_MODE_SINGLE`） | 【未验证】—— 这些名字来自老 app 的编译期依赖（TimeLapse 的 `app/build.gradle` 里那个已死的 `-SNAPSHOT` stub），**需要真机反射验证** |

### 4.1 实时预览参数（Sony 的参数字符串）

```
color-mode  saturation  contrast  sharpness  whitebalance
color-temperture-white-balance  light-balance-for-white-balance
color-compensation-for-white-balance  rgb-matrix-mode  picture-effect
exposure-compensation  dro-mode  dro-level
```

来源：【实测·recipe-lab】`docs/DEVELOPMENT.md` 与【实测·skill】。`rgb-matrix-mode` 在 A6000 上总是 `false`。

### 4.2 已知会「抵消」实时改动的相机状态

| 现象 | 原因 | 动作 | 来源 |
|---|---|---|---|
| app 内画面正常，退出后无效 | **Picture Profile** 处于开启状态 | 设为 Off | 【实测·skill】 |
| 同上 | 模式拨盘在 **MR / 1 / 2** | 拨到 P / A / S / M（Memory Recall 会在开机时载入自己的设置） | 【实测·skill】 |
| 长曝光的间隔小于两倍快门时间 | 长曝光降噪（LENR）占用了相机 | 关掉 LENR，或把间隔放大 | 【社区报告·老 app README】 |
| 回读的值比写入的小 | 菜单范围小于预览范围（例：饱和度预览可到 ±16，菜单只存 ±3） | 把值限制在菜单范围内 | 【实测·recipe-lab】 |

---

## 5. 设置存储（camera settings store）

原生层（OpenMemories-Platform 的 backup 驱动）提供四个函数：

```c
Backup_read(id)                  // 读一个槽位
Backup_write(id >> 16, id, data) // 写一个槽位
Backup_get_attribute(id)         // 读槽位属性
Backup_sync_all()                // 把改动同步到存储
```

来源：【实测·skill】+【上游源码】`recipe-lab-sony-pmca/jni/jni.cpp`。

| 事实 | 来源 |
|---|---|
| 写设置存储的值在**所有模式下**生效，app 关闭后仍生效，**关机再开机后仍生效** —— 这就是这类 app 存在的意义 | 【实测·skill】 |
| **只读位**：每个槽位带属性，`BACKUP_ATTR_READ_ONLY` 位为 1 时 `Backup_write` 返回 `-BACKUP_ERROR_READ_ONLY`。写前必须对每个目标槽位读属性；**只要有一个只读就一个字节都不写**（半写会留下半个值） | 【实测·skill】+【实测·recipe-lab】 |
| 只读位是**逐槽位**的，不是全局锁。`Backup_guess_protection()` 那种「写一个硬编码只读槽位看是否被拒」的做法是错的问题（recipe-lab 因此废掉了旧的 `PROTECTED` 徽章） | 【实测·recipe-lab】 |
| 用户用 **OpenMemories-Tweak 的 Protection 菜单**解除保护 | 【实测·skill】 |
| 只写**单字节**、含义已知、原值已知的槽位；**不碰固件**（设置存储不是固件） | 【实测·skill】 |
| 错误信息必须写明失败的槽位与已写入的字节数 | 【实测·skill】 |
| **退出规则**：退出时把实时参数设为**已存储**的值。不要把实时参数设成 app 启动时的快照 —— 相机会自己把部分实时参数（曝光补偿、白平衡微调）写回存储，用启动快照会撤销刚存好的值 | 【实测·skill】+【实测·recipe-lab】 |

### 5.1 已知槽位 ID（在 A6000 上测得）

| 设置 | ID | 编码 | 来源 |
|---|---|---|---|
| Creative Style | `0x01070175` | 运行时 `color-mode-values` 列表的下标（1 standard, 2 vivid, 3 neutral, 6 mono, 14 sepia） | 【实测·recipe-lab】 |
| Contrast / Saturation / Sharpness | `0x01070178` / `0x01070187` / `0x0107018a` | 有符号字节，菜单范围 ±3 | 【实测·recipe-lab】 |
| Picture Profile no. | `0x0107031c` | 0 = off | 【实测·recipe-lab】 |
| WB 模式 / Kelvin / A-B / G-M | `0x01070019` / `0x01070018` / `0x01070017` / `0x01070016`（另有各模式副本 `0x0107067f`/`0x0107067e`（AWB）、`0x01070683`/`0x01070682`（色温）） | 1 = auto, 14 = 色温；Kelvin/100；有符号，G1 = `0xff` | 【实测·recipe-lab】 |
| Picture Effect | `0x010706f1` | `picture-effect-values` 的下标（Retro = 4） | 【实测·recipe-lab】 |
| Effect 子设置 | `0x010709d8`（high-key tint）/ `0x010706f3`（toy tone）/ `0x010706ee`（partial-colour hue）/ `0x010706ef`（posterization） | 运行时值列表下标 | 【实测·recipe-lab】 |
| Exposure bias | `0x010700b8` + 副本 `0x01070c7f` | 1/3 EV 步进，有符号（+0.7 = 2）；两个都要写 | 【实测·recipe-lab】 |
| DRO | `0x01070104`（等级字节 `0x01070775`） | Off 0, Auto 1, Lv1–5 = 2–6；等级字节 Off/Auto 为 1，Lv n 为 n+1 | 【实测·recipe-lab】 |
| Quality: 文件格式 | `0x01070013`（镜像 `0x01070aa9`） | RAW = 1, RAW+JPEG = 2, JPEG = 0 | 【实测·recipe-lab】 |
| Quality: JPEG 等级 | `0x01070014`（镜像 `0x01070aaa`） | Std = 0, Fine = 1 | 【实测·recipe-lab】 |

这些 ID 是在 **A6000** 上测的，在别的机身上可能落在别处 —— 这就是为什么必须有快照/差分工具。

### 5.2 槽位 ID 的发现方法（唯一可靠的方法）

【实测·skill 第 4 阶段】

1. 读所有已知 ID 的字节，写进 `snapshot.bin`。
2. 离开 app，在相机菜单里**只改一项**。
3. 重新打开 app，读同样的 ID 做差分，把结果以 `id:old>new` 写成 `diff.txt`。

**一次只改一项**：相机自己会改写无关条目，改两项就无法归属。同一次改动重复跑应该复现同样的结果。

recipe-lab 的实测规模：它的 `res/raw/ids.txt` 里有 **15416 行**槽位 ID（收录了所有 ≤16 字节的设置项），差分工具对**全部**这些 ID 做快照与比对。这个规模说明了「读全部 ID」在相机上是可行的（单次快照不是慢操作），新 app 可以照做。

### 5.3 手工读写槽位（电脑侧）

```bash
pmca-console.py bk r <ID>            # 读
pmca-console.py bk w <ID> <DATA>     # 写
pmca-console.py bk patch <ID> <DATA> # 通过修补 backup 文件写
pmca-console.py bk s                 # 同步到磁盘
pmca-console.py bk lock / unlock     # 只读保护开关
```

来源：【上游源码】`Sony-PMCA-RE/pmca/platform/__init__.py:41-48`。

---

## 6. 按键扫描码（Sony `ScalarInput` 的名字）

| 事实 | 来源 |
|---|---|
| **变焦杆不会送到 app**：绑定变焦杆做「演练模式」开关，真机上按了毫无反应（面板没有切换、也没有报错）。面板可用的按键只有方向键、滚轮、中心、TRASH、MENU —— 模式切换必须绑在这些上面（现绑「长按 ↓」） | 【实测·本机】 |


| 控件 | 扫描码 | 来源 |
|---|---|---|
| 滚轮 | 522 / 523 | 【实测·recipe-lab】 |
| 顶部转盘 | 525 / 526 | 【实测·recipe-lab】 |
| 四向键 | 103(上) / 108(下) / 105(左) / 106(右) | 【实测·recipe-lab】 |
| 中心键 | 232 | 【实测·recipe-lab】 |
| MENU | 514（NEX 机身是 229，即 `SK1`） | 【实测·recipe-lab】 |
| TRASH | 595（NEX 机身是 513，即 `SK2`） | 【实测·recipe-lab】 |
| 快门 | 516 / 518 | 【实测·recipe-lab】 |
| Fn | 520 | 【实测·recipe-lab】 |
| AEL / C1 / DISP / PLAY / MOVIE / 变焦杆 | 532 / 622 / 608 / 207 / 515 / 610,611 | 【实测·recipe-lab】 |

**处理规则：**

| 规则 | 来源 |
|---|---|
| 每个功能都必须落在**所有机身都有**的键上：滚轮、四向、中心、MENU、TRASH、快门。Fn 只作快捷键 | 【实测·recipe-lab】 |
| Fn 只在探测报告存在时才画进图例，**不要维护机型表** | 【实测·recipe-lab】 |
| 按下与长按用 `Handler.postDelayed` 计时、松键取消，**不依赖固件的按键重复事件**；长按超时定义成常量（recipe-lab 用 600 ms） | 【实测·skill】 |
| 吞掉不需要的键，避免它们触发固件功能（recipe-lab 吞 AEL 532、C1 622、DISP 608、PLAY 207；放行 MOVIE 515 与变焦杆 610/611） | 【实测·skill】+【实测·recipe-lab】 |
| `getRepeatCount() > 0` 的中心/MENU/TRASH/Fn 事件要丢弃，否则长按会先开再关 | 【实测·recipe-lab】 |
| 图例**不写**长按，也不在长按触发时留下卡住的键 | 【实测·recipe-lab】 |
| 每个键都要能打印扫描码（按键日志），这是解决机型差异的唯一手段 | 【实测·recipe-lab】 |

---

## 7. 安装链路

| 路径 | 命令 | 说明 | 来源 |
|---|---|---|---|
| A（先试这个）索尼安装通道 | `pmca-console.py install -f app.apk` | 复刻官方流程：本地起 HTTPS 服务器 → 发 xpd → 相机取回 spk（内含加密 APK）→ 相机解密后用包管理器安装。过程中相机闪屏、黑屏、自己切换模式都是正常的；以电脑输出为准 | 【上游做法·skill】 |
| B 原生 APK 直装 | `pmca-console.py install -d native -f app.apk` | 挂载数据分区写 `/data/app/app-N.apk` 并改 `packages.xml`。接受任意自签名证书、绕过包管理器，但**错误写入可能让相机无法启动** | 【上游做法·skill】 |

前置：相机开机 + 存储卡 + `Setup → USB Connection` = **Mass Storage** + USB 连接；相机屏幕显示 USB Mode。

验收：`pmca-console.py info` 能列出机身 → 安装完成后拔线、**关机再开机** → `MENU → Application → Application List` 里出现该 app → 打开后能画出内容。

**构建与安装分离：** 容器只负责构建，**安装始终从宿主机走 USB**（宿主机/Windows 能看到 USB，容器通常看不到）。

---

## 8. 构建环境的坑（每条都花掉过一小时）

| 坑 | 规则 | 来源 |
|---|---|---|
| `jni/platform/errno.h` | 它属于 updater 构建，会遮蔽同名的 NDK 头文件导致原生链接失败。构建前移开、构建后从 `EXIT` trap 还原；子模块变脏就是 bug | 【实测·recipe-lab】 |
| `apksigner` / `keytool` 从 `PATH` 找 `java` | 只设 `JAVA_HOME` 不够，`build.sh` 要把 `$JAVA_HOME/bin` 前置到 `PATH`，否则第 6 步 `exec: java: not found` | 【实测·recipe-lab】 |
| `d8` / `d8.bat` | 用 `java -cp d8.jar` 调用，因为 `d8.bat` 会用 `PATH` 上任意一个 Java | 【实测·recipe-lab】 |
| `-SNAPSHOT` 依赖 | 老 app 用 jitpack 的 `-SNAPSHOT` 依赖，无法离线复现。不要沿用 | 【实测·本仓分析】 |
| 单元类里的 `android.*` import | `tools/test.sh` 编译单元类时**不提供 `android.jar`**，一个 import 就让测试任务失败。反向也禁止：单元类不得调用 Activity | 【实测·recipe-lab】 |
| `/mnt/c/...` 上构建 | 每个文件操作走 9p，毫秒级；构建要做几千次 → 秒 vs 分钟。仓库必须放在 Linux 文件系统上 | 【实测·recipe-lab，WSL 场景】 |
| 相机在 WSL 里不可见 | 在 Windows 侧安装（`usbipd` 转发或直接在 Windows 跑 Sony-PMCA-RE） | 【实测·recipe-lab】 |

---

## 9. 待本项目验证的问题（第一次接上相机后按顺序做）

| # | 问题 | 怎么验 |
|---|---|---|
| Q1 | 机型号、固件版本、`MENU → Application` 是否存在 | 相机菜单 + `ScalarProperties.getString("model.name")` |
| Q2 | `CameraEx` 的反射签名在真机上是否如文档所写 | 开发者菜单的「软件自检」 |
| Q3 | `ParametersModifier` 的哪些方法真实存在（§4 表格里标【未验证】的那些） | 逐方法反射并打印结果 → `caps.txt` |
| Q4 | 这台机身上有哪些键（`getKeyStatus(scan).valid`） | 开发者菜单的「能力清单」+「按键日志」 |
| Q5 | 间隔的实测精度：连续拍 100 张，每张的实际 `at` 与写卡耗时 | 会话日志（`PLAN.md` §5.2） |
| Q6 | 存储卡写满 / 拔卡时相机的行为与文件计数的可观测性 | 故意塞满卡跑一次，看 `Frames` 能否在 N 秒内判定 |
| Q7 | 计划能否安全地存进一个自有槽位（`PLAN.md` §3.3 方案 A） | 快照/差分找出候选槽位 → 写 → 关机再开机 → 回读 |
| Q8 | 长拍摄中插拔 USB / 相机弹窗后，续拍是否真的可行 | 4 小时连拍 + 中途打断 |
| Q9 | `APO/NO` 广播在这台机身上是否真的阻止了自动关机 | 关掉省电设置，拍 20 分钟看是否自动关机 |
| Q10 | 静音快门 / 驱动模式 / 曝光锁在 AE 与 Drive 菜单里的真实取值范围 | 菜单对照 + 槽位回读 |
| Q11 | **这台机身是否自带「间隔拍摄」功能**（若有，它就是「不自己驱动快门」降级方案的实现基础；见 `PLAN.md` M7 注） | 相机菜单逐项找 + 菜单差分找它的槽位 |
| Q12 | 连续拍摄的稳定性上界：多少张之后相机会死机 | 30 分钟 / 2 小时 / 4 小时的连拍，记录张数与是否有异常；兼容性文档里要写出「验证到多少张」 |
