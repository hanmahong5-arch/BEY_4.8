# Beyond 4.8 × ShiguangSuite 接入手册

本文记述将 `D:\拾光ai\tools\ShiguangSuite` 嵌入 Beyond 4.8 客户端与服务端之间的全链路部署方案。

---

## 一、架构总览

```
                                玩家机
                      ┌──────────────────────────┐
                      │  shiguang-launcher.exe   │
                      │  (Wails + React 白标)    │
                      └────────────┬─────────────┘
                                   │  WSS :10443 (token handoff 下发)
                                   ▼
  ┌────────────────────────────── 本机部署 ───────────────────────────────┐
  │  shiguang-control :10443  ◀── REST/WSS, session token 签发 + 验证    │
  │  shiguang-gate    :2107 / :7778 / :10241  ◀── 透明 TCP 代理 + PROXY v2│
  │  ─────────────────────────────────────────────                      │
  │  Beyond LS        :12107  ← 内部，expect_proxy_v2 = true             │
  │  Beyond GS        :17778  ← 内部，expect_proxy_v2 = true             │
  │  Beyond CS        :20241  ← 内部，PROXY v2 不支持（Netty 3）         │
  └──────────────────────────────────────────────────────────────────────┘
```

**端口偏移策略**：Beyond 四服（LS/GS/CS）内部监听地址偏移 `+10000`，让出原始端口 `2107/7778/10241` 给 shiguang-gate；避免同主机同端口 bind 冲突。

---

## 二、已完成改动清单

| 模块 | 文件 | 改动 |
|------|------|------|
| 源码 commons | `server/source/commons/.../ProxyProtocolV2.java`, `Acceptor.java` | HAProxy PROXY v2 blocking 预读 |
| 源码 login-server | `.../utils/TokenHandoff.java`, `configs/Config.java`, `clientpackets/CM_LOGIN.java`, `controller/AccountController.java`, `network/NetConnector.java` | Token Handoff + expect_proxy_v2 配置 + SG- 前缀路由 |
| 源码 game-server | `.../configs/network/NetworkConfig.java` | expect_proxy_v2 配置键 |
| 编译产物 | `prod/login-server/libs/commons-4.8-SNAPSHOT.jar`, `login-server-4.8-SNAPSHOT.jar` | 已含 `ProxyProtocolV2.class`, `TokenHandoff.class` |
| 配置 LS | `prod/login-server/config/network/network.properties` | `socket_address = 127.0.0.1:12107`, `expect_proxy_v2 = true`, `token_handoff.url = http://127.0.0.1:10443/api/token/validate` |
| 配置 GS | `prod/game-server/config/network/network.properties` | `socket_address = 127.0.0.1:17778`, `expect_proxy_v2 = true` |
| 配置 CS | `prod/chat-server/config/network/network.properties` | `socket_address = 127.0.0.1:20241` |
| 客户端 | `client/bin32/version.dll` | `LoadSessionToken()` 读取 `.sg-session` + `-sg-token-handoff` 命令行 flag |
| 部署 | `tools/shiguang/gate-48.yaml`, `control.yaml`, `start-*.bat`, `stop.bat`, `smoke-test.bat` | 一键启动/停止/冒烟 |
| 补丁 | `tools/shiguang/patches/bey48/chunk-manifest.json` | 4MB 块 manifest（HMAC-SHA256 签名，2591 文件 / 8068 块 / 25.54 GB） |

---

## 三、启动顺序

启动务必按此顺序，否则 `expect_proxy_v2` 开关会使直连失败：

```
1. BEY_4.8/prod/login-server/start.bat     # 监听 127.0.0.1:12107
2. BEY_4.8/prod/game-server/start.bat      # 监听 127.0.0.1:17778
3. BEY_4.8/prod/chat-server/start.bat      # 监听 127.0.0.1:20241
4. BEY_4.8/tools/shiguang/start-all.bat    # 启动 gate + control
5. 玩家运行 shiguang-launcher.exe（或 client/start32.bat）
```

或按分步启动：
```
tools\shiguang\start-gate.bat     # 仅启动 gate
tools\shiguang\start-control.bat  # 仅启动 control
tools\shiguang\smoke-test.bat     # 离线校验配置
```

---

## 四、Token Handoff 登录流程

