package ru.fornax.fornaxcitadel.paper

import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.fornax.fornaxcitadel.addon.Addon
import ru.fornax.fornaxcitadel.addon.CoreGameplay
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.facade.ActionManager
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.facade.ObservationFacade
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationService
import ru.fornax.fornaxcitadel.modules.queue.ActionQueueService
import ru.fornax.fornaxcitadel.modules.queue.ActionSettings
import ru.fornax.fornaxcitadel.paper.command.CitadelCommand
import ru.fornax.fornaxcitadel.paper.command.CitadelCommandRegistration
import ru.fornax.fornaxcitadel.paper.config.PaperMessages
import ru.fornax.fornaxcitadel.paper.config.PaperSettingsReader
import ru.fornax.fornaxcitadel.paper.material.CombineMaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.MaterialFacade
import ru.fornax.fornaxcitadel.paper.modules.chunks.PaperChunkService
import ru.fornax.fornaxcitadel.paper.modules.observation.Full3DObservation
import ru.fornax.fornaxcitadel.paper.modules.observation.PaperObservationService
import ru.fornax.fornaxcitadel.paper.modules.placement.register
import ru.fornax.fornaxcitadel.paper.modules.resolver.MaterialPlacementService
import ru.fornax.fornaxcitadel.paper.scheduler.PaperScheduler
import ru.fornax.fornaxcitadel.paper.storage.PersistContainerChunkStorage
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.logging.Level

class FornaxCitadel : JavaPlugin() {
    /** Published through Bukkit's services manager after startup for use on the server's main thread. */
    lateinit var api: FornaxCitadelAPI
        private set
    private lateinit var paperGameplay: PaperGameplay
    private var schedulerAdapter: PaperScheduler? = null
    private var citadelStorage: CitadelStorage? = null
    private var chunkBackend: PersistContainerChunkStorage? = null
    private var chunkService: PaperChunkService? = null
    private var actionQueue: PluginActionQueue? = null
    private var observationService: PaperObservationService? = null
    private var commandRegistration: CitadelCommandRegistration? = null

