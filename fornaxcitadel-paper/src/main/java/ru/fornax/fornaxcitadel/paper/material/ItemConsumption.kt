package ru.fornax.fornaxcitadel.paper.material

import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Consumes from the already validated main-hand stack when committing a successful operation. */
internal fun Player.consumeOneFromMainHand(item: ItemStack) {
    if (gameMode == GameMode.CREATIVE) return
    item.amount--
    inventory.setItemInMainHand(if (item.amount == 0) null else item)
}
