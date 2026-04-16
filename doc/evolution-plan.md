# BEY_4.8 Evolution Plan — Multi-Agent Team Design
## 从「脚本化世界」到「会思考的生态系统」

> **Author**: Claude (Opus 4.6)
> **Date**: 2026-04-14
> **Status**: Draft — awaiting user review
> **Scope**: BEY_4.8 Java production server only
> **Counter-signed by**: (待用户确认后标记)

---

## 0. 设计原则 (不可违反)

1. **观测先于优化** — 没有可观测性数据作为依据, 任何"演化"都是幻觉
2. **可逆优先** — 每个新功能必须能被一个 config flag 关掉, 默认 OFF
3. **影子部署** — 先 dev 环境 soak, 再 prod, 不允许跳级
4. **增量小,思考大** — 每个 team 只做一个边界清楚的交付物
5. **验收可测** — 每个 phase 有客观的 gate 条件, 不是"看起来差不多就行"

---

## 1. 现状基线审计

### 1.1 已部署但未经游戏内验证

| 系统 | 代码位置 | 验证状态 | 风险 |
|------|---------|---------|------|
| Smart Cast v2 (双路径) | `FirstTargetProperty.java` + `SmartCastHelper.java` | ❌ 未验证 | 中 |
| Swarm Intelligence | `ai.swarm.*` (3 files) + 4 处挂钩 | ❌ 未验证 | **高** (热路径) |
| NPC Alert/Displaced/Linger | `ReturningEventHandler.java` | ⚠️ 部分验证 | 低 |
| Flow Luck System | `LuckService.java` + 多处挂钩 | ❌ 未验证 | 低 |
| Free Trade / Skip ID / 等 | 多个 config flag | ⚠️ 已配置, 功能未逐一实测 | 低 |

### 1.2 未知未知 (Unknown unknowns)

- `thinkAttack()` 每轮现在调用 `NpcAttentionScorer.bestTarget()`, 其中含 `forEachNpc()` 扫描 + 5 个头评分, **性能影响未测**
- 信息素网格的 `ConcurrentHashMap<Long, AtomicLong>` 在高玩家密度下的 allocation rate 未测
- 多 NPC 同时触发 `broadcastHate` 时是否有 aggro list 雪崩, 未测

### 1.3 真正缺失的能力

```
什么在缺?         ┆ 为什么重要?
─────────────────┼────────────────────────
可观测性         ┆ 没有数据, 后续每一步都是猜
行为测试框架     ┆ 无法快速迭代, 每次都要登录
回退机制         ┆ 出问题只能改 config 重启
指标总线         ┆ "自适应"类功能需要真实指标
决策日志         ┆ 无法解释 NPC 为什么做某事
```

这才是"演化"的前置条件。不是 Materializer, 不是 XML Generator。

---

## 2. 阶段拓扑

```
Phase 0 (必做, 并行)
├─ Team A (观测工具)    ──┐
└─ Team B (测试框架)    ──┤
                          ↓ Gate 0
Phase 1 (串行)
├─ Team C (验证 & 报告) ──┐
└─ Team D (精准修复)    ──┤
                          ↓ Gate 1
Phase 2
└─ Team E (指标总线)    ──┐
                          ↓ Gate 2 (soak 1 week)
Phase 3
└─ Team F (自适应倍率)  ──┐
                          ↓ Gate 3 (Opus 审查)
Phase 4
└─ Team G (Utility AI)  ──┘
                          ↓ Gate 4
                        ----- 真正的"思考系统"到此建立 -----
```

---

## 3. Team 定义

### Team A — 观测层工具集 (Phase 0)

**Goal**: 让我们能看到 swarm 和 AI 系统的内部状态

**Agent 搭配**: `Explore` + `general-purpose`

**Scope**:
- 新建: `com.aionemu.gameserver.ai.swarm.debug.*`
- 修改: `PlayerCommandHandler` 或对应的 admin command 注册点
- 仅在 dev 环境启用 (config flag `gameserver.custom.swarm_debug_enabled`)

**交付物**:

1. **`.admin swarm` 命令** — 打印玩家周围 5×5 cell 的信息素热度矩阵:
   ```
   [Swarm] pos=(1234.5, 2345.6) map=210040000
   ╔═══╦═══╦═══╦═══╦═══╗
   ║  0║  0║320║180║  0║
   ║  0║410║880║540║ 50║  ← player here (•)
   ║  0║220║ •1200║720║ 30║
   ║  0║ 60║480║210║  0║
   ║  0║  0║  0║  0║  0║
   ╚═══╩═══╩═══╩═══╩═══╝
   ```

