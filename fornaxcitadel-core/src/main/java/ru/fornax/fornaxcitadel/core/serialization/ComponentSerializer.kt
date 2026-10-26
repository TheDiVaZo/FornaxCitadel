package ru.fornax.fornaxcitadel.core.serialization

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.component.ComponentType
import ru.fornax.fornaxcitadel.core.component.OpaqueComponent
import ru.fornax.fornaxcitadel.core.storage.ComponentSnapshot
import ru.fornax.fornaxcitadel.core.util.InvalidKeyException
import ru.fornax.fornaxcitadel.core.util.Key
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException

sealed interface ComponentDecodeResult {
    data class Decoded(
        val type: ComponentType<out Component>,
        val component: Component
    ) : ComponentDecodeResult

    data object Unknown : ComponentDecodeResult

    data class Corrupted(
        val cause: Throwable
    ) : ComponentDecodeResult

    data class InfrastructureFailure(
        val cause: Throwable
    ) : ComponentDecodeResult
}

object ComponentSerializer {
    private const val MAX_COMPONENT_DATA_SIZE = 16 * 1024 * 1024

    fun encode(
        type: ComponentType<*>,
        component: Component
    ): ComponentSnapshot {
        if (!type.componentClass.isInstance(component)) {
            throw IllegalArgumentException(
                "Component ${component.javaClass.name} does not match type ${type.componentClass.name}"
            )
        }
        return ComponentSnapshot.takeOwnership(type.key, component.toByteArray())
    }

    fun encode(component: OpaqueComponent): ComponentSnapshot {
        return ComponentSnapshot(component.key, component.payloadData)
    }

    @Throws(IOException::class)
    fun write(component: ComponentSnapshot, output: DataOutput) {
        if (component.payloadData.size > MAX_COMPONENT_DATA_SIZE) {
            throw IOException("Component data is too large: ${component.payloadData.size}")
        }

        output.writeUTF(component.key.namespace)
        output.writeUTF(component.key.key)
        output.writeInt(component.payloadData.size)
        component.payloadData.writeTo(output)
    }

    @Throws(IOException::class)
    fun read(input: DataInput): ComponentSnapshot {
        return read(input) { it }
    }

    @Throws(IOException::class)
    internal fun read(
        input: DataInput,
        canonicalizeKey: (Key) -> Key
    ): ComponentSnapshot {
        val namespace = input.readUTF()
        val value = input.readUTF()
        val parsedKey = try {
            Key(namespace, value)
        } catch (exception: InvalidKeyException) {
            throw IOException("Invalid component key $namespace:${value}", exception)
        }
        val key = canonicalizeKey(parsedKey)
        val dataSize = input.readInt()
        val data = readData(input, dataSize)
        return ComponentSnapshot.takeOwnership(key, data)
    }

    @Throws(IOException::class)
    fun <T : Component> decode(
        component: ComponentSnapshot,
        type: ComponentType<T>
    ): T {
        if (component.key != type.key) {
            throw IOException("Component key ${component.key} does not match type ${type.key}")
        }

        val value = createComponent(type)
        component.payloadData.readInto(value)
        return value
    }

    fun decodeSafely(
        component: ComponentSnapshot,
        type: ComponentType<out Component>?
    ): ComponentDecodeResult {
        if (type == null) return ComponentDecodeResult.Unknown

        if (component.key != type.key) {
            return ComponentDecodeResult.InfrastructureFailure(
                IOException(
                    "Component key ${component.key} does not match type ${type.key}"
                )
            )
        }

        val value = try {
            createUntypedComponent(type)
        } catch (exception: IOException) {
            return ComponentDecodeResult.InfrastructureFailure(exception)
        } catch (exception: LinkageError) {
            return ComponentDecodeResult.InfrastructureFailure(exception)
        }

        return try {
            component.payloadData.readInto(value)
            ComponentDecodeResult.Decoded(type, value)
        } catch (exception: IOException) {
            ComponentDecodeResult.Corrupted(exception)
        } catch (exception: RuntimeException) {
            ComponentDecodeResult.Corrupted(exception)
        } catch (exception: LinkageError) {
            ComponentDecodeResult.InfrastructureFailure(exception)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Throws(IOException::class)
    private fun createUntypedComponent(
        type: ComponentType<out Component>
    ): Component {
        return createComponent(type as ComponentType<Component>)
    }

    @Throws(IOException::class)
    private fun <T : Component> createComponent(type: ComponentType<T>): T {
        try {
            val constructor = type.componentClass.getDeclaredConstructor()
            if (!constructor.canAccess(null)) {
                constructor.trySetAccessible()
            }
            return constructor.newInstance()
        } catch (exception: ReflectiveOperationException) {
            throw IOException("Cannot create component ${type.key}", exception)
        } catch (exception: SecurityException) {
            throw IOException("Cannot create component ${type.key}", exception)
        } catch (exception: RuntimeException) {
            throw IOException("Cannot create component ${type.key}", exception)
        }
    }

    @Throws(IOException::class)
    private fun readData(input: DataInput, size: Int): ByteArray {
        if (size !in 0..MAX_COMPONENT_DATA_SIZE) {
            throw IOException("Invalid component data size: $size")
        }

        return ByteArray(size).also(input::readFully)
    }
}
