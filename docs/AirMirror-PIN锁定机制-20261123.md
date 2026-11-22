# AirMirror PIN 锁定机制与三模式落地（T06114）

| 项 | 内容 |
| --- | --- |
| 任务 | T06114《开发 PIN 锁定机制》 |
| 关联需求 | REQ-005（三种模式、撤销即时生效）、REQ-022（PIN 锁定跨重启）、REQ-017（错误 5 次锁 60 秒且倒计时可见） |
| 验收项 | AC-05（30 天未用重新确认、PIN 锁定跨重启、撤销信任立即断开） |
| 日期 | 2026-11-23 |
| 代码基线 | `PinCode.kt`、`PeerMask.kt`、`AuthGate.kt`、`DeviceStore.kt`、`Pairing.kt`、`ReceiverSession.kt`、`ReceiverActivity.kt`、`SenderActivity.kt`、`ErrorNotice.kt`、`A11yCopy.kt`、`BrowserCastServer.kt` |
| 截图证据 | `build/pin-locked-20261123.png`（Robolectric 1920×1080 TV 比例） |

## 01 基线问题

T06113 已交付三档模式的 UI 与持久化，但明确把行为验收留给 T06114/T06115；T06114 开始时还有四个缺口：

1. `AuthGate` 只认 6 位投屏码 / 16 位扫码令牌，`AccessMode.PIN` 与 `TRUSTED_ONLY` 只是落盘值，不改变放行结果。
2. 没有 4 位 PIN、失败计数、60 秒锁定，也没有跨重启的锁定快照。
3. TV 待连接页在 PIN / 仅信任模式下仍显示 6 位码与二维码，等于给出一条绕过 PIN 的旁路。
4. 失败来源若直接写日志或落盘就是完整 IP，违反“IP 存掩码”。

本任务闭环 **PIN 状态机 + 三模式判定 + TV/发送端 UI + 错误文案 + 来源掩码**；真机 M2 联调仍归 T06115。

## 02 三档模式的最终行为

`AuthGate.authorize(deviceId, deviceName, credential, peer)` 先查拒绝静默期，再按 `DeviceStore.accessMode()` 路由：

| 模式 | 可信设备 | 未知设备 | 6 位投屏码 / 扫码令牌 | PIN 成功后的信任 |
| --- | --- | --- | --- | --- |
| `CONFIRM` 每次确认 | 免确认，续期 | 空凭据进 `PendingPrompt`，由电视显式确认 | 一次性放行 | 不写信任列表 |
| `PIN` | 免确认，续期 | 只认 4 位 PIN | **拒绝**，不消费码/令牌 | 一次性会话，不写信任列表 |
| `TRUSTED_ONLY` 仅信任设备 | 免确认，续期 | 一律 `TRUSTED_ONLY` | **拒绝**，不消费码/令牌 | 不适用 |

关键约束：

- PIN 档的 6 位码与 16 位扫码令牌都不参与判定；防止“切到 PIN 后仍用二维码绕过”。
- 仅信任档即使递上当前有效的 6 位码或令牌也拒绝；白名单是唯一入口。
- 一次性凭据在拒绝路径不被消费，切回 `CONFIRM` 后仍可正常使用。
- 撤销信任仍走 T06113 的 `host.endActiveSession()`；PIN 档的会话不写信任列表，因此“撤销”不会误伤一次性 PIN 会话。

## 03 PIN 状态机

`PinCode` 是纯 Kotlin 状态机，时钟、随机数、读写函数全部注入，JVM 单测可精确控制 60 秒边界。

| 参数/行为 | 口径 |
| --- | --- |
| PIN 长度 | 固定 4 位数字，`SecureRandom` 首次生成 |
| 失败计数 | 每次 4 位错误 +1；长度不对不烧次数 |
| 锁定阈值 | 连续 5 次错误锁 60 秒 |
| 锁定期 | 正确 PIN 也不认，返回 `Locked(secondsLeft)` |
| 成功 | 清失败计数、解除锁定、清来源掩码 |
| 跨重启 | PIN、失败次数、锁定截止时间、来源掩码同一份快照落盘 |
| 机主改 PIN | 电视设置页数字键盘；保存即清失败与锁定；仅接受纯 4 位 |
| PIN 轮转 | 本任务固定 PIN；PRD 未要求成功后轮转，避免接管未完成时提前换 PIN |

