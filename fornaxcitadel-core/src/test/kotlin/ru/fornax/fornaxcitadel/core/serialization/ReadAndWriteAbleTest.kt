package ru.fornax.fornaxcitadel.core.serialization

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.OpaqueComponent
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.storage.CitadelSnapshot
import ru.fornax.fornaxcitadel.core.storage.ComponentSnapshot
import ru.fornax.fornaxcitadel.core.storage.OpaqueUpdateResult
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReadAndWriteAbleTest {

    @Test
    fun `snapshot dto defensively copies collections and payload`() {
        val sourcePayload = byteArrayOf(1, 2, 3)
        val component = ComponentSnapshot(INT_KEY, sourcePayload)
        val sourceComponents = mutableListOf(component)
        val citadel = CitadelSnapshot(0, sourceComponents)
        val sourceCitadels = mutableListOf(citadel)
        val snapshot = ChunkCordSnapshot(
            UUID.randomUUID(), 1, -2,
            sourceCitadels
        )

        sourcePayload[0] = 9
        sourceComponents.clear()
        sourceCitadels.clear()
        val exposedPayload = component.payload
        exposedPayload[1] = 9

        assertEquals(1, snapshot.citadels.size)
        assertEquals(1, snapshot.citadels.single().components.size)
        assertContentEquals(byteArrayOf(1, 2, 3), component.payload)
        assertContentEquals(byteArrayOf(1, 2, 3), component.toByteArray())
    }

    @Test
    fun `opaque payload remains immutable across public boundaries`() {
        val sourcePayload = byteArrayOf(1, 2, 3)
        val opaque = OpaqueComponent(UNKNOWN_KEY, sourcePayload)

        sourcePayload[0] = 9
        val exposedOpaquePayload = opaque.toByteArray()
        exposedOpaquePayload[1] = 9

        val snapshot = ComponentSerializer.encode(opaque)
        val exposedSnapshotPayload = snapshot.payload
        exposedSnapshotPayload[2] = 9

        assertSame(opaque.payloadData, snapshot.payloadData)
        assertContentEquals(byteArrayOf(1, 2, 3), opaque.toByteArray())
        assertContentEquals(byteArrayOf(1, 2, 3), snapshot.toByteArray())
    }

    @Test
    fun `binary snapshot read canonicalizes equal component keys`() {
        val firstKey = Key("shared", "component")
        val secondKey = Key(
            String("shared".toCharArray()),
            String("component".toCharArray())
        )
        val snapshot = ChunkCordSnapshot(
            UUID.randomUUID(), 0, 0,
            listOf(
                CitadelSnapshot(
                    ChunkCord.packPosition(0, 0, 0),
                    listOf(ComponentSnapshot(firstKey, byteArrayOf(1)))
                ),
                CitadelSnapshot(
                    ChunkCord.packPosition(1, 0, 0),
                    listOf(ComponentSnapshot(secondKey, byteArrayOf(2)))
                )
            )
        )

        val decoded = ChunkCordSnapshot.fromByteArray(snapshot.toByteArray())
        val decodedFirstKey = decoded.citadels[0].components.single().key
        val decodedSecondKey = decoded.citadels[1].components.single().key

        assertSame(decodedFirstKey, decodedSecondKey)
    }

    @Test
    fun `snapshot dto serializes without type manager`() {
        val world = UUID.randomUUID()
        val packedPosition = ChunkCord.packPosition(15, 319, 0)
        val payload = byteArrayOf(4, 5, 6)
        val snapshot = ChunkCordSnapshot(
            world, -10, 23,
            listOf(
                CitadelSnapshot(
                    packedPosition,
                    listOf(ComponentSnapshot(INT_KEY, payload))
                )
            )
        )

        val decoded = ChunkCordSnapshot.fromByteArray(snapshot.toByteArray())
        val decodedCitadel = decoded.citadels.single()
        val decodedComponent = decodedCitadel.components.single()

        assertEquals(world, decoded.world)
        assertEquals(-10, decoded.x)
        assertEquals(23, decoded.z)
        assertEquals(packedPosition, decodedCitadel.packedPosition)
        assertEquals(INT_KEY, decodedComponent.key)
        assertContentEquals(payload, decodedComponent.payload)
    }

    @Test
    fun `serializes coordinates`() {
        val coordinates = WXZCord(UUID.randomUUID(), -12, 34)

        val decoded = ReadAndWriteAble.readWXZCord(
            ReadAndWriteAble.toByteArray(coordinates)
        )

        assertEquals(coordinates, decoded)
    }

    @Test
    fun `serializes chunk using stable keys across registration order`() {
        val world = UUID.randomUUID()
        val chunk = ChunkCord(world, -3, 7)
        val entity = chunk.putCitadel(0, -64, 15)
        val sourceTypes = TypeManager()
        val sourceOtherType = sourceTypes.registerType(OTHER_KEY, OtherComponent::class.java)
        val sourceIntType = sourceTypes.registerType(INT_KEY, IntComponent::class.java)

        chunk.putComponent(entity, sourceIntType, IntComponent(42))
        chunk.putComponent(entity, sourceOtherType, OtherComponent())

        val targetTypes = TypeManager()
        val targetIntType = targetTypes.registerType(INT_KEY, IntComponent::class.java)
        val targetOtherType = targetTypes.registerType(OTHER_KEY, OtherComponent::class.java)
        val data = ChunkCordSnapshotMapper(sourceTypes).snapshot(chunk).toByteArray()
        val decoded = ChunkCordSnapshotMapper(targetTypes).restore(
            ChunkCordSnapshot.fromByteArray(data)
        )
        val decodedEntity = assertNotNull(decoded.getCitadel(0, -64, 15))
        val decodedComponent = assertNotNull(decoded.getComponent(decodedEntity, targetIntType))

        assertEquals(world, decoded.world)
        assertEquals(-3, decoded.x)
        assertEquals(7, decoded.z)
        assertNotEquals(entity.id, decodedEntity.id)
        assertEquals(entity.packedPosition, decodedEntity.packedPosition)
        assertEquals(
            targetIntType.flag or targetOtherType.flag,
            decodedEntity.components()
        )
        assertEquals(42, decodedComponent.value)
        assertNotNull(decoded.getComponent(decodedEntity, targetOtherType))
    }

    @Test
    fun `preserves unknown component until its type is registered again`() {
        val chunk = ChunkCord(UUID.randomUUID(), 4, -8)
        val entity = chunk.putCitadel(3, 12, 9)
        val sourceTypes = TypeManager()
        val sourceType = sourceTypes.registerType(INT_KEY, IntComponent::class.java)

        chunk.putComponent(entity, sourceType, IntComponent(73))

        val sourceData = ChunkCordSnapshotMapper(sourceTypes).snapshot(chunk).toByteArray()
        val unavailableTypes = TypeManager()
        val unavailableMapper = ChunkCordSnapshotMapper(unavailableTypes)
        val opaqueChunk = unavailableMapper.restore(ChunkCordSnapshot.fromByteArray(sourceData))
        val opaqueEntity = assertNotNull(opaqueChunk.getCitadel(3, 12, 9))

        assertEquals(0L, opaqueEntity.components())
        assertNull(unavailableTypes.getType(INT_KEY))
        val firstExplicitType = unavailableTypes.registerType(OTHER_KEY, OtherComponent::class.java)
        assertEquals(0, firstExplicitType.id.toInt())
        assertEquals(1L, firstExplicitType.flag)

        val passthroughData = unavailableMapper.snapshot(opaqueChunk).toByteArray()
        assertContentEquals(sourceData, passthroughData)

        val restoredTypes = TypeManager()
        val restoredType = restoredTypes.registerType(INT_KEY, IntComponent::class.java)
        val restoredChunk = ChunkCordSnapshotMapper(restoredTypes).restore(
            ChunkCordSnapshot.fromByteArray(passthroughData)
        )
        val restoredEntity = assertNotNull(restoredChunk.getCitadel(3, 12, 9))
        val restoredComponent = assertNotNull(
            restoredChunk.getComponent(restoredEntity, restoredType)
        )

        assertEquals(restoredType.flag, restoredEntity.components())
        assertEquals(73, restoredComponent.value)
    }

    @Test
    fun `removes opaque components with their owner or all owner components`() {
        val chunk = ChunkCord(UUID.randomUUID(), 1, 2)
        val sourceTypes = TypeManager()
        val sourceType = sourceTypes.registerType(INT_KEY, IntComponent::class.java)
        val retainedEntity = chunk.putCitadel(1, 20, 2)
        val removedEntity = chunk.putCitadel(4, 21, 5)

        chunk.putComponent(retainedEntity, sourceType, IntComponent(11))
        chunk.putComponent(removedEntity, sourceType, IntComponent(22))

        val unavailableTypes = TypeManager()
        val unavailableMapper = ChunkCordSnapshotMapper(unavailableTypes)
        val opaqueChunk = unavailableMapper.restore(
            ChunkCordSnapshot.fromByteArray(
                ChunkCordSnapshotMapper(sourceTypes).snapshot(chunk).toByteArray()
            )
        )
        val opaqueRetainedEntity = assertNotNull(opaqueChunk.getCitadel(1, 20, 2))
        assertEquals(listOf(INT_KEY, INT_KEY), opaqueChunk.captureOpaqueComponents().map { it.key })

        opaqueChunk.removeAllComponents(opaqueRetainedEntity)
        assertEquals(listOf(INT_KEY), opaqueChunk.captureOpaqueComponents().map { it.key })
        assertNotNull(opaqueChunk.removeCitadel(4, 21, 5))
        assertTrue(opaqueChunk.captureOpaqueComponents().isEmpty())

        val restoredTypes = TypeManager()
        val restoredType = restoredTypes.registerType(INT_KEY, IntComponent::class.java)
        val restoredChunk = ChunkCordSnapshotMapper(restoredTypes).restore(
            ChunkCordSnapshot.fromByteArray(
                unavailableMapper.snapshot(opaqueChunk).toByteArray()
            )
        )
        val restoredRetainedEntity = assertNotNull(restoredChunk.getCitadel(1, 20, 2))

        assertEquals(0L, restoredRetainedEntity.components())
        assertNull(restoredChunk.getComponent(restoredRetainedEntity, restoredType))
        assertNull(restoredChunk.getCitadel(4, 21, 5))
    }

    @Test
    fun `removeComponent also removes opaque data for its key`() {
        val sourceTypes = TypeManager()
        val sourceType = sourceTypes.registerType(INT_KEY, IntComponent::class.java)
        val chunk = ChunkCord(UUID.randomUUID(), 0, 0)
        val entity = chunk.putCitadel(2, 30, 4)
        chunk.putComponent(entity, sourceType, IntComponent(31))

        val targetTypes = TypeManager()
        val targetMapper = ChunkCordSnapshotMapper(targetTypes)
        val opaqueChunk = targetMapper.restore(
            ChunkCordSnapshot.fromByteArray(
                ChunkCordSnapshotMapper(sourceTypes).snapshot(chunk).toByteArray()
            )
        )
        val opaqueEntity = assertNotNull(opaqueChunk.getCitadel(2, 30, 4))
        val targetType = targetTypes.registerType(INT_KEY, IntComponent::class.java)

        assertNull(opaqueChunk.removeComponent(opaqueEntity, targetType))

        val restoredChunk = targetMapper.restore(
            ChunkCordSnapshot.fromByteArray(targetMapper.snapshot(opaqueChunk).toByteArray())
        )
        val restoredEntity = assertNotNull(restoredChunk.getCitadel(2, 30, 4))
        assertNull(restoredChunk.getComponent(restoredEntity, targetType))
    }

    @Test
    fun `registers and gets component types explicitly`() {
        val types = TypeManager()
        val registered = types.registerType(INT_KEY, IntComponent::class.java)

        assertSame(registered, types.registerType(INT_KEY, IntComponent::class.java))
        assertSame(registered, types.getType(INT_KEY))
        assertSame(registered, types.getType(IntComponent::class.java))
        assertFailsWith<IllegalArgumentException> {
            types.registerType(INT_KEY, OtherComponent::class.java)
        }
        assertFailsWith<IllegalArgumentException> {
            types.registerType(Key("test", "renamed-int"), IntComponent::class.java)
        }
    }

    @Test
    fun `discards corrupted registered component and preserves its neighbors`() {
        val world = UUID.randomUUID()
        val packedPosition = ChunkCord.packPosition(0, 0, 0)
        val unknownPayload = byteArrayOf(9, 8, 7)
        val snapshot = ChunkCordSnapshot(
            world, 0, 0,
            listOf(
                CitadelSnapshot(
                    packedPosition,
                    listOf(
                        ComponentSnapshot(INT_KEY, byteArrayOf(1, 2)),
                        ComponentSnapshot(OTHER_KEY, byteArrayOf()),
                        ComponentSnapshot(UNKNOWN_KEY, unknownPayload)
                    )
                )
            )
        )
        val types = TypeManager()
        val intType = types.registerType(INT_KEY, IntComponent::class.java)
        val otherType = types.registerType(OTHER_KEY, OtherComponent::class.java)
        val corrupted = ArrayList<CorruptedComponentPayload>()
        val mapper = ChunkCordSnapshotMapper(types, corrupted::add)

        val restored = mapper.restore(snapshot)
        val entity = assertNotNull(restored.getCitadel(0, 0, 0))

        assertNull(restored.getComponent(entity, intType))
        assertNotNull(restored.getComponent(entity, otherType))
        assertEquals(otherType.flag, entity.components())

        val savedComponents = mapper.snapshot(restored).citadels.single().components
        assertEquals(listOf(OTHER_KEY, UNKNOWN_KEY), savedComponents.map { it.key })
        assertContentEquals(
            unknownPayload,
            savedComponents.single { it.key == UNKNOWN_KEY }.payload
        )
        assertEquals(1, corrupted.size)
        assertEquals(WXZCord(world, 0, 0), corrupted.single().location)
        assertEquals(packedPosition, corrupted.single().packedPosition)
        assertEquals(INT_KEY, corrupted.single().key)
    }

    @Test
    fun `diagnostic failure does not restore a corrupted payload`() {
        val types = TypeManager()
        val type = types.registerType(INT_KEY, IntComponent::class.java)
        val mapper = ChunkCordSnapshotMapper(types) {
            throw IllegalStateException("diagnostic failed")
        }
        val snapshot = ChunkCordSnapshot(
            UUID.randomUUID(), 0, 0,
            listOf(
                CitadelSnapshot(
                    ChunkCord.packPosition(0, 0, 0),
                    listOf(ComponentSnapshot(INT_KEY, byteArrayOf(1, 2)))
                )
            )
        )

        val restored = mapper.restore(snapshot)
        val entity = assertNotNull(restored.getCitadel(0, 0, 0))

        assertNull(restored.getComponent(entity, type))
        assertEquals(0, mapper.snapshot(restored).citadels.single().components.size)
    }

    @Test
    fun `opaque update restores discards and retains components with exact statistics`() {
        val world = UUID.randomUUID()
        val packedPosition = ChunkCord.packPosition(4, 50, 6)
        val unknownPayload = byteArrayOf(4, 3, 2, 1)
        val snapshot = ChunkCordSnapshot(
            world, 7, -9,
            listOf(
                CitadelSnapshot(
                    packedPosition,
                    listOf(
                        ComponentSnapshot(INT_KEY, IntComponent(73).toByteArray()),
                        ComponentSnapshot(CORRUPT_KEY, byteArrayOf(1, 2)),
                        ComponentSnapshot(UNKNOWN_KEY, unknownPayload)
                    )
                )
            )
        )
        val types = TypeManager()
        val corrupted = ArrayList<CorruptedComponentPayload>()
        val mapper = ChunkCordSnapshotMapper(types, corrupted::add)
        val chunk = mapper.restore(snapshot)
        val entity = assertNotNull(chunk.getCitadel(4, 50, 6))

        assertEquals(
            setOf(INT_KEY, CORRUPT_KEY, UNKNOWN_KEY),
            chunk.captureOpaqueComponents().map { it.key }.toSet()
        )

        val intType = types.registerType(INT_KEY, IntComponent::class.java)
        types.registerType(CORRUPT_KEY, CorruptibleComponent::class.java)
        val candidates = mapper.captureOpaqueComponents(chunk)
        val resolutions = mapper.decodeOpaqueComponents(candidates)

        val result = mapper.applyOpaqueComponents(chunk, resolutions)

        assertEquals(OpaqueUpdateResult(1, 1, 1, 0), result)
        assertSame(entity, chunk.getCitadel(4, 50, 6))
        assertEquals(73, assertNotNull(chunk.getComponent(entity, intType)).value)
        assertEquals(intType.flag, entity.components())
        assertEquals(listOf(UNKNOWN_KEY), chunk.captureOpaqueComponents().map { it.key })
        assertEquals(CORRUPT_KEY, corrupted.single().key)

        val savedComponents = mapper.snapshot(chunk).citadels.single().components
        assertEquals(listOf(INT_KEY, UNKNOWN_KEY), savedComponents.map { it.key })
        assertContentEquals(
            unknownPayload,
            savedComponents.single { it.key == UNKNOWN_KEY }.payload
        )
    }

    @Test
    fun `opaque update skips a candidate changed between capture and apply`() {
        val packedPosition = ChunkCord.packPosition(2, 30, 4)
        val types = TypeManager()
        val mapper = ChunkCordSnapshotMapper(types)
        val chunk = mapper.restore(
            ChunkCordSnapshot(
                UUID.randomUUID(), 0, 0,
                listOf(
                    CitadelSnapshot(
                        packedPosition,
                        listOf(ComponentSnapshot(INT_KEY, IntComponent(31).toByteArray()))
                    )
                )
            )
        )
        val entity = assertNotNull(chunk.getCitadel(2, 30, 4))
        val candidates = mapper.captureOpaqueComponents(chunk)
        val intType = types.registerType(INT_KEY, IntComponent::class.java)
        val resolutions = mapper.decodeOpaqueComponents(candidates)

        assertNull(chunk.removeComponent(entity, intType))

        assertEquals(
            OpaqueUpdateResult(skipped = 1),
            mapper.applyOpaqueComponents(chunk, resolutions)
        )
        assertTrue(chunk.captureOpaqueComponents().isEmpty())
        assertNull(chunk.getComponent(entity, intType))
        assertEquals(0L, entity.components())
    }

    @Test
    fun `component construction failure still aborts chunk restoration`() {
        val types = TypeManager()
        types.registerType(INFRASTRUCTURE_KEY, RequiredArgumentComponent::class.java)
        val snapshot = ChunkCordSnapshot(
            UUID.randomUUID(), 0, 0,
            listOf(
                CitadelSnapshot(
                    ChunkCord.packPosition(0, 0, 0),
                    listOf(ComponentSnapshot(INFRASTRUCTURE_KEY, byteArrayOf()))
                )
            )
        )

        assertFailsWith<IOException> {
            ChunkCordSnapshotMapper(types).restore(snapshot)
        }
    }

    @Test
    fun `does not serialize runtime citadel ids`() {
        val chunk = ChunkCord(UUID.randomUUID(), -5, 6)
        val sourceEntity = chunk.putCitadel(6, 70, 8)
        val mapper = ChunkCordSnapshotMapper(TypeManager())
        val snapshot = ChunkCordSnapshot.fromByteArray(mapper.snapshot(chunk).toByteArray())

        val firstEntity = assertNotNull(mapper.restore(snapshot).getCitadel(6, 70, 8))
        val secondEntity = assertNotNull(mapper.restore(snapshot).getCitadel(6, 70, 8))

        assertNotEquals(sourceEntity.id, firstEntity.id)
        assertNotEquals(firstEntity.id, secondEntity.id)
        assertEquals(sourceEntity.packedPosition, firstEntity.packedPosition)
        assertEquals(firstEntity.packedPosition, secondEntity.packedPosition)
    }

    @Test
    fun `version one record contains position but no runtime id`() {
        val chunk = ChunkCord(UUID.randomUUID(), 2, -4)
        val entity = chunk.putCitadel(6, 70, 8)

        val data = ChunkCordSnapshotMapper(TypeManager()).snapshot(chunk).toByteArray()
        val buffer = ByteBuffer.wrap(data)

        assertEquals(1, buffer.getInt(CHUNK_VERSION_OFFSET))
        assertEquals(SINGLE_EMPTY_CITADEL_SIZE, data.size)
        assertEquals(entity.packedPosition, buffer.getInt(FIRST_CITADEL_POSITION_OFFSET))
        assertEquals(0, buffer.getInt(FIRST_COMPONENT_COUNT_OFFSET))
    }

    @Test
    fun `serializes packed positions at both world corners`() {
        val chunk = ChunkCord(UUID.randomUUID(), 0, 0)
        chunk.putCitadel(0, -64, 0)
        chunk.putCitadel(15, 319, 15)
        val mapper = ChunkCordSnapshotMapper(TypeManager())

        val decoded = mapper.restore(
            ChunkCordSnapshot.fromByteArray(mapper.snapshot(chunk).toByteArray())
        )

        assertNotNull(decoded.getCitadel(0, -64, 0))
        assertNotNull(decoded.getCitadel(15, 319, 15))
        assertEquals(2, decoded.size)
    }

    @Test
    fun `rejects invalid packed position in serialized chunk`() {
        val chunk = ChunkCord(UUID.randomUUID(), 0, 0)
        chunk.putCitadel(0, -64, 0)
        val snapshot = ChunkCordSnapshotMapper(TypeManager()).snapshot(chunk)

        for (invalidPosition in listOf(-1, ChunkCord.POSITION_COUNT)) {
            val invalidData = snapshot.toByteArray().copyOf()
            ByteBuffer.wrap(invalidData).putInt(
                FIRST_CITADEL_POSITION_OFFSET,
                invalidPosition
            )

            assertFailsWith<IOException> {
                ChunkCordSnapshot.fromByteArray(invalidData)
            }
        }
    }

    private class IntComponent(
        var value: Int = 0
    ) : Component() {
        override fun write(output: DataOutput) {
            output.writeInt(value)
        }

        override fun read(input: DataInput) {
            value = input.readInt()
        }
    }

    private class OtherComponent : Component() {
        override fun write(output: DataOutput) = Unit

        override fun read(input: DataInput) = Unit
    }

    private class CorruptibleComponent(
        var value: Int = 0
    ) : Component() {
        override fun write(output: DataOutput) {
            output.writeInt(value)
        }

        override fun read(input: DataInput) {
            value = input.readInt()
        }
    }

    private class RequiredArgumentComponent(
        private val value: Int
    ) : Component() {
        override fun write(output: DataOutput) {
            output.writeInt(value)
        }

        override fun read(input: DataInput) {
            input.readInt()
        }
    }

    private companion object {
        const val CHUNK_VERSION_OFFSET = 4
        const val FIRST_CITADEL_POSITION_OFFSET = 36
        const val FIRST_COMPONENT_COUNT_OFFSET = 40
        const val SINGLE_EMPTY_CITADEL_SIZE = 44
        val INT_KEY = Key("test", "int")
        val OTHER_KEY = Key("test", "other")
        val CORRUPT_KEY = Key("test", "corrupt")
        val INFRASTRUCTURE_KEY = Key("test", "infrastructure")
        val UNKNOWN_KEY = Key("test", "unknown")
    }
}
