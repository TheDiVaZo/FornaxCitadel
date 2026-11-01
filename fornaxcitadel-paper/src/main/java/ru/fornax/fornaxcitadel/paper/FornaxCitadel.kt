package ru.fornax.fornaxcitadel.paper

import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
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
import ru.fornax.fornaxcitadel.paper.modules.permission.PaperPermissionService
import ru.fornax.fornaxcitadel.paper.modules.queue.PaperActionQueueService
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
    private val services = mutableListOf<LifecycleService>()

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
            services.add(PaperPermissionService(module.actions, permissions))
            services.add(module)

            val chunks = PaperChunkListener(this, manager, backend).also { chunkListener = it }
            val items = CitadelItems(this, citadelTypes)
            materials = CombineMaterialRegistry("fornaxcitadel" to items)
            placement = PaperPlacementService(this, module, materials, settings.placement).also(services::add)
            citadelTypes.keys.forEach { type ->
                placement.registerMaterial(requireNotNull(citadelTypes.itemKey(type)), type)
            }
            playerDamage = PlayerDamageModifierService().apply {
                if (settings.playerDamage.newcomersEnabled) {
                    register(NerfingNewcomersModifier(
                        settings.playerDamage.newcomerFirstJoin,
                        settings.playerDamage.newcomerPlayTimeTicks
                    ))
                }
                if (settings.playerDamage.creativeModeEnabled) register(CreativeModeDamageModifier())
            }
            services.add(PaperBreakingService(this, module, playerDamage))
            services.add(PaperExplosionService(this, module, settings.explosionPowers))
            services.add(PaperEnvironmentService(this, module))

            val commands = CitadelCommand(this, manager, module, materials, citadelTypes, chunks::saveAll)
            requireNotNull(getCommand("citadel")) { "The citadel command is missing from plugin.yml" }.apply {
                setExecutor(commands)
                tabCompleter = commands
            }
            services.forEach { it.enable() }
            server.pluginManager.registerEvents(chunks, this)
            chunks.loadAlreadyLoadedChunks()

            PaperActionQueueService(this, module.actions, settings.postedActionLimit).also {
                services.add(it)
                it.enable()
            }
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
        server.scheduler.cancelTasks(this)
        for (service in services.asReversed()) {
            try {
                service.disable()
            } catch (exception: Exception) {
                logger.log(Level.SEVERE, "Could not disable ${service.javaClass.simpleName}", exception)
            }
        }
        services.clear()
        HandlerList.unregisterAll(this)
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
