# BEY_4.8 自定义特性运维手册

> 面向 GM / 运维 — 单人要塞 (Solo Fortress) 与全体攻击 (FFA) 两大自研特性的配置、管理命令、排错与指标。
> Target audience: GMs / SREs. Reference for config, admin commands, troubleshooting, and metrics of the two custom features.

## 1. 特性总览

### 1.1 全体攻击模式 (FFA)
- **入口**: 玩家施放 `绷带治疗` (skill id 245) 4 秒读条完成 → 进入/退出 FFA
- **效果**: 进入后同时对所有玩家与所有 NPC 敌对；死亡掉落 N 件随机装备至地面宝箱 (NPC 230103)，180 秒后自动消失
- **冷却**: 切换间隔 30 秒 (防抖)；安全区 (Sanctum/Pandaemonium) 禁止切换
- **频率限制**: 每小时最多 20 次切换 (防宏脚本)，可配置
- **连杀播报**: 每 3 连杀全服广播 (可配置)，死亡/退出重置
- **配套**: NPC 硬核档 (HP ×1.5 / ATK ×1.35 / SPD ×1.1) 与 `穿啥掉啥 / 卖啥掉啥` 概率掉落

### 1.2 单人要塞 (Solo Fortress)
- **攻占**: 军团要塞战胜利时，若开启 `SOLO_FORTRESS_ENABLED` 且 `PREFER_SOLO`，顶伤玩家独占要塞 (legion_id 清零)
- **加冕广播**: 全服黄框宣告新君，含爵位称号
- **爵位体系**: 1座=男爵, 2座=子爵, 3座=伯爵, 4座=侯爵, 5+座=公爵
- **传送**: 独主无视种族传送至己方要塞
- **领主 Buff**: 进入己方要塞区域自动施加可配置 skill (默认关闭)
- **税银**: 每小时邮寄 kinah (按 `occupy_count` 递增)
- **衰败**: 连续离线 > 7 日 → 要塞归 Balaur
- **领主赏金**: 任何人击杀领主获 `BASE × 拥有要塞数` AP；同一凶手/受害者 15 分钟内只奖赏一次 (反刷)
- **易主哀报**: 新攻下时广播旧主陨落
- **登临问候**: 领主登录时私信告知治下要塞名录 + 爵位

---

## 2. 管理员命令

### `//fortress`
| 子命令 | 作用 |
|---|---|
| `//fortress list` | 枚举所有要塞、种族、军团、独主状态、tier |
| `//fortress reset <id>` | 强制清除独主、写库、全服广播 |
| `//fortress status` | `fortress.*` 指标 + 计时 + 当前独主数 |
| `//fortress leaderboard` | 领主排行榜 (按拥有要塞数降序, 含爵位) |
| `//fortress grant <player> <id>` | 直接授予要塞领主权 (GM 覆写) |
| `//fortress history [n]` | 最近 n 条要塞审计日志 (默认 10) |

### `//ffa`
| 子命令 | 作用 |
|---|---|
| `//ffa list` | 列出在线处于 FFA 状态的玩家 |
| `//ffa clear <name>` | 强制退出 FFA (卡状态救援) |
| `//ffa status` | `ffa.*` + `npc.hardcore.*` 指标快照 |
| `//ffa history [n]` | 最近 n 条 FFA 审计日志 (默认 10) |

---

## 3. 配置参考

所有配置项位于 `config/main/custom.properties`，reload 需重启 game server。

### 3.1 FFA
| Key | 默认 | 说明 |
|---|---|---|
| `gameserver.custom.ffa_mode_enabled` | true | 主开关 |
| `gameserver.custom.ffa_trigger_skill_id` | 245 | 触发技能 ID (0 禁用劫持) |
| `gameserver.custom.ffa_toggle_cooldown_ms` | 30000 | 切换冷却 (ms) |
| `gameserver.custom.ffa_loot_chest_npc_id` | 230103 | 掉落宝箱 NPC ID |
| `gameserver.custom.ffa_loot_chest_lifetime_ms` | 180000 | 宝箱存续时长 (ms) |
| `gameserver.custom.ffa_drop_item_count` | 1 | 每次死亡掉几件装备 |
| `gameserver.custom.ffa_max_toggles_per_hour` | 20 | 每小时最多切换次数 (0 禁用) |
| `gameserver.custom.ffa_kill_streak_broadcast_threshold` | 3 | 每 N 连杀全服广播 (0 禁用) |

