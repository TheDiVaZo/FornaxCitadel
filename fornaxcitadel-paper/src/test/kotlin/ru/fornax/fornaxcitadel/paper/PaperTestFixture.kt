package ru.fornax.fornaxcitadel.paper

import org.bukkit.Chunk
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.Statistic
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.RegisteredListener
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.storage.DBStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.breaking.BreakPolicy
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemovalPolicy
import ru.fornax.fornaxcitadel.paper.material.CitadelItems
import ru.fornax.fornaxcitadel.paper.material.CombineMaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.breaking.PaperBreakingListener
import ru.fornax.fornaxcitadel.paper.modules.breaking.PaperBreakingService
import ru.fornax.fornaxcitadel.paper.modules.breaking.damage.PlayerDamageModifierService
import ru.fornax.fornaxcitadel.paper.modules.environment.PaperEnvironmentListener
import ru.fornax.fornaxcitadel.paper.modules.environment.PaperEnvironmentService
import ru.fornax.fornaxcitadel.paper.modules.explosion.PaperExplosionListener
import ru.fornax.fornaxcitadel.paper.modules.explosion.PaperExplosionService
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionPowerSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperPlacementListener
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperPlacementService
import ru.fornax.fornaxcitadel.paper.modules.placement.PlacementSettings
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Real core lifecycle with a deliberately small Bukkit interface boundary. */
internal class PaperTestFixture(
    load: Boolean = true,
    permissions: Boolean = false,
    registerTypes: Boolean = true,
    breakPolicy: BreakPolicy? = null,
    initializeSystems: Boolean = true,
    explosionPowers: ExplosionPowerSettings = ExplosionPowerSettings(),
    placementSettings: PlacementSettings = PlacementSettings(),
    citadelSettings: CitadelTypesSettings = CitadelTypesSettings(linkedMapOf(
        "reinforced" to CitadelTypeSettings(PaperCitadelTypes.REINFORCED, health = HealthBehaviorSettings.Simple(100.0)),
        "maturing" to CitadelTypeSettings(PaperCitadelTypes.MATURING, health = HealthBehaviorSettings.Maturing(10.0, 40.0, 600)),
        "invulnerable" to CitadelTypeSettings(PaperCitadelTypes.INVULNERABLE, breakPolicy = CitadelBreakPolicy.UNBREAKABLE)
    ))
) {
    val worldId = UUID(1L, 2L)
    val position = CitadelPosition(worldId, 1, 64, 2)
    var mainThread = true
    var enabled = true
    var worldChunkLoaded = true
    var worldAvailable = true
    var worldSaveCount = 0
    var pdcAccesses = 0
    var freshChunkWrappers = false
    var vanillaBlockChanges = 0
    var blockLookups = 0
    val scheduledTasks = mutableListOf<TestScheduledTask>()
    val listenerRegistrations = mutableListOf<Pair<Listener, Plugin>>()
    val players = mutableMapOf<UUID, TestPlayer>()
    private val blocks = mutableMapOf<Triple<Int, Int, Int>, Block>()
    private val blockMaterials = mutableMapOf<Triple<Int, Int, Int>, Material>()
    val chunkData = TestPersistentData()
    val chunk: Chunk by lazy { chunkWrapper() }
    val world: World = paperProxy("world") { method, args ->
        when (method.name) {
            "getUID" -> worldId
            "getName" -> "test_world"
            "getMinHeight" -> -64
            "getMaxHeight" -> 320
            "isChunkLoaded" -> worldChunkLoaded && args[0] == 0 && args[1] == 0
            "getLoadedChunks" -> if (worldChunkLoaded) arrayOf(if (freshChunkWrappers) chunkWrapper() else chunk) else emptyArray<Chunk>()
            "save" -> { worldSaveCount++; null }
            "getBlockAt" -> {
                blockLookups++
                if (args.size == 3) block(args[0] as Int, args[1] as Int, args[2] as Int)
                else (args[0] as Location).let { block(it.blockX, it.blockY, it.blockZ) }
            }
            "getChunkAt" -> error("Tests must never auto-load a Bukkit chunk")
            else -> unsupported(method)
        }
    }
    val server: Server = paperProxy("server") { method, args ->
        when (method.name) {
            "getWorld" -> world.takeIf { worldAvailable && (args[0] == worldId || args[0] == "test_world") }
            "getWorlds" -> if (worldAvailable) listOf(world) else emptyList<World>()
            "getPlayer", "getPlayerExact" -> if (args[0] is UUID) players[args[0]]?.player
                else players.values.firstOrNull { it.name == args[0] }?.player
            "isPrimaryThread" -> mainThread
            "getLogger" -> Logger.getLogger("PaperTest")
            "getName", "getVersion", "getBukkitVersion" -> "PaperTest"
            "getScheduler" -> bukkitScheduler
            "getPluginManager" -> pluginManager
            else -> unsupported(method)
        }
    }
    private val bukkitScheduler: BukkitScheduler = paperProxy("scheduler") { method, args ->
        when (method.name) {
            "runTask" -> TestScheduledTask(args[1] as Runnable).also(scheduledTasks::add).task
            else -> unsupported(method)
        }
    }
    private val pluginManager: PluginManager = paperProxy("plugin manager") { method, args ->
        when (method.name) {
            "registerEvents" -> {
                listenerRegistrations.add((args[0] as Listener) to (args[1] as Plugin))
                null
            }
            else -> unsupported(method)
        }
    }
    val plugin: Plugin = paperProxy("plugin") { method, _ ->
        when (method.name) {
            "getName" -> "FornaxCitadel"
            "namespace" -> "fornaxcitadel"
            "getServer" -> server
            "getLogger" -> Logger.getLogger("PaperTest")
            "isEnabled" -> enabled
            else -> unsupported(method)
        }
    }
    val types = TypeManager()
    val backend = MemoryBackend()
    val storage = ChunkBackendLifecycleCoordinator(backend, DirectScheduler, ChunkCordSnapshotMapper(types), Executor(Runnable::run))
    val manager = SimpleComponentManager(storage, types)
    val module = CitadelModule(manager, RemovalPolicy { context, _ ->
        context.actorId?.let(players::get)?.let { it.online && "fornaxcitadel.admin" in it.permissions } == true
    })
    val citadelTypes = PaperCitadelTypes(citadelSettings)
    val items = CitadelItems(plugin, citadelTypes)
    val materials = CombineMaterialRegistry("fornaxcitadel" to items)
    val placementService = PaperPlacementService(materials, placementSettings).also { service ->
        citadelTypes.keys.forEach { definition ->
            service.registerMaterial(checkNotNull(citadelTypes.itemKey(definition)), definition)
        }
    }
    val playerDamage = PlayerDamageModifierService()
    val breakingService = PaperBreakingService(playerDamage)
    val explosionService = PaperExplosionService(explosionPowers)
    val environmentService = PaperEnvironmentService()
    val placementListener get() = listenerRegistrations.map { it.first }.filterIsInstance<PaperPlacementListener>().single()
    val breakingListener get() = listenerRegistrations.map { it.first }.filterIsInstance<PaperBreakingListener>().single()
    val explosionListener get() = listenerRegistrations.map { it.first }.filterIsInstance<PaperExplosionListener>().single()
    val environmentListener get() = listenerRegistrations.map { it.first }.filterIsInstance<PaperEnvironmentListener>().single()
    val installer = player(UUID(3L, 4L), "installer")
    val stranger = player(UUID(5L, 6L), "stranger")

    init {
        if (registerTypes) {
            if (breakPolicy == null) {
                citadelTypes.register(module)
            } else {
                val reinforced = citadelSettings.types.getValue("reinforced")
                module.registerDefinition(CitadelDefinition(PaperCitadelTypes.REINFORCED, emptyList()),
                    breakPolicy, reinforced.health?.createBehavior())
                val remaining = citadelSettings.types.filterKeys { it != "reinforced" }
                if (remaining.isNotEmpty()) PaperCitadelTypes(CitadelTypesSettings(remaining)).register(module)
            }
        }
        if (permissions) module.actions.validatorCoordinator.registerValidator(PaperPermissionValidator(server, citadelTypes))
        if (initializeSystems) {
            placementService.init(plugin, module)
            breakingService.init(plugin, module)
            explosionService.init(plugin, module)
            environmentService.init(plugin, module)
        }
        if (load) manager.loadChunk(worldId, 0, 0).join()
    }

    fun player(id: UUID, name: String): TestPlayer = TestPlayer(id, name, world).also { players[id] = it }

    fun chunkWrapper(): Chunk = paperProxy("chunk wrapper") { method, _ ->
        when (method.name) {
            "getWorld" -> world
            "getX", "getZ" -> 0
            "isLoaded" -> worldChunkLoaded
            "getPersistentDataContainer" -> { pdcAccesses++; chunkData.container }
            else -> unsupported(method)
        }
    }

    fun block(x: Int = position.x, y: Int = position.y, z: Int = position.z): Block {
        val key = Triple(x, y, z)
        return blocks.getOrPut(key) {
            paperProxy("block $key") { method, args ->
                when (method.name) {
                    "getWorld" -> world
                    "getX" -> x
                    "getY" -> y
                    "getZ" -> z
                    "getType" -> blockMaterials[key] ?: Material.STONE
                    "getBlockData" -> paperProxy<Directional>("piston facing") { property, _ ->
                        if (property.name == "getFacing") BlockFace.SOUTH else unsupported(property)
                    }
                    "isEmpty", "isLiquid" -> false
                    "getChunk" -> chunk
                    "getLocation" -> Location(world, x.toDouble(), y.toDouble(), z.toDouble())
                    "getRelative" -> if (args[0] is BlockFace) {
                        val direction = args[0] as BlockFace
                        val distance = if (args.size == 2) args[1] as Int else 1
                        block(x + direction.modX * distance, y + direction.modY * distance, z + direction.modZ * distance)
                    } else block(x + args[0] as Int, y + args[1] as Int, z + args[2] as Int)
                    "setType" -> { vanillaBlockChanges++; blockMaterials[key] = args[0] as Material; null }
                    else -> unsupported(method)
                }
            }
        }
    }

    fun material(material: Material, position: CitadelPosition = this.position) {
        blockMaterials[Triple(position.x, position.y, position.z)] = material
    }

    fun place(type: Key = PaperCitadelTypes.REINFORCED) {
        assertEquals(ActionResult.SUCCESS, module.actions.execute(PlaceAction(context(installer), position, type)))
    }

    fun context(player: TestPlayer) = ActionContext(player.id, Key("test", "player"))
    fun health(): Double = assertNotNull(manager.findCitadel(worldId, position.x, position.y, position.z))
        .getComponent(Health::class.java)!!.health

    fun markedStack(type: Key = PaperCitadelTypes.REINFORCED, amount: Int = 2): TestItemStack =
        TestItemStack(Material.AMETHYST_SHARD, amount).apply {
            data.values[NamespacedKey(plugin, "citadel_type")] = type.asString()
        }

    fun runScheduledTasks() {
        mainThread = true
        val batch = scheduledTasks.toList()
        scheduledTasks.clear()
        batch.filterNot { it.cancelled }.forEach { it.action.run() }
    }
}

