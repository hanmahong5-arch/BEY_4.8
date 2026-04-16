# BEY_4.8 Validation Procedure — Phase 1 / Team C

> **Purpose**: a step-by-step in-game test procedure that a human GM runs with
> the Phase 0 observability tools to validate Smart Cast v2 and Swarm Intelligence.
>
> **Fill in** the PASS/FAIL columns as you run each scenario. When done, save
> this document as `validation-<YYYY-MM-DD>.md` and hand back the results —
> Team D will open one issue per failing row.
>
> **Prerequisite**: You are logged in as a GM account with access level ≥ 9
> and have a test character in a low-density zone.

---

## 0. Environment Check

| # | Action | Expected | PASS/FAIL |
|---|--------|---------|:---------:|
| 0.1 | `//swarm pulse` (before enabling debug) | "debug is OFF" note + all counters zero | |
| 0.2 | `//swarm toggle` | "SWARM_DEBUG_ENABLED = true" reply | |
| 0.3 | Check `log/swarm_decisions.log` exists | file present, empty or previous rows | |
| 0.4 | `//swarm deposit 1000` then `//swarm heat` | center cell P1000 + occupied=1 | |
| 0.5 | `//swarm reset` | counters clear | |

---

## 1. Smart Cast v2 — TARGET Path

**Setup**: Pick a Gladiator / Templar (close-range class). Equip a weapon. Find 2 NPCs close to each other (~5m apart), one in front of you, one behind.

| # | Skill | Action | Expected | PASS/FAIL |
|---|-------|--------|---------|:---------:|
| 1.1 | Rupture Arrow (Ranger) or any TARGET single-skill | No target selected, face the front NPC, press skill | Skill auto-locks onto front NPC, damage applies | |
| 1.2 | Same skill | No target, face 180° away from both NPCs | Expected: 360° fallback picks nearest (front NPC) — should NOT fail with "invalid target" | |
| 1.3 | AoE skill (e.g. Wind Strike) | No target, face front NPC | Standard area resolution, should not need fallback | |
| 1.4 | Mantra / buff skill (FRIEND target) | On self with no ally selected | Should target self, no error | |
| 1.5 | Debuff skill | Target selected is a mob, press | Should debuff the target as normal | |

---

## 2. Smart Cast v2 — TARGETORME Path

**Setup**: Pick a healer / cleric class for TARGETORME skills.

| # | Skill | Action | Expected | PASS/FAIL |
|---|-------|--------|---------|:---------:|
| 2.1 | Healing skill with a friend selected | Cast while friend selected | Heals the friend (not self) | |
| 2.2 | Healing skill with no selection | Cast | Heals self (TARGETORME default) | |
| 2.3 | Enemy TARGETORME skill (rare, ~218 skills) | Enemy selected, cast | Damages enemy | |
| 2.4 | Enemy TARGETORME skill | No selection, face enemy | Auto-locks onto nearest enemy in cone (via TARGETORME smart-cast) | |

---

## 3. Swarm — Deposit & Decay

**Setup**: Stand in a quiet corner of any map. `//swarm toggle` (enable). `//swarm reset`.

| # | Action | Expected | PASS/FAIL |
|---|--------|---------|:---------:|
| 3.1 | `//swarm heat` (before any combat) | All cells `.` | |
| 3.2 | Aggro and kill one mob ~5m in front | Deposit on first hit, `//swarm heat` shows `+` or `*` in that cell | |
| 3.3 | Wait 60s, `//swarm heat` again | Previous cell now lower intensity (~half) | |
| 3.4 | Wait 3 minutes, `//swarm heat` | Cell back to `.` (empty) | |

---

## 4. Swarm — Hate Broadcast on Death

**Setup**: Find a tight cluster of 3-5 same-tribe NPCs. `//swarm reset`.

| # | Action | Expected | PASS/FAIL |
|---|--------|---------|:---------:|
| 4.1 | Kill ONE NPC in the cluster | All nearby same-tribe NPCs immediately turn hostile and pursue | |
| 4.2 | After 4.1, `//swarm pulse` | `distressBroadcasts = 0`, `pheromoneDeposits ≥ 1`, and broadcast radius visible | |
| 4.3 | After 4.1, `//swarm decisions` | Possibly empty (death broadcast triggers AIEventType.ATTACK, not TARGET_CHANGED) | |

