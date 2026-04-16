#!/bin/bash
# Build Beyond Aion 4.8 Java server and deploy JARs to prod or dev runtime.
#
# Usage:
#   ./build-all.sh        # defaults to dev
#   ./build-all.sh prod   # deploy to prod/
#   ./build-all.sh dev    # deploy to dev/

set -e

TARGET="${1:-dev}"
if [[ "$TARGET" != "prod" && "$TARGET" != "dev" ]]; then
    echo "[error] Unknown target '$TARGET'. Use: prod | dev"
    exit 1
fi

WORKSPACE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_HOME="/d/jdk-25/jdk-25.0.1"
MVN="/d/apache-maven-3.9.9/bin/mvn"
DEPLOY_DIR="$WORKSPACE/../$TARGET"

export JAVA_HOME

echo "[build] Building Beyond Aion 4.8 (target: $TARGET)..."
cd "$WORKSPACE/source"
"$MVN" clean package -q

echo "[deploy] Deploying JARs to $DEPLOY_DIR ..."
cp commons/target/commons-4.8-SNAPSHOT.jar     "$DEPLOY_DIR/game-server/libs/"
cp commons/target/commons-4.8-SNAPSHOT.jar     "$DEPLOY_DIR/login-server/libs/"
cp commons/target/commons-4.8-SNAPSHOT.jar     "$DEPLOY_DIR/chat-server/libs/"
cp game-server/target/game-server-4.8-SNAPSHOT.jar   "$DEPLOY_DIR/game-server/libs/"
cp login-server/target/login-server-4.8-SNAPSHOT.jar "$DEPLOY_DIR/login-server/libs/"
cp chat-server/target/chat-server-4.8-SNAPSHOT.jar   "$DEPLOY_DIR/chat-server/libs/"

# Sync runtime-compiled script handlers (admin commands, player commands, quest handlers, etc.)
# These are .java files compiled by the server's classpath scanner at startup — NOT packaged in the JAR.
echo "[deploy] Syncing data/handlers to $DEPLOY_DIR/game-server/data/handlers ..."
mkdir -p "$DEPLOY_DIR/game-server/data/handlers"
cp -r game-server/data/handlers/* "$DEPLOY_DIR/game-server/data/handlers/"

# Merge-only sync of commands.properties — preserves prod's custom access levels
# but backfills any NEW commands that source has but prod doesn't.
SRC_CMDS="game-server/config/administration/commands.properties"
DST_CMDS="$DEPLOY_DIR/game-server/config/administration/commands.properties"
if [[ -f "$SRC_CMDS" && -f "$DST_CMDS" ]]; then
    while IFS= read -r line; do
        [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]] && continue
        key="${line%%=*}"
        key="${key// /}"
        [[ -z "$key" ]] && continue
        if ! grep -qE "^${key}[[:space:]]*=" "$DST_CMDS"; then
            echo "$line" >> "$DST_CMDS"
            echo "[deploy]   + backfilled: $key"
        fi
    done < "$SRC_CMDS"
fi

echo "[ok] Beyond Aion 4.8 built and deployed to $TARGET."
