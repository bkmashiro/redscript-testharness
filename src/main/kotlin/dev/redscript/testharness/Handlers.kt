package dev.redscript.testharness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import org.bukkit.Bukkit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// GET /scoreboard?player=Alice&obj=kills  -> { "player": "Alice", "obj": "kills", "value": 3 }
// GET /scoreboard?player=@a&obj=score     -> [{ "player": "Alice", "value": 5 }, ...]
class ScoreboardHandler(private val plugin: TestHarnessPlugin) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") { plugin.respond(exchange, 405, mapOf("error" to "Method not allowed")); return }
        val params = plugin.parseQuery(exchange.requestURI.query)
        val playerName = params["player"] ?: run { plugin.respond(exchange, 400, mapOf("error" to "player required")); return }
        val objName = params["obj"] ?: run { plugin.respond(exchange, 400, mapOf("error" to "obj required")); return }

        val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        val objective = scoreboard.getObjective(objName) ?: run {
            plugin.respond(exchange, 404, mapOf("error" to "Objective '$objName' not found")); return
        }

        // Support @a, @e[...] via selector resolution
        if (playerName.startsWith("@")) {
            val latch = CountDownLatch(1)
            var results: List<Map<String, Any>> = emptyList()
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val players = Bukkit.selectEntities(Bukkit.getConsoleSender(), playerName)
                results = players.mapNotNull { entity ->
                    val score = objective.getScore(entity.name)
                    if (score.isScoreSet) mapOf("player" to entity.name, "obj" to objName, "value" to score.score)
                    else null
                }
                latch.countDown()
            })
            latch.await(5, TimeUnit.SECONDS)
            plugin.respond(exchange, 200, results)
        } else {
            val score = objective.getScore(playerName)
            if (!score.isScoreSet) {
                plugin.respond(exchange, 404, mapOf("error" to "No score for '$playerName' in '$objName'"))
                return
            }
            plugin.respond(exchange, 200, mapOf("player" to playerName, "obj" to objName, "value" to score.score))
        }
    }
}

// GET /block?x=0&y=64&z=0 -> { "x": 0, "y": 64, "z": 0, "type": "minecraft:stone", "data": {...} }
class BlockHandler(private val plugin: TestHarnessPlugin) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") { plugin.respond(exchange, 405, mapOf("error" to "Method not allowed")); return }
        val params = plugin.parseQuery(exchange.requestURI.query)
        val x = params["x"]?.toIntOrNull() ?: run { plugin.respond(exchange, 400, mapOf("error" to "x required")); return }
        val y = params["y"]?.toIntOrNull() ?: run { plugin.respond(exchange, 400, mapOf("error" to "y required")); return }
        val z = params["z"]?.toIntOrNull() ?: run { plugin.respond(exchange, 400, mapOf("error" to "z required")); return }
        val worldName = params["world"] ?: "world"

        val latch = CountDownLatch(1)
        var result: Map<String, Any> = emptyMap()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val world = Bukkit.getWorld(worldName) ?: run {
                result = mapOf("error" to "World '$worldName' not found")
                latch.countDown()
                return@Runnable
            }
            val block = world.getBlockAt(x, y, z)
            result = mapOf(
                "x" to x, "y" to y, "z" to z,
                "world" to worldName,
                "type" to block.type.key.toString(),
                "blockData" to block.blockData.asString
            )
            latch.countDown()
        })
        latch.await(5, TimeUnit.SECONDS)
        plugin.respond(exchange, if (result.containsKey("error")) 404 else 200, result)
    }
}

// GET /entity?sel=@e[type=minecraft:zombie] -> [{ "uuid", "type", "x", "y", "z", "health", "tags" }]
class EntityHandler(private val plugin: TestHarnessPlugin) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") { plugin.respond(exchange, 405, mapOf("error" to "Method not allowed")); return }
        val params = plugin.parseQuery(exchange.requestURI.query)
        val sel = params["sel"] ?: "@e"

        val latch = CountDownLatch(1)
        var results: List<Map<String, Any>> = emptyList()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                val entities = Bukkit.selectEntities(Bukkit.getConsoleSender(), sel)
                results = entities.map { e ->
                    mapOf(
                        "uuid" to e.uniqueId.toString(),
                        "name" to e.name,
                        "type" to e.type.key.toString(),
                        "x" to e.location.x,
                        "y" to e.location.y,
                        "z" to e.location.z,
                        "world" to (e.world.name),
                        "tags" to e.scoreboardTags.toList()
                    )
                }
            } catch (ex: Exception) {
                results = listOf(mapOf("error" to ex.message.orEmpty()))
            }
            latch.countDown()
        })
        latch.await(5, TimeUnit.SECONDS)
        plugin.respond(exchange, 200, results)
    }
}

