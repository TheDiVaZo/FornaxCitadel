package ru.fornax.fornaxcitadel.paper.modules.health

import org.bukkit.GameMode
import ru.fornax.fornaxcitadel.core.facade.HealthFacade
import ru.fornax.fornaxcitadel.core.facade.ReadableCitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource

/** Authorizes configured damage and creative destruction for every action source. */
class PaperDamageValidator(
    private val citadels: ReadableCitadelFacade,
    private val health: HealthFacade,
    private val creativeModeEnabled: Boolean
) : ActionValidator<DamageAction> {
    override val actionClass = DamageAction::class.java

    override fun validate(action: DamageAction): ActionValidator.Result {
        val citadel = try {
            citadels.findProtecting(action.position)
        } catch (_: CitadelResolutionUnavailableException) {
            return ActionValidator.Result.Rejected
        } ?: return ActionValidator.Result.Rejected

        if (action.mode == DamageAction.Mode.DESTROY) {
            if (!creativeModeEnabled) return ActionValidator.Result.Rejected
            val player = (action.context.source as? PlayerSource)?.player ?: return ActionValidator.Result.Rejected
            return if (player.gameMode == GameMode.CREATIVE) ActionValidator.Result.Success
            else ActionValidator.Result.Rejected
        }

        return if (health.isInvulnerable(citadel)) ActionValidator.Result.Rejected else ActionValidator.Result.Success
    }
}
