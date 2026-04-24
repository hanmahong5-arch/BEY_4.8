# BEY_4.8 自评估 / Agent Health Loop

目录下工具让 Agent（或 cron / `loop` skill）可反复评估服务状态，无需人盯。

## 组件

| 脚本 | 作用 | 出参 |
|------|------|------|
| `self-eval.sh [prod\|dev]` | 单次快照评估（端口/HTTP/特性 marker/错误/指标/DB） | stdout 清单；最后行 `VERDICT=PASS\|DEGRADED\|FAIL`；exit 0/2/1 |
| `watch-loop.sh [prod\|dev] [interval_sec] [fail_threshold]` | 循环跑 self-eval，JSONL 追加，连续 FAIL 自愈重启 | `<env>/game-server/log/self-eval-history.jsonl` |

## 典型用法

### 手动单次
```bash
bash tools/health/self-eval.sh prod
echo $?   # 0=PASS, 2=DEGRADED, 1=FAIL
```

### Agent 循环（前台或后台 PID 挂着）
```bash
bash tools/health/watch-loop.sh prod 120 3
# prod, 每 120s 一次, 连续 3 次 FAIL 触发自动重启
```

### Agent 消费历史
```bash
# 最近 10 次结果
tail -10 prod/game-server/log/self-eval-history.jsonl
```

### 配合 Claude `loop` skill
```
/loop 5m bash tools/health/self-eval.sh prod
```

## 检查项（`self-eval.sh`）

1. **Ports** — 内部端口 9021/12107/17778/9100 (prod) 监听
2. **HTTP** — `/api/{health,status,metrics,fortress/leaderboard,season/leaderboard}` → 200
3. **Feature init** — SoloFortress / PvpSeason / Achievement / ManagementServer 启动标记 + 4 个 admin 命令注册
4. **Error scan** — `server_errors.log` 最近 500 行 ERROR/Exception/FATAL 计数
5. **Metrics sanity** — `/api/metrics` 含 `counters` + `timings`
6. **DB connectivity** — aion_ls / aion_gs / aion_cs 各执行 `SELECT 1`

## 扩展要点

新增特性时往 `self-eval.sh` 的 markers / endpoints / commands 列表加一条即可。保持脚本纯 bash + curl + psql 依赖，无第三方包，便于 CI / cron / agent 反复调用。
