package dev.redscript.testharness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import org.bukkit.Bukkit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// POST /player  body: { "action": "set_score"|"trigger"|"add_score"|"list_scores", "name": "...", ... }
// GET  /player?name=FakePlayer1  -> { "name": "FakePlayer1", "online": false, "scores": {...} }
class PlayerHandler(private val plugin: TestHarnessPlugin) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        when (exchange.requestMethod) {
            "POST" -> handlePost(exchange)
            "GET"  -> handleGet(exchange)
            else   -> plugin.respond(exchange, 405, mapOf("error" to "Method not allowed"))
        }
    }

    private fun handlePost(exchange: HttpExchange) {
        val body = plugin.parseBody(exchange)
        val action = body["action"] as? String ?: run {
            plugin.respond(exchange, 400, mapOf("error" to "action required")); return
        }
        val name = body["name"] as? String ?: run {
            plugin.respond(exchange, 400, mapOf("error" to "name required")); return
        }

        when (action) {
            "set_score" -> {
                val obj = body["obj"] as? String ?: run {
                    plugin.respond(exchange, 400, mapOf("error" to "obj required")); return
                }
                val value = (body["value"] as? Number)?.toInt() ?: run {
                    plugin.respond(exchange, 400, mapOf("error" to "value required")); return
                }
                val latch = CountDownLatch(1)
                var ok = false
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players set $name $obj $value")
                    ok = true
                    latch.countDown()
                })
                latch.await(5, TimeUnit.SECONDS)
                plugin.respond(exchange, 200, mapOf("ok" to ok, "name" to name, "obj" to obj, "value" to value))
            }

            "trigger" -> {
                val trigger = body["trigger"] as? String ?: run {
                    plugin.respond(exchange, 400, mapOf("error" to "trigger required")); return
                }
                val latch = CountDownLatch(1)
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players enable $name $trigger")
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players set $name $trigger 1")
                    latch.countDown()
                })
                latch.await(5, TimeUnit.SECONDS)
                plugin.respond(exchange, 200, mapOf("ok" to true, "triggered" to trigger, "player" to name))
            }

            "add_score" -> {
                val obj = body["obj"] as? String ?: run {
                    plugin.respond(exchange, 400, mapOf("error" to "obj required")); return
                }
                val amount = (body["amount"] as? Number)?.toInt() ?: 1
                val latch = CountDownLatch(1)
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players add $name $obj $amount")
                    latch.countDown()
                })
                latch.await(5, TimeUnit.SECONDS)
                plugin.respond(exchange, 200, mapOf("ok" to true, "name" to name, "obj" to obj, "amount" to amount))
            }

            "reset_score" -> {
                val obj = body["obj"] as? String ?: run {
                    plugin.respond(exchange, 400, mapOf("error" to "obj required")); return
                }
                val latch = CountDownLatch(1)
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players reset $name $obj")
                    latch.countDown()
                })
                latch.await(5, TimeUnit.SECONDS)
                plugin.respond(exchange, 200, mapOf("ok" to true, "name" to name, "obj" to obj))
            }

            else -> plugin.respond(exchange, 400, mapOf("error" to "unknown action: $action"))
        }
    }

    private fun handleGet(exchange: HttpExchange) {
        val params = plugin.parseQuery(exchange.requestURI.query)
        val name = params["name"] ?: run {
            plugin.respond(exchange, 400, mapOf("error" to "name required")); return
        }

        val latch = CountDownLatch(1)
        var scores: Map<String, Int> = emptyMap()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val sb = Bukkit.getScoreboardManager().mainScoreboard
            val result = mutableMapOf<String, Int>()
            sb.objectives.forEach { obj ->
                val score = obj.getScore(name)
                if (score.isScoreSet) result[obj.name] = score.score
            }
            scores = result
            latch.countDown()
        })
        latch.await(5, TimeUnit.SECONDS)
        val online = Bukkit.getPlayerExact(name) != null
        plugin.respond(exchange, 200, mapOf("name" to name, "online" to online, "scores" to scores))
    }
}
