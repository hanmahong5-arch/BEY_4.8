# BEY_4.8 — Beyond-Aion 4.8 生产服

> AL-Aion 4.8 Java 游戏服务端，经七维工业级演进加固的自研特性生态。
> Production-grade Java game server with industrial-grade custom features.

## 快速开始

```bash
export JAVA_HOME=/d/jdk-25/jdk-25.0.1
cd server

bash build-all.sh prod   # 编译 + 部署到 prod/
bash start.sh prod       # 启动生产环境
bash stop.sh             # 停止
```

### 端口

| 服务 | 公开端口 | 内部端口 | 描述 |
|---|---|---|---|
| Chat Server | 10241 | 9021 | 聊天频道 |
| Login Server | 2107 | 12107 | 认证 |
| Game Server | 7778 | 17778 | 游戏世界 |
| Management API | — | 9100 | HTTP 仪表盘 |

公开端口由 `shiguang-gate` TCP 代理转发。内部端口偏移 +10000 便于多实例。

### 客户端

配对 **4.8 客户端** (`client/start32.bat` → 连 2107)。

---

## 自研特性

按 **七维工业级框架**（System Design / Tool Contract / Retrieval / Reliability / Security / Observability / Product）全覆盖：

### 1. Solo Fortress 单人要塞
顶伤玩家独占要塞，爵位体系（男爵→公爵），自动税银 / 衰败 / 领主赏金。
- 文档: [`doc/features/solo-fortress-and-ffa.md`](doc/features/solo-fortress-and-ffa.md)
- 命令: `//fortress list|status|leaderboard|grant|reset|history`

### 2. FFA 全体攻击模式
施放技能 245 切换 FFA，死亡装备入公共宝箱，连杀播报，频率硬限。
- 文档: [`doc/features/solo-fortress-and-ffa.md`](doc/features/solo-fortress-and-ffa.md)
- 命令: `//ffa list|status|clear|history`

### 3. PvP Season 赛季争锋
周期性 PvP 赛季（默认 7 天），实时排行榜，赛季奖励自动发放。
- 文档: [`doc/features/pvp-season.md`](doc/features/pvp-season.md)
- 命令: `//season status|leaderboard|archive|rollover|history`

### 4. Achievement 成就系统
10 预置里程碑（PvP 击杀 / 要塞独占 / FFA 连杀 / 领主屠杀）。BitSet 防重持久化。
- 文档: [`doc/features/achievements.md`](doc/features/achievements.md)
- 命令: `//ach catalog|list|grant|revoke|history`

### 5. NPC Hardcore 硬核档
NPC HP×1.5 / ATK×1.35 / SPD×1.1，装备 / 商品按概率掉落。

### 其他现有特性
- Luck System v2 - 动量幸运
- Smart Cast v2 - 方向性自动瞄准
- Swarm Intelligence - NPC 信息素协调
- Utility AI - NPC 长期目标

---

## 仪表盘 & API

Management HTTP 端口 `:9100`：

| 路径 | 用途 |
|---|---|
| `/api/health` | 健康检查 |
| `/api/status` | JVM + 玩家数 |
| `/api/dashboard` | **HTML 仪表盘**（暗色主题 + 领主/赛季排行榜 + 指标）|
| `/api/metrics` | 全指标 JSON（counters + timings）|
| `/api/audit/tail?feature=<f>&n=<n>` | 审计日志 tail |
| `/api/fortress/leaderboard` | 领主排行 JSON |
| `/api/season/leaderboard?n=<n>` | 赛季排行 JSON |

---

## 工程规范

### 工业级保证
- **异常边界**：所有钩点用 `try { } catch (Throwable)` 包裹；自定义代码不污染 vanilla 路径
- **指标持久化**：`CustomFeatureMetrics` 存 `log/custom_metrics_snapshot.json`，重启恢复
- **审计日志**：`CustomAuditLog` JSONL 格式于 `log/custom_audit.jsonl`
- **反滥用**：packed-key cooldown（反刷奖励）+ 每小时硬限（反宏脚本）
- **输入净化**：`BroadcastUtil.sanitize()` 防控制字符注入广播

### 新增特性流程
参见 skill `aion-4.8-gameplay-mods` 的 [`references/custom-feature-hardening.md`](~/.claude/skills/aion-4.8-gameplay-mods/references/custom-feature-hardening.md)。

关键陷阱：
1. 新管理命令必须加入 `config/administration/commands.properties` 否则启动 NPE
2. 新字段需 3 路同步：Java 字段 + DAO + SQL schema
3. 配置修改需重启（无热重载）

### 数据库
- `aion_gs` / `aion_ls` / `aion_cs`（PostgreSQL）
- Java DAO 直接写 SQL，**无存储过程**
- schema 变更必须双文件：`sql/aion_gs.sql` + `sql/update.sql`

---

## 仓库结构

```
BEY_4.8/
├── CLAUDE.md                  AI Agent 工作指南
├── README.md                  本文件
├── server/                    Maven 源码
│   ├── source/                Java 工程
│   └── build-all.sh           构建脚本
├── prod/                      生产运行时（JARs + 配置 + 日志）
├── dev/                       开发运行时
├── doc/
│   ├── features/              特性运维手册
│   │   ├── solo-fortress-and-ffa.md
│   │   ├── pvp-season.md
│   │   └── achievements.md
│   ├── evolution-plan.md      演进规划
│   └── CHANGELOG_EVOLUTION.md
├── client/                    4.8 客户端（25GB，.gitignore 排除）
└── tools/                     开发工具（monono2, version-dll 等）
```

---

## 许可与致谢

基于 AL-Aion 开源项目（AGPL）。

- Upstream: https://gitlab.com/al-aion/core
- 本仓库: https://github.com/hanmahong5-arch/BEY_4.8

生成日期: 2026-04-16
