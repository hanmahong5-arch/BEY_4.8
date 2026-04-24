#!/bin/bash
# BEY_4.8 自评估脚本 — 可放入 Agent 循环反复跑
# 用法: bash tools/health/self-eval.sh [prod|dev]
# 出参: 最后一行 VERDICT=PASS|FAIL|DEGRADED；exit 0=pass, 1=fail, 2=degraded
set -u
ENV="${1:-prod}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOG_DIR="$ROOT/$ENV/game-server/log"
MGMT="http://127.0.0.1:9100"
if [ "$ENV" = "dev" ]; then MGMT="http://127.0.0.1:9101"; fi

PASS=0 FAIL=0 WARN=0
OK()   { echo "  [OK]   $*"; PASS=$((PASS+1)); }
BAD()  { echo "  [FAIL] $*"; FAIL=$((FAIL+1)); }
WARN() { echo "  [WARN] $*"; WARN=$((WARN+1)); }

echo "=== BEY_4.8 Self-Eval ($ENV) @ $(date -Iseconds) ==="

# 1. 端口（内部监听端口；公开 2107/7778 由 shiguang-gate 前端转发，不在此检查）
echo "[1] Ports (internal)"
if [ "$ENV" = "prod" ]; then PORTS="9021 2107 7778 9100"; else PORTS="9121 2207 7878 9101"; fi
for p in $PORTS; do
  if netstat -ano 2>/dev/null | grep -q ":$p .*LISTENING"; then OK "port $p listening"
  else BAD "port $p NOT listening"; fi
done

# 2. 健康端点
echo "[2] HTTP endpoints"
for path in /api/health /api/status /api/metrics /api/fortress/leaderboard /api/season/leaderboard; do
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$MGMT$path" 2>/dev/null || echo 000)
  if [ "$code" = "200" ]; then OK "$path → 200"
  else BAD "$path → $code"; fi
done

# 3. 服务初始化标记
echo "[3] Feature init markers"
CONSOLE="$LOG_DIR/server_console.log"
if [ ! -f "$CONSOLE" ]; then BAD "no console log at $CONSOLE"
else
  for marker in "SoloFortress" "PvpSeason" "Achievement" "ManagementServer"; do
    if grep -q "$marker" "$CONSOLE" 2>/dev/null; then OK "$marker booted"
    else WARN "$marker marker not found in console"; fi
  done
  # FFA / Admin commands are lazy — verify registration in commands.properties
  CMD_CFG="$ROOT/$ENV/game-server/config/administration/commands.properties"
  if [ -f "$CMD_CFG" ]; then
    for cmd in fortress ffa season ach; do
      if grep -qE "^$cmd *=" "$CMD_CFG"; then OK "admin cmd //$cmd registered"
      else BAD "admin cmd //$cmd missing from commands.properties"; fi
    done
  fi
fi

# 4. 错误扫描（最近 500 行）
echo "[4] Error scan (last 500 lines)"
ERR="$LOG_DIR/server_errors.log"
if [ -f "$ERR" ]; then
  recent=$(tail -500 "$ERR" 2>/dev/null | grep -cE "ERROR|Exception|FATAL")
  recent="${recent:-0}"
  if [ "$recent" -eq 0 ] 2>/dev/null; then OK "errors.log clean"
  else WARN "$recent error lines in last 500 of errors.log"; fi
fi

# 5. 指标 JSON 字段
echo "[5] Metrics sanity"
metrics=$(curl -s --max-time 5 "$MGMT/api/metrics" 2>/dev/null)
if [ -n "$metrics" ]; then
  for key in counters timings; do
    if echo "$metrics" | grep -q "\"$key\""; then OK "metrics.$key present"
    else BAD "metrics.$key missing"; fi
  done
else BAD "no metrics payload"; fi

# 6. DB 连通
echo "[6] DB connectivity"
for db in aion_ls aion_gs aion_cs; do
  if PGPASSWORD='Lurus@ops' psql -h 127.0.0.1 -U aion -d "$db" -tAc "SELECT 1" 2>/dev/null | grep -q 1
  then OK "$db reachable"; else BAD "$db unreachable"; fi
done

# 结果
echo "=== Result: pass=$PASS warn=$WARN fail=$FAIL ==="
if [ "$FAIL" -gt 0 ]; then echo "VERDICT=FAIL"; exit 1
elif [ "$WARN" -gt 0 ]; then echo "VERDICT=DEGRADED"; exit 2
else echo "VERDICT=PASS"; exit 0; fi
