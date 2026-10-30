package ru.fornax.fornaxcitadel.paper.modules.breaking.damage

import org.bukkit.entity.Player
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle

/** Computes damage for one player and target on the owning game thread. */
class PlayerDamageModifierService {
    private var modifiers: List<PlayerDamageModifier> = emptyList()

    fun register(modifier: PlayerDamageModifier) {
        if (modifiers.none { it === modifier }) {
            modifiers = (modifiers + modifier).sorted()
        }
    }

    fun getDamage(citadel: CitadelHandle, player: Player): Double {
        val currentModifiers = modifiers
        var damage = 1.0
        for (modifier in currentModifiers) {
            val modifiedDamage = modifier.getDamage(player, citadel, damage)
            check(modifiedDamage.isFinite() && modifiedDamage >= 0.0) {
                "Player damage modifier ${modifier.javaClass.name} returned invalid damage: $modifiedDamage"
            }
            damage = modifiedDamage
        }
        return damage
    }
}
