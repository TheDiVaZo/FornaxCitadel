package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.OpaqueBatchUpdateResult
import ru.fornax.fornaxcitadel.core.storage.OpaqueUpdateResult
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CitadelRangeQueryTest {
    @Test
    fun `query visits inclusive layers across storage groups with global negative coordinates`() {
        val fixture = Fixture()
        val chunk = fixture.seed(WORLD, -2, -3)
        chunk.putCitadel(0, -42, 0)
        val first = chunk.putCitadel(1, -41, 2)
        val second = chunk.putCitadel(15, -40, 15)
        val third = chunk.putCitadel(8, -39, 7)
        chunk.putCitadel(0, -38, 0)
        fixture.seed(OTHER_WORLD, -2, -3).putCitadel(1, -41, 2)
        fixture.seed(WORLD, -1, -3).putCitadel(1, -41, 2)
        chunk.resetDirty()

        val found = fixture.query(WORLD, -2, -3, -41, -39)

        assertEquals(
            listOf(
                CitadelPosition(WORLD, -31, -41, -46),
                CitadelPosition(WORLD, -17, -40, -33),
                CitadelPosition(WORLD, -24, -39, -41)
            ),
            found.map { it.position }
        )
        assertEquals(listOf(first.id, second.id, third.id), found.map { it.runtimeId })
        assertTrue(found.all { it.isCurrent })
        assertFalse(chunk.isDirty)
        assertEquals(0, fixture.storage.loadCalls)
    }

    @Test
    fun `query clips extreme height bounds and supports a single boundary layer`() {
        val fixture = Fixture()
        val chunk = fixture.seed(WORLD, 0, 0)
        val bottom = chunk.putCitadel(0, -64, 0)
        chunk.putCitadel(0, 0, 0)
        val top = chunk.putCitadel(15, 319, 15)

        assertEquals(listOf(-64, 0, 319), fixture.query(minY = Int.MIN_VALUE, maxY = Int.MAX_VALUE).map { it.y })
        assertEquals(bottom.id, fixture.query(minY = Int.MIN_VALUE, maxY = -64).single().runtimeId)
        assertEquals(top.id, fixture.query(minY = 319, maxY = Int.MAX_VALUE).single().runtimeId)
        assertEquals(listOf(0), fixture.query(minY = 0, maxY = 0).map { it.y })
        assertTrue(fixture.query(minY = Int.MIN_VALUE, maxY = -65).isEmpty())
        assertTrue(fixture.query(minY = 320, maxY = Int.MAX_VALUE).isEmpty())
    }

    @Test
    fun `absent and empty chunks produce no results and never trigger loading`() {
        val fixture = Fixture()
        fixture.seed(WORLD, 0, 0)

        assertTrue(fixture.query().isEmpty())
        repeat(10) { coordinate ->
            assertTrue(fixture.query(WORLD, coordinate + 1, -coordinate, -64, 319).isEmpty())
        }
        assertTrue(fixture.query(OTHER_WORLD, 0, 0, -64, 319).isEmpty())

        assertEquals(0, fixture.storage.loadCalls)
        assertEquals(setOf(WXZCord(WORLD, 0, 0)), fixture.storage.chunks.keys)
    }

    @Test
    fun `invalid ranges and chunk coordinates are rejected even without a loaded chunk`() {
        val fixture = Fixture()

        assertFailsWith<IllegalArgumentException> { fixture.query(minY = 1, maxY = 0) }
        assertFailsWith<IllegalArgumentException> { fixture.query(minY = Int.MAX_VALUE, maxY = Int.MIN_VALUE) }
        for (invalid in listOf(Short.MIN_VALUE.toInt() - 1, Short.MAX_VALUE.toInt() + 1)) {
            assertFailsWith<IllegalArgumentException> { fixture.query(chunkX = invalid) }
            assertFailsWith<IllegalArgumentException> { fixture.query(chunkZ = invalid) }
            assertFailsWith<IllegalArgumentException> {
                fixture.query(chunkX = invalid, minY = 320, maxY = Int.MAX_VALUE)
            }
        }
        assertEquals(0, fixture.storage.loadCalls)
        assertTrue(fixture.storage.chunks.isEmpty())
    }

    @Test
    fun `supported chunk coordinate edges preserve exact global block coordinates`() {
        val fixture = Fixture()
        val chunkX = Short.MIN_VALUE.toInt()
        val chunkZ = Short.MAX_VALUE.toInt()
        fixture.seed(WORLD, chunkX, chunkZ).putCitadel(0, 5, 15)

        val found = fixture.query(WORLD, chunkX, chunkZ, 5, 5).single()

        assertEquals(CitadelPosition(WORLD, -524288, 5, 524287), found.position)
        assertTrue(found.isCurrent)
    }

    @Test
    fun `returned handles expire on entity replacement and cannot remove the replacement`() {
        val fixture = Fixture()
        val chunk = fixture.seed(WORLD, 0, 0)
        chunk.putCitadel(2, 3, 4)
        val original = fixture.query().single()
        assertTrue(original.isCurrent)

        chunk.removeCitadel(2, 3, 4)
        val replacementEntity = chunk.putCitadel(2, 3, 4)
        val replacement = fixture.query().single()

        assertFalse(original.isCurrent)
        assertFalse(fixture.manager.removeCitadel(original))
        assertTrue(replacement.isCurrent)
        assertEquals(original.position, replacement.position)
        assertNotEquals(original.runtimeId, replacement.runtimeId)
        assertEquals(replacementEntity.id, replacement.runtimeId)
    }

    @Test
    fun `returned handles expire after unload and reloading the same address`() {
        val fixture = Fixture()
        fixture.seed(WORLD, 0, 0).putCitadel(2, 3, 4)
        val original = fixture.query().single()

        fixture.manager.unloadChunk(WORLD, 0, 0).join()
        assertTrue(fixture.query().isEmpty())
        fixture.seed(WORLD, 0, 0).putCitadel(2, 3, 4)
        val reloaded = fixture.query().single()

        assertFalse(original.isCurrent)
        assertFalse(fixture.manager.removeCitadel(original))
        assertTrue(reloaded.isCurrent)
        assertEquals(original.position, reloaded.position)
        assertNotEquals(original.runtimeId, reloaded.runtimeId)
        assertEquals(0, fixture.storage.loadCalls)
    }

    private class Fixture {
        val storage = ReadOnlyQueryStorage()
        val manager = ComponentManager(storage, TypeManager())

        fun seed(world: UUID, chunkX: Int, chunkZ: Int): ChunkCord {
            val location = WXZCord(world, chunkX, chunkZ)
            return ChunkCord(location).also { storage.chunks[location] = it }
        }

        fun query(
            world: UUID = WORLD,
            chunkX: Int = 0,
            chunkZ: Int = 0,
            minY: Int = -64,
            maxY: Int = 319
        ): List<CitadelHandle> = mutableListOf<CitadelHandle>().also { found ->
            manager.forEachCitadel(world, chunkX, chunkZ, minY, maxY, found::add)
        }
    }

    private class ReadOnlyQueryStorage : CitadelStorage {
        val chunks = mutableMapOf<WXZCord, ChunkCord>()
        var loadCalls = 0

        override fun getIfChunkLoaded(location: WXZCord): ChunkCord? = chunks[location]

        override fun loadChunk(location: WXZCord): CompletableFuture<ChunkCord> {
            loadCalls++
            error("A range query must never load chunks")
        }

        override fun unloadChunk(location: WXZCord): CompletableFuture<Void> {
            chunks.remove(location)
            return CompletableFuture.completedFuture(null)
        }

        override fun updateOpaque(chunk: ChunkCord): CompletableFuture<OpaqueUpdateResult> =
            error("A range query must not update components")

        override fun updateOpaque(key: Key): CompletableFuture<OpaqueBatchUpdateResult> =
            error("A range query must not update components")

        override fun saveAll(): CompletableFuture<Void> = error("A range query must not save chunks")

        override fun closeAndSaveAll(): CompletableFuture<Void> = error("A range query must not close storage")
    }

    private companion object {
        val WORLD = UUID(1L, 2L)
        val OTHER_WORLD = UUID(3L, 4L)
    }
}