// GET /chat?since=0 -> [{ "tick": N, "sender": "...", "message": "..." }]
// GET /chat?last=10 -> last 10 messages
class ChatHandler(private val plugin: TestHarnessPlugin) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val params = plugin.parseQuery(exchange.requestURI.query)
        val since = params["since"]?.toLongOrNull() ?: 0L
        val last = params["last"]?.toIntOrNull()
        var msgs = plugin.chatLog.filter { (it["tick"] as? Long ?: 0L) >= since }
        if (last != null) msgs = msgs.takeLast(last)
        plugin.respond(exchange, 200, msgs)
    }
}

// GET /events?type=death&since=0 -> [{ "tick", "type", "data" }]
class EventsHandler(private val plugin: TestHarnessPlugin) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val params = plugin.parseQuery(exchange.requestURI.query)
        val since = params["since"]?.toLongOrNull() ?: 0L
        val type = params["type"]
        var evts = plugin.eventLog.filter { (it["tick"] as? Long ?: 0L) >= since }
        if (type != null) evts = evts.filter { it["type"] == type }
        plugin.respond(exchange, 200, evts)
    }
}

// POST /command { "cmd": "/function arena:start" } -> { "ok": true }
class CommandHandler(private val plugin: TestHarnessPlugin) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") { plugin.respond(exchange, 405, mapOf("error" to "Use POST")); return }
        val body = plugin.parseBody(exchange)
        val cmd = body["cmd"] as? String ?: run { plugin.respond(exchange, 400, mapOf("error" to "cmd required")); return }

        val latch = CountDownLatch(1)
        var success = false
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.removePrefix("/"))
            } catch (ex: Exception) {
                plugin.logger.warning("Command failed: $cmd - ${ex.message}")
            }
            latch.countDown()
        })
        latch.await(5, TimeUnit.SECONDS)
        plugin.respond(exchange, 200, mapOf("ok" to success, "cmd" to cmd))
    }
}

// POST /tick { "count": 20 } -> { "ok": true, "ticksWaited": 20 }
// Waits for real server ticks (50ms each)
class TickHandler(private val plugin: TestHarnessPlugin) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") { plugin.respond(exchange, 405, mapOf("error" to "Use POST")); return }
        val body = plugin.parseBody(exchange)
        val count = (body["count"] as? Double)?.toInt() ?: 1

        val latch = CountDownLatch(1)
        // Schedule on main thread after N ticks
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { latch.countDown() }, count.toLong())
        latch.await((count * 50L + 5000L), TimeUnit.MILLISECONDS)
        plugin.respond(exchange, 200, mapOf("ok" to true, "ticksWaited" to count))
    }
}

// GET /status -> { "online": true, "tps": 20.0, "players": N, "tick": N, "worlds": [...] }
class StatusHandler(private val plugin: TestHarnessPlugin) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val tps = Bukkit.getTPS()
        plugin.respond(exchange, 200, mapOf(
            "online" to true,
            "tps_1m" to tps[0],
            "tps_5m" to tps[1],
            "tps_15m" to tps[2],
            "players" to Bukkit.getOnlinePlayers().size,
            "playerNames" to Bukkit.getOnlinePlayers().map { it.name },
            "worlds" to Bukkit.getWorlds().map { it.name },
            "version" to Bukkit.getVersion()
        ))
    }
}

// POST /reset { "clearArea": true, "x1": -50, "y1": 0, "z1": -50, "x2": 50, "y2": 100, "z2": 50 }
// Clears logs + optionally fills test area with air + resets scoreboards
class ResetHandler(private val plugin: TestHarnessPlugin) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val body = if (exchange.requestMethod == "POST") plugin.parseBody(exchange) else emptyMap()
        plugin.chatLog.clear()
        plugin.eventLog.clear()

        val latch = CountDownLatch(1)
        var steps = mutableListOf("logs cleared")

        Bukkit.getScheduler().runTask(plugin, Runnable {
            val x1 = (body["x1"] as? Double)?.toInt() ?: -50
            val y1 = (body["y1"] as? Double)?.toInt() ?: 0
            val z1 = (body["z1"] as? Double)?.toInt() ?: -50
            val x2 = (body["x2"] as? Double)?.toInt() ?: 50
            val y2 = (body["y2"] as? Double)?.toInt() ?: 100
            val z2 = (body["z2"] as? Double)?.toInt() ?: 50
            // Force-load chunks in test area (required in void worlds with no players)
            val chunkX1 = (x1 shr 4) - 1
            val chunkZ1 = (z1 shr 4) - 1
            val chunkX2 = (x2 shr 4) + 1
            val chunkZ2 = (z2 shr 4) + 1
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "forceload add $chunkX1 $chunkZ1 $chunkX2 $chunkZ2")
            steps.add("chunks forceloaded ($chunkX1,$chunkZ1) to ($chunkX2,$chunkZ2)")

            // Clear test area if requested
            if (body["clearArea"] == true) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fill $x1 $y1 $z1 $x2 $y2 $z2 minecraft:air")
                steps.add("area ($x1,$y1,$z1) to ($x2,$y2,$z2) cleared")
            }

            // Kill all non-player entities if requested
            @Suppress("UNUSED_EXPRESSION")
            if (body["killEntities"] == true) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "kill @e[type=!minecraft:player]")
                steps.add("entities killed")
            }

            // Reset scoreboard objectives if requested
            if (body["resetScoreboards"] == true) {
                val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
                scoreboard.objectives.forEach { obj ->
                    // Reset all scores for all tracked entities
                    obj.scoreboard?.entries?.forEach { entry ->
                        try { obj.getScore(entry).resetScore() } catch (_: Exception) {}
                    }
                }
                steps.add("scoreboards reset")
            }

            latch.countDown()
        })
        latch.await(10, TimeUnit.SECONDS)
        plugin.respond(exchange, 200, mapOf("ok" to true, "steps" to steps))
    }
}

