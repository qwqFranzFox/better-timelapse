# better-timelapse

一个面向索尼 PlayMemories Camera Apps（PMCA）相机的延时摄影 app：装在相机里，不需要电脑、不需要手机，能在三脚架上连续工作数小时，并且**敢让人离开**。

## 现在到哪一步了

**阶段：能拍了。** 相机上安装、开拍、写卡、逐帧核对都已经在真机上走通：**间隔 5s 连拍成功，卡上文件与张数完全一致**；间隔 2s 会被机身拒绝（这台机身的每张最小周期约 2.9s，见 [PLATFORM-FACTS](docs/PLATFORM-FACTS.md) §4）。纯核心有 **58 个单元测试**（`tools/test.sh`，不需要相机）。

| 已经成立（真机验证） | 还没有 |
|---|---|
| 拍摄链路：`takePicture` / 抓 2.5s / `cancelTakePicture` + `startPreview`，预览流常开 | 计划持久化（关机再开机还在） |
| 卡上核对：`卡增 / 核实 / 未核实 / 最新 <路径> / 读数 / 回执` | 长时运行的生存（关屏、自动关机、会不会被杀） |
| 绝对时刻表 + 跳过 + 实测间隔/漂移 | 完成屏（结果摘要）、MENU 菜单、退出规则 |
| 静音快门（演练模式默认打开）、中文渲染、三行诊断 | 会话日志（拍一晚的现场数据） |

手感（按键、屏显、误触代价）的现状与下一阶段顺序另立一份：[docs/FEEL-PLAN.md](docs/FEEL-PLAN.md)。

| 文档 | 内容 | 读者 |
|---|---|---|
| [docs/PAIN-POINTS.md](docs/PAIN-POINTS.md) | 老 app（`TimeLapse/`）的 20 条使用痛点 + 20 条开发痛点，每条都有 `文件:行号` 证据 | 想知道「为什么要重写」 |
| [docs/PLAN.md](docs/PLAN.md) | 新 app 的目标、非目标、架构与模块划分、界面规格、按键模型、数据与持久化、10 个里程碑与**每个里程碑的真机红测**、风险登记 | 想知道「要做什么、怎么验收」 |
| [docs/PLATFORM-FACTS.md](docs/PLATFORM-FACTS.md) | 平台硬事实：能力边界、Android 2.3.7 约束、签名与版本规则、反射接口、设置存储与已知槽位 ID、按键扫描码、安装链路、构建的坑 | 动代码之前先读这个 |
| [docs/DECISIONS.md](docs/DECISIONS.md) | 已定的 21 条决定（连理由）与 12 个必须在真机上测出来的开放问题 | 想改设计之前先读这个 |
| [docs/FEEL-PLAN.md](docs/FEEL-PLAN.md) | 键位现状、已确认的手感问题（P0/P1/P2）与 F1–F4 顺序 | 想知道「用起来什么感觉、下一步改哪」 |

## 一句话结论

老 app 的问题是**产品级**的而不是功能级的：没有停止、任何打断都静默终止、设置不进相机存储、收尾屏是捐款信息、张数可以虚报。所以新 app 的第一优先级不是「加功能」，而是**让一次拍摄能安全地跑到最后并且说实话**。

## 怎么构建（容器，推荐）

宿主机只需要 **podman（或 docker）+ git**。不需要 JDK、Android SDK、NDK，宿主机装的是哪个 JDK 也无关紧要 —— 镜像里钉死 JDK 17 + build-tools 30.0.3 + platform 28 + **NDK r16b**（最后一个带 GCC 4.9 的 NDK，而这个 `armeabi` / `android-14` 目标只能用那个工具链）。

```bash
./build-container.sh            # 首次会先构建镜像（约 1 GB 下载，一次性），然后执行 build.sh + tools/test.sh
./build-container.sh bash       # 进容器里的工具链
./build-container.sh ./build.sh # 只跑一条命令
```

镜像细节、为什么必须容器化、以及容器不能做的事（**装到相机上**，因为容器看不到 USB）见 [docker/README.md](docker/README.md)。

