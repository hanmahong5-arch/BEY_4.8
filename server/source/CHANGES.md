# Beyond Aion 4.8 - Java 25 Compilation Report

## Date: 2026-03-07

## Build Result: SUCCESS (after 1 fix)

All four modules compile cleanly on Java 25.

```
Reactor Summary for aion-server 4.8-SNAPSHOT:
  aion-server (parent)      SUCCESS [  0.798 s]
  Aion Commons              SUCCESS [  7.019 s]
  Aion Chat Server          SUCCESS [  1.898 s]
  Aion Game Server          SUCCESS [ 32.492 s]
  Aion Login Server         SUCCESS [  1.304 s]
  Total time: 43.713 s
```

## Build Environment

- **Java**: OpenJDK 25.0.1 (Eclipse Temurin-25.0.1+8-LTS)
- **Maven**: Apache Maven 3.9.11
- **OS**: Windows Server 2019 / CYGWIN_NT-10.0-17763
- **Compiler Plugin**: maven-compiler-plugin 3.14.1
- **Target Release**: Java 25 (`maven.compiler.release=25`)

## Code Changes Required: 1 fix

### Fix 1: AccountDAO.java — Undefined constant reference

**File**: `login-server/src/com/aionemu/loginserver/dao/AccountDAO.java`
**Line**: 37

**Before**:
```java
account.setName(rs.getString(MYSQL_TABLE_ACCOUNT_NAME));
```

**After**:
```java
account.setName(rs.getString(ACCOUNT_NAME_COLUMN));
```

**Reason**: `MYSQL_TABLE_ACCOUNT_NAME` was a leftover reference from MySQL-era code. The correct constant `ACCOUNT_NAME_COLUMN` is defined on line 20 of the same file. This was an incomplete MySQL-to-PostgreSQL migration artifact.

## Build Artifacts

| Module | Artifact | Size |
|--------|----------|------|
| commons | `commons-4.8-SNAPSHOT.jar` | 151 KB |
| chat-server | `chat-server.zip` (distribution) | 5.4 MB |
| game-server | `game-server.zip` (distribution) | 123 MB |
| login-server | `login-server.zip` (distribution) | 4.3 MB |

## Compiler Warnings Summary

Zero compiler warnings from source code. Only Maven-level informational messages.

## Key Dependencies (All Java 25 Compatible)

| Library | Version | Status |
|---------|---------|--------|
| HikariCP | 7.0.2 | Compatible |
| PostgreSQL JDBC | 42.7.4 | Compatible |
| FastJSON2 | 2.0.60 | Compatible |
| Logback | 1.5.21 | Compatible |
| Quartz | 2.5.1 | Compatible |
| Netty | 3.10.6.Final | Compatible |
| JAXB Impl | 2.3.9 | Compatible |
| JUnit Jupiter | 6.0.1 | Compatible |

## Runtime Notes

No `--add-opens` flags needed in startup scripts (cleaner module access than 5.8).
