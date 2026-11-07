package ru.fornax.fornaxcitadel.paper.modules.breaking.damage.modifiers

import org.bukkit.GameMode
import org.bukkit.entity.Player
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifier

class CreativeModeDamageModifier : PlayerDamageModifier {
    override fun priority(): PlayerDamageModifier.Priority = PlayerDamageModifier.Priority.LOWEST

    override fun getDamage(player: Player, citadel: CitadelHandle, currentDamage: Double): Double =
        // Keep creative damage finite and lethal.
        if (player.gameMode == GameMode.CREATIVE) Double.MAX_VALUE else currentDamage
}
