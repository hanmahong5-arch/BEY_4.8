# BEY_4.8 开发计划 / Development Plan

> 本文档是 BEY_4.8 工作区的唯一计划来源。所有规划、方案、战略均在此记录。
> This document is the single source of planning truth for the BEY_4.8 workspace.

---

# 第一部分：游戏机制深度诊断
# Part I: Deep Diagnosis of Existing Game Mechanics

## 1.1 机制全景 / Mechanism Landscape

通过代码扫描（2026-04-13），BEY_4.8 已实现以下核心系统：

| 系统 | 核心文件 | 完整度 | 核心问题 |
|------|---------|--------|---------|
| 技能引擎 | SkillEngine, Skill, 178个Effect类 | ★★★★★ | 静态，无情境化，无玩家创造空间 |
| 战斗系统 | AttackUtil, StatFunctions | ★★★★☆ | 纯数值博弈，缺乏机制深度 |
| NPC/怪物AI | AbstractAI, NpcAI, WalkManager | ★★★☆☆ | 状态机固化，无记忆，无适应性 |
| 任务系统 | QuestEngine, XMLQuest | ★★★★☆ | 线性叙事，玩家行为对世界无影响 |
| 飞行系统 | FlyController | ★★★☆☆ | 无高度分层，无战略意义，差异化被浪费 |
| 阵营系统 | Race, TribeRelationService | ★★★☆☆ | 二元对立，零和博弈，生态极脆弱 |
| 围城战 | SiegeService, FortressSiege | ★★★★☆ | 时间固定，非活跃期玩家完全无感 |
| 副本系统 | InstanceService, InstanceEngine | ★★★★☆ | 重复性高，缺乏叙事感，排行不可见 |
| 经济系统 | DropService, BrokerService | ★★★☆☆ | 无真正稀缺物，通胀宿命 |
| 军团系统 | LegionService（8级成长）| ★★★☆☆ | 是组队标签，不是策略单元 |
| 角色成长 | PlayerGameStats, AbyssRank | ★★★★☆ | 满级后只剩数值，无横向成长 |
| 住宅系统 | HousingService, HousingBid | ★★★☆☆ | 装饰性强，功能性弱，孤立无社区感 |

## 1.2 根本性设计问题 / Fundamental Design Flaws

### 问题一：阵营系统的「生态死亡螺旋」

AION 的天魔二元对立是零和设计的极端形式：
- 一方人数占优 → 反复击杀劣势方 → 劣势方玩家流失 → 差距扩大 → 服务器生态崩溃
- Race.java 中存在 NEUT（中立种族）但从未用于玩家
- SiegeService 存在 Balaur 重置逻辑（龙族夺回要塞），但此机制未与玩家叙事联动
- **同族绝对不可攻击**意味着内部竞争完全缺失，军团之间的张力无法释放

> **根本问题**：对立关系是服务器生态的唯一张力来源，一旦失衡，整个生态坍塌。

### 问题二：战斗是「数字比大小」而非「决策博弈」

- 178个Effect类涵盖了几乎所有效果类型，但玩家在战斗中的决策空间极小
- 同一职业几乎只有一个"最优解"技能循环
- 怪物/Boss没有「弱点矩阵」——不需要思考克制，只需要堆属性
- 技能是固定配置，不存在Build多样性
- LuckSystem 已实现动量机制，但仅作用于结果概率，没有作用于战斗策略层

> **根本问题**：技能复杂度都消耗在服务端计算，但玩家感知不到「因为我做了正确决策所以赢了」。

### 问题三：飞行是被浪费的差异化特征

- FlyController 完全没有 Z 轴高度检测逻辑（已确认）
- 飞行的唯一意义是移动速度和越过地形障碍
- FREE_FLIGHT 配置已取消 FP 限制，进一步削弱飞行的稀缺感
- AION 号称「飞翔」是核心体验，但在现有实现中飞行没有战略意义
- 空中维度（Z 轴）作为博弈空间完全未被开发

> **根本问题**：差异化特征的价值没有被制度化——飞行不影响任何战略决策。

### 问题四：任务是「打卡清单」而非「世界参与」

- 任务完成 = 世界状态不变
- 玩家是故事的**旁观者**，不是**参与者**
- QuestEngine 支持变量（QuestVar）和条件（QuestConditions），但没有「全服公共进度」的概念
- 没有「玩家的行为可以改变世界」的任何设计

> **根本问题**：任务完成感来自个人奖励，而不是「我推动了世界」的成就感。

### 问题五：经济系统无法产生「传奇时刻」

- 所有道具都可以无限刷出，没有真正的稀缺性
- 掉落是个人/队伍事件，没有「全服事件」
- 传奇的「屠龙刀」为什么让一个服务器的人几十年后还记得？因为**全服只有一把，所有人都知道它在谁手里**
- BrokerService 的拍卖行只是价格发现机制，没有稀缺性放大器

> **根本问题**：当「最好的装备」可以被任何人刷出来时，装备失去了社会意义，只剩数值意义。

