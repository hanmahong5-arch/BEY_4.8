#!/bin/bash
# 评估循环：周期性跑 self-eval.sh，结果追加 JSONL；连续 N 次 FAIL 触发一次自动重启尝试
# 用法: bash tools/health/watch-loop.sh [prod|dev] [interval_sec] [max_consec_fail]
set -u
ENV="${1:-prod}"
INTERVAL="${2:-120}"
FAIL_THRESHOLD="${3:-3}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HIST="$ROOT/$ENV/game-server/log/self-eval-history.jsonl"
SCRIPT="$ROOT/tools/health/self-eval.sh"
mkdir -p "$(dirname "$HIST")"

consec_fail=0
restarts=0
echo "[watch] env=$ENV interval=${INTERVAL}s threshold=$FAIL_THRESHOLD → $HIST"

while true; do
  ts=$(date -Iseconds)
  out=$(bash "$SCRIPT" "$ENV" 2>&1)
  code=$?
  verdict=$(echo "$out" | tail -1 | sed -E 's/.*VERDICT=//')
  summary=$(echo "$out" | grep "=== Result:" | sed -E 's/.*Result: //; s/ ===//')
  # append JSONL（手搓；summary/verdict 简单 token，不含 "）
  printf '{"ts":"%s","env":"%s","verdict":"%s","exit":%d,"summary":"%s","restarts":%d}\n' \
    "$ts" "$ENV" "$verdict" "$code" "$summary" "$restarts" >> "$HIST"

  if [ "$code" -eq 0 ]; then
    consec_fail=0
  elif [ "$code" -eq 1 ]; then
    consec_fail=$((consec_fail+1))
    echo "[watch] $ts FAIL ($consec_fail/$FAIL_THRESHOLD): $summary"
    if [ "$consec_fail" -ge "$FAIL_THRESHOLD" ]; then
      echo "[watch] $ts consecutive failures exceeded — attempting restart"
      (cd "$ROOT/server" && bash stop.sh && sleep 3 && bash start.sh "$ENV") >> "$ROOT/$ENV/game-server/log/watch-restart.log" 2>&1
      restarts=$((restarts+1))
      consec_fail=0
      sleep 30
    fi
  else
    echo "[watch] $ts DEGRADED: $summary"
  fi
  sleep "$INTERVAL"
done
