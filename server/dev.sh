#!/bin/bash
# dev.sh — Unified development CLI for Beyond 4.8
# Usage: ./dev.sh <command> [options]
#
# Commands:
#   build     Build all modules (or specify: ls gs cs commons)
#   deploy    Copy JARs to run directories
#   restart   Stop + start all servers
#   cycle     build + deploy + restart (full iteration cycle)
#   status    Show running processes and port status
#   logs      Tail server logs (gs|ls|cs)
#   errors    Show recent errors from all logs
#   migrate   Run database migrations
#   db        Open psql shell (gs|ls|cs)
#   health    Quick health check (ports + error count)

set -e

WORKSPACE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_HOME="/d/jdk-25/jdk-25.0.1"
MVN="/d/apache-maven-3.9.9/bin/mvn"
PSQL="/c/Program Files/PostgreSQL/16/bin/psql.exe"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

SRC="$WORKSPACE/beyond-4.8/source"
RUN="$WORKSPACE/beyond-4.8/run"
MIGRATIONS="$RUN/migrations"

# Color output
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[dev]${NC} $*"; }
ok()    { echo -e "${GREEN}[ok]${NC} $*"; }
warn()  { echo -e "${YELLOW}[warn]${NC} $*"; }
fail()  { echo -e "${RED}[fail]${NC} $*"; }

cmd_build() {
    local target="${1:-all}"
    info "Building ${target}..."
    cd "$SRC"
    case "$target" in
        all)     "$MVN" package -DskipTests -q ;;
        gs)      "$MVN" package -pl game-server -am -DskipTests -q ;;
        ls)      "$MVN" package -pl login-server -am -DskipTests -q ;;
        cs)      "$MVN" package -pl chat-server -am -DskipTests -q ;;
        commons) "$MVN" package -pl commons -DskipTests -q ;;
        *)       fail "Unknown target: $target"; exit 1 ;;
    esac
    ok "Build complete"
}

cmd_deploy() {
    info "Deploying JARs..."
    local changed=0
    for mod in game-server login-server chat-server commons; do
        local jar="$SRC/$mod/target/$mod-4.8-SNAPSHOT.jar"
        [ ! -f "$jar" ] && continue
        case "$mod" in
            game-server)  local dest="$RUN/game-server/libs/" ;;
            login-server) local dest="$RUN/login-server/libs/" ;;
            chat-server)  local dest="$RUN/chat-server/libs/" ;;
            commons)
                # Commons goes to all three
                for d in game-server login-server chat-server; do
                    cp "$jar" "$RUN/$d/libs/"
                done
                changed=$((changed+3)); continue ;;
        esac
        cp "$jar" "$dest"
        changed=$((changed+1))
    done
    ok "Deployed $changed JARs"
}

cmd_restart() {
    info "Stopping servers..."
    taskkill //F //IM java.exe 2>/dev/null || true
    sleep 2
    info "Starting Beyond 4.8..."
    bash "$WORKSPACE/start-beyond.sh"
}

cmd_cycle() {
    local start_time=$SECONDS
    cmd_build "${1:-all}"
    cmd_deploy
    cmd_restart
    cmd_health
    local elapsed=$((SECONDS - start_time))
    ok "Full cycle completed in ${elapsed}s"
}

cmd_status() {
    echo -e "${CYAN}=== Processes ===${NC}"
    tasklist 2>/dev/null | grep -E "java\.exe" || echo "No Java processes"
    echo -e "\n${CYAN}=== Ports ===${NC}"
    netstat -an 2>/dev/null | grep -E "LISTENING.*(2107|7778|9021|10241)" || echo "No game ports listening"
    echo -e "\n${CYAN}=== DB Connections ===${NC}"
    netstat -an 2>/dev/null | grep -c "123.56.80.174:5432.*ESTABLISHED" || echo "0"
    echo " active PostgreSQL connections"
}

cmd_logs() {
    local server="${1:-gs}"
    case "$server" in
        gs) tail -f "$RUN/game-server/log/server_console.log" ;;
        ls) tail -f "$RUN/login-server/log/server_console.log" ;;
        cs) tail -f "$RUN/chat-server/log/server_console.log" ;;
        *)  fail "Unknown server: $server (use gs|ls|cs)" ;;
    esac
}

