package ru.fornax.fornaxcitadel.addon

import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.systems.action.ActionMeta
import ru.fornax.fornaxcitadel.core.systems.action.ActionRegistration
import ru.fornax.fornaxcitadel.core.systems.action.ExistingCitadelValidator
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.DamageProcessor
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.modules.health.MaturationService
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.health.MaturingDamageModifier
import ru.fornax.fornaxcitadel.modules.place.PlaceActionValidator
import ru.fornax.fornaxcitadel.modules.place.PlaceProcessor
import ru.fornax.fornaxcitadel.modules.place.PlaceService
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveProcessor
import ru.fornax.fornaxcitadel.modules.remove.RemoveService
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction

/** Registers the persisted core types at construction, before the first chunk is loaded. */
class CoreAddon(module: CitadelModule) : Addon {
    init {
        module.componentManager.registerComponent(CitadelKind.KEY, CitadelKind::class.java)
        module.componentManager.registerComponent(CitadelOwner.KEY, CitadelOwner::class.java)
        module.componentManager.registerComponent(Health.KEY, Health::class.java)
        module.componentManager.registerComponent(Maturing.KEY, Maturing::class.java)
    }

    val place = PlaceService(module.definitions, module.citadels)
    val remove = RemoveService(module.citadels)
    val health = HealthService(remove, module.citadels, MaturationService())

    private val actions = module.actions
    private val maturationModifier = MaturingDamageModifier(health.maturation)
    private val requireApproval = ActionMeta(false)
    private val protectedActions = listOf(RemoveAction.KEY, DamageAction.KEY, DismantleAction.KEY,
        RepairAction.KEY, UpgradeAction.KEY)
    private val registrations = listOf(
        ActionRegistration(actions, RemoveProcessor(remove),
            listOf(ExistingCitadelValidator(RemoveAction::class.java, module.citadels))),
        ActionRegistration(actions, DamageProcessor(health),
            listOf(ExistingCitadelValidator(DamageAction::class.java, module.citadels))),
        ActionRegistration(actions, PlaceProcessor(place), listOf(PlaceActionValidator(module.citadels, module.definitions)))
    )
    private var enabled = false

    override fun onEnable() {
        if (enabled) return
        try {
            health.registerFirstModifier(maturationModifier)
            protectedActions.forEach { actions.registerActionMetaIfAbsent(it, requireApproval) }
            registrations.forEach { it.enable() }
            enabled = true
        } catch (failure: Throwable) {
            try {
                onDisable()
            } catch (cleanupFailure: Throwable) {
                if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    override fun onDisable() {
        registrations.asReversed().forEach { it.disable() }
        protectedActions.asReversed().forEach { actions.unregisterActionMeta(it, requireApproval) }
        health.unregisterModifier(maturationModifier)
        enabled = false
    }
}
