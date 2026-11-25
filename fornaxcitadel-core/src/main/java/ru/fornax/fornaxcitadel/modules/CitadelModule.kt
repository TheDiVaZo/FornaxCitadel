package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.addon.CoreAddon
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolver
import ru.fornax.fornaxcitadel.core.resolver.DirectCitadelResolver
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.modules.queue.ActionQueueService
import ru.fornax.fornaxcitadel.modules.queue.ActionSettings

/**
 * Provides the shared action and component infrastructure on the owning game thread.
 * Construction creates [coreAddon] and registers its types through [componentManager] before chunks are loaded.
 *
 * Register definitions before loading chunks and call [CoreAddon.onEnable] to install core gameplay.
 * Submit external requests through [actions]. [enable] starts the shared queue for all addons.
 * [disable] stops the queue and discards pending actions.
 */
class CitadelModule @JvmOverloads constructor(
    val componentManager: ComponentManager,
    scheduler: Scheduler,
    resolver: CitadelResolver = DirectCitadelResolver(componentManager),
    actionSettings: ActionSettings = ActionSettings()
) : LifecycleService {
    val definitions = CitadelDefinitionRegistry()
    val citadels = CitadelFacade(componentManager, resolver)
    val actions = ActionManager()
    val actionQueue = ActionQueueService(actions, scheduler, actionSettings)
    val coreAddon = CoreAddon(this)

    override fun enable() {
        try {
            actionQueue.enable()
        } catch (failure: Throwable) {
            actions.clearPosted()
            throw failure
        }
    }

    override fun disable() {
        try {
            actionQueue.disable()
        } finally {
            actions.clearPosted()
        }
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
