package ru.fornax.fornaxcitadel.paper

import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.systems.observation.ObservationService
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.queue.ActionSettings
import ru.fornax.fornaxcitadel.paper.command.CitadelCommandRegistration
import ru.fornax.fornaxcitadel.paper.config.PaperMessages
import ru.fornax.fornaxcitadel.paper.config.PaperSettings
import ru.fornax.fornaxcitadel.paper.material.CombineMaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.chunks.PaperChunkService
import ru.fornax.fornaxcitadel.paper.modules.observation.Full3DObservation
import ru.fornax.fornaxcitadel.paper.modules.observation.PaperObservationService
import ru.fornax.fornaxcitadel.paper.modules.resolver.MaterialPlacementService
import ru.fornax.fornaxcitadel.paper.scheduler.PaperScheduler
import ru.fornax.fornaxcitadel.paper.storage.PersistContainerChunkStorage
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.logging.Level

class FornaxCitadel : JavaPlugin() {
    lateinit var paperAddon: PaperAddon
        private set
    /**
     * Register external material providers on the server's main thread after enable.
     * Configured item and block keys resolve on use, so providers may register later.
     */
    lateinit var materials: CombineMaterialRegistry
        private set
    /** Register block placement/protection rules on the main thread after enable. */
    lateinit var materialPlacement: MaterialPlacementService
        private set
    /** Register presentation systems on the main thread after enable. Players are observed automatically. */
    lateinit var observation: ObservationService<Player>
        private set
    private var schedulerAdapter: PaperScheduler? = null
    private var citadelStorage: CitadelStorage? = null
    private var chunkBackend: PersistContainerChunkStorage? = null
    private val shutdownSteps = ArrayDeque<() -> Unit>()

    override fun onEnable() {
        try {
            check(citadelStorage == null && chunkBackend == null && schedulerAdapter == null) {
                "Previous citadel storage shutdown has not completed"
            }
            saveDefaultConfig()
            val settings = PaperSettings.read(config)
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
            materials = CombineMaterialRegistry()
            materialPlacement = MaterialPlacementService(server, materials, manager)
            val actionSettings = ActionSettings(maxActionsPerTick = settings.postedActionLimit) { failure ->
                logger.log(Level.SEVERE, "Queued citadel action failed; remaining actions stay queued", failure)
            }
            val module = CitadelModule(manager, scheduler, materialPlacement, actionSettings)
            val chunks = PaperChunkService(this, manager, backend, settings.saveIntervalTicks)
            observation = ObservationService(manager, scheduler, settings.observation.intervalTicks) { failure ->
                logger.log(Level.SEVERE, "Citadel observation failed", failure)
            }
            paperAddon = PaperAddon(
                this, settings, messages, module.actions, module.citadels, module.definitions,
                module.coreAddon.health, module.coreAddon.remove, materials, materialPlacement, observation, chunks::saveAll
            )
            val observationSource = Full3DObservation(this, module.actions, module.citadels, chunks,
                settings.observation.full3D)
            val observers = PaperObservationService(this, observation, observationSource)
            val commands = CitadelCommandRegistration(::getCommand, paperAddon.commands, messages)

            start(chunks::enable, chunks::disable)
            start(paperAddon::onEnable, paperAddon::onDisable)
            start(module.coreAddon::onEnable, module.coreAddon::onDisable)
            start(module::enable, module::disable)
            start(observers::enable, observers::disable)
            start(commands::enable, commands::disable)
        } catch (exception: Exception) {
            logger.log(Level.SEVERE, "Could not enable FornaxCitadel", exception)
            shutdown()
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() = shutdown()

    private fun start(enable: () -> Unit, disable: () -> Unit) {
        shutdownSteps.addFirst(disable)
        enable()
    }

    private fun shutdown() {
        while (shutdownSteps.isNotEmpty()) {
            try {
                shutdownSteps.removeFirst().invoke()
            } catch (exception: Exception) {
                logger.log(Level.SEVERE, "Could not stop a citadel service", exception)
            }
        }
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
            // Keep storage/backend references so a later shutdown attempt can retry pending saves.
            logger.log(Level.SEVERE, "Could not save all citadel data during shutdown", exception)
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
