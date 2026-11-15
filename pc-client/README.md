# AirMirror PC 发送端（v0.2 原生采集/编码）

Windows PC → Android 接收端的单链路发送端。复用 `:protocol-core` 的线协议、握手、投屏码锚与端到端加密。

## 当前能力

- TCP 直连接收端（默认端口 `41523`），HELLO / AUTH / AUTH_RESULT 完整握手
- ECDH P-256 + HKDF-SHA256 + AES-256-GCM（与 Android 端同口径）
- 屏幕采集：FFmpeg `ddagrab`（DXGI Desktop Duplication，GPU 抓屏），失败自动回退 `gdigrab`
- H.264：优先 `h264_mf`（Media Foundation 硬编，NV12），依次回退 `h264_qsv` / `libopenh264` / `libx264`
- 输出 1080p30，SPS/PPS 作为 CONFIG 帧，IDR/P 作为 VIDEO 帧
- 会话控制：PING/PONG、pause/resume、sync（重启编码器强制 IDR）
- 断线后 3 秒自动重连
- 手动输入 IP + 6 位投屏码

## 构建

```powershell
$env:JAVA_HOME='D:\code\Java\zulu17.52.17-ca-jdk17.0.12-win_x64'
$env:GRADLE_USER_HOME='E:\Android\gradle_cache'
.\gradlew.bat :pc-client:installDist "-PairmirrorFfmpegDir=<ffmpeg 运行目录>"
```

`airmirrorFfmpegDir` 指向包含 `ffmpeg.exe` 及其依赖 DLL 的目录；Gradle 会把它复制到发行包的 `ffmpeg/` 子目录。未传该参数时，运行时依次查找：

1. `-Dairmirror.ffmpeg=<路径>` / `AIRMIRROR_FFMPEG`
2. 发行目录下 `ffmpeg/ffmpeg.exe`
3. `PATH` 中的 `ffmpeg.exe`

找不到 FFmpeg 时回退 AWT Robot + JCodec（仅低分辨率/低帧率兜底）。

产物：`release/build/pc-client/install/pc-client/bin/pc-client.bat`

## 运行

图形界面：

```powershell
.\release\build\pc-client\install\pc-client\bin\pc-client.bat
```

命令行（无 UI，便于自动化）：

```powershell
.\release\build\pc-client\install\pc-client\bin\pc-client.bat --host 192.168.1.20 --port 41523 --code 123456 --width 1920 --height 1080 --fps 30 --bitrate 8000
```

## 已验证 / 未验证

已在 `:pc-client:test` 中验证：

- Annex-B 按 AUD 切帧、SPS/PPS 抽为 CONFIG、IDR 标记
- 与假接收端完成 HELLO→HELLO_ACK→AUTH→AUTH_RESULT，且握手后 `secure=true`
- 指定 `-PairmirrorFfmpeg` 时，真实 FFmpeg 链路在 1920x1080@30 下 3 秒内至少送出 60 帧

未验证 / 已知缺口：

- 采集是 DXGI Desktop Duplication，不是 Windows Graphics Capture（WGC 需后续原生 helper）
- `h264_qsv` 依赖 Intel 核显；无 QSV 时回退 `h264_mf`/`libopenh264`，帧率可能低于 30
- 无音频（WASAPI/AAC 未实现）
- 无 mDNS 自动发现、无二维码扫码
- 无 Windows 安装包/防火墙说明
- 2 小时稳定性与 glass-to-glass 120–200ms 尚未在真实 Android 接收端实测

## 后续替换点

1. `FfmpegCaptureEncoder` → Windows Graphics Capture 原生 helper（同一录制协议，Java 侧无需改）
2. 新增 `AudioCapture`（WASAPI loopback）+ AAC-LC，复用 AUDIO 帧
3. 发现层接 mDNS `_airmirror._tcp.` 与 UDP 41524/41525