2. **`.admin ai <npcObjectId>` 命令** — 打印目标 NPC 的完整 AI 快照:
   ```
   NPC 布洛尼工人 [object 3847291]
     state=FIGHT substate=NONE
     HP: 1240/3500 (35%)
     aggro list:
       - Player "Kael"    hate=23400  dist=8.2m
       - Player "Elune"   hate=12100  dist=12.4m
     attention scores (5-head breakdown):
       target             hate  prox  dist  cons  hp    TOTAL
       Kael               0.74  0.84  0.42  0.50  0.00  0.549
       Elune              0.62  0.75  0.30  0.00  1.00  0.577 ◀ best
     pheromone at NPC pos: 880
     would switch target: YES (Δ=0.028 < threshold=0.15) → NO
   ```

3. **`swarm_decisions.log`** — 滚动日志, 每次 `tryReevaluateTarget` 产生决策时写一行 CSV:
   ```
   timestamp,npcId,oldTarget,newTarget,score_old,score_new,switched
   2026-04-14T10:23:45.123,3847291,Kael,Elune,0.549,0.577,false
   ```

4. **性能计数器** — 暴露在 `.admin pulse` 里:
   - `thinkAttack` p50/p99 耗时 (ns)
   - `tryReevaluateTarget` 每秒调用次数
   - `forEachNpc` 每秒调用次数
   - 信息素网格 cell 总数 + 每秒 deposit 次数

**成功标准** (客观可测):
- 登录 dev, 打一只 NPC
- `.admin swarm` 返回非空矩阵且数值随时间衰减
- `.admin ai <id>` 能看到完整的 5 头分数
- `swarm_decisions.log` 有新条目

**风险**: 低 (全部 dev only, 生产 flag 默认 OFF)

**依赖**: 无 (Phase 0 并行)

---

### Team B — 回归测试框架 (Phase 0)

**Goal**: 让我们不用登录游戏就能验证 AI 行为的核心路径

**Agent 搭配**: `tdd-guide` (主导) + `Plan` (测试策略)

**Scope**:
- 新建: `source/game-server/src/test/java/com/aionemu/gameserver/ai/swarm/`
- 新建: `source/game-server/src/test/java/.../TestFixtures.java` (mock 工厂)
- 不修改任何产品代码

**交付物**:

1. **`SwarmPheromoneGridTest.java`**:
   - `deposit_thenSample_returnsIntensity()`
   - `decay_halflife60s_reducesByHalf()` (用 mock clock)
   - `findStrongestNearby_belowThreshold_returnsNull()`
   - `findStrongestNearby_singleCell_returnsCenterWorldCoords()`
   - `concurrency_1000DepositsFromThreads_noLoss()`

2. **`NpcAttentionScorerTest.java`**:
   - `headHate_zero_returns0`, `headHate_max_returns1`, `headHate_middle_monotonic`
   - `headProximity_closer_higherScore`
   - `headHpPressure_below30pct_returns1`, `headHpPressure_above60pct_returns0`, `headHpPressure_ramp_linear`
   - `bestTarget_twoCandidates_picksHigherScore`
   - `bestTarget_emptyAggroList_returnsNull`

3. **`SwarmBehaviorIntegrationTest.java`** (mock Npc + KnownList):
   - `onNpcDied_broadcastsHateToSameTribePeers`
   - `onNpcDied_doesNotBroadcastToOtherTribe`
   - `broadcastDistress_afterSaturation_doesNotFireAgain`
   - `shouldSuppressDisengage_belowThreshold_returnsFalse`

4. **游戏内场景脚本** (dev 环境 admin 命令):
   - `.test swarm focus_fire` — 在玩家周围召唤 5 个 test NPC + 2 个 mock target (一高血一低血), 验证多数 NPC 最终集火低血目标
   - `.test swarm investigation` — 在 30m 外沉积高强度信息素, 验证附近空闲 NPC 会走过去

**成功标准**:
- `cd source && mvn test -pl game-server` 绿
- 单元测试覆盖率 ≥ 60% (`ai.swarm.*` 包内)
- 两个 scenario 测试各跑 3 次, 结果一致

**风险**: 零 (test-only)

**依赖**: 无

---

### Team C — 验证 & Bug Hunt (Phase 1)

**Goal**: 用 Team A/B 的工具, 产出"现有系统真的在工作"的证据

