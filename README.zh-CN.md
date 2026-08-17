<img width="256" height="256" alt="icon_128x128@2x" src="https://github.com/user-attachments/assets/90133f83-b4f6-41c6-aab9-25d0859d2a47" />

## bitchat for Android（中文）

一款去中心化点对点即时通讯应用，采用双传输架构：本地蓝牙 Mesh 网络用于离线通信，基于互联网的 Nostr 协议用于全球互联。无需账号、无需手机号、无中心服务器。

这是 bitchat 的 Android 实现，与 [iOS 版](https://github.com/permissionlesstech/bitchat)完全二进制协议兼容，可跨平台 Mesh 通信。

[bitchat.free](http://bitchat.free)

[GitHub Releases](https://github.com/permissionlesstech/bitchat-android/releases)

[<img alt="Get it on Google Play" height="60" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png"/>](https://play.google.com/store/apps/details?id=com.bitchat.droid)

## 效果预览

<table>
  <tr>
    <th>离线 Mesh 会话</th>
    <th>Geohash 地球仪选点</th>
  </tr>
  <tr>
    <td><img src="docs/screenshots/readme-mesh-chat.png" alt="四端 Bitchat Mesh 会话（含图片、语音、文字消息）" width="360"/></td>
    <td><img src="docs/screenshots/readme-geohash-globe.png" alt="Bitchat geohash 选点器（地球 + geohash 网格）" width="360"/></td>
  </tr>
</table>

## 许可证

本项目以公有领域发布，详见 [LICENSE](LICENSE.md)。

## 功能特性

- **双传输架构**：蓝牙 LE Mesh 离线通信 + Nostr 中继互联网通信
- **互联网 P2P 直连**：陌生人之间无服务器互联网直连——二维码、分享链接、geohash 频道探测三种入口（无需互关好友）
- **基于位置频道**：用 geohash 坐标在 Nostr 中继上创建地理聊天室
- **智能消息路由**：自动选择最优传输，对端不可达时排队重试
- **端到端加密**：[Noise 协议](https://noiseprotocol.org)（XX 模式，X25519 + ChaCha20-Poly1305）加密 Mesh 与直连私聊
- **去中心化 Mesh**：蓝牙 LE 自动发现与多跳中继（最多 7 跳）
- **Wi-Fi Aware 传输**：支持设备上的高带宽本地 Mesh
- **频道聊天**：基于主题的群聊，可选密码保护（Argon2id + AES-256-GCM）
- **IRC 风格命令**：熟悉的 `/join`、`/msg`、`/who` 交互
- **Tor 支持**：内置 Tor（Arti）提供私密联网
- **紧急擦除**：三击立即清除全部数据
- **跨平台**：与 bitchat iOS/macOS 二进制协议兼容

## 技术架构

### 蓝牙 Mesh 网络（离线）

- 蓝牙范围内点对点直连，附近设备多跳中继
- Noise 协议会话（前向保密），身份由静态公钥派生
- 紧凑二进制包格式，支持分片、TTL 路由与去重
- 自适应占空比与连接数限制，保障电池续航
- 前台服务让 Mesh 在 Android 后台限制内保持存活

### Nostr 协议（互联网）

- 通过公共中继全球可达，geohash 位置频道
- 私信在 Mesh 不可用时回退到 Nostr（互关好友）
- 每个 geohash 区域使用临时密钥

### 互联网 P2P 直连（面向中国网络优化）

无服务器的直连通道（`internetp2p`）：通过互联网连接两台设备，**无 TURN、无中继、无信令服务器**。信令复用现有端到端加密的 Nostr DM 流；STUN 仅为可选反射器。

- **三种免互关入口**：二维码、分享/复制链接（`bitchat-p2p://`）、geohash 频道探测——任一种都能与陌生人开启直连会话。
- **先探测 NAT 再选协议**：RFC 5780 探测划分本机 NAT 类型（锥型/对称/公网），再加端口分配探测（`PortBehaviorProbe`）区分可预测（递增型）与随机对称 NAT，据此选择穿透策略。
- **多级降级（直连优先）**：
  1. Tier 0 — 局域网直连（同 Wi-Fi，无需穿透）
  2. Tier 1 — 全局 IPv6 直连；入站 TCP 被阻断（蜂窝常见）时，Tier 1b 改走 IPv6 UDP 打洞
  3. Tier 2 — 经典 UDP 打洞；可预测（递增型）对称 NAT 用端口预测扫描（RFC 5128 N+1）扩大命中窗口
  4. Tier 3 — TCP 同时打开；随机对称 NAT（中国移动 CGNAT 典型）用多端口生日攻击（同一共享端口区间 bind+connect，4–8 端口加抖动）
  5. Nostr 中继兜底——直连非必需
- **UDP → TCP 升级**：国内运营商（移动/联通）对 UDP 高强度限速而 TCP 基本不限——UDP 打洞成功后链路会短暂重连 TCP（重新 `[BP2P][nonce]` 握手认证）；失败则保留 UDP 链路继续用。
- **安全、fail-closed**：每条链路（UDP/TCP/IPv6）都必须完成 `[BP2P][nonce]` 握手（nonce 经加密 Nostr DM 带外传输）；握手失败立即关闭（防 DoS）。身份在 Noise 会话绑定前保持未验证，UI 会明确标注。

### Android 技术栈

- Kotlin、Jetpack Compose（Material 3）、MVVM
- 所有网络与状态基于协程与 Flow
- 核心组件：`MeshForegroundService`（常驻连接）、`BluetoothMeshService` / `WifiAwareMeshService`（传输）、`UnifiedMeshService`（传输选择）、`NoiseSessionManager`（加密会话）、`MessageRouter`（Mesh/P2P/Nostr 路由 + 发件箱重试）、`InternetMeshTransport` + `NatTraversalEngine`（互联网 P2P 直连）

## 构建

需要 Android Studio 与 Android SDK（API 26+）。

### 一键构建脚本（Windows）

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

执行 `:app:assembleDebug`，将 APK 复制到 `release/`（小写），并清理旧的大写 `Release/` 目录。

### 手动构建

```bash
git clone https://github.com/permissionlesstech/bitchat-android.git
cd bitchat-android
./gradlew assembleDebug
```

安装到已连接设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

应用会在运行时请求蓝牙、定位（BLE 扫描需要）与通知权限。

发布版 APK 与 Android App Bundle 可在固定的 Linux 容器中逐字节复现。维护者请参考
[Android 发布指南](docs/maintainer-release-guide.md)；构建信任模型与
GitHub/Google Play 公开验证流程见[可复现构建](docs/reproducible-builds.md)。

## 测试

```bash
# 单元测试
./gradlew test

# Lint
./gradlew lint

# 仪器化测试（需要设备或模拟器）
./gradlew connectedAndroidTest
```

注意：BLE Mesh 行为难以模拟；协议与会话逻辑由单元测试覆盖，射频层行为需真机验证。
