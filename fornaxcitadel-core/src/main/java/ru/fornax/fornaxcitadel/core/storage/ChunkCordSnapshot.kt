package ru.fornax.fornaxcitadel.core.storage

import ru.fornax.fornaxcitadel.core.serialization.ComponentSerializer
import ru.fornax.fornaxcitadel.core.serialization.ReadAndWriteAble
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.util.Collections
import java.util.UUID

class ChunkCordSnapshot(
    world: UUID,
    x: Int,
    z: Int,
    citadels: Collection<CitadelSnapshot> = emptyList()
) : WXZCord(world, x, z) {
    val citadels: List<CitadelSnapshot> = immutableCopy(citadels)

    init {
        require(this.citadels.size <= MAX_CITADEL_COUNT) {
            "Too many citadels: ${this.citadels.size}"
        }

        val positions = HashSet<Int>(this.citadels.size)
        for (citadel in this.citadels) {
            require(positions.add(citadel.packedPosition)) {
                "Duplicate citadel position: ${citadel.packedPosition}"
            }
        }
    }

    fun toByteArray(): ByteArray {
        return try {
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use(::write)
                bytes.toByteArray()
            }
        } catch (exception: IOException) {
            throw UncheckedIOException(exception)
        }
    }

    @Throws(IOException::class)
    fun write(output: DataOutput) {
        output.writeInt(CHUNK_MAGIC)
        output.writeInt(CHUNK_FORMAT_VERSION)
        ReadAndWriteAble.writeWXZCord(this, output)
        output.writeInt(citadels.size)

        for (citadel in citadels) {
            output.writeInt(citadel.packedPosition)
            output.writeInt(citadel.components.size)
            for (component in citadel.components) {
                ComponentSerializer.write(component, output)
            }
        }
    }

    companion object {
        private const val CHUNK_MAGIC = 0x46435843
        private const val CHUNK_FORMAT_VERSION = 1
        private const val MAX_CITADEL_COUNT = ChunkCord.POSITION_COUNT
        internal const val MAX_STORED_COMPONENT_COUNT = 4_096

        @JvmStatic
        @Throws(IOException::class)
        fun fromByteArray(bytes: ByteArray): ChunkCordSnapshot {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val snapshot = read(input)
                if (input.available() != 0) {
                    throw IOException("Unread trailing data: ${input.available()} bytes")
                }
                return snapshot
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun read(input: DataInput): ChunkCordSnapshot {
            val magic = input.readInt()
            if (magic != CHUNK_MAGIC) {
                throw IOException("Invalid chunk data magic: ${magic.toUInt().toString(16)}")
            }

            val version = input.readInt()
            if (version != CHUNK_FORMAT_VERSION) {
                throw IOException("Unsupported chunk data version: $version")
            }

            val coordinates = ReadAndWriteAble.readWXZCord(input)
            val citadelCount = input.readInt()
            if (citadelCount !in 0..MAX_CITADEL_COUNT) {
                throw IOException("Invalid citadel count: $citadelCount")
            }

            val positions = HashSet<Int>(citadelCount)
            val canonicalKeys = HashMap<Key, Key>()
            val citadels = ArrayList<CitadelSnapshot>(citadelCount)
            repeat(citadelCount) {
                val packedPosition = input.readInt()
                validatePackedPosition(packedPosition)
                if (!positions.add(packedPosition)) {
                    throw IOException("Duplicate citadel position: $packedPosition")
                }

                val componentCount = input.readInt()
                if (componentCount !in 0..MAX_STORED_COMPONENT_COUNT) {
                    throw IOException("Invalid component count: $componentCount")
                }

                val componentKeys = HashSet<Key>(componentCount)
                val components = ArrayList<ComponentSnapshot>(componentCount)
                repeat(componentCount) {
                    val component = ComponentSerializer.read(input) { key ->
                        canonicalKeys.getOrPut(key) { key }
                    }
                    if (!componentKeys.add(component.key)) {
                        throw IOException(
                            "Duplicate component key ${component.key} for citadel $packedPosition"
                        )
                    }
                    components.add(component)
                }
                citadels.add(CitadelSnapshot(packedPosition, components))
            }

            return ChunkCordSnapshot(
                coordinates.world,
                coordinates.x,
                coordinates.z,
                citadels
            )
        }

        @Throws(IOException::class)
        private fun validatePackedPosition(packedPosition: Int) {
            if (packedPosition !in 0..ChunkCord.MAX_PACKED_POSITION) {
                throw IOException("Invalid packed citadel position: $packedPosition")
            }
        }
    }
}

class CitadelSnapshot(
    val packedPosition: Int,
    components: Collection<ComponentSnapshot> = emptyList()
) {
    val components: List<ComponentSnapshot> = immutableCopy(components)

    init {
        require(packedPosition in 0..ChunkCord.MAX_PACKED_POSITION) {
            "Invalid packed position: $packedPosition"
        }
        require(this.components.size <= ChunkCordSnapshot.MAX_STORED_COMPONENT_COUNT) {
            "Too many components: ${this.components.size}"
        }

        val keys = HashSet<Key>(this.components.size)
        for (component in this.components) {
            require(keys.add(component.key)) {
                "Duplicate component key ${component.key} for citadel $packedPosition"
            }
        }
    }
}

class ComponentSnapshot internal constructor(
    val key: Key,
    internal val payloadData: ImmutablePayload
) {
    constructor(key: Key, payload: ByteArray) : this(
        key,
        ImmutablePayload.copyOf(payload)
    )

    val payload: ByteArray
        get() = payloadData.copy()

    fun toByteArray(): ByteArray = payloadData.copy()

    internal companion object {
        fun takeOwnership(key: Key, payload: ByteArray): ComponentSnapshot {
            return ComponentSnapshot(key, ImmutablePayload.takeOwnership(payload))
        }
    }
}

private fun <T> immutableCopy(values: Collection<T>): List<T> {
    return Collections.unmodifiableList(ArrayList(values))
}
