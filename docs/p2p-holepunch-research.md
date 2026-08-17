# NAT 打洞方案调研（适用于 chat-everywhere 互联网 P2P 通道）

> 调研目的：为项目自研互联网 P2P 直连通道（纯 Kotlin socket、零新依赖、
> 去中心化、无 TURN/专用中继、信令复用加密 Nostr DM）寻找适合中国
> "大内网"（CGNAT 普遍、对称 NAT、Google STUN 被墙）的打洞增强方案。
>
> 调研时间：2026-08（web 检索汇总，含实测/论文/开源实现）。

## 一、项目现状（已实现的四级降级）

1. **Tier 0 局域网直连**：双方候选携带 `lanHost`，同内网优先 TCP 直连。
2. **Tier 1 IPv6 直连**：双方有全局 IPv6 时 TCP 直连（蜂窝 IPv6 入站常被
   阻断，实测常失败）。
3. **Tier 2 UDP 打洞**：STUN 反射公网端点 + 双向握手（STUN 在多运营商下
   全失败 → `UDP_BLOCKED`，且只打单个 mapped 端口，对称 NAT 无解）。
4. **Tier 3 TCP 同时打开**：单端口 simultaneous-open（对称 NAT 下成功率
   极低）。
5. 兜底：Nostr relay（用户自控）。

实测痛点：中国移动蜂窝 = 随机对称 NAT（NAT4），双 NAT4 无法普通打洞；
STUN（stun.miwifi/cloudflare/l.google）在移动数据下全部不可达。

## 二、适合本项目的打洞技术（按适配度排序）

### 2.1 TSO（TCP Simultaneous Open）+ Birthday Attack —— 专为中国 CGNAT 设计

- **来源**：`zmkjh/lain`（GitHub，开源，README 明示"专为中国移动 CGNAT
  设计"）；RFC 5128 亦描述 TCP 同时打开（`[TCP]` figure 6）。
- **原理**：双方各用 `bind(同端口).connect(对端同端口)` 发起 TCP 连接，
  出站 SYN 在各自 NAT 建立映射，对端 SYN 交叉抵达完成握手。**无需 TCP
  listener**，纯 bind+connect。
- **生日攻击**：从同一端口范围（如 50000-50007）并发 `N×M` 对端口
  （lain 用 8×8=64 对，±50ms jitter 防 CGNAT 限速）；自适应端口数
  （端口保持型 NAT 用 4 端口，随机型用 8 端口），RTT 驱动超时。
- **对本项目**：直接对应 Tier 3，目前只打单端口 → 升级为多端口并发
  生日攻击，对称 NAT 成功率从 ≈0% 拉高到可用。改动集中在
  `NatTraversalEngine.tryTcpConnect`。
- 参考实现：lain（Rust）、RFC 5128 §5。

### 2.2 对称 NAT 端口预测（UDP N+1 / Birthday Attack）

- **来源**：RFC 5128 §3.5（"N+1" 技术）；`saorsa-transport`
  `port_prediction.rs`（线性 delta 预测 + 观测历史）；`stun_max`
  （Birthday Attack 256 sockets + 端口预测 ±1000，宣称 NAT3+NAT4 组合
  ~98%）；EasyTier（区分 NAT4 与 NAT4E——递增型对称，可端口预测）；
  `Poisson 方法`（Atlantis Press, 2019）。
- **原理**：对称 NAT 常按顺序（+1/+delta）分配公网端口。通过 STUN 或
  对端观测，记录某 IP 的历史外部端口，推断 delta，预测下一端口，向
  预测的多个端口并发打洞。
- **对本项目**：直接对应 Tier 2，目前只向 STUN 反射的**单个** mapped
  端口发握手 → 升级为向预测的 N 个端口并发打洞（NAT4E 场景可打通；
  纯随机 NAT4 仍失败，回落 Nostr）。
- 注意：中国移动 NAT4 为"对 remote_addr 哈希随机分区"，端口不可预测
  → 端口预测只对递增型 NAT4E / 部分家宽有效。

### 2.3 IPv6 打洞（蜂窝 IPv6 入站被阻断的解药）

- **来源**：EasyTier（明确区分 IPv6 可入站→直连；不可入站→IPv6 UDP
  打洞）；iroh/QUIC NAT traversal（IETF draft-seemann-quic-nat-traversal）。
- **原理**：IPv6 无 NAT，但运营商/设备防火墙常阻断入站。双方同时向对方
  IPv6 发 UDP 包，防火墙认为是对出站包的响应而放行 → 双向打通。
- **对本项目**：Tier 1（IPv6 TCP 直连）在蜂窝失败时，**不是放弃 IPv6，
  而是改走 IPv6 UDP 打洞**（比 IPv4 打洞更可能成功——无 NAT 映射问题）。
- 补充：464XLAT / NAT64 下 UE 获 IPv6-only 前缀，IPv6 直连天然可行；
  若运营商 NAT66，行为同 IPv4 NAT，可套用 2.2。

### 2.4 QUIC 打洞（多路径 + 加密协商）—— 参考但不采用

