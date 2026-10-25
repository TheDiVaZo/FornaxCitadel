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
        return ComponentSnapshot(type.key, component.toByteArray())
    }

    fun encode(component: OpaqueComponent): ComponentSnapshot {
        return ComponentSnapshot(component.key, component.toByteArray())
    }

    @Throws(IOException::class)
    fun write(component: ComponentSnapshot, output: DataOutput) {
        val data = component.toByteArray()
        if (data.size > MAX_COMPONENT_DATA_SIZE) {
            throw IOException("Component data is too large: ${data.size}")
        }

        output.writeUTF(component.key.namespace)
        output.writeUTF(component.key.key)
        output.writeInt(data.size)
        output.write(data)
    }

    @Throws(IOException::class)
    fun read(input: DataInput): ComponentSnapshot {
        val namespace = input.readUTF()
        val value = input.readUTF()
        val key = try {
            Key(namespace, value)
        } catch (exception: InvalidKeyException) {
            throw IOException("Invalid component key $namespace:${value}", exception)
        }
        val dataSize = input.readInt()
        val data = readData(input, dataSize)
        return ComponentSnapshot(key, data)
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
        value.safeRead(component.toByteArray())
        return value
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