internal class TestPlayer(val id: UUID, val name: String, private val world: World) {
    var online = true
    var gameMode = GameMode.SURVIVAL
    var firstPlayed = 0L
    var playTimeTicks = 0
    var actionBarFailure: RuntimeException? = null
    var held: ItemStack = TestItemStack(Material.AIR, 0)
    var target: Block? = null
    var targetLookups = 0
    val permissions = mutableSetOf("fornaxcitadel.place", "fornaxcitadel.damage")
    val messages = mutableListOf<Any?>()
    val addedItems = mutableListOf<ItemStack>()
    val inventory: PlayerInventory = paperProxy("inventory $name") { method, args ->
        when (method.name) {
            "getItemInMainHand" -> held
            "setItemInMainHand" -> { held = args[0] as ItemStack? ?: TestItemStack(Material.AIR, 0); null }
            "addItem" -> {
                (args[0] as Array<*>).forEach { addedItems.add(it as ItemStack) }
                hashMapOf<Int, ItemStack>()
            }
            else -> unsupported(method)
        }
    }
    val player: Player = paperProxy("player $name") { method, args ->
        when (method.name) {
            "getUniqueId" -> id
            "getName" -> name
            "getWorld" -> world
            "getGameMode" -> gameMode
            "getFirstPlayed" -> firstPlayed
            "getStatistic" -> if (args.contentEquals(arrayOf(Statistic.PLAY_ONE_MINUTE))) playTimeTicks else unsupported(method)
            "getInventory" -> inventory
            "getTargetBlockExact" -> { targetLookups++; target }
            "isOnline" -> online
            "isDead" -> false
            "hasPermission" -> args[0] in permissions
            "sendMessage" -> { messages.add(args.lastOrNull()); null }
            "sendActionBar" -> {
                actionBarFailure?.let { throw it }
                messages.add(args.lastOrNull())
                null
            }
            else -> unsupported(method)
        }
    }
}