**Agent 搭配**: `systematic-debugging` (或 `Explore` + `everything-claude-code:code-reviewer`)

**Scope**:
- 只读全代码库
- 产出 `doc/validation-2026-04-14.md` 报告
- **不修改任何代码**

**交付物** — 报告必须包含:

1. **Smart Cast 验证矩阵**:
   - TARGET 路径: 3 个技能实测 (近程、远程、AoE)
   - TARGETORME 路径: 3 个技能实测
   - 每个测试: 无目标施放, 观察是否自动选择, 记录预期 vs 实际

2. **Swarm 验证矩阵**:
   - Scenario 1: 单 NPC 打单玩家 → 信息素如何沉积和衰减
   - Scenario 2: 打死一只 NPC → 同族 NPC 是否收到仇恨广播
   - Scenario 3: 玩家逃跑, NPC 站在信息素热区 → 是否拒绝脱战
   - Scenario 4: 空闲 NPC 在信息素热区边缘 → 是否走过去
   - Scenario 5: 5 只 NPC 同时打玩家 → 是否集火残血目标
   - 每个 scenario 必须有 `.admin swarm` 截图 / `swarm_decisions.log` 片段作为证据

3. **性能基线**:
   - 用 Team A 的 `.admin pulse` 数据: 空闲服务器 vs 5 玩家 vs 20 玩家(若能凑够)
   - `thinkAttack` p99 耗时曲线
   - 判定: 是否有性能衰减迹象?

4. **Bug 清单** (PR 候选):
   - Each bug: title, severity, reproduce steps, suspected root cause, suggested fix file path
   - **不写代码, 只列问题**

**成功标准**:
- 报告产出并存入 `BEY_4.8/doc/validation-2026-04-14.md`
- 每个 scenario 有明确 PASS/FAIL 结论
- Bug 清单按 severity 排序

**风险**: 零 (只读)

**依赖**: Team A ✓ + Team B ✓

---

### Team D — 精准修复 (Phase 1)

**Goal**: 按 Team C 报告, 一个 bug 一个 commit 地修复

**Agent 搭配**: `everything-claude-code:build-error-resolver` 或 `general-purpose`

**Scope**:
- 只改 Team C 清单上的代码
- 每个 fix 必须附带一个回归测试 (在 Team B 的框架内)
- 单独 commit, commit message 引用 Team C 清单的 issue id

**成功标准**:
- 每个 bug 有 before/after 的 scenario 验证
- `mvn test` 绿
- Team C 清单里的 high severity 全部清零

**依赖**: Team C ✓

**Gate 1 决策点** (由用户执行):
- 剩余 bug 数量可接受?
- 性能基线是否有退化?
- 如否 → 回到 Team C/D 迭代
- 如是 → 进入 Phase 2

---

### Team E — WorldPulse 指标总线 (Phase 2)

**Goal**: 建立"全服状态"的单一事实源, 让后续自适应功能有数据基础

**Agent 搭配**: `everything-claude-code:architect` (设计) + `general-purpose` (实现)

**Scope**:
- 新建包: `com.aionemu.gameserver.metrics.*`
- 新建 PG 表: `world_pulse` (在 `aion_gs` 数据库)
- Migration 脚本: `doc/migrations/2026-04-14-worldpulse.sql`

**交付物**:

1. **`WorldPulse` 单例** — 持续采样 (每分钟 tick):
   ```java
   public final class WorldPulse {
       public record Snapshot(
           Instant at,
           int onlinePlayers,
           long pveKillsLastMin,
           long pvpKillsLastMin,
           long kinahInjected,   // NPC drops + quests
           long kinahSinked,     // broker fees, repairs, etc.
           double avgCombatDurationMs,
           int activeInstances
       ) {}
       public static Snapshot current();
       public static List<Snapshot> history(Duration window);
   }
   ```

2. **`RegionHeat` 宏观热度图** — 按地图 ID 分桶:
   ```java
   public final class RegionHeat {
       public int heat(int mapId);  // 0-100
       public Map<Integer, Integer> topN(int n);
   }
   ```
   `heat` 定义: 过去 5 分钟该地图信息素总沉积量的归一化值

3. **`.admin pulse` 命令** — 打印当前 WorldPulse + RegionHeat top-5

4. **PG 持久化后台任务** — 每 5 分钟把 `Snapshot` 写入 `world_pulse` 表, 保留 30 天

