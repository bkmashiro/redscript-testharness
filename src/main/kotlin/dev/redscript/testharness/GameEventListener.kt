package dev.redscript.testharness

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.Bukkit

class GameEventListener(private val plugin: TestHarnessPlugin) : Listener {

    private fun currentTick(): Long = Bukkit.getServer().currentTick.toLong()

    @EventHandler
    fun onChat(event: AsyncPlayerChatEvent) {
        plugin.chatLog.add(mapOf(
            "tick" to currentTick(),
            "type" to "chat",
            "sender" to event.player.name,
            "message" to event.message
        ))
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        plugin.chatLog.add(mapOf(
            "tick" to currentTick(),
            "type" to "death_message",
            "message" to (event.deathMessage() ?: "")
        ))
        plugin.eventLog.add(mapOf(
            "tick" to currentTick(),
            "type" to "death",
            "player" to event.entity.name,
            "cause" to event.entity.lastDamageCause?.cause?.name.orEmpty()
        ))
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        plugin.eventLog.add(mapOf(
            "tick" to currentTick(),
            "type" to "join",
            "player" to event.player.name
        ))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.eventLog.add(mapOf(
            "tick" to currentTick(),
            "type" to "quit",
            "player" to event.player.name
        ))
    }

    @EventHandler
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val key = event.advancement.key
        plugin.eventLog.add(mapOf(
            "tick" to currentTick(),
            "type" to "advancement",
            "player" to event.player.name,
            "advancement" to "${key.namespace}:${key.key}"
        ))
    }
}