internal class TestScheduledTask(val action: Runnable) {
    var cancelled = false
    val task: BukkitTask = paperProxy("scheduled task") { method, _ ->
        when (method.name) {
            "cancel" -> { cancelled = true; null }
            "isCancelled" -> cancelled
            "isSync" -> true
            "getTaskId" -> System.identityHashCode(this)
            else -> unsupported(method)
        }
    }
}

internal class TestPersistentData {
    val values = mutableMapOf<NamespacedKey, Any>()
    val container: PersistentDataContainer = paperProxy("PDC") { method, args ->
        when (method.name) {
            "get" -> values[args[0]]
            "getOrDefault" -> values[args[0]] ?: args[2]
            "has" -> values.containsKey(args[0])
            "set" -> { values[args[0] as NamespacedKey] = args[2]!!; null }
            "remove" -> { values.remove(args[0]); null }
            "isEmpty" -> values.isEmpty()
            "getKeys" -> values.keys.toSet()
            else -> unsupported(method)
        }
    }
}

internal class TestItemStack(private val material: Material, amount: Int) : ItemStack() {
    private var count = amount
    val data = TestPersistentData()
    private val metadata: ItemMeta by lazy {
        paperProxy("item meta") { method, _ ->
            when (method.name) {
                "getPersistentDataContainer" -> data.container
                "clone" -> metadata
                else -> unsupported(method)
            }
        }
    }
    override fun getType(): Material = material
    override fun getAmount(): Int = count
    override fun setAmount(amount: Int) { count = amount }
    override fun hasItemMeta(): Boolean = data.values.isNotEmpty()
    override fun getItemMeta(): ItemMeta = metadata
    override fun getPersistentDataContainer(): PersistentDataContainer = data.container
    override fun isEmpty(): Boolean = count == 0 || material == Material.AIR
    public override fun clone(): ItemStack = TestItemStack(material, count).also { it.data.values.putAll(data.values) }
}

