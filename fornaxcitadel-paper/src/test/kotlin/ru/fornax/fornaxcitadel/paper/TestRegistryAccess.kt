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
import org.bukkit.inventory.ItemType
import org.bukkit.inventory.meta.ItemMeta
import java.lang.reflect.Modifier

/**
 * Test metadata for fixture blocks and API item forms, not a simulated Paper server.
 * Material.isBlock/isItem/isSolid/isInteractable/getHardness execute their real API code against this registry.
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
        registry(when (type) {
            BlockType::class.java -> "minecraft:block"
            ItemType::class.java -> "minecraft:item"
            else -> "legacy:${type.name}"
        }) as Registry<T>

    private val blocks: Map<String, BlockType.Typed<BlockData>> by lazy {
        val unbreakable = setOf(
            Material.BEDROCK, Material.BARRIER, Material.END_PORTAL_FRAME,
            Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.MOVING_PISTON,
            Material.END_PORTAL, Material.END_GATEWAY, Material.NETHER_PORTAL, Material.LIGHT
        )
        val solid = setOf(
            Material.STONE, Material.COBBLESTONE, Material.STONE_BRICKS,
            Material.DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.DEEPSLATE_BRICKS,
            Material.BRICKS, Material.OBSIDIAN, Material.CRYING_OBSIDIAN,
            Material.BLACKSTONE, Material.POLISHED_BLACKSTONE, Material.NETHER_BRICKS,
            Material.END_STONE, Material.IRON_BLOCK, Material.GOLD_BLOCK, Material.DIAMOND_BLOCK,
            Material.EMERALD_BLOCK, Material.NETHERITE_BLOCK,
            Material.SAND, Material.CHEST, Material.TRAPPED_CHEST, Material.CHISELED_BOOKSHELF,
            Material.BEDROCK, Material.BARRIER, Material.END_PORTAL_FRAME,
            Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.TNT,
            Material.OAK_PLANKS, Material.STONE_SLAB,
            Material.OAK_DOOR, Material.IRON_DOOR, Material.RED_BED, Material.BLUE_BED,
            // Force the predicate to exercise its explicit piston exclusion.
            Material.PISTON, Material.STICKY_PISTON, Material.PISTON_HEAD, Material.MOVING_PISTON
        ) + copperChestMaterials
        val nonSolid = setOf(Material.WATER, Material.LAVA, Material.AIR, Material.CAVE_AIR, Material.VOID_AIR,
            Material.END_PORTAL, Material.END_GATEWAY, Material.NETHER_PORTAL, Material.LIGHT)
        val interactable = setOf(Material.CHEST, Material.TRAPPED_CHEST, Material.CHISELED_BOOKSHELF,
            Material.OAK_DOOR, Material.IRON_DOOR, Material.RED_BED, Material.BLUE_BED) + copperChestMaterials
        (solid + nonSolid).associate { material ->
            val hardness = when {
                material in unbreakable -> -1.0f
                material == Material.TNT -> 0.0f
                else -> 1.0f
            }
            material.key.asString() to blockType(material, material in solid, material in interactable, hardness)
        }
    }

    private val items: Map<String, ItemType.Typed<ItemMeta>> by lazy {
        // Read declarations only: field values initialize ItemType, and Material.isItem queries this registry.
        val itemNames = ItemType::class.java.declaredFields.asSequence()
            .filter { Modifier.isStatic(it.modifiers) && ItemType::class.java.isAssignableFrom(it.type) }
            .map { it.name }
            .toSet()
        Material.entries.asSequence()
            .filter { !it.isLegacy && it.name in itemNames }
            .associate { material -> material.key.asString() to itemType(material) }
    }

    private fun registry(name: String): Registry<Keyed> = registries.getOrPut(name) {
        paperProxy("test registry $name") { method, args ->
            val entries: Map<String, Keyed> = when (name) {
                "minecraft:block" -> blocks
                "minecraft:item" -> items
                else -> emptyMap()
            }
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

    private fun itemType(material: Material): ItemType.Typed<ItemMeta> {
        val key = NamespacedKey.minecraft(material.key.key)
        lateinit var type: ItemType.Typed<ItemMeta>
        type = paperProxy("test item type $key") { method, _ ->
            when (method.name) {
                "getKey", "key" -> key
                "asMaterial" -> material
                "typed" -> type
                "getItemMetaClass" -> ItemMeta::class.java
                else -> unsupported(method)
            }
        }
        return type
    }

    private fun blockType(material: Material, solid: Boolean, interactable: Boolean, hardness: Float): BlockType.Typed<BlockData> {
        val key = NamespacedKey.minecraft(material.key.key)
        lateinit var type: BlockType.Typed<BlockData>
        type = paperProxy("test block type $key") { method, _ ->
            when (method.name) {
                "getKey", "key" -> key
                "asMaterial" -> material
                "isSolid" -> solid
                "isInteractable" -> interactable
                "getHardness" -> hardness
                "isAir" -> material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR
                "typed" -> type
                "getBlockDataClass" -> BlockData::class.java
                else -> unsupported(method)
            }
        }
        return type
    }
}