5. **SQL schema**:
   ```sql
   CREATE TABLE world_pulse (
       ts              TIMESTAMPTZ PRIMARY KEY,
       online_players  INT NOT NULL,
       pve_kills       INT NOT NULL,
       pvp_kills       INT NOT NULL,
       kinah_injected  BIGINT NOT NULL,
       kinah_sinked    BIGINT NOT NULL,
       avg_combat_ms   REAL NOT NULL,
       active_insts    INT NOT NULL
   );
   CREATE INDEX ON world_pulse (ts DESC);
   ```

**成功标准**:
- dev 环境 soak 24 小时后, `world_pulse` 表有 ≥ 288 行记录
- `.admin pulse` 实时返回
- 能从 psql 画出 kinah_injected 的时间序列

**风险**: 低 (采集, 不改游戏行为)

**依赖**: Phase 1 验证通过

**Gate 2 决策点**: soak 1 week 后, 数据是否有意义? 能否看出玩家活动模式?

---

### Team F — 自适应经济倍率 (Phase 3)

**Goal**: 让 drop / XP / kinah 倍率根据 WorldPulse 自适应, 但带硬约束

**Agent 搭配**: `everything-claude-code:architect` (必须 Opus 审) + `everything-claude-code:security-reviewer` (数学模型 red-team)

**Scope**:
- 新建: `com.aionemu.gameserver.services.economy.AdaptiveRates`
- 修改: `Rates` lookup 点 (插入一个 multiplier chain)
- 配置默认 OFF, 只在 dev 开启

**交付物**:

1. **`AdaptiveRates` 控制器** — 每 10 分钟读 WorldPulse:
   ```java
   // 伪代码 — 真实实现必须有数学证明
   float dropMultiplier(int mapId) {
       double baseline = historicalMean(kinahInjected, 7.days);
       double current  = WorldPulse.current().kinahInjected;
       double ratio    = current / baseline;
       // 反向控制: 当前通胀高 → 降低 drop
       float adjust = (float) Math.pow(0.95, ratio - 1.0);
       // 硬约束: [0.8, 1.2]
       return clamp(adjust, 0.8f, 1.2f);
   }
   ```

2. **约束检查层** — 任何调整超过 ±20% 直接拒绝, 写 warning log

3. **`.admin rates` 命令** — 显示当前各地图的 drop/xp/kinah 乘数 + 为什么是这个值

4. **Red-Team 测试**:
   - 模拟 `WorldPulse` 发送极端数据 (全服 1000 玩家暴刷 1 小时)
   - 验证 multiplier 收敛在约束内, 不震荡
   - 验证极端输入不会 NaN / Infinity

5. **Kill switch**: `gameserver.custom.adaptive_rates_enabled`, 默认 `false`

**成功标准**:
- Red-Team 测试 100% 通过
- dev 环境 soak 48 小时, 曲线平滑且在约束内
- Opus 审签 commit message: `[Reviewed by Opus 4.6]`

**风险**: **高** — 经济系统错误会破坏游戏平衡

**依赖**: Team E soak ≥ 1 周

**Gate 3 决策点** — 用户必须明确确认:
- 数学模型是否合理?
- 约束是否足够严?
- 默认是否保持 OFF?

---

### Team G — Utility AI 层 (Phase 4)

**Goal**: 给 NPC 可选的"长期目标"层, 叠加在现有 AITemplate 之上, 不替换

**Agent 搭配**: `everything-claude-code:architect` + (具体实现按领域分派)

**Scope**:
- 新建包: `com.aionemu.gameserver.ai.utility.*`
- **不修改** `AITemplate.java` 或现有 AI state machine
- 仅对 spawn 表中标记了 `utility_enabled=true` 的 NPC 生效 (白名单扩展机制)

**交付物**:

1. **`UtilityGoal` 接口**:
   ```java
   public interface UtilityGoal {
       /** 当前上下文下, 这个 goal 的效用值 [0, 1] */
       float score(Npc npc, UtilityContext ctx);
       /** 选出 goal 后, 返回要执行的动作 */
       UtilityAction selectAction(Npc npc, UtilityContext ctx);
       /** 完成或中断时回调 */
       void onComplete(Npc npc, UtilityContext ctx);
   }
   ```

2. **3 个示例 goal**:
   - `PatrolGoal` — 按配置点巡逻, score = 非战斗 && 到上次巡逻时间 > 5min
   - `DefendTerritoryGoal` — RegionHeat 高时向热点聚集, score = tribe是guard类 && RegionHeat > 50
   - `RestGoal` — 非战斗 + HP不满 → 找 spawn 点附近安全位置

