package ru.fornax.fornaxcitadel.paper

import org.bukkit.Chunk
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.plugin.Plugin
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
import ru.fornax.fornaxcitadel.modules.breaking.DamageBreakPolicy
import ru.fornax.fornaxcitadel.modules.breaking.UnbreakablePolicy
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.SimpleHealthBehavior
import ru.fornax.fornaxcitadel.modules.ownership.OwnerInitializer
import ru.fornax.fornaxcitadel.modules.ownership.OwnerOrDamagePolicy
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.remove.RemovalPolicy
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
internal class PaperTestFixture(load: Boolean = true, permissions: Boolean = false, registerTypes: Boolean = true) {
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
    val scheduledTasks = mutableListOf<TestScheduledTask>()
    val players = mutableMapOf<UUID, TestPlayer>()
    private val blocks = mutableMapOf<Triple<Int, Int, Int>, Block>()
    private val materials = mutableMapOf<Triple<Int, Int, Int>, Material>()
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
            "getBlockAt" -> if (args.size == 3) block(args[0] as Int, args[1] as Int, args[2] as Int)
                else (args[0] as Location).let { block(it.blockX, it.blockY, it.blockZ) }
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
            else -> unsupported(method)
        }
    }
    private val bukkitScheduler: BukkitScheduler = paperProxy("scheduler") { method, args ->
        when (method.name) {
            "runTask" -> TestScheduledTask(args[1] as Runnable).also(scheduledTasks::add).task
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
    val items = CitadelItems(plugin)
    val listener = PaperPlayerListener(plugin, manager, module, items, 25.0)
    val owner = player(UUID(3L, 4L), "owner")
    val stranger = player(UUID(5L, 6L), "stranger")

    init {
        if (registerTypes) {
            module.registerDefinition(
                CitadelDefinition(PaperCitadelTypes.REINFORCED, listOf(OwnerInitializer())),
                OwnerOrDamagePolicy,
                SimpleHealthBehavior(100.0)
            )
            module.registerDefinition(CitadelDefinition(PaperCitadelTypes.DEV, emptyList()), DamageBreakPolicy, SimpleHealthBehavior(100.0))
            module.registerDefinition(CitadelDefinition(PaperCitadelTypes.INVULNERABLE, emptyList()), UnbreakablePolicy)
        }
        if (permissions) module.actions.validatorCoordinator.registerValidator(PaperPermissionValidator(server, PaperSettings()))
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
                    "getType" -> materials[key] ?: Material.STONE
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
                    "setType" -> { vanillaBlockChanges++; materials[key] = args[0] as Material; null }
                    else -> unsupported(method)
                }
            }
        }
    }

    fun material(material: Material, position: CitadelPosition = this.position) {
        materials[Triple(position.x, position.y, position.z)] = material
    }

    fun place(type: Key = PaperCitadelTypes.REINFORCED) {
        assertEquals(ActionResult.SUCCESS, module.actions.execute(PlaceAction(context(owner), position, type)))
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
    var held: ItemStack = TestItemStack(Material.AIR, 0)
    var target: Block? = null
    var targetLookups = 0
    val permissions = mutableSetOf("fornaxcitadel.place", "fornaxcitadel.damage")
    val messages = mutableListOf<Any?>()
    val inventory: PlayerInventory = paperProxy("inventory $name") { method, args ->
        when (method.name) {
            "getItemInMainHand" -> held
            "setItemInMainHand" -> { held = args[0] as ItemStack? ?: TestItemStack(Material.AIR, 0); null }
            else -> unsupported(method)
        }
    }
    val player: Player = paperProxy("player $name") { method, args ->
        when (method.name) {
            "getUniqueId" -> id
            "getName" -> name
            "getWorld" -> world
            "getGameMode" -> gameMode
            "getInventory" -> inventory
            "getTargetBlockExact" -> { targetLookups++; target }
            "isOnline" -> online
            "isDead" -> false
            "hasPermission" -> args[0] in permissions
            "sendMessage", "sendActionBar" -> { messages.add(args.lastOrNull()); null }
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