### 3.2 NPC 硬核档
| Key | 默认 | 说明 |
|---|---|---|
| `gameserver.custom.npc_hp_multiplier` | 1.5 | HP 倍率 |
| `gameserver.custom.npc_atk_multiplier` | 1.35 | 攻击倍率 |
| `gameserver.custom.npc_speed_multiplier` | 1.1 | 速度倍率 |
| `gameserver.custom.npc_equip_drop_chance` | 0.02 | NPC 装备单槽掉率 (2%) |
| `gameserver.custom.npc_sell_drop_chance` | 0.005 | 商人 sell list 单项掉率 (0.5%) |
| `gameserver.custom.npc_boss_drop_mult` | 3.0 | Boss 双概率倍率 |

### 3.3 单人要塞
| Key | 默认 | 说明 |
|---|---|---|
| `gameserver.custom.solo_fortress_enabled` | true | 主开关 |
| `gameserver.custom.solo_fortress_prefer_solo` | true | true: 顶伤玩家独占；false: 仅无军团者独占 |
| `gameserver.custom.solo_fortress_lord_buff_skill_id` | 0 | 领主区域 buff skill (0 关) |
| `gameserver.custom.solo_fortress_hourly_tax_kinah` | 500000 | 基础小时税 kinah |
| `gameserver.custom.solo_fortress_tax_tier_mult` | 0.25 | 每 tier 税银递增率 |
| `gameserver.custom.solo_fortress_decay_days` | 7 | 离线衰败天数 |
| `gameserver.custom.solo_fortress_sweep_interval_ms` | 3600000 | 税/衰败扫描周期 |
| `gameserver.custom.solo_fortress_bounty_ap` | 10000 | 领主击杀单要塞基础赏金 AP |

---

## 4. 指标术语表

指标通过 `CustomFeatureMetrics` 单例累计。重启时从 `log/custom_metrics_snapshot.json` 恢复 (Reliability)，关闭时自动保存。通过 `//fortress status` 或 `//ffa status` 查询。

### FFA
| Key | 含义 |
|---|---|
| `ffa.toggle.enter` | 进入 FFA 次数 |
| `ffa.toggle.exit` | 退出 FFA 次数 |
| `ffa.death` | FFA 状态下死亡次数 |
| `ffa.chest.spawned` | 生成宝箱次数 |
| `ffa.chest.items_total` | 宝箱累计物品数 |
| `ffa.kills` | FFA 模式下击杀数 |
| `ffa.streak.broadcast` | 连杀广播触发次数 |

### NPC 硬核
| Key | 含义 |
|---|---|
| `npc.hardcore.bonus_equip_drops` | 额外装备掉落件数 |
| `npc.hardcore.bonus_sell_drops` | 额外商品掉落件数 |

### 单人要塞
| Key | 含义 |
|---|---|
| `fortress.solo.capture` | 独占攻占次数 |
| `fortress.solo.dethrone` | 领主被废次数 |
| `fortress.solo.bounty.awarded` | 赏金发放次数 |
| `fortress.solo.bounty.ap_total` | 赏金 AP 累计 |
| `fortress.solo.bounty.denied_cooldown` | 因冷却被拒次数 |
| `fortress.solo.tax.paid` | 税银发放次数 |
| `fortress.solo.tax.kinah_total` | 税银 kinah 累计 |
| `fortress.solo.decay` | 衰败回收次数 |
| `fortress.solo.lord_buff.applied` | 领主 buff 施加次数 |

---

## 5. 故障排查

| 现象 | 可能原因 | 应对 |
|---|---|---|
| 玩家施放绷带后未进 FFA | skill id 未劫持 / 主开关关 | `grep FFA` config；`//ffa status` 查触发计数 |
| FFA 死亡未掉落装备 | `FFA_DROP_ITEM_COUNT=0` / 装备全空 | 查 `ffa.chest.spawned` 计数；检查 server_console.log `[FFA]` 行 |
| 宝箱 NPC 未生成 | `FFA_LOOT_CHEST_NPC_ID` 不存在 | 查 `server_console.log` 中 `failed to spawn loot chest npc` |
| 要塞战结束独主未生效 | `SOLO_FORTRESS_ENABLED=false` / 无顶伤玩家 | `//fortress list` 查当前状态 |
| 领主赏金未发 | 同一对 15 分钟内已发 / NPC 击杀 | 查 `fortress.solo.bounty.denied_cooldown` |
| 税银未到邮箱 | 邮箱满 / 玩家名变更 | `SYSMAIL_LOG` 搜 `LORD_TAX` |
| 衰败未触发 | 领主在线 / `lastOnline` 异常 | `//fortress list` 查；DB: `SELECT last_online FROM players WHERE id=?` |
| 服务启动日志无 `sweep scheduled` | `SOLO_FORTRESS_ENABLED=false` 或 SiegeService 未启 | 检查 `SiegeConfig.SIEGE_ENABLED` |

