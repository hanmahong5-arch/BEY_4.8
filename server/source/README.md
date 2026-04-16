# Aion 4.8 Server Emulator

A community-driven server emulator for the MMORPG **Aion: The Tower of Eternity**, version 4.8.

> Based on [Beyond Aion](https://github.com/beyond-aion/aion-server) with modifications and improvements.
> This project is intended to be faithful to the original experience while implementing quality-of-life enhancements.

## Architecture

```
Client (Aion 4.8)
    |
    v
Login Server          ── Authentication, Account Management, Server List
    |
    v
Game Server           ── World, Combat, AI, Quests, Instances, Services
Chat Server           ── In-game Messaging, Channel Management
    |
    v
PostgreSQL Database   ── Player Data, World State, Items, Quests
    |
Commons Library       ── Network I/O, Database Layer, Configuration, Utilities
```

## Modules

| Module | Description |
|--------|-------------|
| **commons** | Shared library: NIO networking, HikariCP database pool, configuration system, logging |
| **game-server** | Core game server: world management, combat, AI engine, quests, instances, 60+ services |
| **login-server** | Login server: authentication, encryption, account management, game server registration |
| **chat-server** | Chat server: Netty-based messaging, channel management, encryption |

## Key Features

### Custom Features
- **PvPvE Map** - Increased AP rates and boss spawns for competitive play
- **Eternal Challenge** - Solo instance with deep-learning AI boss that mirrors player tactics
- **QoL Improvements** - Customized drop lists, player commands, PvP/PvE rewards

### Core Systems
- **Combat & Skill Engine** - Full skill system with motion validation against no-animation hacks
- **AI Engine** - NPC behavior with queued skills and event-driven handlers
- **Quest Engine** - Hundreds of fixed and scripted quests
- **Instance System** - Instanced dungeons with custom mechanics
- **GeoEngine** - Collision detection with fixed obstacles, doors, shields, and terrain checks
- **Siege System** - Territory control warfare
- **Housing System** - Player housing with auctions
- **Event Engine** - Automatic buffs, config overrides, and scheduled events
- **Anti-Cheat** - True invisibility against anti-hide hacks, motion validation

### Technical Improvements
- Memory leak fixes - server runs stable for months without restart
- Concurrency fixes and performance optimizations
- Discord webhook logging support
- Class file caching for faster startup
- Regular Java and dependency updates

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 25 |
| Build | Apache Maven |
| Database | PostgreSQL (migrated from MySQL) |
| Connection Pool | HikariCP 7.0.2 |
| Logging | SLF4J + Logback 1.5.x |
| Chat Networking | Netty 3.10.6 |
| JSON | FastJSON2 |
| Scheduling | Quartz 2.5.1 |
| Testing | JUnit Jupiter 6.0.1 |
| Code Style | EditorConfig |

## Prerequisites

- **JDK 25** or later
- **Apache Maven 3.x**
- **PostgreSQL** database server
- **2-4 GB RAM** (recommended)

## Build

```bash
# Build all modules from root directory
mvn package

# Output:
#   game-server/target/game-server.zip
#   login-server/target/login-server.zip
#   chat-server/target/chat-server.zip
```

## Database Setup

1. Initialize databases with SQL scripts:
   ```bash
   psql -U aion -d aion_gs -f game-server/sql/aion_gs.sql
   psql -U aion -d aion_ls -f login-server/sql/aion_ls.sql
   ```
2. Default connection: `localhost:5432`, user `aion`, password `aion`
3. Whitelist game server in the `gameserver` table of the login server database

## Running

Import as a Maven project in your IDE (IntelliJ recommended). Set the working directory to each module's directory.

**Main classes:**
- Game Server: `com.aionemu.gameserver.GameServer`
- Login Server: `com.aionemu.loginserver.LoginServer`
- Chat Server: `com.aionemu.chatserver.ChatServer`

## Configuration

Default configs work out of the box. For customization, create override files:

| Override File | Purpose |
|--------------|---------|
| `config/mygs.properties` | Game server overrides |
| `config/myls.properties` | Login server overrides |
| `config/mycs.properties` | Chat server overrides |

These files take precedence over standard `.properties` files and won't be modified during updates.

Key configuration files in `config/main/`:

| File | Purpose |
|------|---------|
| `gameserver.properties` | Core server settings |
| `rates.properties` | XP, AP, drop, crafting rates |
| `custom.properties` | Custom feature toggles |
| `security.properties` | Anti-hack and security |
| `housing.properties` | Housing system |
| `siege.properties` | Siege mechanics |

## Game Client Setup

1. Download the Aion 4.8 client
2. Copy [version.dll](https://github.com/beyond-aion/aion-version-dll/releases/latest) into `bin32/` and `bin64/` folders
3. Create `start.bat` in the game root:
   ```batch
   start /affinity 7FFFFFFF "" "bin64\AION.bin" -ip:127.0.0.1 -port:2106 -cc:2 -lang:ENG -loginex
   ```

## Project Structure

```
beyond-aion-4.8/
├── commons/              # Shared utilities and base classes
│   └── src/              # Database, network, config, logging
├── game-server/          # Game server (main module)
│   ├── src/              # Game logic (~2,300 Java files)
│   ├── config/           # Server configuration
│   └── sql/              # Database schema
├── login-server/         # Login server
│   └── src/              # Authentication (~93 Java files)
├── chat-server/          # Chat server
│   └── src/              # Messaging (~61 Java files)
├── config/               # Shared configuration
└── pom.xml               # Maven parent POM
```

## Contributing

Please read the [contributing guidelines](.github/CONTRIBUTING.md) before submitting changes.

## License

GNU General Public License v3 (GPLv3) - see [LICENSE](LICENSE) for details.

## Acknowledgments

Built upon the foundation of the Aion emulation community. Special thanks to the Beyond Aion team and all contributors who have dedicated their time to improving this emulator.

## Disclaimer

This software is provided for educational and research purposes. The developers are not responsible for any misuse of this software. Aion is a registered trademark of NCSOFT Corporation.