### 问题六：NPC 是「场景道具」

- NPC 固定坐标，固定台词，固定功能，固定刷新时间
- NpcShoutsService 存在但只处理静态配置的台词
- 没有日程（时段相关行为）
- 没有记忆（不知道玩家之前做过什么）
- 没有对世界状态的反应（围城战结束后，NPC 说的话一字不变）

> **根本问题**：玩家无法相信「这是一个活着的世界」，因为世界里所有的「人」都是假人。

### 问题七：军团是「组队标签」不是「战略单元」

- LegionService 有 8 级成长体系和贡献点，基础已完整
- 但军团的作用本质上只是：有个共同的名字、共用一个仓库、可以在围城战里一起行动
- 没有军团外交（结盟/宣战/竞争协议）
- 没有军团领地（除了围城战时的要塞占领）
- 没有军团文化积累（每个军团看起来都一样）
- 同阵营军团之间没有竞争机制

> **根本问题**：军团是玩家自发形成的社群，但游戏没有给这个社群提供足够的「身份差异化工具」。

---

# 第二部分：现代游戏设计洞见
# Part II: Insights from Modern Game Design

以下洞见来自对顶级游戏设计的系统分析，每条洞见后附 AION 场景下的具体应用方向。

---

## 2.1 米哈游（原神/崩铁）：情感绑定驱动留存

**洞见 A：角色是情感锚点，不是数值容器**
原神的核心发现：玩家为「喜欢的角色」付出，不是为「更高的数字」付出。每个角色有声音、有故事、有在世界中的位置，玩家愿意花时间「认识」他们。
→ **AION应用**：NPC 不应该是没有名字的"商人"，而应该是有历史、有台词记忆、有对玩家行为反应的「世界居民」。玩家帮助过的 NPC 记得你。

**洞见 B：元素反应 = 机制多样性而非数值多样性**
崩铁的弱点克制系统（火/冰/雷/风属性，破防/击破机制）让同样的战斗因为队伍组合不同而完全不同。复杂度来自「机制组合」而非「数值堆叠」。
→ **AION应用**：为怪物/Boss 引入「弱点类型」，克制时触发「破防」特殊状态，让职业组合产生化学反应。

**洞见 C：多目标并行消除「空虚感」**
任何时刻登录，玩家同时有：日常任务 / 活动进度 / 成就追踪 / 长期目标。不存在「不知道干什么」的状态。
→ **AION应用**：「纪元目标板」系统，同时展示 5 层次目标（今日/本周/纪元/个人/阵营），始终有事可做且进度可见。

---

## 2.2 传奇（热血传奇/传奇世界）：稀缺性创造共同历史

**洞见 D：全服唯一物品创造「历史性时刻」**
屠龙刀为什么让一个服务器的人几十年后还记得？不是因为它多强，是因为**全服所有人都在追这把刀，所有人都知道它在谁手里**。这是一个「共同历史时刻」。
→ **AION应用**：每个纪元全服只掉落 3 件「传世装备」，每次易主全服广播，装备记录历代持有者。

**洞见 E：「红名系统」给 PK 附加道德张力**
传奇的红名让杀人有代价：连续 PK 后变红名，被更多人追杀。这个机制让 PvP 不是纯粹的强者欺负弱者，而是有风险的抉择。
→ **AION应用**：引入「背叛者路径」，允许玩家选择成为流亡者，获得特权但被双方可攻击。

**洞见 F：声望驱动口碑传播**
传奇里，打怪多的人全服都知道。这种「自然知名度」系统激励玩家活跃，玩家天然愿意口耳相传。
→ **AION应用**：「纪元英雄」系统，把真实玩家的成就写入世界编年史，让名字被记住。

---

## 2.3 黑神话·悟空：BOSS 是叙事高潮

**洞见 G：每个 BOSS 都是一个完整的故事**
黑神话的每个 Boss 死前都有最后的台词，都有前因后果，玩家在打 Boss 的同时在经历一个故事高潮。「击杀」不只是「数值胜利」，是「叙事时刻」。
→ **AION应用**：世界 Boss 死亡时触发服务端叙事事件：LLM 生成首杀玩家的专属见证文字，全服广播，永久写入编年史。

**洞见 H：「变身/形态转换」创造战斗节奏感**
悟空可以变身成不同形态，每种形态有不同的战斗风格。这让战斗不是同一套连招的重复，而是有节奏变化的体验。
→ **AION应用**：在现有变身技能（FormCondition 已存在）基础上，设计「形态专属强化」：不同变身形态有专属克制加成。

---

## 2.4 艾尔登法环：隐秘发现驱动社区共鸣

**洞见 I：没有地图标记的秘密比有标记的内容更有价值**
艾尔登法环大量内容没有地图提示，玩家社区自发讨论、合力发现，产生的口碑比任何营销都强。「我发现了一个别人不知道的东西」的感觉是极强的情感奖励。
→ **AION应用**：每个纪元埋设 2-3 个「无公告的隐藏内容」，等待玩家社区自行发现。

