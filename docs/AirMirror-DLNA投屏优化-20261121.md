# AirMirror DLNA 投屏稳定性优化（2026-11-21 / 11-22）

## 结论

优酷、抖音等视频 App 的“搜索不稳定、重试多次才投成功”主要不是 Wi-Fi 信号问题，而是 DLNA 发现链路与协议兼容问题：

1. 极光盒子自带的“云视听小电视 / 极光投屏 / 多屏互动”常占用 UDP 1900，AirMirror 的 SSDP 监听绑定不上时，HTTP 服务仍在，但收不到手机的 M-SEARCH，表现为“设备时有时无”。
2. 原实现对 `ssdp:all` 只回一条 `ST: ssdp:all` 的应答，部分控制点会丢弃；应按 `rootdevice`、`uuid`、`MediaRenderer` 和三个 service 分别应答。
3. 抖音/优酷部分链路使用乐播 SDK 的 DLNA 或 Lelink 私有协议；AirMirror 只实现 DLNA。App 优先 Lelink 且不回退时，仍不会列出设备，这部分不是本次代码能解决的。
4. 搜到设备但投播失败，常见原因是视频 URL 是手机本地代理/带鉴权，盒子取不到；或 HLS/FLV 未在 `GetProtocolInfo` 声明；或手机切到移动数据/VPN。

## 本次改动

- `DlnaServer`：描述文件的 `URLBase` / `presentationURL` 改为**按请求时刻的本机地址生成**，不再使用启动时缓存的 IP。否则 Wi-Fi/有线切换后，SSDP 能搜到但控制点按旧网段取描述/控制 URL，直接表现为“搜到却投播失败”。
- `Ssdp.searchResponses()`：`ssdp:all` 按目标逐条应答，具体 ST 仍只回一条。
- SSDP 绑定成功后连发 3 次 `ssdp:alive`（间隔 200ms），周期从 60s 改为 20s；缓存仍为 180s。
- 1900 端口绑定失败时，接收端状态区与浮层提示“端口被系统投屏占用”，避免用户只看到“时有时无”。
- 监听 `ConnectivityManager` 网络变化：更新 SSDP `LOCATION` 并重建组播监听，HTTP 端口与已建立媒体流不动。
- `Upnp.sinkProtocols()` 增加 HLS（`application/vnd.apple.mpegurl` / `application/x-mpegURL`）、FLV、3GP、WebM、TS、AAC、WAV、AVI 声明。
- 设备描述补 `modelNumber`，提升老控制点兼容性。

## 2026-11-22 局域网取证（电视盒仍是旧包）

从 PC `192.168.50.65` 向 `239.255.255.250:1900` 发 `ST: ssdp:all` 的 `M-SEARCH`，收包 4 秒，共 22 条应答。其中 AirMirror 的关键证据：

```text
from: 192.168.50.223:1900
st: ssdp:all
usn: uuid:18dcf54e-24fd-4722-92cd-64d7b5855c71::ssdp:all
server: AirMirror/1.0 UPnP/1.0 DLNADOC/1.50
location: http://192.168.1.223:34161/description.xml
```

同一个盒子的 HTTP 描述接口实际是通的：

- `http://192.168.50.223:34161/description.xml` → 200，1527 字节；`friendlyName` 为 `bpz01-64`。
- 描述里的 `<URLBase>` 与 `<presentationURL>` 仍是 `http://192.168.1.223:34161/`。
- `http://192.168.1.223:34161/description.xml` → 4 秒超时。

结论：盒子已经在 `192.168.50.x`，但 AirMirror 公告的是旧网段 `192.168.1.223`。控制点能通过 SSDP 发现，但取描述/控制 URL 会打到不存在的地址，所以出现“能搜到、投播反复失败”。同时 `ST` 只有单条 `ssdp:all`、描述无 `modelNumber`，说明盒子上跑的仍是旧 APK，本轮修复尚未装上去。

电视盒没有 ADB，无法远程安装或拉日志；真机复测必须在盒子文件管理器/下载器中手动安装新 APK。安装后复测顺序：先确认描述文件 `URLBase` 等于盒子当前 IP，再让手机搜一次并投一条 HLS/普通 MP4。

## 验证

- `:app:compileDebugKotlin` 通过。
- `SsdpTest`、`UpnpSoapTest`、`UpnpDescriptorTest` 通过。
- 模拟器 5556 安装并启动通过；接收端启动后 logcat 出现 `ssdp listener unavailable, retrying: already bound`，说明 1900 冲突检测与 2 秒重试路径已实际触发。
- 新包在模拟器上取描述文件：`<URLBase>http://10.0.2.15:44533/</URLBase>`，`presentationURL` 同地址，`modelNumber` 为 `1.0`；说明 `URLBase` 已按运行时地址生成，不再是旧网段。
- 新包 `GetProtocolInfo` 的 Sink 列表实测包含 `application/vnd.apple.mpegurl`、`application/x-mpegURL`、`video/x-flv`、`video/mp2t`、`video/webm` 等。
- 通过 `adb forward` 用 SOAP `SetAVTransportURI` + `Play` 推一个 PC 上的 MP4：PC HTTP 日志收到 `GET /amt.mp4 200`，说明取流链路通；模拟器 `MediaPlayer` 随后报 `Error (1,-1010)` 并回到 `STOPPED`，属于模拟器解码器不支持该样本，不是 URL 取不到。
- 全量单测：**779 项通过，0 失败，0 跳过**。其中 `ReferenceServerIntegrationTest` 的 Python `http.server` 启动超时已修复（服务端跳过 `getfqdn` 反查，测试等待放宽到 45 秒）。
- 产物：`AirMirror-0.1.0-m0.20261122-001-universal-debug.apk`
- versionCode：`261122001`；versionName：`0.1.0-m0.20261122-001`
- 大小：6,601,140 B；SHA-256：`FECB3D99E9AB78F419AE82CF894C06AAB7F1FC41AA5B5A2504883F230C3D7309`
- 下载：https://aka.doubaocdn.com/s/uAVZaqbvpb

## 仍需真机确认

电视盒没有 ADB 通道，本次未做极光盒子真机复测。新包必须手动安装，装完按下面顺序验：

1. 接收端启动后先点“更多信息”，看状态区是否出现“DLNA 发现端口 1900 被系统投屏占用”。若出现，先在盒子设置里强制停止/停用“云视听小电视、极光投屏、多屏互动”，再重启 AirMirror 接收端。
2. 用另一台同网设备访问 `LOCATION` 里的 `http://<盒子当前IP>:<端口>/description.xml`，确认 `<URLBase>` 和 `<presentationURL>` 都等于盒子当前 IP，不再出现 `192.168.1.x` 这类旧网段。
3. 手机端在优酷/抖音里搜一次并投一条普通 MP4；再试一条 HLS。若仍只出现盒子自带投屏名字，说明该 App 走的是 Lelink 而不是 DLNA，需要评估乐播 SDK 授权或改用系统级投屏。
4. 若搜到但投播失败，记录手机当时是 Wi-Fi 还是移动数据、视频是本地代理还是直链；这两种失败原因不同，不能都归到“信号不稳定”。

## 不在本次范围

- GENA `LastChange` 事件推送（当前控制点主要靠 `GetPositionInfo` 轮询）。
- Lelink 私有协议、AirPlay、Google Cast。
- 手机端本地代理 URL 的取流兼容。