```
玩家在 launcher 输入账号密码
   │
   ▼
launcher → POST http://127.0.0.1:10443/api/login       （shiguang-control）
   │                                                   （验 account_data.password 或 ExternalAuth）
   ▼
control 签发 session_token（12 字节 hex，TTL 5 分钟，一次性）
   │
   ▼
launcher 写入 client/bin32/.sg-session 文件（隐藏属性）
   │
   ▼
launcher 启动 aion.bin 带 -sg-token-handoff 参数
   │
   ▼
version.dll DLL_PROCESS_ATTACH → LoadSessionToken() 读入并删除 .sg-session
   │
   ▼
游戏发送 CM_LOGIN，密码字段 = "SG-<token>"
   │
   ▼
Beyond LS CM_LOGIN → TokenHandoff.isTokenHandoff("SG-...") == true
   │
   ▼
LS → POST http://127.0.0.1:10443/api/token/validate    （TokenHandoff.validate）
   │
   ▼
control TokenStore.Consume(token) → 返回 {ok, account, server}
   │
   ▼
LS AccountController.loginWithToken() → 按常规登录后处理
```

---

## 五、回滚路径

如遇 PROXY v2 握手异常或玩家投诉，可快速回滚至直连模式：

1. 编辑 `prod/login-server/config/network/network.properties`：
   ```
   loginserver.network.client.socket_address = 0.0.0.0:2107
   loginserver.network.client.expect_proxy_v2 = false
   loginserver.shiguang.token_handoff.url =
   ```
2. 同样处理 `prod/game-server/config/network/network.properties`：
   ```
   gameserver.network.client.socket_address = 0.0.0.0:7778
   gameserver.network.client.expect_proxy_v2 = false
   ```
3. `prod/chat-server/config/network/network.properties`：
   ```
   chatserver.network.client.socket_address = 0.0.0.0:10241
   ```
4. `BEY_4.8/tools/shiguang/stop.bat`（停 gate + control）
5. 重启 Beyond 三服，玩家直连 `127.0.0.1:2107/7778/10241`。

---

## 六、环境依赖（运行时需自查）

1. **本地 PostgreSQL 必须有 `aion` 账号及 `aion_ls` 库**：
   ```sql
   CREATE USER aion WITH PASSWORD 'Lurus@ops';
   CREATE DATABASE aion_ls OWNER aion;
   -- 导入 BEY_4.8/server/source/login-server/sql/aion_ls.sql
   ```
   否则 shiguang-control 启动时会警告 `4.8 DB unreachable`，`/api/login` 与 `/api/register` 接口将 500。Token Handoff 仍可用（因为 `/api/token/validate` 只查内存 TokenStore）。

2. **SHIGUANG_ADMIN_PASS 环境变量**：默认值在 `start-control.bat` 内为 `changeme`，务必替换为强口令。

3. **JWT 密钥**：`control.yaml` 自带 32 字节开发密钥，生产部署前须执行 `openssl rand -hex 32` 重新生成。

---

## 七、已知限制

1. **chat-server 无 PROXY v2**：Netty 3.10（2013 年）不含 `HAProxyMessageDecoder`。升级需迁移 Netty 4.1+，暂不实施。影响：chat 维度真实客户端 IP 丢失，封禁/限速仍由 gate 按对端 IP 执行。
2. **shiguang-control.yaml 含明文 JWT 密钥**：生产环境须由 `openssl rand -hex 32` 重新生成并经 `SHIGUANG_JWT_SECRET` 环境变量注入。
3. **patches 目录仅有 manifest，无 chunk 文件**：因客户端 25.54 GB，全量 chunk 导出需数小时 I/O。首次上线前应在维护窗口内执行 `patchbuilder.exe -client ... -out patches/bey48 -sign-key <KEY>`（去掉 `-manifest-only`）。
4. **control 当前未启用 TLS**：`control.yaml` 中 `tls:` 段已注释。内网部署可接受；互联网暴露前必须启用 Let's Encrypt 证书。

---

## 八、下一步迭代

- [ ] 将 chat-server 迁至 Netty 4.1 并启用 PROXY v2
- [ ] 全量 chunk 导出并接入 Hub `/patches/bey48/` 静态托管
- [ ] launcher 品牌包（logo / 主题 / BGM）走 `brand/` 目录白标注入
- [ ] version.dll `send()` hook 实现 CM_LOGIN 包体注入（替代 `-sg-token-handoff` 命令行参数）
- [ ] Beyond 4.8 GS <-> CS 内部协议也走 gate（可选；提升运维可观测性）

---

