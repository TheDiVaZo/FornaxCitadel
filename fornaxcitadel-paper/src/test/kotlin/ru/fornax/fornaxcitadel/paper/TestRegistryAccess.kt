package ru.fornax.fornaxcitadel.paper

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import net.kyori.adventure.key.Key
import org.bukkit.Keyed
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.block.BlockType
import org.bukkit.block.data.BlockData

/**
 * Test metadata for the few block types used by our fixtures, not a simulated Paper server.
 * Material.isBlock/isSolid execute their real API code against this registry.
 */
class TestRegistryAccess : RegistryAccess {
    private val registries = mutableMapOf<String, Registry<Keyed>>()

    // Registry has eager static fields. Do not create its proxies while ServiceLoader is
    // constructing this provider: RegistryAccessHolder has not stored the instance yet.
    @Suppress("UNCHECKED_CAST")
    override fun <T : Keyed> getRegistry(registryKey: RegistryKey<T>): Registry<T> =
        registry(registryKey.key().asString()) as Registry<T>

    @Suppress("UNCHECKED_CAST", "OVERRIDE_DEPRECATION", "OVERRIDE_DEPRECATION_ERROR", "removal")
    override fun <T : Keyed> getRegistry(type: Class<T>): Registry<T> =
        registry(if (type == BlockType::class.java) "minecraft:block" else "legacy:${type.name}") as Registry<T>

    private val blocks: Map<String, BlockType.Typed<BlockData>> by lazy {
        val solid = setOf(
            Material.STONE, Material.COBBLESTONE, Material.STONE_BRICKS,
            Material.DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.DEEPSLATE_BRICKS,
            Material.BRICKS, Material.OBSIDIAN, Material.CRYING_OBSIDIAN,
            Material.BLACKSTONE, Material.POLISHED_BLACKSTONE, Material.NETHER_BRICKS,
            Material.END_STONE, Material.IRON_BLOCK, Material.GOLD_BLOCK, Material.DIAMOND_BLOCK,
            Material.EMERALD_BLOCK, Material.NETHERITE_BLOCK,
            Material.SAND, Material.CHEST, Material.BEDROCK, Material.OAK_PLANKS, Material.STONE_SLAB,
            // Force the predicate to exercise its explicit piston exclusion.
            Material.PISTON, Material.STICKY_PISTON, Material.PISTON_HEAD, Material.MOVING_PISTON
        )
        val nonSolid = setOf(Material.WATER, Material.LAVA, Material.AIR, Material.CAVE_AIR, Material.VOID_AIR)
        (solid + nonSolid).associate { material ->
            material.key.asString() to blockType(material, material in solid)
        }
    }

    private fun registry(name: String): Registry<Keyed> = registries.getOrPut(name) {
        paperProxy("test registry $name") { method, args ->
            val entries = if (name == "minecraft:block") blocks else emptyMap()
            when (method.name) {
                "get", "getOrThrow" -> {
                    val key = when (val argument = args[0]) {
                        is Key -> argument.asString()
                        is TypedKey<*> -> argument.key().asString()
                        else -> error("Unexpected registry key: $argument")
                    }
                    val value = entries[key]
                    if (value == null && method.name == "getOrThrow") {
                        throw NoSuchElementException("No test metadata for $name/$key")
                    }
                    value
                }
                "getKey", "getKeyOrThrow" -> (args[0] as Keyed).key
                "iterator" -> entries.values.iterator()
                "stream" -> entries.values.stream()
                "keyStream" -> entries.values.stream().map { it.key }
                "size" -> entries.size
                else -> unsupported(method)
            }
        }
    }

    private fun blockType(material: Material, solid: Boolean): BlockType.Typed<BlockData> {
        val key = NamespacedKey.minecraft(material.key.key)
        lateinit var type: BlockType.Typed<BlockData>
        type = paperProxy("test block type $key") { method, _ ->
            when (method.name) {
                "getKey", "key" -> key
                "asMaterial" -> material
                "isSolid" -> solid
                "isAir" -> material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR
                "typed" -> type
                "getBlockDataClass" -> BlockData::class.java
                else -> unsupported(method)
            }
        }
        return type
    }
}
