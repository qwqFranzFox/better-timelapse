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
| A10b | **退出规则已落地**（A10 的后半句终于有实现）：`onPause` 且 `isFinishing()` 时，把 `stored`（= 开相机时从机身读到的值，A25）推回机身，只写真正不同的行；AEL 只在本 app 锁过时解锁 | 此前 app 在机身上改过静音/驱动/AEL 就留在那里，退出后相机处在「上一次运行要的状态」——退出规则从第一版就写在文档里，却没有任何调用者 | `MainActivity.restoreCameraSettings()` + `Plan.driveWire()`（两处调用点共用一种拼写，避免「一个方向生效、另一个方向不生效」）；`aelLockedByUs` 跟踪而非猜测 |
| A10 | 设置存储写入遵守五条安全规则（写前逐槽位查只读、单字节、失败写明槽位与字节数、退出时写「已存储值」、不碰固件） | skill 的 tripwire 与 recipe-lab 的实测 | 任何写路径都必须先过 `Store.lockedFrom()` |
| A11 | 完成屏 = 结果摘要 + 三个下一步；推广只允许出现在 About | 老 app 的完成屏是捐款/邮箱/Instagram（[PAIN-POINTS U-04](PAIN-POINTS.md)） | 需要一个「结果摘要」纯类（`Hud`） |
| A12 | 界面语言用 Java 文本表 + 打包裁剪 CJK 字体，不用 Android 资源 | API 10 上 locale/字体机制不可靠；recipe-lab 已验证（`Lang` + `assets/fonts` + `LangTest` 查 cmap 字形） | 新增语言 = 加一张表 + `subset-font.py` 重新裁字体 |
| A13 | 文档与文件产物分语言：**屏上是中文，写进文件的日志一律英文** | 兼容性报告要引用这些文件，英文便于贴进 issue | `Session`/`DevTools` 的文本不查 `Lang` 表 |
| A14 | 当前阶段先做文档与规划，不产出 APK | 相机未连接、工具链未就位，任何「构建绿」都不是证据 | 见 [PLAN.md](PLAN.md) §6 的 M0 前置 |
| A15 | **构建一律走容器，由用户执行。agent 不在本机安装工具链** | 本机沙箱不允许：`/usr` 是 `nosuid`，rootless podman 的 `newuidmap` 拿不到 capability；`/run`、`/home` 只读，`/opt` 不可写。之前我在工作区里装过一份 4 GB 的 `.toolchain` 跑构建，用户明确否掉了 | 改完代码后**不要自己跑构建**；只在必须验证的节点停下来，让用户执行 `./build-container.sh`，然后把输出贴回来。`tools/setup-toolchain.sh` 只作为「宿主机没有容器运行时」的逃生口保留，仓库不依赖它 |
| A16 | 验证脚本在缺可选依赖时**只跳过并说明，不让构建失败** | 已经踩过：镜像里没有 `python3`，`build.sh` 第 7 步直接以非零退出，而那时 APK 已经签好了 —— 一个可选工具不该把构建判死 | `build.sh` 用 `command -v python3` 判断并打印 `NOT CHECKED` 提示；同时 `docker/Dockerfile` 装上 `python3`，让正常路径始终真的检查 |
| A17 | 每帧结束**必须** `cancelTakePicture()`；快门调用被拒要**重试**，并且单独算一种故障（`FAULT_SHUTTER`），不算「未写入」 | 真机实测（见 [PLATFORM-FACTS](PLATFORM-FACTS.md) §4）：按下中心后**只拍成 1 张**，之后三次调用全被拒，屏上却报「连续 3 张未写入」。老 app 每次 `onShutter` 都无条件 `cancelTakePicture()`（TimeLapse `ShootActivity.java:364`）—— 这一条它是对的，我漏了；而「相机不收这一枪」和「文件没落盘」是两种病，报成一句诊断不了任何东西 | `settleFrame()` 开头 `camera.cancel()`；`fire()` 失败重试 4 次 × 500ms（重试前先 `cancel`）；`Engine.onCallFailed` → `FAULT_SHUTTER`（「快门调用失败」）；平台原话上 `evidence` 行，故障屏同时显示「已落盘 N 张」与最后一次失败原因 |
| A18 | 读卡计数**递归整棵 DCIM**；「app 看不到卡」永远不等于「拍失败」 | 真机实测：同一时刻递归数 `DCIM` 得 669，只数一层子目录得 668 —— 相机确实会写到 roll 子目录以外。只数一层意味着计数永不增长，每一张都被判「未写入」（而且这正是上一轮的故障原因）。另外，把「app 自己的盲区」当成「拍摄失败」直接判故障，是把测量失败读成了负面证据 | `Card.walk()` 递归并记录最新文件**全路径**；面板加「最新 \<文件\> \<n\>前」一行；卡计数不可用时走 `EV_UNVERIFIED`（照拍、标「未核实」），不再计入连续失败 |
| A20 | **拍摄固定的两条：① 走「按下/放开」一对（`takePicture` → 机身窗口 → `cancelTakePicture` + `startPreview`）；② 相机必须一直有活跃预览流**（`SurfaceView` 藏在面板后面，用户看不见）。机身每张所需时间**不偷偷加进间隔**，而是开拍前告警 | 真机现象：`BurstableTakePicture failed`。recipe-lab —— 在**你这台机身**上拍成 77 张的那个 app —— 用的就是这条序列，常量 `SHUTTER_MS = 2500`，而且它一直维持一个 `SurfaceView` 预览；本 app 此前**完全没有预览流**，`burstableTakePicture` 在第一次之后必被拒。同时这也解释了「时间还是不对」：**2s 间隔在这台机身上物理做不到**（需要 ≥2.9s），此前能拍出东西只是因为 app 自己慢到把周期撑到了机身能接受的长度 | `SonyCamera.fire()/release()/attachPreview()/resumePreview()`；`MainActivity` 加 `SurfaceView` + `SurfaceHolder.Callback`；`Frames.PRESS_MS=2500`、`shortestIntervalMs()`、`pressWindowMs()`；开拍前 evidence 行：`间隔 2s 短于本机身所需的 2.9s：相机会来不及` |
| A25 | **开相机时用机身自己的值给计划打底**（`Plan.seed`：`silent-shutter-mode`、`drive-mode` 读回即基线），并且**机身侧设置两个方向都下发**（能关也要能关） | `Plan.stored` 的注释一直写着「相机设置存储里持有的值」，但实现上它只是本 app 默认值的副本 —— 于是「未存」标记与退出规则都是关于空气的陈述，而下发变成单向开关：**本 app 打开过的设置永远关不掉**，因为它以为机身本来就持有默认值（U-07 换个地方复现）。同时发现「静音快门」那一行的说明文字**写反了**（把「不动机械快门」写在了关闭状态） | `SonyCamera.silentShutterHeld()/driveModeHeld()` + `Plan.seed()`；`applyPlanToCamera()` 对静音与 AEL 两个方向都调用（AEL 的「关」方向报不出来，因为 modifier 路线没有回读，所以只对「开」报失败）；`Hud.rowDetail(ROW_SILENT)` 改成说后果：电子快门，无声 / 机械快门，有声 |
| A27 | **机身每张需要多久，由「被拒」当场学出来**（`BodyCycle`：一次被拒发生在上一帧开始后 `T−P`，就证明机身至少忙了 `T−P`），并把它用于①开拍前告警②面板读数 `机身 >X.Xs`；同时**快门回调延迟成为曝光的唯一信号**：回调平均/最长多久到，按下窗口就跟着长（`pressWindowMs(plan, exposureHint)`） | 这个平台**读不到曝光时间**，也没有 busy 标志可问，而 app 一直靠一个常数（2500ms，recipe-lab 用短曝光测的）。**一旦有人设 10s 曝光，这个常数就是错的**：我们在 +2.5s 就放开快门（可能掐断曝光），而且会继续以为机身只需要 2.5s。被拒是唯一免费、精确、不需要新 API 的证据 | `BodyCycle`（纯类，进 UNITS，8 个测试）；`MainActivity.fire()` 在被拒时 `body.refused()`、在拍成时 `body.accepted()`；`warnIfImpossible()` 用 `body.shortestIntervalMs()`；诊断行加 `机身 >X.Xs`；重试策略改为**在帧还来得及的前提下**按 0.5s→4s 退避（上限 12 次 ≈35s），不再固定 4 次×500ms 就放弃 |
| A26 | **app 被切走（onPause）等于这次拍摄结束**：回到前台时干净收尾（DONE + `被打断：已拍 N 张`）并重新打开相机、重新注册快门回调；不尝试「续拍」 | 相机在 onPause 被释放、快门回调被清、定时器全被取消 —— 此前回到前台什么都不做，于是引擎停在「帧进行中」而没有任何东西驱动它：**倒计时冻住、任何键都推不动**。这是最容易被误认为「app 死了」的状态。能不能安全释放再打开并续拍是 Q8 的开放问题，在没有真机证据前不做假设 | `MainActivity.onPause` 记 `runInterrupted`；`onReturn` 在回到前台时收尾 + `openCamera()`（幂等，会重新注册监听并重新下发计划） |
| A24 | **MENU 在运行中不再退出 app，而是干净地结束本次拍摄**（DONE，屏上报出已拍张数）；只有在没有运行可丢的屏上，MENU 才退出 | 老 app 的招牌故障就是这条：MENU/TRASH 一按，app 退出、运行丢失、什么都不问（U-01）。我这一版此前同样把 `finish()` 直接绑在 MENU 短按上 —— 一次误触丢掉整晚。让这个键**做不到**那件事，比事后加确认框更可靠 | `MainActivity.onKeyUp`：`engine.midRun()` 时 `stop(FAULT_NONE)` + 取消四个定时器；否则 `finish()`。`Engine.midRun()` 含 PAUSED。测试：`EngineTest.aStopWithNoFaultIsAFinishedRunAndNotAFailure` / `aPausedRunIsStillARunThePanelMustNotEditUnder` |
| A22 | **运行中只有时刻表可改**：机身侧的三个行（静音快门 / 驱动 / AEL）在运行中锁定并在行内写明原因；改 `间隔` 立即 `rebase`，从下一张起生效（不回头重排已经过去的时刻） | 那三行只在开拍时下发给机身，运行中改只会让屏上写出机身没持有的值 —— U-07「报了没生效的设置」换个地方复现。而间隔是绝对时刻表的输入：`start + n × 间隔` 意味着运行中把 2s 改成 10s 会把下一张排到几分钟之后，那不是「转一格」该有的后果 | `PanelView.edit()` 拒绝 + `Hud.rowDetail(plan, row, locked)` 给出「运行中不改机身设置」；`MainActivity.onPlanEdited` 对 `ROW_INTERVAL` 调 `engine.schedule().rebase(now, plan)` 再 `pump()`；判定用 `Engine.midRun()`（含 PAUSED，因为暂停的运行仍然占着相机与计划） |
| A23 | **屏显三态接到按键上**：长按 ↑ 循环 整屏 → 一行 → 只剩图例（运行中也可用） | 三种模式从第一版就画好了，但 `cyclePanel()` 没有任何调用者 —— 长曝光时不想让屏幕当光源，而解决这件事的模式是**够不到的死代码**。方向键的长按此前没有用途，`↓` 归演练（仅空闲），`↑` 归屏显（运行中正需要） | `MainActivity.onUpHold` → `panel.cyclePanel()`；图例加「长按↑ 屏显」 |
| A21 | **演练模式（长按 ↓ 开关）默认把静音快门打开**；退出演练恢复用户原来的值；变焦杆不再作为入口 | 用户要求「演练模式默认关闭快门声音」。演练本来就不触发快门，但机身此时仍被 app 持有、随时可能被物理快门键触发；静音快门已实测可用，所以就按「默认打开、行仍可改」处理。变焦杆这个键机身不送给 app（见 PLATFORM-FACTS §6），一个没人进得去的模式等于不存在 | 进演练：存下 `ROW_SILENT` → 置 1 → 相机开着就同步下发；退演练：写回原值（下次按中心时随 `applyPlanToCamera()` 生效）；面板提示「演练模式：不动相机、静音快门开」 |
| A19 | **间隔的时钟预算：`due` 时刻到快门调用之间不允许有任何 I/O。** 读卡一律在**工作线程**、**每帧一次**、排在 `稳定延时` 之后；「未核实」= 卡还没答复的张数，可涨可落 | 真机现象「时间还是不对」。原实现每帧在 UI 线程上走**两次**卡目录（fire 前一次、settle 后一次），再加上 400ms 轮询的看门狗也在走卡。几百个文件的 `stat` 走在相机的存储栈上足以吃掉一个 2s 间隔 —— 于是**实际周期由 app 自己的记账速度决定**，而不是由设置决定。判定位置也错了：快门回调后立刻读卡时文件还没写完，所以每一张都判「未写入」 | `fire()` 内零 I/O，基准取上一次走卡的结果；`frameTaken()` 只做 `cancel` + 记账 + 排下一帧；`onVerdict` 在工作线程走一次卡，时刻 = `max(间隔×3/4, 稳定延时)`（`Frames.verdictDelayMs`）；`稳定延时` 从此有了真实职责（决定何时读卡），间隔比它短时开拍前直接在 evidence 行告警；新面板数 `回执 n/m +Xms` 用来确认快门回调到底到没到 |

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
| Q7 | 间隔的实测精度与写卡耗时分布 | 状态条上要不要显示漂移、默认间隔建议值 | 100 张连拍的会话日志 | **部分已答**：5s 稳定可拍、2s 被机身拒（见 [PLATFORM-FACTS](PLATFORM-FACTS.md) §4）；精度分布仍需 100 张以上的会话日志 |
| Q8 | `onPause` 期间相机能不能安全释放再重新打开（续拍的前提） | 长拍摄的生存策略能不能成立 | 拍摄中插拔 USB / 触发弹窗 | 未知 |
| Q9 | 包名与应用显示名（首次真机安装后不可更改） | 决定 `AndroidManifest` 与所有源码目录 | 定名后写进 `docs/PLAN.md` §3.4 | 暂定 `com.voxivoid.bettertimelapse` |
| Q10 | 是否需要 `INTERNET` 之外的权限；`WRITE_EXTERNAL_STORAGE` 在 API 10 上的行为 | 权限最小化 | M1 骨架安装后验证目录可读 | 未知 |
| Q11 | 要不要申请 `WAKE_LOCK`（recipe-lab 没有，也完全没有长时运行机制） | 直接决定「数小时拍摄」这个核心承诺能不能成立 | M7：实测「屏幕关闭但 app 在前台」时进程会不会被杀；再实测拿住 `WAKE_LOCK` 是否真的延长存活。**测出来之前不加** | 未知 |
| Q12 | 本机身是否自带间隔拍摄；若有，是否改成「app 只负责配置它」的降级方案 | 决定快门由谁驱动 —— 这条比 M7 的其它任何事都更影响风险 | 相机菜单找 + 菜单差分找槽位（`PLATFORM-FACTS` Q11） | 未知 |
| Q13 | `Handler.postDelayed` 用的是 **uptime** 时钟，而时刻表用 **elapsedRealtime**：AP 一旦进入深度休眠，两者会分开（休眠期间 uptime 不走），定时器相对时刻表会「变长」 | 长时拍摄的准时性；也是「跳过」会不会在休眠后被误触发的来源 | 关屏/挂机一段时间后看会话日志里目标时刻与实际时刻的差；若确认会休眠，就把定时改成「算出目标时刻、用 elapsedRealtime 自校正」的短周期轮询 | 未知（理论缺口，未在真机上观察到） |

---

## 记录约定

1. **任何时候得到一个真机结论，就把它追加进 [PLATFORM-FACTS.md](PLATFORM-FACTS.md)**，并写清「哪台机身、哪个固件、怎么测的」。这一条比结论本身重要。
2. **区分「实测」与「读源码」：** 报告、PR 描述、提交信息里都要写明。构建绿、单元测试绿**都不是**相机行为的证据。
3. **一次只改一个相机菜单项**再跑差分 —— 相机会自行改写无关条目。
4. **推翻一个「已定」条目时，要连证据一起写进本文件**，不要静默改掉。
