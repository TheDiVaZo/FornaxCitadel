package ru.fornax.fornaxcitadel.paper.modules.acid

import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import java.util.Collections

/** Synchronous pre-destruction event. Targets cannot be added, replaced or reordered by listeners. */
class AcidBlockActivateEvent(
    val player: Player,
    val acidPosition: CitadelPosition,
    val acidType: AcidType,
    val acidReinforcement: CitadelHandle,
    targets: List<AcidTarget>
) : Event(), Cancellable {
    val targets: List<AcidTarget> = Collections.unmodifiableList(targets.toList())
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

data class AcidTarget(val position: CitadelPosition, val reinforcement: CitadelHandle)
