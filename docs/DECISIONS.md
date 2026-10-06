# 关键决定与开放问题（决策记录）

规则：**已经定下来的写进「已定」，没定的写进「开放」，有证据推翻的决定要连证据一起改。** 每条都写明理由与影响面，避免下一个会话（或下一个人）重新讨论一遍。

---

## 已定（除非有真机证据推翻）

| # | 决定 | 理由 | 影响 |
|---|---|---|---|
| A1 | 不沿用老 app 的代码，只沿用它的**设计**（Handler 主循环、ShutterListener、关屏、恢复自动关机） | 老 app 的代码与 Gradle/jcenter/SNAPSHOT 工具链绑死，无法构建（[PAIN-POINTS D-01](PAIN-POINTS.md)） | 新仓库从骨架开始 |
| A2 | 不用 Gradle。七步脚本：ndk-build → aapt → javac → d8 → aapt package → zipalign → apksigner | recipe-lab 已用同一套工具链证明可行；Gradle 5.4.1 + AGP 3.5.0 + jcenter 已不可用 | `build.sh` 与 `build.cmd` 必须同步维护 |
| A3 | 工具链钉死：JDK 17 + build-tools 30.0.3 + platform 28 + **NDK r16b** | 只有 r16b 带 GCC 4.9，更晚的 NDK 编不出 `armeabi` + `stlport_static` + `android-14` 这个组合 | 宿主机装不上就走容器构建（容器只构建，安装仍走宿主机 USB） |
| A4 | 所有 Sony 类调用**只允许**出现在 `Camera.java`，一律反射 + `catch (Throwable)` + 降级 | 构建环境没有索尼 stub jar；反射失败必须是正常分支 | 反射是唯一入口，`CameraTest`/`KeyProbeTest` 用 test double 验证「相机不在场时返回 null 而不抛异常」 |
| A5 | 不用机型表判断能力。一律问相机（`ScalarInput.getKeyStatus(scan).valid`、`ScalarProperties.getString(...)`） | 没有一张机型表能被维护到可信（A5100/A5000 无 Fn、A7S II 的 AEL 无效、DISP 在不同机身语义不同） | 厂商差异通过「探测 + 降级 + 图例只画真有的键 + 开发者菜单能力清单」吸收 |
| A6 | 按键语义与 recipe-lab 保持一致：**方向键 = 换行，左右 = 改值，滚轮 = 快速改值** | 两个 app 的操作记忆可以互用；recipe-lab 的模型已在真机上用过 | 面板/菜单/列表/确认框四种屏共用同一套键语义 |
| A7 | 自绘 Canvas UI，不用 Android 控件 | 老 app 的控件焦点不可见、`dialChanged` 同时喂三条刻度条（[PAIN-POINTS U-12/D-11](PAIN-POINTS.md)） | `PanelView` / `MenuView` / `PromptView` 自己持有「选中行」状态 |
| A8 | 计划存**值 + 单位 + schema 版本**，不存 UI 刻度位置 | 老 app 存 `rawInterval` 这类刻度下标，刻度表一改旧存档就指向别的值 | 与老 app 的存档不兼容（本来也不迁移） |
| A9 | 张数由**存储卡上的文件**确认（目录内新增文件计数），而不是只数快门回调 | 老 app 的 `shotCount++` 无条件执行，卡满/写失败照旧计数 | 需要 `WRITE_EXTERNAL_STORAGE` 才能读 DCIM 目录；`Frames` 是纯类，目录观测在外壳 |
| A10 | 设置存储写入遵守五条安全规则（写前逐槽位查只读、单字节、失败写明槽位与字节数、退出时写「已存储值」、不碰固件） | skill 的 tripwire 与 recipe-lab 的实测 | 任何写路径都必须先过 `Store.lockedFrom()` |
| A11 | 完成屏 = 结果摘要 + 三个下一步；推广只允许出现在 About | 老 app 的完成屏是捐款/邮箱/Instagram（[PAIN-POINTS U-04](PAIN-POINTS.md)） | 需要一个「结果摘要」纯类（`Hud`） |
| A12 | 界面语言用 Java 文本表 + 打包裁剪 CJK 字体，不用 Android 资源 | API 10 上 locale/字体机制不可靠；recipe-lab 已验证（`Lang` + `assets/fonts` + `LangTest` 查 cmap 字形） | 新增语言 = 加一张表 + `subset-font.py` 重新裁字体 |
| A13 | 文档与文件产物分语言：**屏上是中文，写进文件的日志一律英文** | 兼容性报告要引用这些文件，英文便于贴进 issue | `Session`/`DevTools` 的文本不查 `Lang` 表 |
| A14 | 当前阶段先做文档与规划，不产出 APK | 相机未连接、工具链未就位，任何「构建绿」都不是证据 | 见 [PLAN.md](PLAN.md) §6 的 M0 前置 |
| A15 | **构建一律走容器，由用户执行。agent 不在本机安装工具链** | 本机沙箱不允许：`/usr` 是 `nosuid`，rootless podman 的 `newuidmap` 拿不到 capability；`/run`、`/home` 只读，`/opt` 不可写。之前我在工作区里装过一份 4 GB 的 `.toolchain` 跑构建，用户明确否掉了 | 改完代码后**不要自己跑构建**；只在必须验证的节点停下来，让用户执行 `./build-container.sh`，然后把输出贴回来。`tools/setup-toolchain.sh` 只作为「宿主机没有容器运行时」的逃生口保留，仓库不依赖它 |
| A16 | 验证脚本在缺可选依赖时**只跳过并说明，不让构建失败** | 已经踩过：镜像里没有 `python3`，`build.sh` 第 7 步直接以非零退出，而那时 APK 已经签好了 —— 一个可选工具不该把构建判死 | `build.sh` 用 `command -v python3` 判断并打印 `NOT CHECKED` 提示；同时 `docker/Dockerfile` 装上 `python3`，让正常路径始终真的检查 |
| A17 | 每帧结束**必须** `cancelTakePicture()`；快门调用被拒要**重试**，并且单独算一种故障（`FAULT_SHUTTER`），不算「未写入」 | 真机实测（见 [PLATFORM-FACTS](PLATFORM-FACTS.md) §4）：按下中心后**只拍成 1 张**，之后三次调用全被拒，屏上却报「连续 3 张未写入」。老 app 每次 `onShutter` 都无条件 `cancelTakePicture()`（TimeLapse `ShootActivity.java:364`）—— 这一条它是对的，我漏了；而「相机不收这一枪」和「文件没落盘」是两种病，报成一句诊断不了任何东西 | `settleFrame()` 开头 `camera.cancel()`；`fire()` 失败重试 4 次 × 500ms（重试前先 `cancel`）；`Engine.onCallFailed` → `FAULT_SHUTTER`（「快门调用失败」）；平台原话上 `evidence` 行，故障屏同时显示「已落盘 N 张」与最后一次失败原因 |
| A18 | 读卡计数**递归整棵 DCIM**；「app 看不到卡」永远不等于「拍失败」 | 真机实测：同一时刻递归数 `DCIM` 得 669，只数一层子目录得 668 —— 相机确实会写到 roll 子目录以外。只数一层意味着计数永不增长，每一张都被判「未写入」（而且这正是上一轮的故障原因）。另外，把「app 自己的盲区」当成「拍摄失败」直接判故障，是把测量失败读成了负面证据 | `Card.walk()` 递归并记录最新文件**全路径**；面板加「最新 \<文件\> \<n\>前」一行；卡计数不可用时走 `EV_UNVERIFIED`（照拍、标「未核实」），不再计入连续失败 |