**洞见 J：「血迹/留言」系统放大玩家存在感**
艾尔登法环玩家可以在地上留下简短信息，其他玩家能看到。这让世界充满了「其他玩家的存在痕迹」，形成异步社群感。
→ **AION应用**：「玩家地标」系统，重要任务完成处留下可交互地标，其他玩家靠近可以看到玩家名+完成日期。

---

## 2.5 暗黑破坏神4：赛季制规则微变

**洞见 K：规则的改变比内容的增加更高效**
暗黑4每个赛季引入一个新机制（吸血鬼/恶魔契约），让同样的地图因为规则不同而重新变得新鲜。内容量没变，但体验完全不同。
→ **AION应用**：「纪元法则」系统——每个纪元有一条专属规则变化，不需要新地图，只需要改变一个基础参数。

**洞见 L：「Build 多样性」让同一职业有多种玩法**
暗黑4同一职业可以走火焰流、召唤流、近战流等完全不同的路线。这极大延长了单角色的游戏寿命。
→ **AION应用**：「印记系统」——玩家在特定维度积累印记后，解锁该职业的专属变体技能（服务端计算，不改客户端）。

---

## 2.6 魔兽世界（怀旧服）：职业身份感与世界 Boss 社区效应

**洞见 M：「世界 Boss 聚集」是最强的社区事件**
怀旧服世界 Boss 出现时，全服玩家聚集。这种「大家都去同一个地方」的体验是 MMO 独有的，是任何单机游戏无法提供的。
→ **AION应用**：「纪元 Boss」系统，每个纪元有1个非固定时间刷新的世界 Boss，刷新时全服广播位置，制造聚集效应。

**洞见 N：职业有清晰的「社会角色」**
魔兽的坦克/治疗/输出三角关系让每个职业知道自己在队伍中的位置，有强烈的职业身份感。
→ **AION应用**：通过「纪元任务板」给不同职业分配不同的「特供任务」，强化职业的社会功能差异。

---

# 第三部分：限时服务器设计哲学
# Part III: Design Philosophy for Time-Limited Servers

BEY_4.8 是限时服务器，这是最重要的设计约束，也是最独特的设计机会。

## 3.1 限时服的玩家心理

| 心理状态 | 在永久服中 | 在限时服中 |
|---------|----------|----------|
| 时间投入感 | "以后有空再玩" | "现在必须把握" |
| 成就意义 | 可以被超越 | 纪元内永恒，写入历史 |
| 失去感知 | 不太在意 | "传世装备"如果丢了是真的失去 |
| 社群凝聚 | 时间稀释 | 共同经历的密度更高 |

## 3.2 限时服的专属设计原则

**原则一：「终点感」是特权，不是缺陷**
每个纪元的结束是真实的结束。这让玩家知道：「这段历史封存之后，永远不会改变。」这是永久服无法提供的「历史性」。

**原则二：「传承」让结束不是死亡**
纪元结束时，玩家的核心成就以某种形式传入下一纪元。不是重置，是「历史的积累」。每个纪元在上一个纪元的遗迹上开始。

**原则三：「慢节奏逃逸口」防止焦虑**
限时服天然产生紧迫感，过度的紧迫感会变成压力。需要设计「慢节奏内容」（住宅/社交/探索），给玩家喘气空间。

**原则四：「结局分支」让每个纪元不可复制**
每个纪元根据玩家集体行为走向不同的结局（天族胜/魔族胜/混沌/龙族入侵），这个结局影响下一纪元的初始状态。世界史是真实的，不是剧本。

---

# 第四部分：八大机制改造方案
# Part IV: Eight Core Mechanism Overhauls

---

## 改造一：阵营系统「三极博弈」
### Faction System: Triangular Power Dynamics

**设计目标**：打破天魔二元零和，引入动态平衡，让劣势方也有参与感。

### 1.1 龙族阴影势力（第三极）

利用 SiegeService 已有的 Balaur 重置逻辑，将其升格为「有叙事背景的第三势力」：

```
「龙族阴影」(Balaur Shadow) 触发逻辑：
  条件：某个要塞无人守卫超过 24 小时 (配置参数)
  触发：龙族攻占该要塞（现有 Balaur 逻辑）
  新增：
    1. 全服公告 "龙族阴影入侵了[要塞名]，两族需暂时休战！"
    2. 该要塞周边 500m 范围内，天族魔族 PvP 暂时关闭（24小时）
    3. 合力击退龙族守将：双方都能参与，胜方得要塞，但双方都得奖励
    4. 若 48 小时内无人夺回：触发纪元「龙族威胁」事件，全服怪物强化 10%
```

**技术实现**：
- 扩展 `SiegeService.startSiege()` 增加 BalaurAssault 检测
- 新增 `BalaurThreatService.java` 管理龙族威胁等级
- `TribeRelationService` 增加 `isTemporaryAlly()` 方法（龙族威胁期间）

### 1.2 流亡者路径（「背叛者」机制）

