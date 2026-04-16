#!/bin/bash
# Stop Beyond Aion 4.8 Java server processes.
# Stops all instances regardless of prod/dev (GameServer/LoginServer/ChatServer share class names).
#
# Usage:
#   ./stop.sh        # stop all (prod + dev)
#   ./stop.sh prod   # stop prod only (uses port check)
#   ./stop.sh dev    # stop dev only (uses port check)

JAVA_HOME="/d/jdk-25/jdk-25.0.1"
export PATH="$JAVA_HOME/bin:$PATH"

kill_by_class() {
    local class="$1"
    local pids
    pids=$(jps -l 2>/dev/null | grep "$class" | awk '{print $1}')
    if [ -z "$pids" ]; then
        echo "[stop] No running $class processes."
        return
    fi
    for pid in $pids; do
        echo "[stop] Killing $class (PID $pid)..."
        /c/Windows/System32/taskkill.exe //PID "$pid" //F 2>/dev/null && echo "[stop] Killed PID $pid." || echo "[warn] Failed to kill PID $pid."
    done
}

kill_by_class "com.aionemu.gameserver.GameServer"
kill_by_class "com.aionemu.loginserver.LoginServer"
kill_by_class "com.aionemu.chatserver.ChatServer"

echo "[stop] Done."
