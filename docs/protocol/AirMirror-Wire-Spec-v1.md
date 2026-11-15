# AirMirror 私有通道线协议规范 v1（冻结稿）

- 版本：v1（对应代码 `protocol/Version.kt` 的 `CURRENT=1`、`MIN_SUPPORTED=1`）
- 适用端：Android 现有客户端、Windows/macOS PC 客户端、HarmonyOS NEXT 客户端
- 状态：**冻结**。新增能力只加 capability 位或 flags，不改 20 字节帧头布局
- 基准实现：`protocol-core`（Kotlin/JVM）
- 一致性向量：`protocol-core/src/test/resources/vectors/`

> 本规范只描述线协议（wire format），不规定 UI 与业务交互。所有多字节整数均为**大端**，除非明确标注“小端”。

---

## 1. 传输模型

| 项 | 约定 |
|---|---|
| 媒体/控制通道 | TCP，客户端（发送端）连接接收端监听端口 |
| 端口发现 | mDNS 服务类型 `_airmirror._tcp.`，TXT 记录 `fp=<设备长期指纹>` |
| 局域网兜底发现 | UDP 探测端口 `41524`、通知端口 `41525`（详见 `net/ProbeProtocol.kt`） |
| 连接方向 | 发送端 → 接收端；接收端不主动回连（跨网打洞除外） |
| 编码 | 同一 TCP 连接上双向承载帧；每帧独立解析 |
| 关闭 | 发送 `BYE` 后关闭 TCP；异常断开按断线恢复流程处理 |

---

## 2. 帧格式（20 字节帧头）

| 偏移 | 长度 | 字段 | 类型 | 说明 |
|---:|---:|---|---|---|
| 0 | 4 | magic | uint32 | 固定 `0x414D5243`（ASCII `AMRC`） |
| 4 | 1 | version | uint8 | 当前 `1`；接收端接受 `1..CURRENT` |
| 5 | 1 | type | uint8 | 帧类型，见 §3 |
| 6 | 1 | flags | uint8 | 位标志，见 §4 |
| 7 | 1 | reserved | uint8 | 必须写 0，读取忽略 |
| 8 | 8 | ptsUs | int64 | 媒体呈现时间戳（微秒）；控制帧可为 0 |
| 16 | 4 | payloadLen | int32 | 载荷字节数，必须 ≥0 且 ≤上限 |
| 20 | N | payload | bytes | 载荷，长度等于 payloadLen |

**载荷上限**：

- 握手帧（HELLO / HELLO_ACK）：`64 * 1024` 字节（未认证连接不得申请更大内存）。
- 认证后的媒体/控制帧：`8 * 1024 * 1024` 字节。
- 超限、负长度、magic 不符、版本不在支持区间、未知 type → 立即断连并记录错误。

### 2.1 帧示例（VIDEO）

```
414d5243 01 03 11 00 00000000075bcd15 00000004 deadbeef
|magic |v |t |f |rs| ptsUs            |len     |payload
```

对应向量：`vectors/frame-video.json`。

---

## 3. 帧类型

| code | 名称 | 方向 | 载荷 |
|---:|---|---|---|
| 1 | HELLO | 发送端→接收端 | 版本区间 + 能力位 + 设备名 + 可选公钥/投屏码签名 |
| 2 | HELLO_ACK | 接收端→发送端 | 选定版本 + 能力位 + 设备名 + 可选公钥/签名 |
| 3 | VIDEO | 双向（通常发送端→接收端） | H.264/H.265 码流字节 |
| 4 | AUDIO | 双向（通常发送端→接收端） | AAC 码流字节 |
| 5 | CONTROL | 双向 | `k=v&k=v` 会话指令（§7） |
| 6 | PING | 双向 | 空或任意 |
| 7 | PONG | 双向 | 空或任意 |
| 8 | BYE | 双向 | 空 |
| 9 | ERROR | 双向 | UTF-8 错误文本 |
| 10 | AUTH | 发送端→接收端 | `id=...&cred=...&resume=1`（§6） |
| 11 | AUTH_RESULT | 接收端→发送端 | `ok=1/0&reason=...` |

未知 type 必须断连，不得静默忽略。

---

## 4. flags 位

