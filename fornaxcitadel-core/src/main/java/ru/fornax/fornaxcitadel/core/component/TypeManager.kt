package ru.fornax.fornaxcitadel.core.component

import it.unimi.dsi.fastutil.bytes.Byte2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.Collections

class TypeManager {
    private val typeLock = Any()
    private val typeMap = mutableMapOf<Class<out Component>, ComponentType<out Component>>()
    private val typesByKey = mutableMapOf<Key, ComponentType<out Component>>()
    private val typeList = mutableListOf<ComponentType<out Component>>()
    private val typesById = Byte2ObjectOpenHashMap<ComponentType<out Component>>()
    private val groupsById = Long2ObjectOpenHashMap<ComponentGroup>()

    private var nextFlag = 1L
    private var genGroupID: Short = 0
    private var genTypeID: Byte = 0

    @Suppress("UNCHECKED_CAST")
    fun <T : Component> getType(componentClass: Class<T>): ComponentType<T>? {
        return synchronized(typeLock) {
            typeMap[componentClass] as ComponentType<T>?
        }
    }

    fun getType(key: Key): ComponentType<out Component>? {
        return synchronized(typeLock) {
            typesByKey[key]
        }
    }

    internal fun getTypes(
        keys: Collection<Key>
    ): Map<Key, ComponentType<out Component>> {
        return synchronized(typeLock) {
            val result = LinkedHashMap<Key, ComponentType<out Component>>()
            for (key in keys) {
                typesByKey[key]?.let { result[key] = it }
            }
            Collections.unmodifiableMap(result)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Component> registerType(
        key: Key,
        componentClass: Class<T>
    ): ComponentType<T> {
        return synchronized(typeLock) {
            val keyType = typesByKey[key]
            if (keyType != null) {
                require(keyType.componentClass == componentClass) {
                    "Component key $key is already registered for ${keyType.componentClass.name}"
                }
                return@synchronized keyType as ComponentType<T>
            }

            val classType = typeMap[componentClass]
            require(classType == null) {
                "Component class ${componentClass.name} is already registered as ${classType?.key}"
            }

            if (genTypeID >= MAX_COMPONENT_COUNT) {
                throw IllegalStateException("max components has been registered.")
            }

            val type = ComponentType(key, componentClass, nextFlag, genTypeID)
            typesById.put(genTypeID, type)
            nextFlag = nextFlag shl 1
            genTypeID++
            typeMap[componentClass] = type
            typesByKey[key] = type
            typeList.add(type)

            type
        }
    }

    fun getOrRegisterGroup(name: String? = null, vararg types: ComponentType<*>): ComponentGroup {
        var group: ComponentGroup?
        var mask: Long = 0
        for (type in types) mask = mask or type.flag
        group = groupsById.get(mask)
        if (group == null) {
            group = ComponentGroup(name, mask, genGroupID)
            groupsById.put(group.mask, group)
            genGroupID++
        }
        return group
    }

    companion object {
        const val MAX_COMPONENT_COUNT = 64
    }
}
