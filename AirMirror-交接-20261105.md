# AirMirror 交接文档（2026-11-05）

> 用途：交给新会话继续执行。读完即可接手，无需回溯对话历史。
> 权威来源优先级：本文（最新动态）→《AirMirror-项目现状总览-20261105.md》（能力地图/需裁事项）→ `.workbuddy/memory/2026-11-05.md`（十轮工作日志）。

---

## 一、一页速览

- **M2 的 13 个 FS 全部落地**（U+E 级验证），四个产品裁决（T04496 安全 / T04305 首启引导 / HDR 行 / T04402）**全部收口**；T04446（冷启动）、T04396（C 级精简包）也已关单。
- 今日已提交 commit：`8f1878a`（T04496）、`e58e03c`（T04305）、`fc48972`（PRD HDR 收敛）、`1f42d5f`（README 开发取证）、`52733b9`（wrapper 腾讯镜像，环境修复）。
- **下一步建议**：① T04486（权限补救跳转设置）→ ② T04397 / T04493 二选一 → ③ 催用户裁决「待办树权威」。
- **主要阻塞**：产品裁决 4 项未拍板（见第五节 B 类）；R 级验收缺真机/发布环境；host MCP 的 mtask 工具 stale（有降级通道，见第三节）。

---

## 二、项目与环境基线

- **路径**：仓库根目录 `AirMirror`（相对工作区，不写绝对路径）；包名 `io.github.wangnan05563.airmirror`（不是 ayagames）。
- **技术栈**：Android 工程，AGP 8.7.3 / Kotlin 2.0.21 / minSdk 19（Q-5 硬下限）/ target 35 / **无 AndroidX** / JVM-only JUnit4。
- **铁律**：协议与判定逻辑一律写成不引用 `android.*` 的纯函数——这是 490+ 个 JVM 单测能跑的前提。
- **构建**（必设 JAVA_HOME）：
  ```bash
  export JAVA_HOME='/d/code/Java/zulu17.52.17-ca-jdk17.0.12-win_x64'
  ./gradlew testDebugUnitTest assembleDebug --console=plain --offline
  ```
- **ABI splits**：armeabi-v7a / arm64-v8a 独立包 + universal（含 x86_64）。**模拟器只能装 universal 包**（`.mtask_tmp` 历史冒烟脚本已统一改指 `app-universal-debug.apk`）。
- **模拟器**：`emulator-5554`（x86_64 / API 37）。取证拓扑：模拟器内双端 + `adb forward tcp:14523 tcp:41523` + `adb reverse` + Node 中间代理抓包（详见 README「开发取证」）。
- **磁盘**（⚠️ 今天 D 盘曾 100% 满阻塞构建，现恢复 15G 可用）：构建前先 `df -h /d /c /e`；C 盘也仅 3.1G。`/d/code/gradle_cache`（1.9G，单版本 8.9 在用）与 `/d/android`（SDK 2.2G）**不可删**。

---

## 三、mtask 工作纪律（硬规则）

1. **回传通道**：host MCP 的 mtask 工具 stale（报 `no valid session`），一律走降级脚本（自管 initialize 握手，自动读写 `~/.workbuddy/mcp.json` 的连接信息）：
   ```bash
   cd /c/Users/hspcadmin/.workbuddy/skills/mtask/scripts
   python mcp-direct-call.py get|list|result|done|create|merge|raw|progress ...
   ```
2. **闭环顺序**：开工 `progress start <taskNo>` → 改代码 → 全量单测+assembleDebug → 独立 commit → `raw mtask_link_git_commit {"taskNo":"...","commits":["<完整SHA>"]}`（**参数是 commits 数组**，注意引号）→ `result <taskNo> <结论.md>` → `done <taskNo>`（不传 verified）→ `progress done`。
3. **诚实标注**：GUI/交互类、真机腿未走的单子**只传 `{status:'done'}`，绝不传 verified:true**（验证全闭环才可 `done <taskNo> verified`）。
4. **`update_task_result` 是整文替换**：追加结论前必须先 `get` 读回原 `handle_result` 再拼接，直接覆盖会丢前人证据。
5. **状态自动驱动**：get→running、result→unread、done→unread、verified:false→failed。脚本不再兜底写状态。
6. **执行会话**：`progress` 的会话标识按任务缓存在 `%TEMP%`，每次开工必须 `start` 换新标识；异常收口用 `progress fail`。
7. **派生单**：`create "<标题>" desc.md <projectName> <原单taskNo>`（第 4 参=derivedFrom 溯源）；归并 `merge <派生单> <原单> 结论.md` 后**一律归档**派生单。
8. **并发纪律**：`.workbuddy/` 是项目数据不可删；别人的 WIP 不碰；只 `git add` 自己的路径。