cmd_errors() {
    echo -e "${CYAN}=== Recent Errors ===${NC}"
    for s in game-server login-server chat-server; do
        local log="$RUN/$s/log/server_console.log"
        [ ! -f "$log" ] && continue
        local cnt=$(grep -c "ERROR" "$log" 2>/dev/null || true); cnt=${cnt:-0}; cnt=${cnt##* }
        if [ "$cnt" -gt 0 ]; then
            echo -e "\n${RED}[$s] $cnt errors:${NC}"
            grep "ERROR" "$log" | tail -5
        else
            echo -e "${GREEN}[$s] 0 errors${NC}"
        fi
    done
}

cmd_migrate() {
    info "Running database migrations..."
    [ ! -d "$MIGRATIONS" ] && { warn "No migrations directory"; return; }

    for db in aion_gs aion_ls aion_cs; do
        # Create tracking table if not exists
        PGPASSWORD=postgres "$PSQL" -h 123.56.80.174 -U postgres -d "$db" -c "
            CREATE TABLE IF NOT EXISTS _migrations (
                filename VARCHAR(255) PRIMARY KEY,
                applied_at TIMESTAMP DEFAULT NOW()
            );" 2>/dev/null

        # Apply new migrations
        local applied=0
        for f in "$MIGRATIONS"/*.sql; do
            [ ! -f "$f" ] && continue
            local fname=$(basename "$f")
            local done=$(PGPASSWORD=postgres "$PSQL" -h 123.56.80.174 -U postgres -d "$db" -tAc \
                "SELECT COUNT(*) FROM _migrations WHERE filename='$fname'" 2>/dev/null)
            if [ "$done" = "0" ]; then
                info "  [$db] Applying $fname..."
                if PGPASSWORD=postgres "$PSQL" -h 123.56.80.174 -U postgres -d "$db" -f "$f" 2>&1 | grep -q "ERROR"; then
                    warn "  [$db] $fname had errors (check output)"
                fi
                PGPASSWORD=postgres "$PSQL" -h 123.56.80.174 -U postgres -d "$db" -c \
                    "INSERT INTO _migrations (filename) VALUES ('$fname') ON CONFLICT DO NOTHING;" 2>/dev/null
                applied=$((applied+1))
            fi
        done
        [ "$applied" -eq 0 ] && ok "[$db] Up to date" || ok "[$db] Applied $applied migration(s)"
    done
}

cmd_db() {
    local target="${1:-gs}"
    case "$target" in
        gs) PGPASSWORD=postgres "$PSQL" -h 123.56.80.174 -U postgres -d aion_gs ;;
        ls) PGPASSWORD=postgres "$PSQL" -h 123.56.80.174 -U postgres -d aion_ls ;;
        cs) PGPASSWORD=postgres "$PSQL" -h 123.56.80.174 -U postgres -d aion_cs ;;
        *)  fail "Unknown db: $target (use gs|ls|cs)" ;;
    esac
}

cmd_health() {
    echo -e "${CYAN}=== Health Check ===${NC}"
    local all_ok=true

    for pair in "2107:LoginServer" "7778:GameServer" "9021:ChatServer"; do
        local port="${pair%%:*}" name="${pair##*:}"
        if netstat -an 2>/dev/null | grep -q ":${port}.*LISTENING"; then
            ok "$name :$port"
        else
            fail "$name :$port NOT LISTENING"
            all_ok=false
        fi
    done

    # Error count
    local errs=$(grep -c "ERROR" "$RUN/game-server/log/server_console.log" 2>/dev/null || true); errs=${errs:-0}; errs=${errs##* }
    if [ "$errs" -eq 0 ]; then
        ok "GS errors: 0"
    else
        warn "GS errors: $errs"
    fi

    $all_ok && ok "All healthy" || fail "Issues detected"
}

# --- Main dispatcher ---
case "${1:-help}" in
    build)   cmd_build "$2" ;;
    deploy)  cmd_deploy ;;
    restart) cmd_restart ;;
    cycle)   cmd_cycle "$2" ;;
    status)  cmd_status ;;
    logs)    cmd_logs "$2" ;;
    errors)  cmd_errors ;;
    migrate) cmd_migrate ;;
    db)      cmd_db "$2" ;;
    health)  cmd_health ;;
    help|*)
        echo "Usage: ./dev.sh <command>"
        echo ""
        echo "  build [gs|ls|cs|all]  Build modules"
        echo "  deploy                Copy JARs to run/"
        echo "  restart               Stop + start servers"
        echo "  cycle [gs|ls|cs|all]  Build + deploy + restart + health"
        echo "  status                Show processes and ports"
        echo "  logs [gs|ls|cs]       Tail server log"
        echo "  errors                Show recent errors"
        echo "  migrate               Run DB migrations"
        echo "  db [gs|ls|cs]         Open psql shell"
        echo "  health                Quick health check"
        ;;
esac