`tools/setup-toolchain.sh` + `./build.sh` 是**不用容器**的等价路径（装到仓库内 `.toolchain/`），只为「宿主机完全没有容器运行时」这一种情况保留；仓库里没有任何东西依赖它。

## 工作区里的目录

| 目录 / 文件 | 作用 |
|---|---|
| `build-container.sh` `docker/` | **构建入口**：容器化工具链 + 七步构建脚本 |
| `build.sh` | 七步构建本体（ndk-build → aapt R.java → javac → d8 → aapt package → zipalign → apksigner v1 + 验证），无 Gradle |
| `tools/version.sh` | 版本号唯一来源与 `versionCode` 公式 |
| `tools/check-apk.py` | 不依赖任何 Android 工具检查产物：minSdk ≤ 10、ABI 只有 armeabi、`.so` 真的是 ARM、v1 签名、版本号 |
| `tools/test.sh` | 离机单元测试（JUnit 5，只需 JDK 17），并把「纯类不许 import `android.*`」变成编译错误 |
| `src/` `jni/` `res/` `assets/` `AndroidManifest.xml` | **新 app 的代码**：主屏 UI 已可看可操作（见下） |
| `tools/subset-font.py` `tools/check-font.py` | 裁中文字体 + 断言字形覆盖（缺字就是豆腐块，所以这是硬门禁） |
| `jni/platform/` | `ma1co/OpenMemories-Platform`，**钉在 `5155afa`**（见下） |
| `TimeLapse/` | **分析对象**：老 app 的完整源码（最后提交 2020-06，33 个提交，0 个测试） |
| `recipe-lab-sony-pmca/` | **方法来源**：同类 PMCA app，2025 年仍在维护。七步构建、容器、离机单元测试、按键探测、Java 文本表、约束式提交流程都取自它 |
| `Sony-PMCA-RE/` | **工具**：ma1co 的安装工具。把 APK 装到相机上只有它这条路 |

## 已经落地的 UI（第 3 轮）

主屏是一整块自绘 Canvas，八个设置行、状态条、进度条、按键图例都在一屏里。**不缩放的 dp 宽度**：所有尺寸从控件实测宽高推算，换任何面板分辨率都不会被裁掉（老 app 用固定 dp 且没有 ScrollView，换台机身就溢出，U-20）。

| 文件 | 行数 | 职责 |
|---|---|---|
| `Plan.java` | 178 | 计划模型 + **想要的值 / 相机已存储的值** 两份，`revert()` 就是「退出规则」 |
| `Steps.java` | 180 | 每格值多少：`interval/张数/延时` 各有粗（滚轮）细（左右）两档，**双向可预期** |
| `Keys.java` | 168 | 扫描码、按下/长按状态机、要吞掉的键 |
| `Hud.java` | 227 | 屏上每一句文字与每一种时长格式（**时长只在这里算一次**） |
| `PanelView.java` | 447 | 自绘主屏、行选择反色、进度条、图例 |
| `MainActivity.java` | 201 | 按键分发、Handler、demo 时钟；`onKeyDown` 与 `onKeyUp` 都消费 |

**键位语义（按你的要求）：** **左右键走大档，滚轮走最小档**——扫滚轮是快而不准的动作，不能让它越过你在意的值；左右是有意按下的，所以承担跳跃。两者都写在屏上。实测：间隔 2s 处左右 ±1s、滚轮 ±100ms；张数 300 处左右 ±5、滚轮 ±1；初始延时 ±5s / ±1s。

**屏上能做什么（无需相机）：** 滚轮/左右调值 · 上下换行 · 中心走状态机（待机 → 3 秒倒计时 → 等待 → 曝光 → 完成）· TRASH 暂停/继续 · **变焦杆**跑演示（自动走完七个状态并累计张数，用来看进度条）· MENU 按住 = 菜单占位、短按 = 退出。

**老 app 的可用性缺陷在这版里被直接消掉：** 每行显示「值 + 单位 + 滚轮/左右各是多少」（U-08）；张数走到 9999 才进「不限」，两个方向都有反应；时长只有一个算式（U-10/D-18）；进度写「已落盘」并带未存标记（U-06/A8）。