Race.java 已有 NEUT 种族，TribeRelationService 有完整敌我逻辑，可以在此基础上实现：

```
「流亡者」(Exile) 状态：
  进入条件：
    - 深渊点达到 500,000（说明是活跃玩家，不是新手误触）
    - 玩家主动使用 .exile 命令，需要确认
  状态效果：
    - 玩家头顶标志变为灰色（两族均可见）
    - 双方阵营玩家均可攻击该玩家
    - 解锁「中立区域」入口（中间地带 NPC 村庄）
    - 中立区 NPC 提供稀缺材料，但每次购买需消耗「流亡声望」
    - 可以正常完成该中立 NPC 的任务（走遍两族土地）
  退出条件：
    - 消耗大量 Kinah + 深渊点「赎罪」
    - 退出后有 24 小时「观察期」，被原阵营 NPC 态度冷淡
  风险设计：
    - 流亡期间死亡惩罚加重（装备耐久损失×2）
    - 强化了死亡的代价感，类似传奇的红名张力
```

### 1.3 阵营动态平衡奖励

```
在线人数差异 → 自动平衡系数（每5分钟更新，来自 WorldStateService）：
  差距 < 20%：无加成
  差距 20-40%：劣势阵营经验/掉落 +10%，全服不可见
  差距 40-60%：劣势阵营经验/掉落 +20%，全服可见（系统提示）
  差距 > 60%：劣势阵营经验/掉落 +30% + 招募奖励包

实现：扩展 EventBuffHandler，基于 WorldStateService 的在线统计
```

---

## 改造二：战斗「克制与共鸣」系统
### Combat: Weakness Matrix & Skill Resonance

**设计目标**：让战斗不只是数值比拼，引入「正确决策 = 更好结果」的机制反馈。

### 2.1 Boss 弱点矩阵

不修改客户端，纯服务端逻辑，通过现有 Effect 系统实现：

```
怪物/Boss XML 模板新增字段：
  <weakness_type>FIRE</weakness_type>        <!-- 弱点类型 -->
  <weakness_amplify>1.35</weakness_amplify>  <!-- 伤害放大系数 -->
  <break_threshold>5000</break_threshold>    <!-- 积累N点弱点伤害触发破防 -->

破防（Break）状态效果：
  - 触发 AttackStatus.WEAKNESS_BREAK
  - Boss 进入 3 秒「虚弱」状态
  - 虚弱期间所有伤害 +25%，且不会使用技能
  - 视觉反馈：通过现有 SM_CASTSPELL 包发送特殊技能动画（ID 复用）
  - 触发全队 chat 频道 "[玩家名] 击破了 [Boss名] 的防御！"

弱点类型 vs 职业（示例）：
  火弱点：魔法师/灵魂术师克制
  冰弱点：游侠/圣职者克制
  光弱点：黑暗职业（魔导师/暗邪）克制
  黑暗弱点：光明职业（圣战士/圣职者）克制
  物理弱点：战士/狂战士克制
```

**技术实现**：
- NPC XML 模板增加 `weakness_type` 属性（DataLoader 读取）
- `AttackUtil.java` 增加弱点检测逻辑（10行代码）
- 新增 `WeaknessBreakEffect.java`（复用现有 Effect 框架）

### 2.2 技能共鸣系统（Combat Tempo）

基于现有 LuckSystem 的 momentum 模式，扩展为战斗内的节奏追踪：

```
战斗节奏追踪（服务端，每个玩家独立）：
  连续 3 次使用「同系技能」（魔法/物理/神圣）→ 触发「共鸣」
  共鸣效果：下一个技能效果提升 15%（通过动态 Buff 实现）
  共鸣层数上限：3 层
  共鸣失去：使用不同系技能 / 被打断 / 3 秒未使用技能

  连续击杀（PvP）：
  1 kill → 「锋芒」buff：速度+5%
  3 kills → 「战意」buff：全属性+8%
  5 kills → 「无双」buff：全属性+15%，所有人可见头顶光效
  死亡：全部失去，这是张力来源
```

**技术实现**：
- 在 `Creature.java` 增加 `combatTempoCounter` 和 `comboKillCount` 字段
- `AttackUtil.java` 的伤害计算后钩子增加共鸣检测
- 共鸣 Buff 通过现有 `EffectController.addEffect()` 实现（无需改客户端）

---

## 改造三：飞行「制空博弈」
### Flight System: Aerial Strategic Dimension

**设计目标**：将飞行从移动手段升级为战略资源，开发 Z 轴这个完全未利用的空间。

### 3.1 高度分层逻辑

FlyController 完全没有高度检测，可以从零实现：

```
Z轴分层（世界坐标系）：
  地面层（Z < 50）：正常行走区，无特殊规则
  飞行层（50 ≤ Z < 200）：标准飞行，正常 FP（或无消耗）
  高空层（Z ≥ 200）：
    - 需要持有「风晶石」（消耗品，怪物掉落）
    - 进入时检测背包，扣除 1 枚/5分钟
    - 高空视野增大（服务端：增大获取附近对象的扫描半径）
    - 高空区域可发现「空中浮岛」（地图已有高空地形，只需启用）
    - 高空滞留超过 5 分钟且未离开 → 触发「高空警报」，该位置对所有人广播
```