---

## 四、本会话（2026-11-05）已完成

| 事项 | 结果 |
| --- | --- |
| T04402 最小权限 | 收口（结论早已写满，补 done；commit `9d3e21a`） |
| T04496 自动允许×空凭据 | **落地**：`AuthGate` 空凭据 `autoAllowAt=Long.MAX_VALUE`，匿名设备不再 5 秒永久信任（commit `8f1878a`，+2 锁死测试） |
| T04305 首启三步引导 | **落地**：全屏引导卡片（局域网→后台/自启→设备名，每步可跳过+跳过全部）；默认设备名改「机型+随机两位」（杂牌回落「电视+随机两位」），`DeviceStore.ensureDefaultDeviceName()` 落盘保证稳定（commit `e58e03c`，+9 纯 JVM 单测，477 例全绿） |
| HDR 行裁决 | **落地**：PRD FS-05 新增「HDR 适用范围」（仅本地/DLNA 含 HDR 片源，镜像链路不承诺 HDR）+ 异常表收窄 + 验收行（commit `fc48972`）；总览第六节 #2/#3/#6 已标注裁定结果 |
| 6 张 R 级派生单 | **归档**为 M3/R backlog：T04483 / T04488 / T04491 / T04492 / T04499 / T04500（`mtask_set_tasks_archived`，非删除） |
| README | 补「开发取证」小节（验证拓扑 + `.mtask_tmp` 分片规范 + 取证安全纪律，commit `1f42d5f`）；版本口径与已实现/未实现速览此前已就位 |
| T04446 冷启动 2 秒 | 复核收口：结论早已写满（模拟器 5 轮实测 + 真机腿归属），done / verified=false |
| 工作日志 | `.workbuddy/memory/2026-11-05.md` 已记至第十轮 |

**工作树状态**：源码干净，未跟踪的只有 `.workbuddy/`、总览与本文（惯例不提交）。

---

## 五、剩余任务清单（按可做性分类）

### A. 立即可做（无裁决、无设备依赖）

| 任务 | 内容与口径 | 备注 |
| --- | --- | --- |
| **T04486** `[AI] 补片：缺权限补救要能「跳转设置」` | 源 T04402：被永久拒绝后的重开引导 + 跳转系统设置。纯代码片 | 参考 `RuntimePermission.kt` 及其 9 条单测 |
| **T04397** `[PRD] 2.2 协议模块按需加载机制` | C 级「协议按需加载，闲置 5 分钟释放」（PRD 1152/1405）。**注意勿过度设计**：当前接收端只有 DLNA+私有通道两个模块，AirPlay/Cast（M6）未实现；机制价值在协议变多之后 | 与 T04396（已关单）结论衔接 |
| **T04493** `[派生][3.2] 握手身份锚` | 把投屏码绑进密钥派生，防主动替换公钥的中间人。安全增强片，纯逻辑可单测 | KeyExchange/Handshake 是纯函数，好下手 |

> ✅ **T04396（C 级 32 位精简包）已于本会话关单**：release 三包实测各 1,096,329 B（≈1.05MB），C 级 <28MB 余量 96%（done / verified=false，构建环境事故记录见其结论）。

### B. 等用户裁决（总览第六节剩余项）

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | **待办树权威** | 归档旧 D 树 vs 257 张平铺新树。**阻塞面最大**：19 张旧结论单口径收敛、约 40 张父节点/里程碑单收口（T04424/27/30/33/36 等）都要等它 |
| 4 | REQ-077 vs REQ-136 | 拥塞杠杆顺序；代码已按 REQ-077（先降帧率再降分辨率），建议维持，用户未最终拍板 |
| 5 | 音量归属语义 | T04208 第四项：明确「手机调音量不影响镜像」并按 playback-capture 语义补测 |
| 7 | 许可证/域名 | Q-3/Q-4/Q-6 + 5.x 法务单（T04438 等）全悬在此 |
| — | T04494 流量形状掩盖 | 帧头明文暴露是否需要掩盖，需产品裁定 |

### C. 缺环境（R 级，在册未归档）