落盘键为 `DeviceStore.KEY_PIN_STATE = "pin_state_v1"`，编码为：

```text
# AirMirror PIN v1
<4 位 PIN>\t<0..5 失败次数>\t<锁定截止毫秒>\t<来源掩码>
```

未知版本头、坏行、越界失败次数一律返回 `null` 并重新生成，不沿用可能被读出的旧值。

## 04 来源掩码

`PeerMask` 只保留可定位网段，不保存完整地址：

| 输入 | 输出 |
| --- | --- |
`192.168.1.42` | `192.168.1.*`
`192.168.1.42:54321` | `192.168.1.*`
`fe80:1:2:3:4:5` | `fe80:1:2:3:*`
`[fe80::1%eth0]:1234` | `fe80:1:*`
空 / 非法 / 越界 IPv4 | 空串

锁定日志只写 `peer=${PeerMask.mask(peer)}`；`SharedPreferences` 快照中也不会出现完整 IP。

## 05 TV 与发送端 UI

### TV 待连接页

- `CONFIRM`：保持原有设备名 + 6 位码（环形倒计时）+ 二维码。
- `PIN`：隐藏 6 位码与二维码，显示 4 位 PIN（`7 5 0 3` 可读分组）与“在发送端输入这 4 位数字”。
- 锁定期：主等待页直接显示 `已锁定 · N 秒后可重试 · 来源 x.y.z.*`，倒计时每秒刷新；不藏在“更多信息”里。
- `TRUSTED_ONLY`：显示“仅信任设备”和“新设备需先在电视加入信任列表”，不暴露任何可输入凭据。
- 模式切换后立即刷新等待页；浏览器入口同时隐藏。

### TV 设置页

- 访问控制分组新增“电视 PIN：xxxx · 双击修改”。
- 修改 PIN 走遥控器可用的 0–9 数字键盘 + 清空/删除，不使用 `EditText`；保存按钮满 4 位才可用。
- 所有键盘键、PIN 行补 `UiKit.touchTarget()` 与 `A11yCopy` 描述；设置页继续由 `TvFocus.applyTree()` 接管焦点。
- 每秒倒计时只改可见文本；读屏描述仅在“展示/锁定”翻转时更新并播报，避免每秒打断。

### 发送端

- 输入框接待 4–6 位数字，提示改为“电视上的 6 位投屏码，或 PIN 模式下的 4 位 PIN”。
- 4 位走 PIN 路径，6 位走原投屏码路径；手动 IP 直连同样接受 4 位 PIN。
- 新增 `PIN_REQUIRED / PIN_WRONG / PIN_LOCKED / TRUSTED_ONLY` 四个 `ErrorNotice.Cause`，每条都有标题、下一步与动作，且回查中文原因能归到正确 Cause。

### 浏览器入口

`BrowserCastServer` 的 token 页面与上传接口在非 `CONFIRM` 模式返回 404；`ReceiverHost.browserCastUrl()` 同时返回 `null`，TV 页不展示入口。避免“PIN/仅信任”模式下用旧 token 绕过。

## 06 验证

### 定向单测

```powershell
$env:JAVA_HOME='D:\code\Java\zulu17.52.17-ca-jdk17.0.12-win_x64'
$env:ANDROID_HOME='E:\Android\sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain `
  --tests "io.github.wangnan05563.airmirror.pairing.PinCodeTest" `
  --tests "io.github.wangnan05563.airmirror.pairing.PeerMaskTest" `
  --tests "io.github.wangnan05563.airmirror.pairing.AuthGateAccessModeTest" `
  --tests "io.github.wangnan05563.airmirror.error.ErrorNoticePinTest" `
  --tests "io.github.wangnan05563.airmirror.ui.PinAccessUiTest" `
  --tests "io.github.wangnan05563.airmirror.ui.AccessibilityManifestTest" `
  --tests "io.github.wangnan05563.airmirror.web.BrowserCastServerTest"
