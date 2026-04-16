# CLAUDE.md — BEY_4.8 Workspace (Beyond-Aion 4.8)

## What Is This

**BEY_4.8** is the **self-contained workspace** for Beyond-Aion — the AL-Aion Java 4.8 production game server. Everything you need is in this directory: server code, game client, and tools.

This workspace is **completely independent** from `ACE_5.8/` (AionCore Go+Lua). Do not cross-reference their code, schemas, or architecture.

## Workspace Layout

```
BEY_4.8/
├── server/            # Source code only (Maven — no runtime here)
│   ├── source/        # Maven projects
│   │   ├── chat-server/ commons/ game-server/ login-server/ pom.xml
│   ├── build-all.sh   # Build + deploy: ./build-all.sh [prod|dev]  (default: dev)
│   ├── start.sh       # Start CS→LS→GS: ./start.sh [prod|dev]     (default: prod)
│   └── stop.sh        # Stop all Java services
│
├── prod/              # ★ PRODUCTION runtime (live players)
│   ├── chat-server/   JARs + config + logs  :9021(GS) / :10241(client)
│   ├── login-server/  JARs + config + logs  :2107
│   └── game-server/   JARs + config + logs  :7778
│
├── dev/               # DEVELOPMENT runtime (testing, feature work)
│   ├── chat-server/   JARs + config + logs  :9121(GS) / :10342(client)
│   ├── login-server/  JARs + config + logs  :2207
│   └── game-server/   JARs + config + logs  :7878
│
├── client/            # Beyond-Aion 4.8 game client (25GB, read-only)
│   └── start32.bat    # prod → :2107 | dev → :2207
└── tools/             # Dev tools for this workspace
    ├── version-dll/   ├── monono2/   ├── AionNetGate/   └── detours/
```

## Build & Run

```bash
export JAVA_HOME=/d/jdk-25/jdk-25.0.1

cd server

# Build and deploy JARs — specify target environment
bash build-all.sh dev    # deploy to dev/ (default)
bash build-all.sh prod   # deploy to prod/

# Start servers — specify environment
bash start.sh prod       # start production (default)
bash start.sh dev        # start development

# Stop all servers
bash stop.sh

# View logs
tail -f ../prod/game-server/log/server_console.log
tail -f ../dev/game-server/log/server_console.log
```

## Prod vs Dev Quick Reference

| | prod/ | dev/ |
|--|-------|------|
| Chat port    | 9021 (GS) / 10241 (client) | **9121 / 10342** |
| Login port   | 2107 | **2207** |
| Game port    | 7778 | **7878** |

**Rule**: Build and test in `dev/` first. Deploy to `prod/` only after validation.

## Architecture

Beyond-Aion 4.8 is a Java server based on the AL-Aion open-source project. It uses a direct DAO pattern — no stored procedures.

```
4.8 Client (:2107/:7778) → Java Netty server
                                ↓
                    PostgreSQL (Java PreparedStatement DAO)
                    Databases: aion_gs / aion_ls / aion_cs
```

Three services:
| Service | Port (client) | Port (internal) | Purpose |
|---------|--------------|-----------------|---------|
| Chat Server | 10241 | 9021 | Chat channels |
| Login Server | 2107 | 9015 | Authentication |
| Game Server | 7778 | — | Game world |

## Database Architecture

| Database | Purpose | Access Pattern |
|----------|---------|----------------|
| `aion_gs` | Game world (players, inventory, legions, abyss) | Java DAO, PreparedStatement |
| `aion_ls` | Login / accounts | Java DAO, PreparedStatement |
| `aion_cs` | Chat | Java DAO, PreparedStatement |

**Key difference from AionCore**: Beyond 4.8 uses **67 DAO classes with direct SQL**, NOT stored procedures.
Table names: `players` / `inventory` / `legions` (NOT NCSoft's `user_data` / `user_item` / `guild`).

## Key Constraints

1. **Java DAO pattern** — write SQL directly in DAO classes with PreparedStatement; no stored procedures
2. **Table naming**: AL-Aion schema (`players`, `inventory`, `legions`) — completely different from NCSoft 5.8 schema
3. **No cross-contamination** — do NOT reference ACE_5.8 schemas, protocols, or code
4. **Production line** — any change to this server requires explicit user confirmation before deploying
5. **Start order matters** — always CS → LS → GS; game server depends on login server being ready
6. **Config location**: `server/run/<service>/config/` — edit files in run/, not source/

## PAK Files

```
client/Data/         Standard ZIP format (PK\x03\x04)        → Python zipfile
client/Levels/       Aion encrypted format (AF B4 FC FB)     → tools/monono2/
client/Objects/      Aion encrypted format
client/Plugin/       Aion encrypted format
```

Decryption reference: `tools/monono2/Common/FileFormats/Pak/PakReader.cs:167-172`

## DLL Injection (version.dll)

- Framework: `tools/version-dll/`
- Loaded automatically when `client/bin32/version.dll` is placed in client dir
- Build: Visual Studio + `DETOURS_PATH` pointing to `tools/detours/`

## Port Quick Reference

| Service | Client Port | GS Internal |
|---------|-------------|-------------|
| Chat Server | 10241 | 9021 |
| Login Server | 2107 | 9015 |
| Game Server | 7778 | — |