internal class MemoryBackend : DBStorage {
    private val snapshots = mutableMapOf<WXZCord, ByteArray>()
    override fun loadChunk(location: WXZCord): CompletableFuture<ChunkCordSnapshot?> =
        CompletableFuture.completedFuture(snapshots[location]?.let(ChunkCordSnapshot::fromByteArray))
    override fun saveChunk(snapshot: ChunkCordSnapshot): CompletableFuture<Void> {
        snapshots[snapshot.location] = snapshot.toByteArray()
        return CompletableFuture.completedFuture(null)
    }
}

internal object DirectScheduler : Scheduler {
    override fun <T> schedule(location: WXZCord, task: Callable<T>): CompletableFuture<T> = try {
        CompletableFuture.completedFuture(task.call())
    } catch (failure: Exception) {
        CompletableFuture.failedFuture(failure)
    }
}

internal inline fun <reified T> paperProxy(
    label: String,
    crossinline respond: (Method, Array<out Any?>) -> Any?
): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { instance, method, args ->
    when (method.name) {
        "toString" -> label
        "hashCode" -> System.identityHashCode(instance)
        "equals" -> instance === args?.get(0)
        else -> respond(method, args ?: emptyArray())
    }
} as T

internal fun unsupported(method: Method): Nothing = error("Unexpected Bukkit call: ${method.declaringClass.simpleName}.${method.name}")

/** Uses Bukkit's own cancellation dispatch instead of bypassing EventHandler semantics. */
internal fun dispatch(listener: Listener, event: Event, plugin: Plugin) {
    val methods = listener.javaClass.methods.filter {
        it.isAnnotationPresent(EventHandler::class.java) && it.parameterTypes.contentEquals(arrayOf(event.javaClass))
    }.sortedBy { it.getAnnotation(EventHandler::class.java).priority.ordinal }
    check(methods.isNotEmpty()) { "No handler for ${event.javaClass.simpleName}" }
    for (method in methods) {
        val handler = method.getAnnotation(EventHandler::class.java)
        RegisteredListener(listener, EventExecutor { _, received -> method.invoke(listener, received) },
            handler.priority, plugin, handler.ignoreCancelled).callEvent(event)
    }
}
