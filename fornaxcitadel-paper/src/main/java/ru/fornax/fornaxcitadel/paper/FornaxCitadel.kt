package ru.fornax.fornaxcitadel.paper

import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.remove.RemovalPolicy
import ru.fornax.fornaxcitadel.paper.material.CitadelItems
import ru.fornax.fornaxcitadel.paper.material.CombineMaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.breaking.PaperBreakingService
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifierService
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.modifiers.CreativeModeDamageModifier
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.modifiers.NerfingNewcomersModifier
import ru.fornax.fornaxcitadel.paper.modules.environment.PaperEnvironmentService
import ru.fornax.fornaxcitadel.paper.modules.explosion.PaperExplosionService
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperPlacementService
import ru.fornax.fornaxcitadel.paper.scheduler.PaperScheduler
import ru.fornax.fornaxcitadel.paper.storage.PaperChunkListener
import ru.fornax.fornaxcitadel.paper.storage.PersistContainerChunkStorage
import java.util.concurrent.Executor
import java.util.logging.Level

class FornaxCitadel : JavaPlugin() {
    /** Register material providers and placement bindings on the server's main thread after enable. */
    lateinit var materials: CombineMaterialRegistry
        private set
    lateinit var placement: PaperPlacementService
        private set
    lateinit var citadelTypes: PaperCitadelTypes
        private set
    /** Additional player damage modifiers can be registered on the main thread after enable. */
    lateinit var playerDamage: PlayerDamageModifierService
        private set
    private var schedulerAdapter: PaperScheduler? = null
    private var chunkListener: PaperChunkListener? = null

    override fun onEnable() {
        try {
            saveDefaultConfig()
            val settings = PaperSettings.read(config)
            val types = TypeManager()
            val scheduler = PaperScheduler(this).also { schedulerAdapter = it }
            val backend = PersistContainerChunkStorage(this, scheduler)
            val mapper = ChunkCordSnapshotMapper(types) { corrupted ->
                logger.log(Level.SEVERE, "Corrupt citadel component ${corrupted.key} in ${corrupted.location}", corrupted.cause)
            }
            // All PDC reads/writes and codec work finish on the main thread in this prototype.
            // Do not replace this executor with a worker pool without redesigning chunk unload.
            val storage = ChunkBackendLifecycleCoordinator(backend, scheduler, mapper, Executor { it.run() })
            val manager = SimpleComponentManager(storage, types)
            citadelTypes = PaperCitadelTypes(settings.citadelTypes)
            val permissions = PaperPermissionValidator(server, citadelTypes)
            val module = CitadelModule(manager, RemovalPolicy { context, _ -> permissions.canAdminister(context) })
            citadelTypes.register(module)
            module.actions.validatorCoordinator.registerValidator(permissions)

            val chunks = PaperChunkListener(this, manager, backend).also { chunkListener = it }
            val items = CitadelItems(this, citadelTypes)
            materials = CombineMaterialRegistry("fornaxcitadel" to items)
            placement = PaperPlacementService(materials, settings.placement)
            citadelTypes.keys.forEach { type ->
                placement.registerMaterial(requireNotNull(citadelTypes.itemKey(type)), type)
            }
            val commands = CitadelCommand(this, manager, module, materials, citadelTypes, chunks::saveAll)
            requireNotNull(getCommand("citadel")) { "The citadel command is missing from plugin.yml" }.apply {
                setExecutor(commands)
                tabCompleter = commands
            }
            server.pluginManager.registerEvents(chunks, this)
            placement.init(this, module)
            playerDamage = PlayerDamageModifierService().apply {
                if (settings.playerDamage.newcomersEnabled) {
                    register(NerfingNewcomersModifier(
                        settings.playerDamage.newcomerFirstJoin,
                        settings.playerDamage.newcomerPlayTimeTicks
                    ))
                }
                if (settings.playerDamage.creativeModeEnabled) register(CreativeModeDamageModifier())
            }
            PaperBreakingService(playerDamage).init(this, module)
            PaperExplosionService(settings.explosionPowers).init(this, module)
            PaperEnvironmentService().init(this, module)
            chunks.loadAlreadyLoadedChunks()

            server.scheduler.runTaskTimer(this, Runnable {
                try {
                    module.actions.drainPosted(settings.postedActionLimit)
                } catch (exception: Exception) {
                    logger.log(Level.SEVERE, "Queued citadel action failed; remaining actions stay queued", exception)
                }
            }, 1L, 1L)
            server.scheduler.runTaskTimer(this, Runnable {
                try {
                    chunks.saveAll()
                } catch (exception: Exception) {
                    logger.log(Level.SEVERE, "Could not save citadel data to loaded chunks", exception)
                }
            }, settings.saveIntervalTicks, settings.saveIntervalTicks)
        } catch (exception: Exception) {
            logger.log(Level.SEVERE, "Could not enable FornaxCitadel", exception)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        HandlerList.unregisterAll(this)
        server.scheduler.cancelTasks(this)
        try {
            schedulerAdapter?.close()
        } finally {
            try {
                chunkListener?.closeAndSaveAll()
            } catch (exception: Exception) {
                logger.log(Level.SEVERE, "Could not save all citadel data during shutdown", exception)
            } finally {
                schedulerAdapter = null
                chunkListener = null
            }
        }
    }
}