### 3.2 空中前哨（全天候争夺点）

不需要修改客户端，利用现有 SpawnService + 状态追踪实现：

```
空中浮岛（选取 5 个现有地图高空位置）：
  - 浮岛上放置「占领石柱」NPC（纯交互用，已有类似对象）
  - 靠近石柱且停留 5 分钟（无敌方干扰）→ 阵营占领
  - 占领效果：
    · 该浮岛每小时刷新 1-3 件稀缺材料（全服唯一时间窗口）
    · 控制军团在编年史中记录为「制空者」
    · 阵营控制浮岛数量 ≥ 3 → 阵营全体获得「空中祝福」buff (+5%全属性)
  - 全天候开放（不像围城战固定时间），随时可以争夺

占领检测实现：
  新建 AerialOutpostService.java
  CronService 每 30 秒检测玩家在浮岛范围内的驻留时间
  利用 World.getInstance().getPlayersInRange() 实现范围检测
```

### 3.3 纪元专属飞行规则

每个纪元的世界法则之一作用于飞行系统（EpochCycle 管理）：

```
纪元飞行变体示例（每纪元选择1条）：
  「狂风纪元」：飞行移动速度 +30%，FP 消耗 +50%（若开启消耗的话）
  「雷鸣纪元」：空中发动攻击技能 +20%，但地面攻击 -5%
  「迷雾纪元」：高度 > 100 时进入「迷雾」，小地图失效，但可发现隐藏浮岛
  「寂静纪元」：飞行静音（脚步声）→ 空中 PvP 极难被发现（偷袭加成）
```

---

## 改造四：任务「蝴蝶效应」
### Quests: Butterfly Effect & World Impact

**设计目标**：让玩家行为可见地影响世界，从旁观者变为参与者。

### 4.1 全服任务公共进度

利用现有 AnnouncementService + 新建进度表实现：

```
全服公共任务框架：
  DB 表：world_quest_progress
    id, quest_id, required_count, current_count, deadline, reward_type,
    trigger_event_id, fail_event_id, epoch_id

  示例任务：
  「封印古神」：全服需要 5000 人次完成「古神封印」任务
    · 进度可通过 .worldnews 命令查看
    · AnnouncementService 在 1000/2500/4000/5000 节点广播进度
    · 完成：触发「古神封印」世界事件（特定区域关闭 3 天，解锁新奖励区域）
    · 失败（截止日期前未完成）：触发「古神苏醒」事件（区域 Boss 强化，特殊 Boss 出现）
    · 双向结果让任务有真实风险

实现：
  QuestEngine.onQuestComplete() 钩子 → WorldQuestService.incrementProgress()
  CronService 每小时检查截止日期
```

### 4.2 玩家地标系统

```
「见证之石」机制（借鉴艾尔登法环血迹）：
  触发条件：完成特定「历史性任务」（首杀某 Boss、完成某史诗任务链）
  效果：在完成地点生成一个持久化的「地标 NPC」（复用现有 NPC spawn）
    外观：一块发光的小石头（用现有可交互对象）
    文字：「在此，[玩家名] 于[日期]完成了[任务名]」（LLM 生成更文学化的表述）
    交互：其他玩家靠近 → 弹出文字 + 给予靠近玩家一个小 buff（「先行者的感召」）

DB 表：player_landmarks
  id, player_name, legion_name, task_id, world_id, x, y, z,
  narrative_text, created_at, epoch_id

实现：
  AbstractQuestHandler.onQuestComplete() 增加 LandmarkService.tryCreateLandmark()
  SpawnService 加载 player_landmarks 表
```

---

## 改造五：NPC「生命日历」
### NPCs: Living Schedule & Memory

**设计目标**：让玩家感知到「这些 NPC 是活着的」，通过时段行为和轻量级记忆实现。

### 5.1 NPC 时段日程

利用游戏内时间（GameTimeService）实现：

```
游戏内时间周期：现实时间 1 小时 = 游戏内 24 分钟（4:1 比例，实际值待确认）
实现方案：SpawnTemplate 新增时段字段

时段定义：DAWN/DAY/DUSK/NIGHT (GameTimeService.isDaytime() 已存在扩展点)

示例 NPC 日程：
  「消息贩子 Kael」：
    DAY: 出现在圣火城广场（坐标A）
    NIGHT: 出现在城市边缘酒馆（坐标B）
    说的台词也不同（白天：战报；夜晚：秘密情报）

  「神秘行商」（夜间专属 NPC）：
    DAWN~DAY: 不出现（unspawned）
    NIGHT: 出现在主城偏僻角落
    出售：高价稀缺材料（白天在拍卖行买不到）
    
实现：
  NpcScheduleService.java（新建）
  CronService 4次/游戏日检测时段变化
  SpawnService.despawn() / respawn() 切换
```

