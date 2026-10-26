package ru.fornax.fornaxcitadel.core.storage

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.OpaqueComponent
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkCordTest {

    @Test
    fun `minecraft chunk load state follows rapid lifecycle events independently of contents`() {
        val chunk = ChunkCord(WXZCord(UUID.randomUUID(), 0, 0))
        assertFalse(chunk.isChunkLoaded())
        chunk.minecraftChunkUnloaded()
        assertFalse(chunk.isChunkLoaded())

        chunk.minecraftChunkLoaded()
        chunk.putCitadel(1, 20, 2)
        chunk.clear()
        assertTrue(chunk.isChunkLoaded())

        repeat(100) {
            chunk.minecraftChunkUnloaded()
            assertFalse(chunk.isChunkLoaded())
            chunk.minecraftChunkLoaded()
            val loadedAfterReload = chunk.isChunkLoaded()
            assertTrue(loadedAfterReload)
        }
        assertTrue(chunk.isEmpty)
    }

    @Test
    fun `putCitadel creates and stores entity`() {
        val chunk = ChunkCord(WXZCord(UUID.randomUUID(), 2, -3))

        val entity = chunk.putCitadel(3, 12, 9)

        assertSame(entity, chunk.getCitadel(3, 12, 9))
        assertEquals(ChunkCord.packPosition(3, 12, 9), entity.packedPosition)
        assertEquals(1, chunk.size)
    }

    @Test
    fun `putCitadel rejects occupied position`() {
        val chunk = ChunkCord(WXZCord(UUID.randomUUID(), 0, 0))
        val original = chunk.putCitadel(7, 42, 11)

        assertFailsWith<IllegalStateException> {
            chunk.putCitadel(7, 42, 11)
        }

        assertSame(original, chunk.getCitadel(7, 42, 11))
        assertEquals(1, chunk.size)
    }

    @Test
    fun `removeCitadel clears mask and makes old entity stale`() {
        val chunk = ChunkCord(WXZCord(UUID.randomUUID(), 0, 0))
        val types = TypeManager()
        val type = types.registerType(COMPONENT_KEY, TestComponent::class.java)
        val entity = chunk.putCitadel(4, 21, 5)
        chunk.putComponent(entity, type, TestComponent(17))

        val removed = chunk.removeCitadel(4, 21, 5)

        assertSame(entity, removed)
        assertEquals(0L, entity.components())
        assertNull(chunk.getCitadel(4, 21, 5))

        val replacement = chunk.putCitadel(4, 21, 5)
        assertNotEquals(entity.id, replacement.id)
        assertNull(chunk.getComponent(replacement, type))
        assertFailsWith<IllegalStateException> {
            chunk.getComponent(entity, type)
        }
    }

    @Test
    fun `clear resets masks and frees occupied positions`() {
        val chunk = ChunkCord(WXZCord(UUID.randomUUID(), 0, 0))
        val type = TypeManager().registerType(COMPONENT_KEY, TestComponent::class.java)
        val first = chunk.putCitadel(4, 21, 5)
        val second = chunk.putCitadel(7, 42, 11)
        chunk.putComponent(first, type, TestComponent(17))
        chunk.putComponent(second, type, TestComponent(23))
        val opaqueKey = Key("test", "opaque")
        chunk.putOpaqueComponent(first, OpaqueComponent(opaqueKey, byteArrayOf(1)))
        chunk.putOpaqueComponent(second, OpaqueComponent(opaqueKey, byteArrayOf(2)))

        chunk.clear()

        assertTrue(chunk.isEmpty)
        assertEquals(0, chunk.size)
        assertEquals(0L, first.components())
        assertEquals(0L, second.components())
        assertTrue(chunk.captureOpaqueComponents().isEmpty())
        assertNull(chunk.getCitadel(4, 21, 5))
        assertNull(chunk.getCitadel(7, 42, 11))

        val replacement = chunk.putCitadel(4, 21, 5)
        assertNotEquals(first.id, replacement.id)
        assertNull(chunk.getComponent(replacement, type))
        assertFailsWith<IllegalStateException> {
            chunk.getComponent(first, type)
        }
    }

    @Test
    fun `component index preserves zero index and swap removal`() {
        val chunk = ChunkCord(WXZCord(UUID.randomUUID(), 0, 0))
        val type = TypeManager().registerType(COMPONENT_KEY, TestComponent::class.java)
        val first = chunk.putCitadel(0, ChunkCord.MIN_Y, 0)
        val middle = chunk.putCitadel(7, 40, 8)
        val last = chunk.putCitadel(15, 319, 15)
        val firstComponent = TestComponent(11)
        val middleComponent = TestComponent(22)
        val lastComponent = TestComponent(33)

        chunk.putComponent(first, type, firstComponent)
        chunk.putComponent(middle, type, middleComponent)
        chunk.putComponent(last, type, lastComponent)

        assertSame(firstComponent, chunk.getComponent(first, type))
        assertSame(middleComponent, chunk.getComponent(middle, type))
        assertSame(lastComponent, chunk.getComponent(last, type))

        val replacement = TestComponent(44)
        assertSame(middleComponent, chunk.putComponent(middle, type, replacement))
        assertSame(replacement, chunk.removeComponent(middle, type))
        assertNull(chunk.getComponent(middle, type))
        assertSame(firstComponent, chunk.getComponent(first, type))
        assertSame(lastComponent, chunk.getComponent(last, type))

        val remaining = ArrayList<Pair<CitadelEntity, TestComponent>>()
        chunk.forEachComponentOfType(type) { entity, component ->
            remaining.add(entity to component)
        }
        assertEquals(setOf(first to firstComponent, last to lastComponent), remaining.toSet())

        assertSame(firstComponent, chunk.removeComponent(first, type))
        assertSame(lastComponent, chunk.removeComponent(last, type))
        assertNull(chunk.removeComponent(first, type))

        val reinserted = TestComponent(55)
        chunk.putComponent(first, type, reinserted)
        assertSame(reinserted, chunk.getComponent(first, type))
    }

    @Test
    fun `packed position is a bijection for every local coordinate`() {
        val seen = BooleanArray(ChunkCord.POSITION_COUNT)
        var positionCount = 0

        for (y in ChunkCord.MIN_Y until ChunkCord.MIN_Y + ChunkCord.WORLD_HEIGHT) {
            for (z in 0 until ChunkCord.CHUNK_SIZE) {
                for (x in 0 until ChunkCord.CHUNK_SIZE) {
                    val packed = ChunkCord.packPosition(x, y, z)

                    assertFalse(seen[packed], "Duplicate packed position: $packed")
                    seen[packed] = true
                    positionCount++

                    assertEquals(x, ChunkCord.unpackX(packed))
                    assertEquals(y, ChunkCord.unpackY(packed))
                    assertEquals(z, ChunkCord.unpackZ(packed))
                }
            }
        }

        assertEquals(ChunkCord.POSITION_COUNT, positionCount)
        assertTrue(seen.all { it })
    }

    @Test
    fun `packed position covers both world corners`() {
        assertEquals(0, ChunkCord.packPosition(0, -64, 0))
        assertEquals(
            ChunkCord.MAX_PACKED_POSITION,
            ChunkCord.packPosition(15, 319, 15)
        )
    }

    private class TestComponent(
        var value: Int = 0
    ) : Component() {
        override fun write(output: DataOutput) {
            output.writeInt(value)
        }

        override fun read(input: DataInput) {
            value = input.readInt()
        }
    }

    private companion object {
        val COMPONENT_KEY = Key("test", "component")
    }
}