---

## 5. Swarm — Distress Call (HP < 50%)

**Setup**: Find a single mob with at least 2 same-tribe neighbors nearby.

| # | Action | Expected | PASS/FAIL |
|---|--------|---------|:---------:|
| 5.1 | Attack the single mob until its HP < 50% | At the moment HP drops below 50%, nearby same-tribe NPCs join the fight | |
| 5.2 | `//swarm pulse` | `distressBroadcasts ≥ 1` | |
| 5.3 | Keep attacking | Distress broadcast fires ONCE only (self-limiting via pheromone saturation) | |

---

## 6. Swarm — Disengage Suppression

**Setup**: In an area where a fight has already happened (use scenario 3 or 4 first to leave pheromone).

| # | Action | Expected | PASS/FAIL |
|---|--------|---------|:---------:|
| 6.1 | Aggro a mob in the hot zone, run until target-too-far | NPC does NOT disengage — stays aggressive, continues chase | |
| 6.2 | `//swarm pulse` | `disengageSuppressed ≥ 1` | |
| 6.3 | Repeat 6.1 in a cold zone (no prior combat) | NPC disengages normally at chaseTarget distance | |

---

## 7. Swarm — Return Suppression

**Setup**: Aggro a mob far from its spawn, kill it via friend. Fresh target for the mob's allies.

| # | Action | Expected | PASS/FAIL |
|---|--------|---------|:---------:|
| 7.1 | Wipe all aggro off a nearby NPC in a hot zone | NPC does NOT walk home, stays at current position in IDLE state | |
| 7.2 | `//swarm pulse` | `returnSuppressed ≥ 1` | |

---

## 8. Swarm — Idle Investigation (pheromone gradient)

**Setup**: An idle NPC ~20m from where combat recently happened.

| # | Action | Expected | PASS/FAIL |
|---|--------|---------|:---------:|
| 8.1 | `//swarm deposit 1500` then move ~25m away | The idle NPC begins walking toward your original position | |
| 8.2 | `//swarm pulse` | `investigateCalls ≥ 1`, `investigateHits ≥ 1` | |

---

## 9. Swarm — Multi-Target Attention Focus Fire

**Setup**: Two PCs close together, one visibly low HP. A single NPC must be able to hit both.

| # | Action | Expected | PASS/FAIL |
|---|--------|---------|:---------:|
| 9.1 | Drop PC-A HP to 25%, PC-B at 80%. Aggro NPC on PC-B first | Within 1-2 attack cycles, NPC switches to PC-A (HP pressure head dominates) | |
| 9.2 | `//swarm target` (select the NPC) | PC-A score > PC-B score | |
| 9.3 | `//swarm decisions` | Should contain the PC-B → PC-A switch with Δ > 0.15 | |

---

## 10. Performance Baseline

**Setup**: `//swarm reset`, debug ON. Normal combat for 5 minutes.

| # | Action | Expected | PASS/FAIL |
|---|--------|---------|:---------:|
| 10.1 | After 5 min of play, `//swarm pulse` | `reevaluate p50 < 100 μs`, `p99 < 1 ms` | |
| 10.2 | `thinkAttackCalls / 300` (calls/sec) | Record the number for baseline | |

Record the numbers here:
```
reevaluateCalls  :
targetSwitches   :
switch rate %    :
investigateHits  :
pheromone deposits:
p50 thinkAttack  :
p99 thinkAttack  :
```

---

## 11. Aggregate Bug Candidate List

For every FAIL row above, open a new row here with:

| # | Scenario | Symptom | Suspected root cause | File:line | Severity |
|---|---------|---------|---------------------|-----------|:--------:|
|   |          |          |                      |           |          |

Severity scale:
- **high** — feature entirely broken, blocks further validation
- **med** — feature partially working, impacts gameplay
- **low** — cosmetic, edge case, doesn't block players

---

## 12. Sign-off

- [ ] All scenarios PASS → Gate 1 approval: proceed to Phase 2
- [ ] Some FAIL → Team D fixes required before Gate 1 approval
- [ ] Blocking bug in Smart Cast or Swarm → rollback the relevant feature, then Team D

**Tester**: ___________  **Date**: ___________  **Commit SHA**: ___________
