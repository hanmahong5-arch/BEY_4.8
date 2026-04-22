# BEY_4.8 成就系统 (Achievement System)

> 面向 GM / 运维 — 里程碑成就、玩家进度追踪、管理命令。
> 基于 BEY_4.8 七维工业级框架。

## 1. 特性总览

### 核心
- 10 个预置里程碑成就（PvP 击杀、要塞独占、FFA 连杀、领主屠杀四大类）
- 玩家首次达成即解锁，BitSet 防重
- 高等级成就（threshold ≥ 100 或同时要塞数）全服广播
- 普通成就仅私信通知
- 持久化：`log/custom_achievements.json`（unlocked BitSet + 累加 counters）

### 成就目录

| ID | 称号 | 条件 | 触发器 | 阈值 |
|---|---|---|---|---|
| 1 | 初啼 | 首次击杀敌对玩家 | PVP_KILL | 1 |
| 2 | 百夫长 | 累计 PvP 击杀 100 人 | PVP_KILL | 100 |
| 3 | 传奇杀神 | 累计 PvP 击杀 1000 人 | PVP_KILL | 1000 |
| 4 | 登基 | 首次独占要塞 | FORTRESS_CAPTURE | 1 |
| 5 | 双冠 | 同时独占 2 座要塞 | FORTRESS_CONCURRENT | 2 |
| 6 | 三冠 | 同时独占 3 座要塞 | FORTRESS_CONCURRENT | 3 |
| 7 | 五冠 | 同时独占 5 座 — 君临天下 | FORTRESS_CONCURRENT | 5 |
| 8 | 狂血 | FFA 连杀 10 人 | FFA_STREAK | 10 |
| 9 | 屠神 | 击杀领主 10 次 | LORD_KILL | 10 |
| 10 | 弑君 | 击杀领主 50 次 | LORD_KILL | 50 |

---

## 2. 触发点

植入于既有服务，**无需额外钩点**：

| 事件源 | 触发器 |
|---|---|
| `PvpSeasonService.onPvpKill` | `PVP_KILL` |
| `SoloFortressService.onSoloCapture` | `FORTRESS_CAPTURE` + `FORTRESS_CONCURRENT` |
| `SoloFortressService.onLordKilled` | `LORD_KILL` |
| `FfaModeService.trackKillerStreak` | `FFA_STREAK` |

全部包装于 `try { } catch (Throwable)`，成就系统故障不污染游戏路径。

---

## 3. 管理员命令

### `//ach`
| 子命令 | 作用 |
|---|---|
| `//ach catalog` | 列出所有成就定义 |
| `//ach list <playerName>` | 查询玩家已解锁列表（可离线查询）|
| `//ach grant <playerName> <id>` | GM 直接授予 |
| `//ach revoke <playerName> <id>` | GM 撤销 |
| `//ach history [n]` | 审计日志追溯 |

Access level: 9

---

## 4. 配置

仅 1 个开关：

| Key | 默认 | 说明 |
|---|---|---|
| `gameserver.custom.achievement_enabled` | true | 主开关 |

成就目录在代码中硬编码（`AchievementService.CATALOG`），扩展需改 Java + 重启。这是有意简化 — 内容固化而非数据驱动。

---

## 5. 指标

| Key | 含义 |
|---|---|
| `achievement.unlocked` | 累计解锁次数 |
| `achievement.unlocked.<id>` | 各成就解锁次数 |

---

## 6. 七维映射

| 维度 | 交付 |
|---|---|
| System Design | `AchievementService` 单例 + Trigger enum 触发器 + Catalog 枚举 |
| Tool & Contract | `//ach` 5 子命令（含离线查询）|
| Retrieval | 审计日志 + catalog 枚举 |
| Reliability | JSON 持久化 + shutdown hook + BitSet 防重 |
| Security | GM grant/revoke 审计 |
| Observability | 指标分 `achievement.unlocked.<id>` 粒度 |
| Product | 高等级成就全服广播 + 称号私信 + emoji 🏆 |

---

## 7. 持久化格式

`log/custom_achievements.json`:

```json
{
  "players": {
    "123": [1, 2, 4, 5],
    "456": [1]
  },
  "counters": {
    "123": [142, 3, 0, 7, 0],
    "456": [5, 0, 0, 0, 0]
  }
}
```

- `players.<id>` = BitSet of unlocked achievement IDs
- `counters.<id>` = 触发器累加值数组，下标为 `Trigger.ordinal()`

---

## 8. 扩展成就

在 `AchievementService.java` 的 static block 中：

```java
add(11, "末日骑士", "FFA 连杀 25 人", Trigger.FFA_STREAK, 25);
```

重启服务器后即生效。已解锁玩家不受影响（BitSet 兼容）。

---

## 9. 关键文件

| 文件 | 作用 |
|---|---|
| `services/achievement/AchievementService.java` | 主服务 + catalog |
| `data/handlers/admincommands/Ach.java` | `//ach` 命令 |
| `log/custom_achievements.json` | 持久化状态 |

生成日期: 2026-04-16 | 基于七维工业级框架
