package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleNotifications
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.MutableCitadelDraft
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.remove.RemoveService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal class HealthFixture(
    startedAtMillis: Long? = 1_000L,
    createInitial: Boolean = true,
    registerMaturing: Boolean = true,
    loaded: Boolean = true
) {
    val types = TypeManager()
    val storage = MemoryStorage(if (loaded) ChunkCord(WXZCord(WORLD, 0, 0)) else null)
    val manager = SimpleComponentManager(storage, types)
    val notifications = CitadelLifecycleNotifications()
    val service = HealthService(manager, RemoveService(manager, notifications))
    val chunk: ChunkCord get() = checkNotNull(storage.chunk)
    val citadel: CitadelHandle get() = reference()

    init {
        manager.registerComponent(Health.KEY.namespace, Health.KEY.key, Health::class.java)
        manager.registerComponent(CitadelKind.KEY.namespace, CitadelKind.KEY.key, CitadelKind::class.java)
        if (registerMaturing) {
            manager.registerComponent(Maturing.KEY.namespace, Maturing.KEY.key, Maturing::class.java)
        }
        if (createInitial) {
            create(startedAtMillis = startedAtMillis)
            chunk.resetDirty()
        }
    }

    fun draft(x: Int = 0, definitionKey: Key = TYPE): MutableCitadelDraft =
        MutableCitadelDraft(CitadelPosition(WORLD, x, 0, 0)).apply {
            addComponent(CitadelKind::class.java, CitadelKind(definitionKey))
        }

    fun create(x: Int = 0, health: Health? = null, startedAtMillis: Long? = null, definitionKey: Key = TYPE): CitadelHandle {
        val draft = draft(x, definitionKey)
        if (health != null) draft.addComponent(Health::class.java, health)
        if (startedAtMillis != null) draft.addComponent(Maturing::class.java, Maturing(startedAtMillis))
        return publish(draft)
    }

    fun publish(draft: MutableCitadelDraft): CitadelHandle {
        assertEquals(ActionResult.SUCCESS, manager.createCitadel(draft))
        return reference(draft.x)
    }

    fun reference(x: Int = 0): CitadelHandle = assertNotNull(manager.findCitadel(WORLD, x, 0, 0))

    internal class MemoryStorage(var chunk: ChunkCord?) : CitadelStorage {
        override fun getIfChunkLoaded(location: WXZCord): ChunkCord? = chunk?.takeIf { it.location == location }
        override fun loadChunk(location: WXZCord): Nothing = error("Unexpected chunk load")
        override fun unloadChunk(location: WXZCord): Nothing = error("Unexpected chunk unload")
        override fun updateOpaque(chunk: ChunkCord): Nothing = error("Unexpected opaque update")
        override fun updateOpaque(key: Key): Nothing = error("Unexpected opaque update")
        override fun saveAll(): Nothing = error("Unexpected save")
        override fun closeAndSaveAll(): Nothing = error("Unexpected close")
    }

    companion object {
        val WORLD: UUID = UUID(1L, 2L)
        val TYPE = Key("test", "healthy")
    }
}

internal class MutableHealthClock(var timeMillis: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    override fun instant(): Instant = Instant.ofEpochMilli(timeMillis)
    override fun millis(): Long = timeMillis
}
