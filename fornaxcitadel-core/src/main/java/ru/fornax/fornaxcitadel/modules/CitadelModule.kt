package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolver
import ru.fornax.fornaxcitadel.core.resolver.DirectCitadelResolver
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.systems.action.ActionMeta
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.modules.place.PlaceService
import ru.fornax.fornaxcitadel.modules.queue.ActionQueueService
import ru.fornax.fornaxcitadel.modules.remove.RemoveAction
import ru.fornax.fornaxcitadel.modules.remove.RemoveService
import ru.fornax.fornaxcitadel.modules.repair.RepairAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction

/**
 * Core lifecycle entry point. Construct and register definitions before loading chunks;
 * call [enable] to attach processors and prerequisite validators and [disable] to detach them.
 *
 * Register read-only authorization checks with [ActionManager.registerValidator]. Damage, explicit
 * destruction, administrative removal, dismantling, repair and upgrade require a validator's Success; a rejection always wins.
 * Enabled services validate target availability and placement prerequisites before execution.
 * These prerequisite checks never grant authorization. Definitions configure initial components;
 * source keys are attribution.
 *
 * Services and the component manager expose trusted low-level operations; external requests
 * must enter through [actions]. Successful actions notify handlers registered with
 * [ActionManager.registerHandler]. Direct service calls are silent. All access is on the owning
 * game thread. Handlers may post actions for [actionQueue] to drain on subsequent ticks while enabled.
 * This module changes component data only; it has no Minecraft/Paper dependencies.
 */
class CitadelModule @JvmOverloads constructor(
    manager: ComponentManager,
    scheduler: Scheduler,
    maxActionsPerTick: Int = 1024,
    resolver: CitadelResolver = DirectCitadelResolver(manager),
    actionFailureHandler: (Exception) -> Unit = ActionQueueService.DEFAULT_FAILURE_HANDLER
) : LifecycleService {

    val definitions = CitadelDefinitionRegistry()
    val citadels = CitadelFacade(manager, resolver)
    val actions = ActionManager(citadels).apply {
        registerActionMeta(RemoveAction.KEY, ActionMeta(false))
        registerActionMeta(DamageAction.KEY, ActionMeta(false))
        registerActionMeta(DismantleAction.KEY, ActionMeta(false))
        registerActionMeta(RepairAction.KEY, ActionMeta(false))
        registerActionMeta(UpgradeAction.KEY, ActionMeta(false))
    }
    val place = PlaceService(actions, definitions, citadels, manager)
    val remove = RemoveService(actions, citadels, manager)
    val health = HealthService(actions, remove, citadels, manager)
    val actionQueue = ActionQueueService(actions, scheduler, maxActionsPerTick, actionFailureHandler)
    private val services: List<LifecycleService> = listOf(remove, health, place, actionQueue)

    override fun enable() {
        try {
            services.forEach { it.enable() }
        } catch (failure: Throwable) {
            try {
                disable()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    override fun disable() {
        var failure: Throwable? = null
        services.asReversed().forEach { service ->
            try {
                service.disable()
            } catch (cleanupFailure: Throwable) {
                val previous = failure
                if (previous == null) failure = cleanupFailure
                else if (previous !== cleanupFailure) previous.addSuppressed(cleanupFailure)
            }
        }
        actions.clearPosted()
        failure?.let { throw it }
    }

    /**
     * Registers initial components for a type, including health and optional maturation.
     * Register authorization separately through validators. Existing definitions cannot be replaced.
     */
    fun registerDefinition(definition: CitadelDefinition) {
        require(definitions.find(definition.key) == null) { "Citadel definition already registered: ${definition.key}" }
        definitions.register(definition)
    }
}