// GET /scoreboard/dump?obj=<objective>  -> { "obj": "__stdlib_queue8_test", "entries": { "$p0": 0, ... } }
// GET /scoreboard/dump?ns=<namespace>   -> same as obj=__<namespace>
class ScoreboardDumpHandler(private val plugin: TestHarnessPlugin) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") { plugin.respond(exchange, 405, mapOf("error" to "Method not allowed")); return }
        val params = plugin.parseQuery(exchange.requestURI.query)

        val objName = when {
            params.containsKey("obj") -> params["obj"]!!
            params.containsKey("ns")  -> "__${params["ns"]!!}"
            else -> { plugin.respond(exchange, 400, mapOf("error" to "obj or ns required")); return }
        }

        val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        val objective = scoreboard.getObjective(objName) ?: run {
            plugin.respond(exchange, 404, mapOf("error" to "Objective '$objName' not found")); return
        }

        val entries = mutableMapOf<String, Int>()
        for (entry in scoreboard.entries) {
            val score = objective.getScore(entry)
            if (score.isScoreSet) {
                entries[entry] = score.score
            }
        }

        plugin.respond(exchange, 200, mapOf("obj" to objName, "entries" to entries))
    }
}

// GET /storage/dump?storage=rs:macro_args -> { "storage": "rs:macro_args", "raw": "{ val: 11 }", "ok": true }
class StorageDumpHandler(private val plugin: TestHarnessPlugin) : HttpHandler {

    private val logFile = java.io.File(plugin.dataFolder.parentFile.parentFile, "logs/latest.log")

    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") { plugin.respond(exchange, 405, mapOf("error" to "Method not allowed")); return }
        val params = plugin.parseQuery(exchange.requestURI.query)
        val storage = params["storage"] ?: run {
            plugin.respond(exchange, 400, mapOf("error" to "storage required")); return
        }

        val latch = CountDownLatch(1)
        var rawOutput = ""

        Bukkit.getScheduler().runTask(plugin, Runnable {
            val logSizeBefore = if (logFile.exists()) logFile.length() else 0L

            // "data get storage <storage>" without a path returns the whole compound
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "data get storage $storage")

            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                rawOutput = readNewLogLines(logSizeBefore)
                latch.countDown()
            }, 2L)
        })

        latch.await(5, TimeUnit.SECONDS)

        val ok = rawOutput.isNotBlank()
        plugin.respond(exchange, 200, mapOf(
            "storage" to storage,
            "raw"     to rawOutput,
            "ok"      to ok
        ))
    }

    private fun readNewLogLines(offsetBytes: Long): String {
        if (!logFile.exists()) return ""
        return try {
            val allBytes = logFile.readBytes()
            if (allBytes.size <= offsetBytes) return ""
            val newContent = String(allBytes, offsetBytes.toInt(), (allBytes.size - offsetBytes).toInt(), Charsets.UTF_8)
            newContent.lines()
                .filter { line ->
                    line.contains("has the following NBT") ||
                    line.contains("has the following contents") ||
                    line.contains("no elements matching") ||
                    line.contains("Found no elements")
                }
                .map { stripLogPrefix(it) }
                .lastOrNull() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun stripLogPrefix(line: String): String {
        val prefixRegex = Regex("""^\[[\d:]+\] \[[^\]]+\]: """)
        val stripped = prefixRegex.replace(line, "").trim()
        // Extract just the NBT compound/value after ": "
        val nbtRegex = Regex("""(?:has the following NBT|has the following contents): (.+)$""")
        val match = nbtRegex.find(stripped)
        return match?.groupValues?.get(1)?.trim() ?: stripped
    }
}

// POST /reload -> safely reload data packs only (NOT full plugin reload)
class ReloadHandler(private val plugin: TestHarnessPlugin) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val latch = CountDownLatch(1)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                // reloadData() reloads advancements, loot tables, and datapacks
                // without restarting plugins — much safer than /reload confirm
                Bukkit.reloadData()
            } catch (e: Exception) {
                plugin.logger.warning("reloadData failed: ${e.message}")
            }
            latch.countDown()
        })
        latch.await(30, TimeUnit.SECONDS)
        plugin.respond(exchange, 200, mapOf("ok" to true))
    }
}