```

结果：**定向 56 项通过**，0 failure / 0 error。覆盖：

- `PinCodeTest` 8 项：生成/持久化、4 次计数、第 5 次锁 60 秒、锁定期正确 PIN 也拒绝、跨实例重启、长度错误不烧次数、成功清账、坏表重生成、codec 往返。
- `PeerMaskTest` 6 项：IPv4、IPv4:port、IPv6、方括号/zone/端口、非法输入、IPv4-mapped IPv6。
- `AuthGateAccessModeTest` 8 项：PIN 档只认 4 位、令牌路径也拒绝、可信设备免 PIN、5 次锁、倒计时解锁、仅信任档拒绝码/令牌、白名单放行、确认档不把 4 位当 PIN。
- `ErrorNoticePinTest` 3 项：四个 Cause 的标题/下一步/动作、中文原因归因、渲染文案回查。
- `PinAccessUiTest` 5 项 + 截图：PIN 页隐藏码/二维码、锁定倒计时与掩码、仅信任提示、遥控器数字键盘改 PIN。
- `AccessibilityManifestTest`：Receiver 接线点从 29 增至 31。
- `BrowserCastServerTest`：访问控制关闭时 token 页面返回 404（含原有 13 项回归）。

### 全量 Android 单测

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain
```

结果：**111 个测试类、839 项测试，0 failure / 0 error / 0 skipped**。T06113 的 808 项基线保持通过，新增 31 项（PinCode 8 / PeerMask 6 / AuthGateAccessMode 8 / ErrorNoticePin 3 / PinAccessUi 5 / BrowserCastServer +1）。

### 截图证据

`build/pin-locked-20261123.png`：1920×1080、`w960dp-h540dp-320dpi`，PIN 模式等待页；PIN、60 秒锁定倒计时与 `192.168.1.*` 来源掩码同屏可见。

### 未覆盖 / 需真机验证

- 真实电视 D-pad 在数字键盘上的九宫格焦点顺序、TalkBack 连续输入体验：Robolectric 只证明可点与文案。
- 真实断电/强杀进程后 `pin_state_v1` 的锁定恢复：单测已模拟跨实例，仍需 T06115 真机断电验证。
- 系统时间被向前/向后篡改时的 60 秒锁定与 30 天 TTL 边界：T06115。
- 真实路由器下不同源 IP 的掩码记录：单测已覆盖 IPv4/IPv6 规则，真机来源 IP 仍待联调。
- DLNA 推送与 AMRC 之外的入站路径不在本任务白名单判定范围；浏览器 token 已单独收口，DLNA 行为仍沿用既有基线。

## 07 文件与下一步

| 文件 | 变更 |
| --- | --- |
| `pairing/PinCode.kt` | 新增 4 位 PIN 状态机与 `PinStateCodec` |
| `pairing/PeerMask.kt` | 新增 IPv4/IPv6 来源掩码 |
| `pairing/AuthGate.kt` | 四参数 `authorize`、三模式路由、PIN/仅信任拒绝原因 |
| `pairing/DeviceStore.kt` | 新增 `pin_state_v1` 读写 |
| `pairing/Pairing.kt` | 注入 `PinCode`，`AuthGate` 读取 `accessMode()` |
| `receiver/ReceiverSession.kt` | 透传 peer、映射 PIN/仅信任原因、锁定日志只写掩码 |
| `receiver/ReceiverHost.kt` | 非确认模式隐藏浏览器入口并关闭 token 服务 |
| `ui/ReceiverActivity.kt` | PIN 等待页、锁定倒计时、仅信任提示、设置页 PIN 行与遥控器键盘 |
| `ui/SenderActivity.kt` | 接受 4/6 位凭据，更新提示与手动 IP 分支 |
| `ui/A11yCopy.kt` | PIN 展示/锁定/键盘/仅信任文案 |
| `error/ErrorNotice.kt` | 四个 PIN/仅信任 Cause 与中文原因归因 |
| `web/BrowserCastServer.kt` | `allowed` 开关，非确认模式 404 |
| `ui/TrustSettingsUiTest.kt` 等 | 更新 Receiver 接线点计数与 PIN 文案样本 |

下一步 **T06115**：M2 真机联调与 Win 测试，重点验证遥控器键盘、断电重启锁定、撤销投屏 1 秒内断开与 PIN 端到端时延。