- **T04487**：3.4 权限腿待真机与低版本（API 23–32 通知非运行期 / 11 以下采不到声音 / 中途撤销）复验。
- **T04497**：设备维度连接限流需两台不同 IP 真机取证。
- **T04495**：公网中继上重跑「中继不可解密」判据。
- 已归档 backlog（环境到位后从归档恢复）：T04483 / T04488 / T04491 / T04492 / T04499 / T04500。

### D. 里程碑后续（M3+）

- **FS-04 跨网中继**：依赖公网中继服务器，未开工。
- **M5/M6 协议兼容**：AirPlay 接收端（T04375）、Google Cast（T04377）、华为 Cast+（T04378）均未开工；Miracast 不承诺（PRD 已定口径）。
- 里程碑父单（M0/M1/M2/M3/M4…）按旧分诊口径「先子后父」收口，等裁决 B#1。

---

## 六、注意事项与坑

1. **磁盘与 Gradle 缓存事故（已修复，接手者须知）**：
   - D 盘今天曾 0 可用；期间 机器级 Gradle 缓存目录（仓库外，含 gradle-8.9 发行版与全部依赖缓存）被外部清理整目录删除，满盘期还写坏了 transforms。
   - **已修复**：wrapper `distributionUrl` 改腾讯镜像（commit `52733b9`，原源 45KB/s 断流 vs 镜像 45 秒 130MB）；依赖经机器级 init 脚本（该缓存目录下的 `init.d/cn-mirrors.gradle`，阿里云源，不入库）重建；全量单测+双构建已绿，热构建恢复 ≈1 分钟。
   - 若日后再遇 transform 反序列化失败：`./gradlew --stop` 后删报错点名的 `caches/8.9/transforms/<hash>` 条目，Gradle 离线自动重建。
   - C 盘仍仅 3.1G，构建前 `df -h /d /c /e`。
2. **host MCP mtask stale**：直接调 `mcp__mtask__*` 会报 no valid session；建议用户在 MCP 设置 re-trust mtask 服务器，期间用第三节降级脚本。
3. **后台残留**：本会话结束时有一个 release 构建后台任务（vZ6EDI 之后的重建）在等 Gradle 发行版下载，另有一个 `release_build*.log` 落在 `.mtask_tmp/`。新会话直接看产物目录即可，无需接续后台任务。
4. **`.mtask_tmp` 历史平铺文件不搬家**：任务结论里引用了路径，搬了就断链；新取证按 README 新布局 `<片号>_<主题>/{scripts,evidence,logs}`。
5. **测试期望易错点**：`Onboarding.defaultDeviceName` 保留中文机型字符（`小米电视-42` 而非 `XiaomiTV-42`）；`AuthGate` 空凭据断言 `Long.MAX_VALUE`。
6. **⚠️（历史条目，已解决）Gradle 缓存「损坏+误清」事故**：详见第 1 条；wrapper 镜像化与依赖缓存重建均已完成，新会话直接构建即可。
7. **模拟器限制**：无法回环广播发现、无法判 HDR/真机延迟/冷启动达标（软件渲染），相关判据一律归 R 级。

---

## 七、关键文件索引

| 文件 | 作用 |
| --- | --- |
| `AirMirror-PRD-v1.1.md` | PRD（文件名 v1.1、内容 v1.4，README 已注明保留原因） |
| `AirMirror-项目现状总览-20261105.md` | 能力地图、需裁事项（第六节）、收束动作清单（第十节） |
| `.workbuddy/memory/2026-11-05.md` | 十轮工作日志（含每片 commit、教训、欠账） |
| `C:/Users/hspcadmin/.workbuddy/skills/mtask/scripts/mcp-direct-call.py` | mtask 降级通道（用法见脚本头注释） |
| `app/src/main/java/.../util/Onboarding.kt` | 首启纯逻辑（默认设备名/清洗）+ `app/src/test/.../OnboardingTest.kt` |
| `app/src/main/java/.../pairing/AuthGate.kt` | 授权门（T04496 空凭据口径） |
| `.mtask_tmp/fs14_coldstart2.sh`、`.mtask_tmp/coldstart_*.txt` | 冷启动实测脚本与原始数据（T04446） |

---

## 八、接手后建议动作序列

1. **做 T04486**（权限补救跳转设置，独立 commit + 闭环）。
2. **T04397 或 T04493** 二选一开工（建议 T04397：FS-12 同主题收尾）。
3. **向用户催裁决**：B 类四项，重点是「待办树权威」——它解锁 19 张旧结论单与约 40 张父节点的批量收敛。
4. 有余力：把「19 张旧结论单口径过时」的清单实际枚举出来（对照当前实现逐张核对），等树裁定后一次性收敛。