## 九、迭代二：边缘情况加固（2026-04-14 晚）

第二轮审查定位出 9 项边缘情况漏洞，已全部修复并重新编译部署。

### P0 安全修复

| 编号 | 问题 | 定位 | 修复 |
|------|------|------|------|
| P0-1 | `/api/token/validate` 无调用方校验 | `shiguang-control/internal/handlers/account.go:226` | 加 `isLoopbackIP()` 硬白名单；非 loopback 返回 403 + 审计日志；token 长度上下界 [8,128] |
| P0-2 | TokenHandoff 返回的 account 字段未白名单过滤（SQL/日志注入风险） | `TokenHandoff.java:110-120` | 正则 `^[A-Za-z0-9_-]{4,32}$` 兜底，非法账户记 ERROR 并拒绝 |

### P1 健壮性修复

| 编号 | 问题 | 定位 | 修复 |
|------|------|------|------|
| P1-3 | PROXY v2 不支持 LOCAL 命令（0x20），健康探针被拒 | `ProxyProtocolV2.java:40-41, 101-110` | 接受 0x20，drain 剩余字节后返回空 ProxyInfo，回退用 socket 原端信息 |
| P1-4 | `PROXY_V2_READ_TIMEOUT_MS` 硬编码 5000ms，高延迟网关下合法连接被拒 | `Acceptor.java:33-55` | 支持 JVM sysprop `-Daion.proxy_v2.read_timeout_ms=<ms>` 覆盖，边界 [1000, 30000] 防呆 |
| P1-4b | 拒绝路径日志未带 peer IP | `Acceptor.java:109-130, 155-175` | 加 `safePeer()` 与 `closeQuietly()` 辅助；SocketTimeoutException 与 IOException 分日志 |
| P1-5 | gate 限速 `rate_per_sec=5, burst=10, max_conn_per_ip=3` 开服日必雪崩 | `gate-48.yaml:27-31` | 调整为 50/100/8；注释说明限速策略与 NAT 场景 |
| P1-6 | `start-*.bat` 未预建 `logs/`，子进程 stdout 重定向静默失败 | `start-gate.bat, start-control.bat, start-all.bat` | 前置 `if not exist logs mkdir logs` |
| P1-7 | `stop.bat` 直接 `taskkill /F`，tokenstore 未 flush | `stop.bat` | 先无 `/F` 触发 WM_CLOSE → 等 5 秒 → 再 `/F` 兜底 |
| P1-8 | smoke-test 未校验 gate upstream 端口与 network.properties 偏移一致性 | `smoke-test.bat:37-50` | 新增 6 项检查（gate route 端口、admin 绑 loopback、token url loopback、chat proxy_protocol: false） |

### 其他意外坑

1. **bat 文件换行符 LF 致 label 查找偶发失败** — 所有 .bat 文件经 python 脚本归一为 CRLF。
2. **cmd `>` 字符重定向陷阱** — smoke-test 中 `"gate route -> LS 12107"` 字符串被 cmd echo 时解析 `>` 为重定向符，意外创建了 LS/GS/CS 三个空文件。已去除 `->`。
3. **stop.bat 中 `\uXXXX` 不被 cmd 识别** — 首次尝试 unicode 转义失败，改用 ASCII 提示文本。

### 验证结果

```
smoke-test.bat: PASS = 22    FAIL = 0
```

- commons + login-server jar 于 2026-04-14 20:53 重新编译，含 ProxyProtocolV2 LOCAL 支持 + TokenHandoff 白名单
- commons jar 同步部署到 game-server/libs + chat-server/libs
- shiguang-control.exe 于 20:53 重建，白名单校验生效：
  ```
  curl http://127.0.0.1:10443/api/token/validate    → 401 (token invalid, IP 通过)
  curl http://192.168.1.32:10443/api/token/validate → 403 token validation restricted to loopback
  ```

### 尚未修复（留给下一轮）

- [ ] control.yaml 的 JWT 密钥仍在明文；应通过环境变量注入
- [ ] 默认 `SHIGUANG_ADMIN_PASS=changeme` 告警级别不够，建议启动时 fail-fast
- [ ] TokenStore 持久化 consistency 文档补充
- [ ] /admin/ SPA 的 JWT middleware 覆盖范围审计
- [ ] launcher 远程下载 `patch_manifest_url` 仍指向 127.0.0.1（当前部署模型为玩家机本地托管，若改为中心化 Hub 下载需改配置）
