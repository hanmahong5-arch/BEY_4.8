# BEY_4.8 PvP 赛季系统 (PvP Season System)

> 面向 GM / 运维 — 周期性 PvP 赛季、实时排行榜、赛季奖励全流程。
> 遵循 BEY_4.8 七维工业级演进框架。

## 1. 特性总览

### 核心机制
- **周期**: 每 N 天自动 rollover（默认 7 天）
- **击杀计数**: 每次异阵营 PvP 击杀 +1 kill，victim +1 death
- **反作弊**: 同一 (killer, victim) 5 分钟内只计一次
- **实时 AP 加成**: 每次计数击杀额外发 N AP（默认 100，独立于 vanilla AP）
- **首杀广播**: 赛季第一杀全服宣告
- **Rollover 结算**: 前 N 名按 `PVP_SEASON_TOP_REWARDS_AP` 分级发 AP，前 3 名广播
- **归档**: 每次 rollover 写 `log/custom_pvp_season_archive_<ms>.json`

### 钩入
- `PlayerController.onDie` line ~336 — `onPvpKill(victim, lastAttacker)`
- `SiegeService.initSieges()` — `PvpSeasonService.getInstance().init()`

---

## 2. 管理员命令

### `//season`
| 子命令 | 作用 |
|---|---|
| `//season status` | 赛季进度 + 指标快照 + 计时直方图 |
| `//season leaderboard [n]` | 排行榜 top n（默认 10，上限 50）|
| `//season archive` | 归档文件列表 |
| `//season rollover` | GM 强制结束当前赛季（审计）|
| `//season history [n]` | 审计日志追溯 |

Access level: 9（GM 专用）

---

## 3. 配置参考

位于 `config/main/custom.properties`：

| Key | 默认 | 说明 |
|---|---|---|
| `gameserver.custom.pvp_season_enabled` | true | 主开关 |
| `gameserver.custom.pvp_season_duration_days` | 7 | 赛季周期（日）|
| `gameserver.custom.pvp_season_flush_interval_ms` | 3600000 | 持久化/边界扫描周期（ms）|
| `gameserver.custom.pvp_season_per_kill_ap` | 100 | 每次计数击杀额外 AP |
| `gameserver.custom.pvp_season_top_rewards_ap` | 100000,50000,25000,10000,5000 | 前 N 名奖励 AP（逗号分隔）|
| `gameserver.custom.pvp_season_first_blood_broadcast` | true | 首杀广播开关 |

---

## 4. 指标术语表

通过 `//season status` 或 `/api/metrics` 查询：

| Key | 含义 |
|---|---|
| `pvpseason.kills` | 有效 PvP 击杀总数 |
| `pvpseason.deaths` | PvP 被杀总数 |
| `pvpseason.ap_awarded` | 累计发放赛季 AP |
| `pvpseason.denied_cooldown` | 因 5 分钟冷却拒绝计数 |
| `pvpseason.rollover` | 赛季结束次数 |
| `pvpseason.reward_paid` | 奖励发放次数 |
| `pvpseason.sweep_ms` | 扫描耗时直方图 |

---

## 5. 七维映射

| 维度 | 交付 |
|---|---|
| System Design | `PvpSeasonService` 单例 + ScheduledFuture sweep |
| Tool & Contract | `//season` 5 子命令 |
| Retrieval | 历史归档文件 + `//season archive` |
| Reliability | 每小时 flush + JVM shutdown hook + 按 entry error 隔离 |
| Security | 5 分钟 killer/victim 冷却 + 异阵营过滤 + GM 审计 |
| Observability | 6 指标 + `sweep_ms` 直方图 |
| Product | 首杀广播 + Top 3 广播 + 前 N 名 AP 奖励 |

---

## 6. 数据流

```
PlayerController.onDie
  └─> PvpSeasonService.onPvpKill
        ├─> 5min anti-farm check → 拒绝 or 累加
        ├─> killer.kills++ / victim.deaths++
        ├─> AP 奖励 via AbyssPointsService
        ├─> AchievementService.onEvent(PVP_KILL)
        └─> 首杀广播（若 kills == 1 且开启）

Hourly sweep
  ├─> 边界检查: now >= start + duration → rolloverSeason
  └─> saveState to log/custom_pvp_season.json

rolloverSeason
  ├─> top N rewards (online: AP addAp + SM_MESSAGE; offline: audit only)
  ├─> 广播 top 3
  ├─> archiveSeason → log/custom_pvp_season_archive_<ms>.json
  └─> stats.clear() + 新 seasonStartMs
```

---

## 7. 故障排查

| 症状 | 可能原因 | 应对 |
|---|---|---|
| `//season leaderboard` 为空 | 无击杀或全被冷却拒 | 查 `pvpseason.denied_cooldown` |
| AP 不加成 | `pvp_season_per_kill_ap = 0` | 调大配置 |
| 赛季不 rollover | `pvp_season_duration_days <= 0` | 配置正值 |
| 持久化丢失 | `log/` 目录无写权限 | 检查文件权限 |
| 奖励未到 | offline 玩家不邮寄（当前设计）| 日志 `audit`；后续可扩展 SystemMailService |

---

## 8. 关键文件

| 文件 | 作用 |
|---|---|
| `services/pvpseason/PvpSeasonService.java` | 主服务 |
| `data/handlers/admincommands/Season.java` | `//season` 命令 |
| `management/ManagementServer.java` | `/api/season/leaderboard`, `/api/dashboard` |
| `log/custom_pvp_season.json` | 当前赛季状态（运行时）|
| `log/custom_pvp_season_archive_*.json` | 历史归档 |

生成日期: 2026-04-16 | 基于七维工业级框架