---

## 6. 数据库

### 6.1 Schema 变更
```sql
ALTER TABLE siege_locations
  ADD COLUMN owner_player_id integer NOT NULL DEFAULT 0,
  ADD COLUMN owner_captured_at bigint NOT NULL DEFAULT 0;
```
已集成至 `sql/aion_gs.sql` 与 `sql/update.sql`。

### 6.2 紧急手动重置
```sql
-- 清除单人领主归属 (所有要塞)
UPDATE siege_locations SET owner_player_id=0, owner_captured_at=0;
-- 仅清除某要塞
UPDATE siege_locations SET owner_player_id=0, owner_captured_at=0, race='BALAUR', legion_id=0 WHERE id=1131;
```
**注意**: DB 直改后须重启 game server 或用 `//fortress reset <id>` 让内存同步。

---

## 7. 异常边界保证

所有自定义特性 hook 点 (PlayerController.onDie、FortressSiege.onCapture、SiegeLocation.onEnter/LeaveZone、PlayerEnterWorldService.enterWorld) 均已用 `try { } catch (Throwable)` 包裹，自定义代码抛错**不会**污染原版死亡、攻城、登录路径。所有错误日志使用 `log.error` 带 feature 前缀 (`[FFA]`、`[SoloFortress]`)。

---

## 8. 审计日志

所有自定义特性事件写入 `log/custom_audit.jsonl`（JSONL 格式，每行一条 JSON）。GM 命令亦记录于此。

通过 `//fortress history [n]` 和 `//ffa history [n]` 查询最近 n 条（默认 10，最多 50）。

字段：`ts` (epoch ms)、`feature` (fortress/ffa/gm)、`action`、`actor`、`detail`。

---

## 9. 七维演进设计映射

| 维度 | 对应设施 |
|---|---|
| System Design | `BroadcastUtil` 集中广播 + sanitize; `CustomFeatureMetrics` 计时统计 |
| Tool & Contract | `//fortress leaderboard\|grant\|history`; `//ffa history` |
| Retrieval | `CustomAuditLog.tail(n, filter)` → history 子命令 |
| Reliability | 指标 save/load 持久化; JVM shutdown hook; sweep duration timing |
| Security & Safety | GM 审计日志; FFA 频率限制; 广播 sanitize 防注入 |
| Evaluation & Observability | `recordTiming` 直方图; timing snapshot 在 status 命令中展示 |
| Product Thinking | 爵位体系 (男爵→公爵); FFA 连杀播报; 领主排行榜 |

---

## 10. 关键文件索引

| 文件 | 作用 |
|---|---|
| `metrics/CustomFeatureMetrics.java` | 指标计数 + 计时 + 持久化 |
| `metrics/CustomAuditLog.java` | JSONL 审计日志 |
| `utils/BroadcastUtil.java` | 广播工具 + 爵位 + sanitize |
| `services/ffa/FfaModeService.java` | FFA 状态机 + 宝箱生成 |
| `services/ffa/NpcLootInjector.java` | NPC 装备/商品额外掉落 |
| `services/siege/SoloFortressService.java` | 独主主服务 (税/衰败/赏金/广播) |
| `services/siege/FortressSiege.java` | onCapture solo 分支 |
| `model/siege/SiegeLocation.java` | `ownerPlayerId` 字段 + 传送放行 + 区域 hook |
| `dao/SiegeDAO.java` | DB 读写 |
| `controllers/PlayerController.java` | FFA + 赏金 hook |
| `services/player/PlayerEnterWorldService.java` | 登临问候 hook |
| `configs/main/CustomConfig.java` | 全部配置项 |
| `data/handlers/admincommands/Fortress.java` | `//fortress` 命令 |
| `data/handlers/admincommands/Ffa.java` | `//ffa` 命令 |

生成日期: 2026-04-14 | 七维演进更新: 2026-04-15
