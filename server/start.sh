#!/bin/bash
# Start Beyond Aion 4.8: Chat Server, Login Server, then Game Server.
#
# Usage:
#   ./start.sh        # defaults to prod
#   ./start.sh prod   # start production servers (ports: 9021/2107/7778)
#   ./start.sh dev    # start development servers (ports: 9121/2207/7878)

TARGET="${1:-prod}"
if [[ "$TARGET" != "prod" && "$TARGET" != "dev" ]]; then
    echo "[error] Unknown target '$TARGET'. Use: prod | dev"
    exit 1
fi

WORKSPACE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_HOME="/d/jdk-25/jdk-25.0.1"
JAVA="$JAVA_HOME/bin/java"
ENV_DIR="$WORKSPACE/../$TARGET"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

LS_DIR="$ENV_DIR/login-server"
GS_DIR="$ENV_DIR/game-server"
CS_DIR="$ENV_DIR/chat-server"

# Health-check ports = the ports JVM actually binds (matches network.properties).
# Local self-test mode: LS/GS bind public ports directly (no shiguang-gate front).
if [[ "$TARGET" == "prod" ]]; then
    CS_PORT=9021
    LS_PORT=2107
    GS_PORT=7778
else
    CS_PORT=9121
    LS_PORT=2207
    GS_PORT=7878
fi

check_port() {
    netstat -ano 2>/dev/null | grep ":$1.*LISTENING" | grep -q .
}

echo "[$TARGET] Starting Chat Server (port $CS_PORT)..."
mkdir -p "$CS_DIR/log"
cd "$CS_DIR"
"$JAVA" -Xms64m -Xmx128m -XX:+UseG1GC -XX:+UseNUMA -XX:+UseCompactObjectHeaders \
    -cp "libs/*" com.aionemu.chatserver.ChatServer \
    >> log/server_console.log 2>&1 &
CS_PID=$!

for i in $(seq 1 15); do
    check_port $CS_PORT && echo "[$TARGET] Chat Server ready (PID $CS_PID)." && break
    sleep 1
done
check_port $CS_PORT || echo "[warn] Chat Server did not start in 15 s (non-fatal)."

echo "[$TARGET] Starting Login Server (port $LS_PORT)..."
mkdir -p "$LS_DIR/log"
cd "$LS_DIR"
"$JAVA" -Xms96m -Xmx256m -XX:+UseG1GC -XX:+UseNUMA -XX:+UseCompactObjectHeaders \
    -cp "libs/*" com.aionemu.loginserver.LoginServer \
    >> log/server_console.log 2>&1 &
LS_PID=$!

for i in $(seq 1 30); do
    check_port $LS_PORT && echo "[$TARGET] Login Server ready (PID $LS_PID)." && break
    sleep 1
done
check_port $LS_PORT || { echo "[error] Login Server did not start. Check $LS_DIR/log/"; exit 1; }

echo "[$TARGET] Starting Game Server (port $GS_PORT)..."
mkdir -p "$GS_DIR/log"
cd "$GS_DIR"
"$JAVA" -Xms1536m -Xmx3072m -XX:+UseG1GC -XX:+UseNUMA -XX:+UseCompactObjectHeaders \
    -cp "libs/*" com.aionemu.gameserver.GameServer \
    >> log/server_console.log 2>&1 &
GS_PID=$!

for i in $(seq 1 120); do
    check_port $GS_PORT && echo "[$TARGET] Game Server ready (PID $GS_PID)." && break
    sleep 1
done
check_port $GS_PORT || { echo "[error] Game Server did not start. Check $GS_DIR/log/"; exit 1; }

echo "[$TARGET] All servers running."
echo "  Chat Server  : client port $((CS_PORT+10220-9021+1)), GS port $CS_PORT  PID=$CS_PID"
echo "  Login Server : client port $LS_PORT                                       PID=$LS_PID"
echo "  Game Server  : client port $GS_PORT                                       PID=$GS_PID"