- **来源**：iroh（docs.iroh.computer/nat-traversal，宣称 ~90% 成功率、
  95% 流量走直连）；IETF QUIC NAT traversal draft；libp2p DCUtR（QUIC
  listener-role 打洞：客户端发随机 UDP 包、服务端发 QUIC 握手）。
- **优点**：确定性高、多路径（Wi-Fi↔蜂窝切换不掉线）、打洞协商加密。
- **对本项目**：QUIC 在 Android 上可用（`okhttp`/自研），但引入依赖 +
  复杂度；当前 TCP/UDP 自研路径已够，QUIC 列为远期参考。

### 2.5 无服务器信令/同步模式（我们已具备）

- **libp2p DCUtR**（specs/relay/DCUtR.md）：靠 relay 连接做信令同步，
  CONNECT/Sync + 半 RTT 定时器对齐双方同时拨号；无专用信令服务器。
- **holepunch.to / hyperdht**：DHT 发现 + UDP 打洞 + Noise 加密流，
  完全无服务器。
- **对本项目**：我们的信令复用加密 Nostr DM（OFFER/ANSWER/CONNECTED）
  即等效 DCUtR 的 relay 同步；`CONNECTED` 双向宣告已对齐时序。**已具备**，
  无需新基础设施。

### 2.6 蜂窝网络 NAT 行为实测结论（NetPiculet, SIGCOMM'11）

- 72 家运营商 NAT 分类：多数为 Independent / Address+Port1（易穿透），
  但 **19 家（26.4%）为 ConnectionR**（连接相关，无法打洞）。
- 发现**随时间递增外部端口**的 NAT 映射类型（需新穿透方案，即 2.2）。
- 单设备可能经过**多个 NAT 映射**（负载均衡）→ 一次打洞可能只穿透一层。
- **对本项目**：蜂窝场景打洞成功率上限受运营商限制；失败时应快速回落
  Nostr（现状已如此），或尝试 IPv6（2.3）。

## 三、业界成功率基线（供预期管理）

| 技术/场景 | 成功率 | 来源 |
|---|---|---|
| libp2p DCUtR（Port-Restricted Cone） | 82.9% | ProbeLab, 2026 |
| libp2p DCUtR（对称 NAT） | 39.7% | ProbeLab, 2026 |
| iroh QUIC 打洞（整体） | ~90% | iroh 官方 FAQ |
| stun_max（NAT3+NAT4 组合） | ~98%（宣称） | stun_max README |
| UDP 打洞（一般 NAT 设备） | >80% | RFC 5128（2008 数据） |
| TCP 打洞（一般 NAT 设备） | ~60% | RFC 5128（2008 数据） |
| TCP/UDP hairpin（同 NAT 下互连） | <25% | RFC 5128 |

## 四、对本项目的落地优先级建议

| 优先级 | 改造 | 现状 → 目标 | 改动范围 |
|---|---|---|---|
| **高** | Tier 3 多端口 TSO（生日攻击） | 单端口同时打开 → 4×4~8×8 端口对并发 bind+connect | `NatTraversalEngine.tryTcpConnect` |
| **高** | Tier 2 端口预测打洞 | 单 mapped 端口 → 观测 delta 预测 N 端口并发 | `NatTraversalEngine.tryUdpPunch` + 候选历史 |
| **中** | Tier 1 IPv6 UDP 打洞回退 | IPv6 TCP 直连失败 → IPv6 UDP 双向打洞 | `establish` Tier 1 分支 |
| 低 | 打洞窗口同步字段 | `punchStartDelayMs` 已随链接传输 → 接入引擎生效 | `PunchCandidate` → `establish` |

约束满足：以上全部**纯 Kotlin socket 可实现、零新依赖、无需服务器**，
符合去中心化硬约束（无 TURN/专用中继；STUN 仍仅为可选反射器，失败不
阻塞，回落 Nostr 兜底）。

## 五、关键参考链接

- libp2p DCUtR 规范：<https://github.com/libp2p/specs/blob/master/relay/DCUtR.md>
- ProbeLab 实测：<https://probelab.io/blog/can-libp2p-punch-through-nats/>
- RFC 5128（P2P 跨 NAT 状态）：<https://www.rfc-editor.org/rfc/rfc5128.html>
- iroh NAT traversal：<https://docs.iroh.computer/concepts/nat-traversal>
- lain（中国 CGNAT TSO）：<https://github.com/zmkjh/lain>
- saorsa-transport 端口预测：<https://github.com/WithAutonomi/saorsa-transport>
- stun_max（生日攻击+端口预测）：<https://github.com/skyformat99/stun_max>
- EasyTier P2P 优化：<https://easytier.cn/guide/network/p2p-optimize.html>
- holepunch.to / hyperdht：<https://github.com/holepunchto/hyperdht>
- NetPiculet 蜂窝 NAT 研究：<https://cs.ucr.edu/~zhiyunq/pub/sigcomm11_netpiculet.pdf>
- 中国移动 NAT 实测讨论：<https://www.v2ex.com/t/968663>