### 5.2 NPC 轻量级记忆

```
「熟识系统」：
  DB 表：player_npc_relations
    player_id, npc_id, interaction_count, first_met, last_met, affinity_level
  
  亲密度等级：
    0-9次：陌生人（默认对话）
    10-29次：熟人（多1个问候选项，偶尔给小道具）
    30+次：老朋友（特殊任务解锁，偶发特殊台词 LLM 生成）

  玩家视角感知：
    第一次交互：正常
    第10次：NPC 第一次叫出玩家名字
    第30次：NPC 说「你总算来了，我有件事只有你知道」→ 触发隐藏任务

  实现：
    DialogService.onNpcTalk() 增加 NpcRelationService.recordInteraction()
    关系查询在 PlayerInfoDAO 级别实现，轻量级
```

---

## 改造六：「传世之物」稀缺经济
### Heirloom Economy: Scarcity & Social Significance

**设计目标**：创造真实的稀缺性，让装备有社会意义而不只有数值意义。

### 6.1 传世装备系统

```
每个纪元：全服只会掉落 3 件「传世装备」（Epoch Heirlooms）

掉落机制：
  - 纪元开始时，随机选定 3 件装备（模板固定，但掉落来源随机在高难Boss中）
  - DropService 在 Boss 掉落计算时，额外检测「传世掉落配额」
  - 一旦配额耗尽，该纪元不再掉落同类型传世装备

传世装备特性：
  - 金色命名：「[第N纪元] 血焰之剑」
  - 属性：不高于当纪元最强普通装备（不是数值极品，是稀缺极品）
  - 特效：独特视觉粒子（复用现有客户端特效ID，选颜色特殊的）
  - 历史：持有者历史记录，可通过交互查看「持有者年鉴」
  - 转移机制：可以交易（FREE_TRADE已启用），每次易主全服广播

DB 表：epoch_heirloom
  item_obj_id, epoch_id, item_template_id, dropped_by_npc, dropped_at,
  current_owner, history_json (持有者链条)

全服广播触发点：
  ItemMoveService.moveItem() 增加 HeirloomService.checkTransfer() 钩子
  AnnouncementService.broadcastToAll("「[纪元] XX之剑」从 [A] 转到了 [B] 手中！")
```

### 6.2 掉落光柱系统

```
稀有物品掉落时，地图上出现可见标记：
  触发条件：掉落品质 >= 传奇（LEGENDARY）
  效果：
    1. 该位置发送特殊粒子包（复用现有技能特效）
    2. AnnouncementService 广播：「[区域名]某处，有异光升起——」（故意不精确位置）
    3. 周边 300m 玩家收到更精确的提示
  
  设计意图：
    - 不精确的位置描述 → 制造竞争和探索欲（传奇「屠龙刀」光效）
    - 玩家会涌向该区域 → 自然产生 PvP 竞争
    - 每次发生都是「全服事件」
```

---

## 改造七：军团「五维外交」
### Legion System: Five Dimensions & Diplomacy

**设计目标**：让军团成为策略单元，不只是社交标签。

### 7.1 军团五维声望

在现有 LegionService（8 级体系）基础上扩展：

```
五个声望维度（军团独立记录）：
  ⚔ 军事声望：PvP 击杀 + 围城战贡献积分
  💰 经济声望：成员物品捐赠 + 军团仓库价值
  🗺 探索声望：成员任务完成数 + 地图探索度
  🤝 外交声望：与其他军团的盟约维护（见7.2）
  📜 传承声誉：历代纪元的积累（跨纪元保留，不重置）

声望的实际影响（玩家可感知）：
  军事声望高 → 该军团成员进入围城战时，守卫 NPC 会喊军团名
  经济声望高 → 主城部分 NPC 给军团成员 5% 折扣
  探索声望高 → 军团成员副本进入的等待时间缩短
  传承声誉高 → NPC 对话中提及军团历史（LLM 生成军团专属台词）

DB 表：legion_reputation（在现有 legion 表基础上增加5个列）
```

### 7.2 军团外交系统

```
军团长可以向其他军团发起外交协议：

协议类型：
  同盟（Alliance）：
    - 副本中共享 +5% 经验加成
    - 战场上友方标识更清晰（服务端标记）
    - 背叛同盟（宣战）→ 军团声望-200
  
  竞争协议（Rivalry）：
    - 双方在特定「竞争区域」可以互相攻击（即使同阵营！）
    - 双方 PvP 击杀对方积分×2（有意义的内部竞争）
    - 这解决了「同族内部无张力」的问题！
  
  宣战（War）：
    - 双方全图可互相攻击
    - 宣战需要消耗大量 Kinah（阻止随意触发）
    - 战争结束条件：一方投降 or 持续 72 小时自动结束
    - 战争结果计入编年史

实现：
  LegionDiplomacyService.java（新建）
  扩展 TribeRelationService.isEnemy() 增加军团外交覆盖层
  PlayerPvpCondition 增加军团竞争/宣战状态检查
```

