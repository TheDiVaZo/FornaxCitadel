package ru.fornax.fornaxcitadel.paper

import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.remove.RemovalPolicy
import ru.fornax.fornaxcitadel.paper.scheduler.PaperScheduler
import ru.fornax.fornaxcitadel.paper.storage.PaperChunkListener
import ru.fornax.fornaxcitadel.paper.storage.PersistContainerChunkStorage
import java.util.concurrent.Executor
import java.util.logging.Level

class FornaxCitadel : JavaPlugin() {
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
            val permissions = PaperPermissionValidator(server, settings)
            val module = CitadelModule(manager, RemovalPolicy { context, _ -> permissions.canAdminister(context) })
            PaperCitadelTypes.register(module, settings)
            module.actions.validatorCoordinator.registerValidator(permissions)

            val chunks = PaperChunkListener(this, manager, backend).also { chunkListener = it }
            val items = CitadelItems(this)
            val commands = CitadelCommand(this, manager, module, items, chunks::saveAll)
            requireNotNull(getCommand("citadel")) { "The citadel command is missing from plugin.yml" }.apply {
                setExecutor(commands)
                tabCompleter = commands
            }
            server.pluginManager.registerEvents(chunks, this)
            server.pluginManager.registerEvents(PaperPlayerListener(this, manager, module, items, settings.damagePerBreak), this)
            server.pluginManager.registerEvents(PaperEnvironmentListener(manager), this)
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