**中文字体（本轮解决的真问题）：** 相机自带字体没有 CJK，装上去满屏豆腐块——这点你在相机上确认过了。现在 APK 自带一份 **Sarasa Gothic UI SC** 的裁剪子集（315 字，55 KB，`assets/fonts/`），字符集由 `tools/subset-font.py` 从 Java 源码里收集，脚本会把成品读回来验证 cmap，缺一个字就拒绝报成功；`tools/check-font.py` 做同样的检查，所以字体过期会先在这里失败，而不是在相机上变成豆腐块。选 UI 字重是因为它比正文切法更紧凑、x-height 更高，适合一眼扫的相机屏。OFL 许可证与来源说明随字体一起打包。

**状态条补全（本轮）：** 规划里的 `BAT 78%  卡: 1.2k 张余量` 现在真的有了。电量与卡余量由 `Device.java` 从平台读（`BatteryManager` 广播 + `StatFs`；用 `getAvailableBlocks()` 而不是 API 18 才有的 `getAvailableBytes()`），每 5 秒推给面板一次；两者**读不到就不显示**，不猜数字——老 app 会印一个自己没验证过的百分比。卡余量按「一帧 6MB」估算并向下取整，屏上写「卡 约 10k 张」。

**面板可见性三态（本轮补完）：** TRASH 循环 `完整 / 单行 / 隐藏`。之前 **Label 态其实是坏的**——切过去和完整态长得一样；现在它只在黑屏上画一行状态+倒计时+规划，这正是长曝光时既看得见读数、又不让屏幕成为画面里的光源的折中。

## 拍照链路（第 4 轮，本轮）

链路是「引擎决定 → 外壳执行 → 卡上确认」三段，中间没有一处靠猜：

```
按键 ──▶ Engine（纯）──── ACT_FIRE ──▶ MainActivity.fire() ──▶ SonyCamera.burstableTakePicture()
              ▲                                                        │
              │                                             快门回调 + 卡目录观测
              └──── EV_SHUTTER / onFrameLost ◀── Card.refresh() + Frames.frameArrived()
```

| 类 | 行数 | 职责 | 能离机测? |
|---|---|---|---|
| `Schedule` | 158 | 绝对时刻表 `t(n)=start+n×interval`，单调时钟，**无常数补偿项** | 是 |
| `Engine` | 327 | 七态状态机；`step(event, now)` 只返回一个动作；错误预算「连续 3 次失败才停」 | 是 |
| `Frames` | 87 | 快门数与落盘数的核算、卡余量、故障判定的**算术** | 是 |
| `SonyCamera` | 333 | 唯一反射入口；关相机、按快门、能力探测、drive/silent/AEL；全部降级 | 否 |
| `Card` | 150 | 列 `DCIM/` 目录数图片、最新文件名、按真实文件大小估帧大小 | 否 |
| `MainActivity` | 440 | 唯一的循环：`pump()` 是唯一推进引擎与唯一投递定时器的地方 | 否 |

**四条硬规则（都写在 `MainActivity` 的类注释里）：**

1. **按下与抬起都消费**——老 app 让 MENU/TRASH/快门漏给固件，相机自己的菜单能打断拍摄（U-01/U-04）。
2. **同时只有一个定时器**——`pump()` 投递前先取消，老 app 是两个 Handler 互相接力在赛跑（D-06）。
3. **没落到卡上就不算一张**——快门回调和看门狗**都去读卡**；4000ms 内没出现新文件就报「丢失」，不计入已拍（U-06/U-16）。卡从「可读」变「读不到」是卡故障，不是慢写，直接停。
4. **单调时钟**——`SystemClock.elapsedRealtime()`，相机校时不会跳（U-18 原因 2）。

**离机模拟验证**（假相机 + 假卡，驱动逻辑与 `MainActivity` 同构）：