### 7.3 军团野战要道

```
引入 10 个「野战据点」（Wild Outposts）：
  位置：各主要地图边缘（远离主城，无人区）
  控制规则：
    - 与空中前哨类似，停留 5 分钟无干扰即占领
    - 军团控制后每小时产出一定量的「军团资源」
    - 军团资源用于升级军团建筑/购买军团专属物品
  与围城战区别：
    - 不需要等待固定时间，全天候争夺
    - 规模小（5人组就可以争夺）
    - 目的是给非顶级军团也有参与感
```

---

## 改造八：「纪元法则」自动迭代
### Epoch Law: Automatic Rule Mutation

**设计目标**：每个纪元有一条独特的世界规则变化，让同样的地图因为规则不同而产生全新体验。

### 8.1 纪元法则系统

```
纪元法则由两部分组成：
  1. 「世界修正器」(World Modifier)：影响全服所有玩家的基础规则
  2. 「纪元专属任务」(Epoch Quest)：本纪元独有的任务链

世界修正器示例库（供 LLM / GM 选择）：
  「暗影纪元」：夜间（游戏内时间）魔族 +15% 属性，白天天族 +15% 属性
  「血月纪元」：PvP 击杀掉落对方 1 件装备（随机，传奇以下）
  「觉醒纪元」：每位玩家随机解锁 1 个职业外技能（随机抽取，有好有坏）
  「迷雾纪元」：全服小地图失效，探索成为核心，隐藏内容增加
  「龙裔纪元」：龙族 NPC 偶尔跟随玩家（随机，好坏不定）
  「虚空纪元」：副本 CD 时间减半，但掉落率也减半

实现：
  DB 表：epoch_law
    epoch_id, law_type, law_params_json, narrative_text

  EpochLawService.java（新建）：
    onEpochStart() → 读取法则配置，应用到 StatFunctions / DropService / FlyController
    各服务通过接口查询当前纪元法则（单例缓存）
```

### 8.2 纪元结局分支

```
每个纪元有 4 种可能结局（由玩家集体行为决定）：

  天族胜利（条件：天族围城战胜率 > 60% + PvP 击杀率 > 55%）：
    下纪元：天族领土扩张（特定地图 GUARD 变为天族守卫）
    叙事：「天族收复了失地，魔族退守深渊边缘……」

  魔族胜利（对称条件）：
    下纪元：魔族据点扩张
    叙事：「黑暗吞噬了最后一片圣光……」

  龙族入侵（条件：龙族在本纪元占领 ≥ 3 个要塞未被夺回）：
    下纪元：起始状态被龙族包围，两族起点不利
    叙事：「没有人注意到，当两族征战时，龙族已悄然占领了一切……」
    这是一个「共同失败」结局，迫使下纪元合作

  混沌乱局（条件：双方均衡，无明显优势方）：
    下纪元：全服出现随机事件概率+50%，「传世装备」数量+1
    叙事：「没有胜者，也没有败者，世界陷入了奇异的平静……」

实现：
  EpochManager.java 在纪元结束时（每周日 23:00）计算结局
  结局影响：通过 SpawnTemplate 热更新（不重启服务器）切换 NPC/守卫状态
```

---

# 第五部分：整体技术实现路线
# Part V: Technical Implementation Roadmap

## 5.1 新增 Java 服务列表（总览）

| 服务名 | 依赖的现有服务 | 工作量 |
|--------|-------------|--------|
| WorldStateService | CronService, SiegeService, PvpService | M |
| NarrativeOrchestratorService | AIAgentService, AnnouncementService | L |
| ChronicleService | WorldStateService | M |
| EpochManager | ChronicleService, NarrativeOrchestratorService | L |
| EpochLawService | StatFunctions, DropService, FlyController | M |
| BalaurThreatService | SiegeService, TribeRelationService | M |
| ExilePlayerService | Race, TribeRelationService, PlayerPvpCondition | M |
| AerialOutpostService | World, CronService, SpawnService | M |
| WeaknessMatrixService | AttackUtil, NPC XML 模板 | S |
| CombatTempoService | Creature, AttackUtil | S |
| HeirloomService | DropService, ItemMoveService, AnnouncementService | M |
| LandmarkService | QuestEngine, SpawnService | S |
| NpcScheduleService | SpawnService, GameTimeService | S |
| NpcRelationService | DialogService, PlayerInfoDAO | S |
| LegionDiplomacyService | LegionService, TribeRelationService | M |
| WorldQuestService | QuestEngine, AnnouncementService | M |

## 5.2 新增 DB 表列表

```sql
-- Phase 1: 世界脉搏与历史
world_state_snapshot    -- 5分钟快照
world_chronicle         -- 历史事件永久记录
narrative_queue         -- AI叙事内容队列
epoch                   -- 纪元元数据
epoch_law               -- 纪元法则配置

-- Phase 2: 玩家感知系统
player_landmarks        -- 玩家地标（见证之石）
player_npc_relations    -- 玩家-NPC 亲密度
world_quest_progress    -- 全服任务公共进度

-- Phase 3: 经济与稀缺
epoch_heirloom          -- 传世装备历史
heirloom_history        -- 持有者链条

-- Phase 4: 军团外交与据点
legion_reputation       -- 军团五维声望
legion_diplomacy        -- 军团外交协议
aerial_outpost_status   -- 空中前哨控制状态
wild_outpost_status     -- 野战据点控制状态
```

