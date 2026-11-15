# AirMirror PC 发送端（MVP v0）

Windows PC → Android 接收端的单链路发送端。复用 `:protocol-core` 的线协议、握手、投屏码锚与端到端加密。

## 当前能力

- TCP 直连接收端（默认端口 `41523`），HELLO / AUTH / AUTH_RESULT 完整握手
- ECDH P-256 + HKDF-SHA256 + AES-256-GCM（与 Android 端同口径）
- 屏幕采集：AWT Robot（主屏/多屏合并区域，缩放到指定分辨率）
- H.264：JCodec 纯 Java 编码，SPS/PPS 作为 CONFIG 帧，IDR/P 作为 VIDEO 帧
- 会话控制：PING/PONG、pause/resume、sync（强制 IDR）
- 手动输入 IP + 6 位投屏码

## 构建

```powershell
$env:JAVA_HOME='D:\code\Java\zulu17.52.17-ca-jdk17.0.12-win_x64'
$env:GRADLE_USER_HOME='E:\Android\gradle_cache'
.\gradlew.bat :pc-client:installDist
```

产物：`release/build/pc-client/install/pc-client/bin/pc-client.bat`

## 运行

图形界面：

```powershell
.\release\build\pc-client\install\pc-client\bin\pc-client.bat
```

命令行（无 UI，便于自动化）：

```powershell
.\release\build\pc-client\install\pc-client\bin\pc-client.bat --host 192.168.1.20 --port 41523 --code 123456 --width 960 --height 540 --fps 15
```

## 已验证 / 未验证

已在 `:pc-client:test` 中验证：

- H.264 首帧输出 SPS(7)+PPS(8)+IDR(5)，后续帧输出 slice
- 与假接收端完成 HELLO→HELLO_ACK→AUTH→AUTH_RESULT，且握手后 `secure=true`

未验证（T05761 验收缺口）：

- 未与真实 Android TV/手机联调
- JCodec 720p 实测约 5–8fps，达不到 1080p30
- 无音频（WASAPI/AAC 未实现）
- 无 mDNS 自动发现、无二维码扫码
- 无 Windows 安装包/防火墙说明

## 后续替换点

1. `ScreenCapture` → Windows Graphics Capture（Win10 1903+），失败回退 DXGI Desktop Duplication
2. `H264VideoEncoder` → Media Foundation 硬编（`h264_mf`），OpenH264 软编回退
3. 新增 `AudioCapture`（WASAPI loopback）+ AAC-LC，复用 AUDIO 帧
4. 发现层接 mDNS `_airmirror._tcp.` 与 UDP 41524/41525