    override fun onEnable() {
        try {
            check(citadelStorage == null && chunkBackend == null && schedulerAdapter == null) {
                "Previous citadel storage shutdown has not completed"
            }
            saveDefaultConfig()
            val settings = PaperSettingsReader.read(config)
            settings.acidConfigurationError?.let { logger.severe("Acid is disabled because its configuration is invalid: $it") }
            val messages = PaperMessages.load(this)
            val scheduler = PaperScheduler(this).also { schedulerAdapter = it }
            val backend = PersistContainerChunkStorage(this, scheduler).also { chunkBackend = it }
            val types = TypeManager()
            val mapper = ChunkCordSnapshotMapper(types) { corrupted ->
                logger.log(Level.SEVERE, "Corrupt citadel component ${corrupted.key} in ${corrupted.location}", corrupted.cause)
            }
            // All PDC reads/writes and codec work finish on the main thread in this prototype.
            // Do not replace this executor with a worker pool without redesigning chunk unload.
            val storage = ChunkBackendLifecycleCoordinator(backend, scheduler, mapper, Executor { it.run() })
                .also { citadelStorage = it }
            val manager = ComponentManager(storage, types)
            val materials = CombineMaterialRegistry()
            val materialPlacement = MaterialPlacementService(server, materials, manager::isChunkLoaded)
            val actionManager = ActionManager()
            val actions = ActionFacade(actionManager)
            val citadels = CitadelFacade(manager, materialPlacement)
            val coreGameplay = CoreGameplay(citadels, actions)
            settings.citadelTypes.register(coreGameplay)
            val actionSettings = ActionSettings(maxActionsPerTick = settings.postedActionLimit) { failure ->
                logger.log(Level.SEVERE, "Queued citadel action failed; remaining actions stay queued", failure)
            }
            val queue = PluginActionQueue(ActionQueueService(actions, scheduler, actionSettings), actionManager)
                .also { actionQueue = it }
            val chunks = PaperChunkService(this, manager, backend, settings.saveIntervalTicks).also { chunkService = it }
            val observation = ObservationService<Player>(manager, scheduler, settings.observation.intervalTicks) { failure ->
                logger.log(Level.SEVERE, "Citadel observation failed", failure)
            }
            paperGameplay = PaperGameplay(
                this, settings, messages, actions, citadels,
                coreGameplay.definitions, coreGameplay.healthFacade, coreGameplay.maturationFacade,
                materials, materialPlacement, observation
            )
            val observationSource = Full3DObservation(this, actions, citadels, chunks,
                settings.observation.full3D)
            val observers = PaperObservationService(this, observation, observationSource).also { observationService = it }
            val command = CitadelCommand(
                this, actions, citadels, coreGameplay.healthFacade, materials, coreGameplay.definitions,
                messages, paperGameplay.acid, chunks::saveAll
            )
            val commands = CitadelCommandRegistration(::getCommand, command, messages)
                .also { commandRegistration = it }

            chunks.enableWithAddons(server.pluginManager.plugins, citadels, coreGameplay)
            paperGameplay.onEnable()
            queue.enable()
            observers.enable()
            commands.enable()
            api = FornaxCitadelAPI(
                citadel = citadels,
                action = actions,
                resolver = materialPlacement,
                observation = ObservationFacade(observation),
                materials = MaterialFacade(materials, materialPlacement, paperGameplay.placement),
                playerDamage = paperGameplay.playerDamage,
                definitions = coreGameplay.definitions,
                health = coreGameplay.healthFacade,
                maturation = coreGameplay.maturationFacade
            )
            server.servicesManager.register(FornaxCitadelAPI::class.java, api, this, ServicePriority.Normal)
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "Could not enable FornaxCitadel", failure)
            onDisable()
            if (failure !is Exception) throw failure
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        stop("the Bukkit API service") {
            if (::api.isInitialized) server.servicesManager.unregister(FornaxCitadelAPI::class.java, api)
        }
        stop("citadel commands") { commandRegistration?.disable() }
        commandRegistration = null
        stop("player observation") { observationService?.disable() }
        observationService = null
        stop("the action queue") { actionQueue?.disable() }
        actionQueue = null
        stop("the Paper addon") {
            if (::paperGameplay.isInitialized) paperGameplay.onDisable()
        }
        stop("the chunk service") {
            chunkService?.disable()
            chunkService = null
        }
        stop("Bukkit listeners") { HandlerList.unregisterAll(this) }
        stop("Bukkit tasks") { server.scheduler.cancelTasks(this) }
        stop("the citadel scheduler") {
            schedulerAdapter?.close()
            schedulerAdapter = null
        }
        try {
            closeStorage()
        } catch (exception: Exception) {
            // Keep storage/backend references so a later shutdown attempt can retry pending saves.
            logger.log(Level.SEVERE, "Could not save all citadel data during shutdown", exception)
        }
    }

    private inline fun stop(name: String, operation: () -> Unit) {
        try {
            operation()
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "Could not stop $name", failure)
        }
    }

    private fun closeStorage() {
        citadelStorage?.closeAndSaveAll()?.let { completion ->
            check(completion.isDone) { "Paper storage shutdown must complete on the main thread" }
            try {
                completion.join()
            } catch (failure: CompletionException) {
                throw (failure.cause as? Exception ?: failure)
            }
        }
        chunkBackend?.flushPendingWorldSaves()
        chunkBackend?.clearRegisteredChunks()
        citadelStorage = null
        chunkBackend = null
    }
}

internal fun PaperChunkService.enableWithAddons(plugins: Array<out Plugin>, citadels: CitadelFacade, core: CoreGameplay) {
    // Dependent addons have completed onLoad but may still be waiting for onEnable.
    for (addon in plugins.filterIsInstance<Addon>()) {
        for (component in addon.defaultComponents()) {
            citadels.registerComponent(component.key, component.componentClass)
        }
        addon.registerDefinitions(core.definitions, core.healthFacade, core.maturationFacade)
    }
    enable()
}

/** Discards pending actions when the plugin stops. The queue itself can be paused independently. */
internal class PluginActionQueue(
    private val queue: ActionQueueService,
    private val actionManager: ActionManager
) : LifecycleService {
    override fun enable() {
        try {
            queue.enable()
        } catch (failure: Throwable) {
            actionManager.clearPosted()
            throw failure
        }
    }

    override fun disable() {
        try {
            queue.disable()
        } finally {
            actionManager.clearPosted()
        }
    }
}
