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
            // Clear test area if requested
            if (body["clearArea"] == true) {
                val x1 = (body["x1"] as? Double)?.toInt() ?: -50
                val y1 = (body["y1"] as? Double)?.toInt() ?: 0
                val z1 = (body["z1"] as? Double)?.toInt() ?: -50
                val x2 = (body["x2"] as? Double)?.toInt() ?: 50
                val y2 = (body["y2"] as? Double)?.toInt() ?: 100
                val z2 = (body["z2"] as? Double)?.toInt() ?: 50
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fill $x1 $y1 $z1 $x2 $y2 $z2 minecraft:air")
                steps.add("area ($x1,$y1,$z1) to ($x2,$y2,$z2) cleared")
            }

            // Kill all non-player entities if requested
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

// POST /reload -> /reload datapacks
class ReloadHandler(private val plugin: TestHarnessPlugin) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val latch = CountDownLatch(1)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "reload confirm")
            latch.countDown()
        })
        latch.await(10, TimeUnit.SECONDS)
        plugin.respond(exchange, 200, mapOf("ok" to true))
    }
}
