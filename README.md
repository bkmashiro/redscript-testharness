# redscript-testharness

A [Paper](https://papermc.io) 1.21.4 plugin that exposes an HTTP API for integration testing [RedScript](https://github.com/bkmashiro/redscript) compiled datapacks on a real Minecraft server.

## Architecture

```
RedScript Test Runner (Node.js)
         ↕ HTTP API (port 25561)
   TestHarness Plugin (Kotlin/Paper)
         ↕ Bukkit/Paper API
      Paper 1.21.4 Server
```

## HTTP API

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/status` | Server status, TPS, player list |
| GET | `/scoreboard?player=X&obj=Y` | Get scoreboard value (supports selectors like `@a`) |
| GET | `/block?x=0&y=64&z=0[&world=world]` | Get block type at position |
| GET | `/entity?sel=@e[type=zombie]` | Get entities matching selector |
| GET | `/chat?since=0` | Get chat/tellraw log since tick N |
| GET | `/events?type=death&since=0` | Get game events (death/join/advancement) |
| POST | `/command` `{"cmd": "/function ns:start"}` | Run command as console |
| POST | `/tick` `{"count": 20}` | Wait N real server ticks |
| POST | `/reset` | Clear logs, optionally fill area + kill entities + reset scoreboards |
| POST | `/reload` | Run `/reload confirm` and wait |

### `/reset` Options

```json
{
  "clearArea": true,
  "x1": -50, "y1": 0, "z1": -50,
  "x2": 50,  "y2": 100, "z2": 50,
  "killEntities": true,
  "resetScoreboards": true
}
```

## Build

Requires Java 21.

```bash
# Install Java 21 (macOS)
brew install openjdk@21
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

# Build plugin JAR
gradle jar

# Output: build/libs/redscript-testharness-1.0.0.jar
```

## Server Setup

### 1. Download Paper

```bash
mkdir ~/mc-test-server && cd ~/mc-test-server
curl -L "https://api.papermc.io/v2/projects/paper/versions/1.21.4/builds/232/downloads/paper-1.21.4-232.jar" -o paper.jar
echo "eula=true" > eula.txt
```

### 2. Configure for testing (`server.properties`)

```properties
online-mode=false
gamemode=creative
spawn-protection=0
difficulty=peaceful
spawn-monsters=false
spawn-animals=false
spawn-npcs=false

# Void superflat world (clean, predictable for tests)
level-type=flat
generator-settings={"biome":"minecraft:the_void","layers":[{"block":"minecraft:air","height":1}],"structures":{"structures":{}}}
```

### 3. Install plugin and start

```bash
cp build/libs/redscript-testharness-1.0.0.jar ~/mc-test-server/plugins/
java -Xmx1G -jar paper.jar --nogui
```

Plugin starts HTTP API on port 25561.

## Usage with MCTestClient

The companion [`MCTestClient`](https://github.com/bkmashiro/redscript/blob/main/src/mc-test/client.ts) in the RedScript repo provides a typed TypeScript client:

```typescript
import { MCTestClient } from './src/mc-test/client'

const mc = new MCTestClient('localhost', 25561)

// Check server is up
await mc.isOnline()

// Full test reset (void world + clear logs + reset state)
await mc.fullReset()

// Install and reload a compiled datapack
// (copy files to world/datapacks/ first)
await mc.reload()
await mc.ticks(40) // wait for load

// Run a function
await mc.command('/function counter:__load')
await mc.ticks(100)

// Assert scoreboard value
await mc.assertScore('ticks', 'counter', 100)

// Assert block was placed
await mc.assertBlock(4, 65, 4, 'minecraft:gold_block')

// Check chat output
await mc.assertChatContains('Game started!')

// Get all entities
const zombies = await mc.entities('@e[type=minecraft:zombie]')
```

## Integration Tests

See `src/__tests__/mc-integration.test.ts` in the RedScript repo.

Run with a live server:
```bash
MC_SERVER_DIR=~/mc-test-server npx jest mc-integration --testTimeout=30000
```

## Why Void Superflat?

- No terrain → `setblock`/`fill` results are fully predictable
- No random mob spawns → entity queries are deterministic
- Fast world generation
- `fullReset()` can clear test area and restore pristine state between tests
