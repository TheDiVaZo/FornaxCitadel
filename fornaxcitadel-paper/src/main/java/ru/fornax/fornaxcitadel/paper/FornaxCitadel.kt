package ru.fornax.fornaxcitadel.paper

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

class FornaxCitadel : JavaPlugin() {

    override fun onEnable() {
        Bukkit.getOnlinePlayers()
        // Plugin startup logic
    }

    override fun onDisable() {
        // Plugin shutdown logic
    }
}
