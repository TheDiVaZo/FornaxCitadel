package ru.fornax.fornaxcitadel.paper

import org.bukkit.command.PluginCommand
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
import ru.fornax.fornaxcitadel.paper.modules.chunks.PaperChunkService
import ru.fornax.fornaxcitadel.paper.modules.environment.PaperEnvironmentService
import ru.fornax.fornaxcitadel.paper.modules.explosion.PaperExplosionService
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperPlacementService
import ru.fornax.fornaxcitadel.paper.modules.permission.PaperPermissionService
import ru.fornax.fornaxcitadel.paper.modules.queue.PaperActionQueueService
import ru.fornax.fornaxcitadel.paper.scheduler.PaperScheduler
import ru.fornax.fornaxcitadel.paper.storage.PersistContainerChunkStorage
import java.util.concurrent.CompletionException
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
    private var componentManager: SimpleComponentManager? = null
    private var chunkBackend: PersistContainerChunkStorage? = null
    private var actionQueue: PaperActionQueueService? = null
    private var commandBinding: Pair<PluginCommand, CitadelCommand>? = null
    private val services = mutableListOf<LifecycleService>()

    override fun onEnable() {
        try {
            check(componentManager == null && chunkBackend == null && schedulerAdapter == null) {
                "Previous citadel storage shutdown has not completed"
            }
            saveDefaultConfig()
            val settings = PaperSettings.read(config)
            val types = TypeManager()
            val scheduler = PaperScheduler(this).also { schedulerAdapter = it }
            val backend = PersistContainerChunkStorage(this, scheduler).also { chunkBackend = it }
            val mapper = ChunkCordSnapshotMapper(types) { corrupted ->
                logger.log(Level.SEVERE, "Corrupt citadel component ${corrupted.key} in ${corrupted.location}", corrupted.cause)
            }
            // All PDC reads/writes and codec work finish on the main thread in this prototype.
            // Do not replace this executor with a worker pool without redesigning chunk unload.
            val storage = ChunkBackendLifecycleCoordinator(backend, scheduler, mapper, Executor { it.run() })
            val manager = SimpleComponentManager(storage, types).also { componentManager = it }
            citadelTypes = PaperCitadelTypes(settings.citadelTypes)
            val permissions = PaperPermissionValidator(server, citadelTypes)
            val module = CitadelModule(manager, RemovalPolicy { context, _ -> permissions.canAdminister(context) })
            citadelTypes.register(module)
            services.add(PaperPermissionService(module.actions, permissions))
            services.add(module)

            val chunks = PaperChunkService(this, manager, backend, settings.saveIntervalTicks).also(services::add)
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
            val command = requireNotNull(getCommand("citadel")) { "The citadel command is missing from plugin.yml" }
            services.forEach { it.enable() }
            commandBinding = command to commands
            command.setExecutor(commands)
            command.tabCompleter = commands
            PaperActionQueueService(this, module.actions, settings.postedActionLimit).also {
                actionQueue = it
                services.add(it)
                it.enable()
            }
        } catch (exception: Exception) {
            logger.log(Level.SEVERE, "Could not enable FornaxCitadel", exception)
            shutdown()
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() = shutdown()

    private fun shutdown() {
        actionQueue?.let(::disableService)
        actionQueue = null
        commandBinding?.let { (command, executor) ->
            if (command.executor === executor) command.setExecutor(null)
            if (command.tabCompleter === executor) command.tabCompleter = null
        }
        commandBinding = null
        for (service in services.asReversed()) {
            disableService(service)
        }
        services.clear()
        HandlerList.unregisterAll(this)
        server.scheduler.cancelTasks(this)
        try {
            schedulerAdapter?.close()
            schedulerAdapter = null
        } catch (exception: Exception) {
            logger.log(Level.SEVERE, "Could not close the citadel scheduler", exception)
        }
        try {
            closeStorage()
        } catch (exception: Exception) {
            // Keep manager/backend references so a later shutdown attempt can retry pending saves.
            logger.log(Level.SEVERE, "Could not save all citadel data during shutdown", exception)
        }
    }

    private fun disableService(service: LifecycleService) {
        try {
            service.disable()
        } catch (exception: Exception) {
            logger.log(Level.SEVERE, "Could not disable ${service.javaClass.simpleName}", exception)
        }
    }

    private fun closeStorage() {
        componentManager?.closeAndSaveAll()?.let { completion ->
            check(completion.isDone) { "Paper storage shutdown must complete on the main thread" }
            try {
                completion.join()
            } catch (failure: CompletionException) {
                throw (failure.cause as? Exception ?: failure)
            }
        }
        chunkBackend?.flushPendingWorldSaves()
        chunkBackend?.clearRegisteredChunks()
        componentManager = null
        chunkBackend = null
    }
}