## 5.3 分阶段实施计划

### Phase 1：基础感知层（第 1-3 周）
**目标：玩家能「感觉到世界在动」**
- [ ] WorldStateService + world_state_snapshot 表
- [ ] ChronicleService + world_chronicle 表
- [ ] 围城战结束时触发 ChronicleService.record()
- [ ] NarrativeOrchestratorService 基础版（围城战后生成叙事公告）
- [ ] .chronicle / .epoch 命令
- [ ] NpcScheduleService 基础版（3个先知NPC有时段日程）

**验收**：围城战结束时，服务器自动广播一条叙事性全服公告；玩家可通过命令查询世界历史。

### Phase 2：战斗机制深化（第 3-5 周）
**目标：战斗有「正确决策」的感知反馈**
- [ ] WeaknessMatrixService + Boss XML 弱点配置（先配 5 个世界 Boss）
- [ ] CombatTempoService（技能共鸣 + 连杀动能）
- [ ] 阵营平衡奖励（ExilePlayerService 基础版）
- [ ] BalaurThreatService（龙族入侵逻辑）

**验收**：打配置了弱点的 Boss 时，克制职业能触发破防，队伍聊天频道有广播。

### Phase 3：稀缺性与社会叙事（第 5-7 周）
**目标：创造全服「历史性时刻」**
- [ ] HeirloomService + 传世装备配置（3件）
- [ ] 掉落光柱系统
- [ ] LandmarkService（见证之石）
- [ ] 纪元英雄记录（纪元结束时统计Top贡献）

**验收**：第一件传世装备掉落时，全服都知道，整个服务器热度上来。

### Phase 4：军团策略与外交（第 7-10 周）
**目标：军团成为有身份感的策略单元**
- [ ] LegionDiplomacyService（竞争协议 + 同盟）
- [ ] 军团五维声望（legion_reputation）
- [ ] 野战据点系统（10个据点）
- [ ] 军团NPC折扣（声望影响现实）

**验收**：出现第一场军团宣战事件，两个同阵营军团在地图上真实交战。

### Phase 5：纪元自动迭代（第 10-14 周）
**目标：无需人工干预，世界自动演化**
- [ ] EpochManager（纪元生命周期管理）
- [ ] EpochLawService（纪元法则系统）
- [ ] 结局分支（4种结局，影响下纪元初始状态）
- [ ] AerialOutpostService（空中前哨）
- [ ] 全系统集成测试

**验收**：第二纪元在第一纪元结局的基础上自动开始，叙事有历史连续性。

---

## 5.4 风险与对策

| 风险 | 可能性 | 影响 | 对策 |
|------|--------|------|------|
| LLM API 不稳定 | 高 | 叙事功能中断 | 50+备用文本库，LLM 失败自动切换 |
| 传世装备系统破坏游戏经济 | 中 | 玩家不满 | 传世装备属性设计为「稀缺但非最强」 |
| 军团宣战机制被滥用 | 中 | 玩家体验负面 | 宣战消耗设置高门槛 + 72小时自动结束 |
| 阵营平衡机制太透明 | 低 | 劣势方觉得被施舍 | 平衡加成「全服不可见」（仅 GM 可查） |
| Z轴高度检测影响现有飞行逻辑 | 中 | 飞行BUG | 高度分层为纯加法逻辑，不修改现有 FlyController 主逻辑 |
| NPC日程导致玩家找不到NPC | 中 | 用户体验下降 | 日程 NPC 均为新增，不移动原有功能 NPC |

---

# 第六部分：SEEE 与本方案的整合
# Part VI: Integration with SEEE

本方案（八大机制改造）与之前规划的「拾光·纪元演化引擎」（SEEE）是同一系统的两个维度：

- **SEEE** = 世界叙事层（AI驱动的故事、NPC台词、公告、编年史）
- **机制改造** = 世界规则层（战斗、阵营、飞行、经济的底层逻辑）

两者共享：
- WorldStateService（状态感知）
- ChronicleService（历史记录）
- EpochManager（纪元管理）
- narrative_queue（叙事内容）

**两者缺一不可**：
> 只有机制改造而无叙事层 → 玩家感知不到「这是一个故事」
> 只有叙事层而无机制改造 → 故事和玩法割裂，玩家不相信叙事

完整的「拾光纪元」体验 = 每一个规则变化都有叙事解释，每一个叙事时刻都有规则层的实体支撑。

---

*最后更新 / Last Updated: 2026-04-13*
*阶段 / Phase: 战略规划完成（机制诊断 + 设计洞见 + 八大改造方案），待 Phase 1 启动*
