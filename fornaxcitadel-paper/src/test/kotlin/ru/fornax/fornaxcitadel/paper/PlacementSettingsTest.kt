package ru.fornax.fornaxcitadel.paper

import org.bukkit.configuration.file.YamlConfiguration
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.modules.placement.BlockListType
import ru.fornax.fornaxcitadel.paper.modules.placement.PlacementSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlacementSettingsTest {
    @Test
    fun `material lists accept vanilla aliases and unresolved custom keys`() {
        val config = YamlConfiguration().apply {
            loadFromString("""
                placement:
                  block-list:
                    type: blacklist
                    blocks: [STONE, minecraft:stone, customblocks:reinforced_stone]
            """.trimIndent())
        }

        val settings = PlacementSettings.read(config)

        assertEquals(BlockListType.BLACKLIST, settings.listType)
        assertEquals(setOf(Key("minecraft", "stone"), Key("customblocks", "reinforced_stone")), settings.blocks)
    }

    @Test
    fun `missing lists preserve defaults while explicit empty lists keep their meaning`() {
        val defaults = PlacementSettings.read(YamlConfiguration())
        assertEquals(BlockListType.WHITELIST, defaults.listType)
        assertEquals(18, defaults.blocks.size)
        assertTrue(Key("minecraft", "stone") in defaults.blocks)
        assertTrue(Key("minecraft", "netherite_block") in defaults.blocks)

        for (type in BlockListType.entries) {
            val config = YamlConfiguration().apply {
                set("placement.block-list.type", type.name)
            }
            val implied = PlacementSettings.read(config)
            assertEquals(if (type == BlockListType.BLACKLIST) emptySet() else defaults.blocks, implied.blocks)

            config.set("placement.block-list.blocks", emptyList<String>())
            val explicit = PlacementSettings.read(config)
            assertEquals(type, explicit.listType)
            assertTrue(explicit.blocks.isEmpty())
        }
    }

    @Test
    fun `bundled defaults do not fill an explicit blacklist`() {
        val bundled = YamlConfiguration().apply {
            set("placement.block-list.type", "WHITELIST")
            set("placement.block-list.blocks", listOf("STONE"))
        }
        val explicit = YamlConfiguration().apply {
            set("placement.block-list.type", "BLACKLIST")
            setDefaults(bundled)
            options().copyDefaults(true)
        }
        assertEquals(PlacementSettings(BlockListType.BLACKLIST, emptySet()), PlacementSettings.read(explicit))
    }

    @Test
    fun `invalid list structure type and material identities are rejected`() {
        val invalidValues = listOf(
            "placement" to "WHITELIST",
            "placement.block-list" to "WHITELIST",
            "placement.block-list.type" to "GREYLIST",
            "placement.block-list.type" to true,
            "placement.block-list.blocks" to "minecraft:stone",
            "placement.block-list.blocks" to listOf(12),
            "placement.block-list.blocks" to listOf(""),
            "placement.block-list.blocks" to listOf("unknown_vanilla_material"),
            "placement.block-list.blocks" to listOf("minecraft:unknown_material"),
            "placement.block-list.blocks" to listOf("LEGACY_STONE"),
            "placement.block-list.blocks" to listOf(":stone"),
            "placement.block-list.blocks" to listOf("custom:"),
            "placement.block-list.blocks" to listOf("custom:invalid value"),
            "placement.block-list.blocks" to listOf("custom:extra:separator")
        )
        for ((path, value) in invalidValues) {
            val config = YamlConfiguration().apply { set(path, value) }
            assertFailsWith<IllegalArgumentException>("$path=$value") { PlacementSettings.read(config) }
        }
        assertFailsWith<IllegalArgumentException> { PlacementSettings(blocks = setOf(Key("", "stone"))) }
        assertFailsWith<IllegalArgumentException> { PlacementSettings(blocks = setOf(Key("custom", ""))) }
    }
}
