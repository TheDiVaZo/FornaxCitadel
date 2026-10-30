package ru.fornax.fornaxcitadel.paper.material

import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import ru.fornax.fornaxcitadel.core.util.Key

/** Custom registries are consulted in registration order, followed by vanilla materials. */
class CombineMaterialRegistry(vararg namespaceToRegistry: Pair<String, MaterialRegistry>) : MaterialRegistry {
    private val registries = linkedMapOf<String, MaterialRegistry>()

    init {
        for ((namespace, registry) in namespaceToRegistry) {
            require(register(namespace, registry)) { "Material namespace $namespace is already registered" }
        }
    }

    fun register(namespace: String, registry: MaterialRegistry): Boolean {
        require(namespace.isNotBlank()) { "Material namespace must not be blank" }
        Key(namespace, "material")
        require(namespace != "minecraft") { "The minecraft namespace is reserved for vanilla materials" }
        require(registry !== this) { "A material registry cannot contain itself" }
        return registries.putIfAbsent(namespace, registry) == null
    }

    override fun getType(key: Key): MaterialAdapter? =
        if (key.namespace == "minecraft") BukkitMaterialRegistry.getType(key)
        else registries[key.namespace]?.getType(key)

    override fun getTypeOrNull(stack: ItemStack): MaterialAdapter? {
        if (!stack.hasMaterial()) return null
        for (registry in registries.values) {
            registry.getTypeOrNull(stack)?.let { return it }
        }
        return BukkitMaterialRegistry.getTypeOrNull(stack)
    }

    override fun getTypeOrNull(block: Block): MaterialAdapter? {
        for (registry in registries.values) {
            registry.getTypeOrNull(block)?.let { return it }
        }
        return BukkitMaterialRegistry.getTypeOrNull(block)
    }
}