3. **`UtilityController`** — 挂在 `NpcAI` 上 (optional, 通过 composition):
   - 每 10 秒评估一次所有 goal, 选 score 最高的
   - 不在 FIGHT state 时才激活

4. **实验区 spawn** — 选一个低流量地图 (如 210040000 某区域), 把 10 个 NPC 标记 utility_enabled
   - 其他地图完全不受影响

5. **一键回退**: `.admin utility disable all` 立即禁用全服 UtilityController

**成功标准**:
- 实验区 NPC 会自主巡逻、聚集、休息
- 非实验区的 NPC 行为、性能、日志完全不变
- `.admin ai <npcId>` 显示当前 active goal

**风险**: 中 (隔离得当, 白名单外零影响)

**依赖**: Phase 3 验证无问题

---

## 4. Hard Rules (不可违反)

| # | 规则 | 执行者 |
|---|------|--------|
| 1 | Sonnet 只执行 Phase 0 (Team A/B); Phase 1+ 全部 Opus 主导 | model router |
| 2 | 生产部署必须用户手动触发, 禁止 agent 执行 `start.sh prod` | rule in session |
| 3 | 每个 team 的产出必须单独 commit, 不打包 | git discipline |
| 4 | 数据库 schema 变更必须先在 dev 跑 migration | Team E 强制 |
| 5 | 任何 `gameserver.custom.*` 新 flag 默认必须 OFF | config convention |
| 6 | 热路径改动必须有 before/after 性能数据 | Team A 采集 |
| 7 | Swarm / AI 行为变更必须有 Team B 的测试覆盖 | TDD 要求 |
| 8 | 每个 Gate 必须用户明确 go/no-go, 不允许"默认通过" | workflow gate |

---

## 5. 明确 NOT DO 清单

- ❌ **不重写 DAO 为 ORM** — 现有 Java DAO 直接 SQL 工作良好
- ❌ **不迁移 MySQL** — 数据库就是 PostgreSQL, 原提示词有误
- ❌ **不做 "Event-Sourced" 重构** — 现有 `AIEventType` 已经是事件驱动
- ❌ **不做 Materializer / XML Generator** — 配置加载不是瓶颈, 不是痛点
- ❌ **不做 Schema-Driven config 生成** — 需求未验证, 先看真实痛点
- ❌ **不重写 Swarm Intelligence** — 刚部署, 先验证, 再迭代
- ❌ **不在 prod 开启任何未经 dev soak 1 周的新功能**

---

## 6. 时间线估算 (仅供参考, 不是承诺)

| Phase | 预估时长 | 可并行度 |
|-------|---------|---------|
| Phase 0 (A + B) | 2-3 session | 高 (并行) |
| Phase 1 (C + D) | 2-4 session | 中 (串行 C→D) |
| Phase 2 (E) | 2 session + 1 周 soak | 低 |
| Phase 3 (F) | 2 session + 1 周 soak | 低 |
| Phase 4 (G) | 3 session + soak | 低 |

注: "session" = 一次有人值守的 Claude 交互回合

---

## 7. 回到原提示词的映射

| 原需求 | 本计划对应 | 备注 |
|--------|-----------|------|
| Schema-Driven Single Source of Truth | Team E (WorldPulse 是运行时单一事实源) | 不是配置生成 |
| Event-Sourced Game Logic | 现有 AIEventType + Team G (UtilityGoal) | 不重写 |
| Hyper-Scaling Reward System | Team F (AdaptiveRates) | 带硬约束 |
| Intelligent Entity Synthesis | Team G (Utility AI) | 白名单叠加 |
| Self-Healing Data Validation | Team B (测试框架) + Team C (验证报告) | 测试驱动 |
| Performance Profiling Loop | Team A (观测) + Team C (基线报告) | 先观测再优化 |
| Autonomous CI/CD | 本计划的 Gate 机制 | 不做无人值守执行 |

每一项原需求都被映射到了具体可验证的交付物, 但**执行方式完全不同**: 不是"AI自主完成", 而是"AI负责实现,用户负责 gate"。

---

## 8. 下一步

**等待**:
- 用户 review 本计划
- 标记每个 phase 的优先级
- 确认 Hard Rules 全部接受
- 指定 Phase 0 的启动时机

**不会**:
- 在用户 review 前执行任何一步
- 修改任何 prod 目录内容
- 自动进入下一阶段 (每个 Gate 必须用户点头)

---

*Signed: Claude Opus 4.6 — 2026-04-14*
*This is a plan, not an execution log.*