| 位 | 名称 | 含义 |
|---:|---|---|
| 0x01 | KEY_FRAME | 该 VIDEO 帧为关键帧（IDR） |
| 0x02 | CONFIG | 载荷为编码器配置（SPS/PPS/VPS），不是可显示图像 |
| 0x04 | END_OF_STREAM | 流结束 |
| 0x08 | CODEC_HEVC | 配置/码流为 H.265；仅当对端在能力位声明 H.265 时使用 |
| 0x10 | ENCRYPTED | 载荷已按 §8 加密；帧头保持明文 |

未定义位必须写 0；读取方忽略未知位。

---

## 5. 握手（HELLO / HELLO_ACK）

### 5.1 HELLO 载荷

| 字段 | 类型 | 说明 |
|---|---|---|
| minVersion | uint16 | 本端最低协议版本 |
| maxVersion | uint16 | 本端最高协议版本 |
| capabilities | int32 | 能力位，见 §5.3 |
| nameLen | uint16 | 设备名 UTF-8 字节数 |
| name | bytes | UTF-8 设备名 |
| keyShareLen（可选） | uint16 | 本端临时公钥 SPKI DER 长度；缺省表示不做加密 |
| keyShare（可选） | bytes | ECDH P-256 公钥（SPKI DER，约 91 字节） |
| proofLen（可选） | uint16 | 投屏码签名长度 |
| proof（可选） | bytes | 用投屏码对 keyShare 的签名（`CodeAnchor`） |

**兼容规则**：老接收端读到 `name` 结束即返回，尾部公钥/签名被忽略；新接收端用 `available()` 判断是否存在可选字段，长度非法一律按“本端不支持加密”处理。

### 5.2 HELLO_ACK 载荷

| 字段 | 类型 | 说明 |
|---|---|---|
| chosenVersion | uint16 | 协商后的版本 |
| capabilities | int32 | 双方能力交集 |
| nameLen | uint16 | 设备名长度 |
| name | bytes | UTF-8 设备名 |
| keyShareLen（可选） | uint16 | 接收端临时公钥长度 |
| keyShare（可选） | bytes | ECDH P-256 公钥 |
| proofLen（可选） | uint16 | 用同一投屏码对双方公钥的签名 |
| proof（可选） | bytes | 签名 |

版本协商：取双方区间的最高公共版本；无交集 → 握手失败、断连。

### 5.3 能力位

| 位 | 名称 | 说明 |
|---:|---|---|
| 0x01 | VIDEO_H264 | 支持 H.264 |
| 0x02 | AUDIO_AAC | 支持 AAC |
| 0x04 | TOUCH_INPUT | 支持触控/输入回传 |

H.265 目前通过 `CODEC_HEVC` flag 表达，不占独立能力位。

### 5.4 握手示例

```
0001 0001 00000003 0002 5043
min  max  caps=3   nameLen=2 "PC"
```

对应向量：`vectors/hello.json`。

---

## 6. 鉴权（AUTH / AUTH_RESULT）

载荷为 UTF-8 的 `k=v&k=v`，值按 `PairingLink` 的 URL 转义口径（`%XX`，未保留字符 `A-Z a-z 0-9 - _ . ~`）。

### 6.1 AUTH

| 键 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 发送端设备标识（用于断线续会/信任记录） |
| `cred` | 是 | 凭据：投屏码/一次性 token（按当前会话类型） |
| `resume` | 否 | `1` 表示断线续会，不重新消耗投屏码 |

### 6.2 AUTH_RESULT

| 键 | 必填 | 说明 |
|---|---|---|
| `ok` | 是 | `1` 接受，`0` 拒绝 |
| `reason` | 否 | 拒绝原因；续会成功时为 `resumed` |

鉴权失败后接收端应关闭连接；发送端不得自动重试同一错误凭据。

---

## 7. CONTROL 会话指令

载荷同为 UTF-8 `k=v`：

| `op` | 语义 | 其他字段 |
|---|---|---|
| `pause` | 暂停 | — |
| `resume` | 恢复 | — |
| `sync` | 请求发送端补关键帧（接收端换解码器/Surface 后） | — |
| `audio` | 音频开关 | `allow=1/0` |
| `takeover` | 请求接管会话 | `from=<设备标识>` |
| `takeover_result` | 接管结果 | `allow=1/0` |

