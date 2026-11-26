package ru.fornax.fornaxcitadel.paper.modules.upgrade

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.ReadableCitadelFacade
import ru.fornax.fornaxcitadel.core.facade.ReadableHandle
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction

/** Checks upgrade prerequisites without granting authorization or loading chunks. */
class UpgradeActionValidator(
    private val citadels: ReadableCitadelFacade,
    private val definitions: CitadelDefinitionRegistry
) : ActionValidator<UpgradeAction> {
    override val actionClass = UpgradeAction::class.java

    override fun validate(action: UpgradeAction): ActionValidator.Result {
        val position = action.position
        if (!CitadelPosition.isSupported(position.x, position.y, position.z)) return ActionValidator.Result.Rejected
        val current = try {
            citadels.findProtecting(position)
        } catch (_: CitadelResolutionUnavailableException) {
            return ActionValidator.Result.Rejected
        } ?: return ActionValidator.Result.Rejected
        return if (definitions.upgradeDestination(current, action.definitionKey) != null) ActionValidator.Result.Neutral
        else ActionValidator.Result.Rejected
    }
}

internal fun CitadelDefinitionRegistry.upgradeDestination(current: ReadableHandle, key: Key): CitadelDefinition? {
    val oldLevel = level(current) ?: return null
    val destination = find(key) ?: return null
    val newLevel = destination.level ?: return null
    return destination.takeIf { newLevel > oldLevel && it.itemKey != null }
}