| 场景 | 结果 |
|---|---|
| 间隔 1s × 20 张 | 已落盘 20 / 已发快门 20 / 超时 0；用时与预测一致；漂移 −1ms（整数除法） |
| 卡不写 | 尝试 3 次后 `FAULT`：**连续 3 张未写入**；已落盘 0 —— **不虚报** |
| 相机不可用 | 立刻 `FAULT`：相机不可用；只试了 1 次，不空转 |
| 卡中途拔掉 | `FAULT`：**存储卡读不到**（不是「已满」，两者措辞分开） |
| **写卡慢 800ms、间隔 1s** | 用时仍与预测一致、漂移 −1ms —— 老 app 在这里会每张多等 800ms |

**还没接的（下一里程碑）：** 真正的拍摄引擎、相机按键探测（`KeyProbe`）、菜单/预检/开发者菜单、写设置存储、多语言文本表（繁体中文需要自己的一份字体子集，同 recipe-lab 一个脚本一个文件）。

## 环境现状（实测，2026-10-07）

| 项 | 状态 |
|---|---|
| 相机 | **未连接**（`lsusb` 无 Sony 设备，`adb devices` 为空）。M1 的四连红测因此还没做 |
| podman | 已安装（`/usr/bin/podman`），但**在本沙箱里无法启动容器**：`/usr` 以 `nosuid` 挂载 → `newuidmap` 拿不到 capability；`/run` 只读 → runroot 不可写。两者都是宿主限制，不是项目问题；`build-container.sh` 会在动手前就报出原因。正常桌面不受影响 |
| JDK | 系统默认 Zulu **21**；容器路径不需要宿主 JDK |
| Android SDK | `/opt/android-sdk` 只有 `cmdline-tools` 且不可写 —— 又一个必须走容器的理由 |
| Sony-PMCA-RE | **可用**：仓库自带的 `Sony-PMCA-RE/.venv/bin/python` 里有全部依赖（`Cryptodome`/`usb`/`tlslite`），系统 `python3` 缺 `pycryptodomex`。安装命令是 `Sony-PMCA-RE/.venv/bin/python Sony-PMCA-RE/pmca-console.py install -f BetterTimelapse.apk` |

## 已踩到并记住的坑

| 坑 | 症状 | 处理 |
|---|---|---|
| `jni/platform` 版本 | 直接 clone `master` 会拿到新版平台：`osal_uipc.c` 已挪进 `stubs/*.S`，而 `Android.mk` 仍在 `drivers/` 找它，结果是 `undefined reference to 'osal_valloc_msg_wait'` | **钉在 `5155afa`**（Recipe Lab 用的同一个提交，它可构建且经过真机验证）。改动这个提交要重新验证 |
| `jni/platform/errno.h` | 它是 updater 专用的 shim，会遮蔽 NDK 的 `<errno.h>`，原生链接失败 | `build.sh` 用 `EXIT` trap 移开再还原；构建后子模块脏就是 bug |
| Android build-tools 压缩包 | 里面还套了一层 `android-11/` 目录，直接解压会得到 `30.0.3/android-11/aapt` | `setup-toolchain.sh` 逐层上提，直到只剩一个目录 |
| 只做 v2 签名 | 相机只认 v1；老 app 曾经发布过一个**只有 v2、因而装不上**的 APK | `--v1-signing-enabled true --v2/v3 false`，并且构建最后一步用 `tools/check-apk.py` 检查「没有 v2/v3 块、有 `META-INF/*.SF`+`*.RSA`」 |

## 下一步

1. **修 bug 优先**：本轮的四个逻辑 bug 已修并各有测试守着（见提交 `engine: …`）；接着按 [FEEL-PLAN](docs/FEEL-PLAN.md) 的 **F1 安全**走 —— 运行中短按 MENU 不该退出、长按 TRASH 停止要有确认、退出规则（把机身设置还原）要落地。
2. **计划持久化**（PLAN M2）：把间隔/张数写进相机的设置存储槽位，关机再开机还在。需要先做槽位快照/差分发现。
3. **长时运行**：加会话日志（每张的实测时刻与写卡耗时），跑 1–2 小时实拍，回答「能不能真的拍一晚」。
4. 每一步都以真机红测结束；构建绿不算证据。
