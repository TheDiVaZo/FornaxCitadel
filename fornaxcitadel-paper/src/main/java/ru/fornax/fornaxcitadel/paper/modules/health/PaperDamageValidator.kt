package ru.fornax.fornaxcitadel.paper.modules.health

import org.bukkit.GameMode
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.facade.ReadableCitadelFacade
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.AcidDestroyAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelDamagePolicy
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes

/** Authorizes configured damage and creative destruction for every action source. */
class PaperDamageValidator(
    private val citadels: ReadableCitadelFacade,
    private val citadelTypes: PaperCitadelTypes,
    private val creativeModeEnabled: Boolean
) : ActionValidator<DamageAction> {
    override val actionClass = DamageAction::class.java

    override fun validate(action: DamageAction): ActionValidator.Result {
        val citadel = try {
            citadels.findProtecting(action.position)
        } catch (_: CitadelResolutionUnavailableException) {
            return ActionValidator.Result.Rejected
        } ?: return ActionValidator.Result.Rejected

        if (action is AcidDestroyAction) {
            // AcidService authorizes only its scoped actions, never a source key alone.
            if (action.consumingSource) return ActionValidator.Result.Neutral
            val kind = citadel.getComponent(CitadelKind::class.java)
            return if (kind != null && citadelTypes.damagePolicy(kind.definitionKey) == CitadelDamagePolicy.DENY)
                ActionValidator.Result.Rejected else ActionValidator.Result.Neutral
        }
        if (action.mode == DamageAction.Mode.DESTROY) {
            if (!creativeModeEnabled) return ActionValidator.Result.Rejected
            val player = (action.context.source as? PlayerSource)?.player ?: return ActionValidator.Result.Rejected
            return if (player.gameMode == GameMode.CREATIVE) ActionValidator.Result.Success
            else ActionValidator.Result.Rejected
        }

        val kind = citadel.getComponent(CitadelKind::class.java)
        return when (kind?.definitionKey?.let(citadelTypes::damagePolicy)) {
            CitadelDamagePolicy.ALLOW -> ActionValidator.Result.Success
            CitadelDamagePolicy.DENY -> ActionValidator.Result.Rejected
            null -> ActionValidator.Result.Success
        }
    }
}
