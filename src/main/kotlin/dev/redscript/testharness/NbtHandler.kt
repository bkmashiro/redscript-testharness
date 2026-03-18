package dev.redscript.testharness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import org.bukkit.Bukkit
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// GET /nbt?storage=rs:d&path=result           -> { "storage": "rs:d", "path": "result", "raw": "...", "value": 3.14 }
//
// Paper uses Adventure Components for command output, so ConsoleCommandSender.sendMessage(String)
// overrides are never called. Instead we:
//   1. Record the log file size before the command
//   2. Dispatch "data get storage <storage> <path>" on the main thread
//   3. Wait one tick, then read new lines appended to logs/latest.log
//   4. Find the NBT output line and parse it
class NbtHandler(private val plugin: TestHarnessPlugin) : HttpHandler {

    private val logFile = File(plugin.dataFolder.parentFile.parentFile, "logs/latest.log")

    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            plugin.respond(exchange, 405, mapOf("error" to "Method not allowed")); return
        }
        val params = plugin.parseQuery(exchange.requestURI.query)
        val storage = params["storage"] ?: run {
            plugin.respond(exchange, 400, mapOf("error" to "storage required")); return
        }
        val path = params["path"] ?: run {
            plugin.respond(exchange, 400, mapOf("error" to "path required")); return
        }

        val latch = CountDownLatch(1)
        var rawOutput = ""

        Bukkit.getScheduler().runTask(plugin, Runnable {
            // Snapshot log position before dispatching
            val logSizeBefore = if (logFile.exists()) logFile.length() else 0L

            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "data get storage $storage $path")

            // Wait one tick then read new log lines
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                rawOutput = readNewLogLines(logSizeBefore)
                latch.countDown()
            }, 2L)
        })

        latch.await(5, TimeUnit.SECONDS)

        val stripped = stripLogPrefix(rawOutput)
        plugin.respond(exchange, 200, mapOf(
            "storage" to storage,
            "path"    to path,
            "raw"     to stripped,
            "value"   to parseNbtValue(rawOutput)
        ))
    }

    private fun readNewLogLines(offsetBytes: Long): String {
        if (!logFile.exists()) return ""
        return try {
            val allBytes = logFile.readBytes()
            if (allBytes.size <= offsetBytes) return ""
            val newContent = String(allBytes, offsetBytes.toInt(), (allBytes.size - offsetBytes).toInt(), Charsets.UTF_8)
            // Look for any NBT/data result line or error line, strip log prefix
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

    /** Strip "[HH:MM:SS] [Thread/LEVEL]: " prefix from a log line. */
    private fun stripLogPrefix(line: String): String {
        // Pattern: "[16:12:57] [Server thread/INFO]: Message"
        val prefixRegex = Regex("""^\[[\d:]+\] \[[^\]]+\]: """)
        return prefixRegex.replace(line, "").trim()
    }

    private fun parseNbtValue(raw: String): Any? {
        if (raw.isBlank()) return null
        val msg = stripLogPrefix(raw).ifBlank { raw.trim() }
        if (msg.contains("no elements matching") || msg.contains("Found no elements")) return null

        // 1.21.4 formats:
        //   "The <path> storage path at <storage> has the following NBT: <value>"
        //   "Storage <id> has the following contents: <value>"
        val nbtRegex = Regex("""(?:has the following NBT|has the following contents): (.+)$""")
        val match = nbtRegex.find(msg) ?: return msg
        val value = match.groupValues[1].trim()
        return when {
            value.endsWith("d") || value.endsWith("D") -> value.dropLast(1).toDoubleOrNull() ?: value
            value.endsWith("f") || value.endsWith("F") -> value.dropLast(1).toFloatOrNull()?.toDouble() ?: value
            value.endsWith("b") || value.endsWith("B") -> value.dropLast(1).toIntOrNull() ?: value
            value.endsWith("s") || value.endsWith("S") -> value.dropLast(1).toIntOrNull() ?: value
            value.endsWith("L") -> value.dropLast(1).toLongOrNull() ?: value
            value.toIntOrNull() != null -> value.toInt()
            value.toDoubleOrNull() != null -> value.toDouble()
            else -> value
        }
    }
}