未知 `op` 必须忽略，不得当成错误或画质上限。

---

## 8. 媒体帧与端到端加密

### 8.1 媒体载荷

- VIDEO：H.264/H.265 Annex-B 码流；`CONFIG` 帧携带 SPS/PPS/VPS，`KEY_FRAME` 标记 IDR。
- AUDIO：AAC 码流（按现有编码器输出）。
- 编码参数（分辨率/码率/帧率）通过 CONTROL 或上层业务扩展传递；v1 规范不新增字段。

### 8.2 密钥协商

1. 每条 TCP 连接临时生成 ECDH P-256 密钥对，私钥只存内存。
2. 握手交换双方 SPKI DER 公钥。
3. 共享秘密 = ECDH(私钥, 对端公钥)。
4. `transcript = SHA-256(clientPub || serverPub || anchor)`；`anchor` 为投屏码锚（无码会话为空）。
5. 双向密钥：
   - `Kc2s = HKDF-SHA256(secret, salt=transcript, info="AMRC-E2E/v1 c2s", 32)`
   - `Ks2c = HKDF-SHA256(secret, salt=transcript, info="AMRC-E2E/v1 s2c", 32)`
6. 双方都必须提供公钥才启用加密；任一侧缺失 → 整条会话明文（兼容老端）。

### 8.3 载荷加密

- 算法：AES-256-GCM，`NoPadding`，tag 128 位。
- 线上格式：`iv(12 字节随机) || 密文 || tag(16 字节)`。
- 帧头保持明文；`ENCRYPTED=0x10` 置位。
- **AAD（11 字节）**：
  - `aad[0] = version`
  - `aad[1] = type.code`
  - `aad[2] = flags & ~ENCRYPTED`（加密位本身不参与认证）
  - `aad[3..10] = ptsUs`，**大端序**，与帧头偏移 8..15 的字节序一致（`aad[3]` 为最高字节，`aad[10]` 为最低字节）
- 解密失败（密钥错/被篡改）→ 立即断连，不得继续喂解码器。

### 8.4 密钥派生示例

HKDF 实现必须与 RFC 5869 一致；`vectors/hkdf-rfc5869-case1.json` 给出标准测试向量（OKM 42 字节）。

---

## 9. 错误与关闭

- `ERROR` 载荷为 UTF-8 文本，仅用于诊断；接收端不得把文本直接展示给终端用户（需映射为本地化文案）。
- 半帧/EOF → 视为连接断开，走断线恢复；不得把半帧数据送入解码器。
- `BYE` 为正常结束；收到后关闭 socket 并释放会话资源。
- 鉴权前只允许 HELLO/HELLO_ACK/AUTH/AUTH_RESULT/PING/PONG/ERROR；其他类型一律拒绝。

---

## 10. 版本兼容规则

1. 帧头布局与字段顺序**不可变**；新字段只能追加在可选尾部或以新 type/flag 表达。
2. 新增能力必须新增 capability 位；对方未声明则不得发送对应码流。
3. 未知 type/flag/`op` 的处理必须明确：type 断连、flag 忽略、op 忽略。
4. 任何加密相关字段缺失都按“不支持加密”降级，不得报错断连。
5. 版本升级需新增 `Version.CURRENT` 并保留 `MIN_SUPPORTED` 旧版本解析能力。

---

## 11. 一致性验收

三端实现必须通过同一组向量：

| 向量 | 覆盖 |
|---|---|
| `vectors/frame-video.json` | 20 字节帧头 + VIDEO 帧编码 |
| `vectors/hello.json` | HELLO 载荷字段顺序/编码 |
| `vectors/hkdf-rfc5869-case1.json` | HKDF-SHA256 标准向量 |
| `vectors/aes-gcm-frame.json` | 固定 key/iv/AAD 的 AES-256-GCM 帧解密 |
| `vectors/code-anchor.json` | 投屏码锚与 client/server HMAC 证明 |

异常路径由 `ProtocolTest` 覆盖：坏 magic、半帧、超长载荷、未知 type、版本不交集均断言抛出。后续向量补充：AUTH 转义、CONNECT 半帧、版本不交集、投屏码锚派生错误。新增向量时**不得修改已有向量语义**，只能追加。
