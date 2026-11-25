package ru.fornax.fornaxcitadel.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.addon.Addon
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.systems.action.ValidatorRegistration
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationService
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.modules.remove.RemoveService
import ru.fornax.fornaxcitadel.paper.command.CitadelCommand
import ru.fornax.fornaxcitadel.paper.config.PaperMessages
import ru.fornax.fornaxcitadel.paper.config.PaperSettings
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidRegistry
import ru.fornax.fornaxcitadel.paper.modules.acid.AcidService
import ru.fornax.fornaxcitadel.paper.modules.breaking.PaperBreakingService
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifierService
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.modifiers.NerfingNewcomersModifier
import ru.fornax.fornaxcitadel.paper.modules.dismantle.PaperDismantleService
import ru.fornax.fornaxcitadel.paper.modules.environment.PaperEnvironmentService
import ru.fornax.fornaxcitadel.paper.modules.explosion.PaperExplosionService
import ru.fornax.fornaxcitadel.paper.modules.health.PaperDamageValidator
import ru.fornax.fornaxcitadel.paper.modules.hpdisplay.CrackHealthObservationSystem
import ru.fornax.fornaxcitadel.paper.modules.permission.PaperPermissionValidator
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperPlacementService
import ru.fornax.fornaxcitadel.paper.modules.repair.PaperRepairService
import ru.fornax.fornaxcitadel.paper.modules.resolver.MaterialPlacementService
import ru.fornax.fornaxcitadel.paper.modules.upgrade.PaperUpgradeService
import ru.fornax.fornaxcitadel.paper.modules.validation.PaperActionStateValidator

/** Configures the gameplay catalog before chunk loading and owns its runtime registrations. */
class PaperAddon(
    plugin: Plugin,
    settings: PaperSettings,
    messages: PaperMessages,
    actions: ActionManager,
    citadels: CitadelFacade,
    definitions: CitadelDefinitionRegistry,
    health: HealthService,
    remove: RemoveService,
    materials: MaterialRegistry,
    materialPlacement: MaterialPlacementService,
    observation: ObservationService<Player>,
    save: () -> Unit
) : Addon {
    val citadelTypes = PaperCitadelTypes(settings.citadelTypes).apply { register(definitions, health) }
    val placement = PaperPlacementService(
        plugin, actions, citadels, health, citadelTypes, materials, settings.placement, messages
    )
    val dismantle = PaperDismantleService(
        plugin, actions, citadels, health, remove, citadelTypes, materials, settings.dismantle
    )
    val repair = PaperRepairService(actions, citadels, health, citadelTypes, materials)
    val upgrade = PaperUpgradeService(
        plugin, actions, citadels, definitions, health, citadelTypes, materials, settings.dismantle
    )
    /** Register modifiers for ordinary player damage on the main thread. */
    val playerDamage = PlayerDamageModifierService()
    private val breaking = PaperBreakingService(
        plugin, actions, citadels, remove, playerDamage, messages, settings.playerDamage.creativeModeEnabled
    )
    val acid = AcidService(
        plugin, actions, citadels, health.maturation, citadelTypes, materials, materialPlacement, breaking,
        AcidRegistry(settings.acid)
    )
    internal val commands = CitadelCommand(
        plugin, actions, citadels, health, materials, citadelTypes, messages, acid, save
    )

    init {
        for (type in citadelTypes.keys) {
            placement.registerMaterial(requireNotNull(citadelTypes.itemKey(type)), type)
        }
        if (settings.playerDamage.newcomersEnabled) {
            playerDamage.register(NerfingNewcomersModifier(
                settings.playerDamage.newcomerFirstJoin, settings.playerDamage.newcomerPlayTimeTicks
            ))
        }
    }

    private val services = listOf(
        ValidatorRegistration(actions, PaperActionStateValidator(plugin.server)),
        ValidatorRegistration(actions, PaperPermissionValidator(citadelTypes)),
        ValidatorRegistration(actions, PaperDamageValidator(citadels, citadelTypes, settings.playerDamage.creativeModeEnabled)),
        placement,
        dismantle,
        repair,
        upgrade,
        breaking,
        acid,
        PaperExplosionService(plugin, actions, citadels, remove, settings.explosionPowers),
        PaperEnvironmentService(plugin, citadels),
        object : LifecycleService {
            private val display = CrackHealthObservationSystem(plugin, health)
            override fun enable() = observation.registerSystem(display)
            override fun disable() {
                observation.unregisterSystem(display)
            }
        }
    )
    private var startedServices = 0

    override fun onEnable() {
        if (startedServices != 0) return
        try {
            for (service in services) {
                // A failed enable can leave subscriptions that its disable must release.
                startedServices++
                service.enable()
            }
        } catch (failure: Throwable) {
            try {
                onDisable()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    override fun onDisable() {
        var failure: Throwable? = null
        while (startedServices > 0) {
            val service = services[--startedServices]
            try {
                service.disable()
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup
                else if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
        failure?.let { throw it }
    }
}
