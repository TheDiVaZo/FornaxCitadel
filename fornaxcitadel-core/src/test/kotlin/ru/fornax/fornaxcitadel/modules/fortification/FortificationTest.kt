package ru.fornax.fornaxcitadel.modules.fortification

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.MutableCitadelDraft
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.serialization.ComponentSerializer
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.Health
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FortificationTest {
    @Test
    fun `initialization uses the prepared level and snapshots level configuration`() {
        val configured = mutableListOf(20.0, 80.0, 100.0)
        val behavior = FortifiedHealthBehavior(configured)
        configured[1] = 999.0
        val draft = MutableCitadelDraft(POSITION)
        FortificationInitializer(2).initialize(ActionContext.SYSTEM, draft)
        val health = TestHealth(0.0, 0.0)

        behavior.initializeHealth(health, draft)

        assertEquals(3, behavior.maximumLevel)
        assertEquals(2, assertNotNull(draft.getComponent(Fortification::class.java)).level)
        assertEquals(80.0, health.maxHealth)
        assertEquals(80.0, health.health)
    }

    @Test
    fun `changing reinforcement preserves damage and repeated refreshes do not repair`() {
        val fixture = Fixture(1)
        val health = TestHealth(20.0, 10.0)
        val behavior = FortifiedHealthBehavior(listOf(20.0, 80.0, 100.0))
        assertFalse(behavior.updateHealth(health, fixture.handle))

        for ((level, expectedMaximum) in listOf(2 to 80.0, 3 to 100.0, 1 to 20.0)) {
            fixture.handle.editComponent(Fortification::class.java) { it.level = level }
            assertTrue(behavior.updateHealth(health, fixture.handle))
            assertEquals(expectedMaximum, health.maxHealth)
            assertEquals(expectedMaximum / 2, health.health)
            assertFalse(behavior.updateHealth(health, fixture.handle))
        }
    }

    @Test
    fun `full health follows reinforcement exactly and dead health stays dead`() {
        val fixture = Fixture(2)
        val behavior = FortifiedHealthBehavior(listOf(20.0, 80.0))
        for ((before, expected) in listOf(20.0 to 80.0, (20.0 - 5e-10) to 80.0, 0.0 to 0.0)) {
            val health = TestHealth(20.0, before)
            assertTrue(behavior.updateHealth(health, fixture.handle))
            assertEquals(80.0, health.maxHealth)
            assertEquals(expected, health.health)
            assertFalse(behavior.updateHealth(health, fixture.handle))
        }
    }

    @Test
    fun `invalid levels and health tables are rejected`() {
        for (level in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { Fortification(level) }
            assertFailsWith<IllegalArgumentException> { FortificationInitializer(level) }
        }
        val component = Fortification(2)
        assertFailsWith<IllegalArgumentException> { component.level = 0 }
        assertEquals(2, component.level)
        for (configuration in listOf(
            emptyList(), listOf(0.0), listOf(-1.0), listOf(Double.NaN),
            listOf(Double.POSITIVE_INFINITY), listOf(80.0, 20.0)
        )) {
            assertFailsWith<IllegalArgumentException> { FortifiedHealthBehavior(configuration) }
        }
    }

    @Test
    fun `missing or unsupported level cannot partially initialize or update health`() {
        val behavior = FortifiedHealthBehavior(listOf(20.0, 80.0))
        val health = TestHealth(50.0, 25.0)
        val draft = MutableCitadelDraft(POSITION)
        assertFailsWith<IllegalStateException> { behavior.initializeHealth(health, draft) }
        draft.addComponent(Fortification::class.java, Fortification(3))
        assertFailsWith<IllegalArgumentException> { behavior.initializeHealth(health, draft) }
        val fixture = Fixture(3)
        assertFailsWith<IllegalArgumentException> { behavior.updateHealth(health, fixture.handle) }
        assertEquals(50.0, health.maxHealth)
        assertEquals(25.0, health.health)

        for ((maximum, current) in listOf(0.0 to 0.0, Double.NaN to 10.0, 20.0 to -1.0, 20.0 to Double.NaN)) {
            val invalid = TestHealth(maximum, current)
            assertFailsWith<IllegalArgumentException> { behavior.updateHealth(invalid, fixture.handle) }
            assertEquals(maximum, invalid.maxHealth)
            assertEquals(current, invalid.health)
        }
    }

    @Test
    fun `reinforcement level survives component serialization and invalid payload preserves state`() {
        val type = TypeManager().registerType(Fortification.KEY, Fortification::class.java)
        val restored = ComponentSerializer.decode(ComponentSerializer.encode(type, Fortification(7)), type)
        assertEquals(7, restored.level)

        for (invalid in listOf(0, -1)) {
            val payload = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(invalid).array()
            assertFailsWith<IOException> { restored.safeRead(payload) }
            assertEquals(7, restored.level)
        }
    }

    private class TestHealth(maximum: Double, current: Double) : Health(maximum, current)

    private class Fixture(level: Int) {
        private val chunk = ChunkCord(WXZCord(POSITION.world, 0, 0))
        private val manager = SimpleComponentManager(LoadedStorage(chunk), TypeManager())
        val handle = run {
            manager.registerComponent(Fortification.KEY.namespace, Fortification.KEY.key, Fortification::class.java)
            val draft = MutableCitadelDraft(POSITION)
            draft.addComponent(Fortification::class.java, Fortification(level))
            assertEquals(ActionResult.SUCCESS, manager.createCitadel(draft))
            assertNotNull(manager.findCitadel(POSITION.world, POSITION.x, POSITION.y, POSITION.z))
        }
    }

    private class LoadedStorage(private val chunk: ChunkCord) : CitadelStorage {
        override fun getIfChunkLoaded(location: WXZCord): ChunkCord? = chunk.takeIf { it.location == location }
        override fun loadChunk(location: WXZCord): Nothing = error("Unexpected chunk load")
        override fun unloadChunk(location: WXZCord): Nothing = error("Unexpected chunk unload")
        override fun updateOpaque(chunk: ChunkCord): Nothing = error("Unexpected opaque update")
        override fun updateOpaque(key: Key): Nothing = error("Unexpected opaque update")
        override fun saveAll(): Nothing = error("Unexpected save")
        override fun closeAndSaveAll(): Nothing = error("Unexpected close")
    }

    private companion object {
        val POSITION = CitadelPosition(UUID(1L, 2L), 1, 2, 3)
    }
}
