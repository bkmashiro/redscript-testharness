package dev.redscript.testharness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import com.google.gson.Gson

class TestHarnessPlugin : JavaPlugin() {

    private lateinit var httpServer: HttpServer
    private val gson = Gson()
    val chatLog = CopyOnWriteArrayList<Map<String, Any>>()
    val eventLog = CopyOnWriteArrayList<Map<String, Any>>()

    override fun onEnable() {
        val port = config.getInt("port", 25561)
        httpServer = HttpServer.create(InetSocketAddress(port), 0)

        httpServer.createContext("/scoreboard", ScoreboardHandler(this))
        httpServer.createContext("/block", BlockHandler(this))
        httpServer.createContext("/entity", EntityHandler(this))
        httpServer.createContext("/chat", ChatHandler(this))
        httpServer.createContext("/events", EventsHandler(this))
        httpServer.createContext("/command", CommandHandler(this))
        httpServer.createContext("/tick", TickHandler(this))
        httpServer.createContext("/status", StatusHandler(this))
        httpServer.createContext("/reset", ResetHandler(this))
        httpServer.createContext("/reload", ReloadHandler(this))

        httpServer.executor = null
        httpServer.start()

        // Register event listeners
        server.pluginManager.registerEvents(GameEventListener(this), this)

        logger.info("TestHarness HTTP API started on port $port")
        logger.info("Endpoints: /scoreboard /block /entity /chat /events /command /tick /status /reset")
    }

    override fun onDisable() {
        if (::httpServer.isInitialized) {
            httpServer.stop(0)
        }
        logger.info("TestHarness HTTP API stopped")
    }

    fun respond(exchange: HttpExchange, code: Int, body: Any) {
        val json = gson.toJson(body)
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        return query.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to java.net.URLDecoder.decode(v, "UTF-8")
        }
    }

    fun parseBody(exchange: HttpExchange): Map<String, Any> {
        val body = exchange.requestBody.bufferedReader().readText()
        if (body.isBlank()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return gson.fromJson(body, Map::class.java) as Map<String, Any>
    }
}
