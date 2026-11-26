package ru.fornax.fornaxcitadel.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.addon.CoreGameplay
import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationService
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
import ru.fornax.fornaxcitadel.paper.modules.placement.register
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperPlacementService
import ru.fornax.fornaxcitadel.paper.modules.repair.PaperRepairService
import ru.fornax.fornaxcitadel.paper.modules.resolver.MaterialPlacementService
import ru.fornax.fornaxcitadel.paper.modules.upgrade.PaperUpgradeService
import ru.fornax.fornaxcitadel.paper.modules.validation.PaperActionStateValidator

/** Registers the catalog and actions at construction and owns Paper gameplay during plugin activation. */
class PaperGameplay(
    plugin: Plugin,
    settings: PaperSettings,
    messages: PaperMessages,
    actions: ActionFacade,
    citadels: CitadelFacade,
    coreGameplay: CoreGameplay,
    materials: MaterialRegistry,
    materialPlacement: MaterialPlacementService,
    private val observation: ObservationService<Player>,
    save: () -> Unit
) {
    private val definitions = coreGameplay.definitions

    init {
        settings.citadelTypes.register(coreGameplay)
        actions.registerValidator(PaperActionStateValidator(plugin.server))
        actions.registerValidator(PaperPermissionValidator(coreGameplay.health))
        actions.registerValidator(PaperDamageValidator(citadels, coreGameplay.health, settings.playerDamage.creativeModeEnabled))
    }

    val placement = PaperPlacementService(
        plugin, actions, citadels, coreGameplay.health, definitions, materials, settings.placement, messages
    )
    val dismantle = PaperDismantleService(
        plugin, actions, citadels, coreGameplay.health, coreGameplay.remove,
        definitions, materials, settings.dismantle
    )
    val repair = PaperRepairService(actions, citadels, coreGameplay.health, definitions, materials)
    val upgrade = PaperUpgradeService(
        plugin, actions, citadels, coreGameplay.definitions, coreGameplay.health,
        materials, settings.dismantle
    )
    /** Register modifiers for ordinary player damage on the main thread. */
    val playerDamage = PlayerDamageModifierService()
    private val breaking = PaperBreakingService(
        plugin, actions, citadels, coreGameplay.remove, playerDamage, messages,
        settings.playerDamage.creativeModeEnabled
    )
    val acid = AcidService(
        plugin, actions, citadels, coreGameplay.health.maturation, definitions, materials,
        materialPlacement, breaking, AcidRegistry(settings.acid)
    )
    private val explosion = PaperExplosionService(plugin, actions, citadels, coreGameplay.remove, settings.explosionPowers)
    private val environment = PaperEnvironmentService(plugin, citadels)
    private val display = CrackHealthObservationSystem(plugin, coreGameplay.health)
    internal val commands = CitadelCommand(
        plugin, actions, citadels, coreGameplay.health, materials, definitions, messages, acid, save
    )

    init {
        if (settings.playerDamage.newcomersEnabled) {
            playerDamage.register(NerfingNewcomersModifier(
                settings.playerDamage.newcomerFirstJoin, settings.playerDamage.newcomerPlayTimeTicks
            ))
        }
    }

    private enum class State { STOPPED, STARTING, RUNNING, STOPPING }
    private var state = State.STOPPED

    fun onEnable() {
        if (state == State.RUNNING) return
        check(state == State.STOPPED) { "Paper gameplay is changing state" }
        state = State.STARTING
        try {
            placement.enable()
            dismantle.enable()
            repair.enable()
            upgrade.enable()
            breaking.enable()
            acid.enable()
            explosion.enable()
            environment.enable()
            observation.registerSystem(display)
            state = State.RUNNING
        } catch (failure: Throwable) {
            stopServices(failure)
            throw failure
        }
    }

    fun onDisable() {
        if (state == State.STOPPED) return
        check(state == State.RUNNING) { "Paper gameplay is changing state" }
        stopServices(null)?.let { throw it }
    }

    private fun stopServices(initialFailure: Throwable?): Throwable? {
        state = State.STOPPING
        var failure = initialFailure
        fun stop(action: () -> Unit) {
            try {
                action()
            } catch (cleanup: Throwable) {
                val first = failure
                if (first == null) failure = cleanup
                else if (first !== cleanup) first.addSuppressed(cleanup)
            }
        }
        stop { observation.unregisterSystem(display) }
        stop { environment.disable() }
        stop { explosion.disable() }
        stop { acid.disable() }
        stop { breaking.disable() }
        stop { upgrade.disable() }
        stop { repair.disable() }
        stop { dismantle.disable() }
        stop { placement.disable() }
        state = State.STOPPED
        return failure
    }
}
