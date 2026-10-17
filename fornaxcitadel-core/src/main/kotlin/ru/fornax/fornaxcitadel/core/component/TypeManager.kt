package ru.fornax.fornaxcitadel.core.component

import it.unimi.dsi.fastutil.bytes.Byte2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap

class TypeManager {
    private val typeMap = mutableMapOf<Class<out Component>, ComponentType>()
    private val typeList = mutableListOf<ComponentType>()
    private val typesById = Byte2ObjectOpenHashMap<ComponentType>()
    private val groupsById = Int2ObjectOpenHashMap<ComponentGroup>()

    private var nextFlag = 1L
    private var genGroupID: Short = 0
    private var genTypeID: Byte = 0

    fun getType(c: Class<out Component>): ComponentType {
        var type: ComponentType? = typeMap[c]
        if (type == null) {
            type = ComponentType(c, nextFlag, genTypeID)
            typesById.put(genTypeID, type)
            nextFlag = nextFlag shl 1
            genTypeID++
            typeMap[c] = type
            typeList.add(type)
        }
        return type
    }

    fun getGroup(vararg types: ComponentType): ComponentGroup {
        var group: ComponentGroup?
        var mask: Long = 0
        for (type in types) mask = mask or type.flag
        group = lookUpGroup(mask)
        if (group == null) {
            group = ComponentGroup(null, mask, genGroupID)
            groupsById.put(group.id.toInt(), group)
            genGroupID++
        }
        return group
    }

    private fun lookUpGroup(mask: Long): ComponentGroup? {
        for (group in groupsById.values) {
            if (group.match(mask)) return group
        }
        return null
    }
}