---

## 开放（必须在真机上测出来再定）

| # | 问题 | 影响 | 怎么定 | 状态 |
|---|---|---|---|---|
| Q1 | 本机相机是哪台、固件版本多少、`MENU → Application` 是否存在 | 决定项目能不能做 | 相机菜单 + `ScalarProperties` | **未知（相机未连接）** |
| Q2 | 计划存哪里：自有槽位（方案 A）/ 文件（方案 B）/ 混合（方案 C） | 决定 `Store.java` 的形状与「关机再开机仍在」能否做到 | 用快照/差分找候选槽位 → 写 → 关机再开机 → 回读 | 暂定 C（见 [PLAN.md](PLAN.md) §3.3） |
| Q3 | `ParametersModifier` 的方法名是否如老 app 所写 | 静音快门/驱动/AEL/burst 四个功能能不能做 | 开发者菜单「能力清单」逐方法反射 | **未验证**（[PLATFORM-FACTS](PLATFORM-FACTS.md) §4） |
| Q4 | burst（连拍）模式在这台机身上的行为：驱动模式常量、释放行为、速度 | burst 是「间隔 0」的实现基础 | 真机逐项设值 + 观察 | 未知 |
| Q5 | 关闭屏幕（`DisplayManager`）与自动关机广播（`APO/NO`）在长拍摄中的真实效果 | 4 小时以上的拍摄可行性 | 20 分钟以上的实拍 | 未知 |
| Q6 | 存储卡写满 / 拔卡时相机的行为与目录可观测性 | `Frames` 的故障判定延迟与准确度 | 故意塞满卡跑一次 | 未知 |
| Q7 | 间隔的实测精度与写卡耗时分布 | 状态条上要不要显示漂移、默认间隔建议值 | 100 张连拍的会话日志 | 未知 |
| Q8 | `onPause` 期间相机能不能安全释放再重新打开（续拍的前提） | 长拍摄的生存策略能不能成立 | 拍摄中插拔 USB / 触发弹窗 | 未知 |
| Q9 | 包名与应用显示名（首次真机安装后不可更改） | 决定 `AndroidManifest` 与所有源码目录 | 定名后写进 `docs/PLAN.md` §3.4 | 暂定 `com.voxivoid.bettertimelapse` |
| Q10 | 是否需要 `INTERNET` 之外的权限；`WRITE_EXTERNAL_STORAGE` 在 API 10 上的行为 | 权限最小化 | M1 骨架安装后验证目录可读 | 未知 |
| Q11 | 要不要申请 `WAKE_LOCK`（recipe-lab 没有，也完全没有长时运行机制） | 直接决定「数小时拍摄」这个核心承诺能不能成立 | M7：实测「屏幕关闭但 app 在前台」时进程会不会被杀；再实测拿住 `WAKE_LOCK` 是否真的延长存活。**测出来之前不加** | 未知 |
| Q12 | 本机身是否自带间隔拍摄；若有，是否改成「app 只负责配置它」的降级方案 | 决定快门由谁驱动 —— 这条比 M7 的其它任何事都更影响风险 | 相机菜单找 + 菜单差分找槽位（`PLATFORM-FACTS` Q11） | 未知 |

---

## 记录约定

1. **任何时候得到一个真机结论，就把它追加进 [PLATFORM-FACTS.md](PLATFORM-FACTS.md)**，并写清「哪台机身、哪个固件、怎么测的」。这一条比结论本身重要。
2. **区分「实测」与「读源码」：** 报告、PR 描述、提交信息里都要写明。构建绿、单元测试绿**都不是**相机行为的证据。
3. **一次只改一个相机菜单项**再跑差分 —— 相机会自行改写无关条目。
4. **推翻一个「已定」条目时，要连证据一起写进本文件**，不要静默改掉。
