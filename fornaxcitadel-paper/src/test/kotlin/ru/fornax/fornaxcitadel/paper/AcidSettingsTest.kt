package ru.fornax.fornaxcitadel.paper

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import ru.fornax.fornaxcitadel.paper.modules.acid.*
import kotlin.test.*

class AcidSettingsTest {
    @Test
    fun `bundled defaults match settings and an empty catalog stays empty`() {
        val bundled = YamlConfiguration()
        javaClass.getResourceAsStream("/config.yml")!!.reader().use { bundled.load(it) }
        assertEquals(AcidSettings(), AcidSettings.read(bundled))
        assertEquals(emptyList(), read("acid:\n  types: {}").types)
        assertEquals(AcidSettings(), read(""))
    }

    @Test
    fun `identifiers are independent of materials and disabled duplicates are allowed`() {
        val settings = read("""
            acid:
              types:
                siege:
                  material: EMERALD_BLOCK
                  max-targets: 4
                  maturation-multiplier: 1.25
                retired:
                  enabled: false
                  material: EMERALD_BLOCK
                  max-targets: 1
                  maturation-multiplier: 1
        """.trimIndent())
        assertEquals("siege", AcidRegistry(settings).find(Material.EMERALD_BLOCK)?.id)
        assertNull(AcidRegistry(settings).find(Material.GOLD_BLOCK))
    }

    @Test
    fun `invalid values disable only acid when reading plugin settings`() {
        val invalidTypes = listOf(
            "material: STICK\nmax-targets: 1\nmaturation-multiplier: 1",
            "material: AIR\nmax-targets: 1\nmaturation-multiplier: 1",
            "material: NOT_A_BLOCK\nmax-targets: 1\nmaturation-multiplier: 1",
            "material: GOLD_BLOCK\nmax-targets: 0\nmaturation-multiplier: 1",
            "material: GOLD_BLOCK\nmax-targets: 7\nmaturation-multiplier: 1",
            "material: GOLD_BLOCK\nmax-targets: 1.5\nmaturation-multiplier: 1",
            "material: GOLD_BLOCK\nmax-targets: 1\nmaturation-multiplier: 0.5",
            "material: GOLD_BLOCK\nmax-targets: 1\nmaturation-multiplier: .NaN",
            "material: GOLD_BLOCK\nmax-targets: 1\nmaturation-multiplier: .inf",
            "material: GOLD_BLOCK\nmax-targets: 1\nmaturation-multiplier: 1\nenabled: text"
        ).map { "acid:\n  types:\n    bad:\n" + it.prependIndent("      ") }
        val invalid = invalidTypes + listOf("acid: []", "acid:\n  activation: []", "acid:\n  activation:\n    max-distance: 0",
            "acid:\n  enabled: 'false'")
        for (text in invalid) {
            val config = YamlConfiguration().apply { loadFromString(text) }
            assertFailsWith<IllegalArgumentException>(text) { AcidSettings.read(config) }
            val plugin = PaperSettings.read(config)
            assertFalse(plugin.acid.enabled, text)
            assertNotNull(plugin.acidConfigurationError, text)
            assertTrue(plugin.citadelTypes.types.isNotEmpty())
        }
    }

    @Test
    fun `removed drop settings are rejected as unknown fields`() {
        for (drops in listOf("{}", "false", "\n    target-block: true\n    target-reinforcement: false\n    acid-block: false\n    acid-reinforcement: true")) {
            val config = YamlConfiguration().apply { loadFromString("acid:\n  drops: $drops") }
            val failure = assertFailsWith<IllegalArgumentException> { AcidSettings.read(config) }
            assertEquals("Unknown field in acid: [drops]", failure.message)
            val plugin = PaperSettings.read(config)
            assertFalse(plugin.acid.enabled)
            assertNotNull(plugin.acidConfigurationError)
            assertTrue(plugin.citadelTypes.types.isNotEmpty())
        }
    }

    @Test
    fun `duplicate active materials and invalid distances cannot be installed`() {
        assertFailsWith<IllegalArgumentException> {
            AcidSettings(types = listOf(AcidType("one", Material.STONE, 1, 1.0), AcidType("two", Material.STONE, 2, 2.0)))
        }
        assertFailsWith<IllegalArgumentException> { AcidSettings(maxDistance = -1) }
        assertNull(AcidRegistry(AcidSettings(enabled = false)).find(Material.GOLD_BLOCK))
    }

    @Test
    fun `raw mappings accept exact whole numbers and numeric failures retain the acid path`() {
        val config = YamlConfiguration().apply {
            set("acid", mapOf(
                "activation" to mapOf("max-distance" to java.math.BigDecimal("5.000")),
                "types" to mapOf("custom" to mapOf(
                    "material" to "GOLD_BLOCK", "max-targets" to 2.0, "maturation-multiplier" to 1
                ))
            ))
        }
        val settings = AcidSettings.read(config)
        assertEquals(5, settings.maxDistance)
        assertEquals(2, settings.types.single().maxTargets)

        val path = "acid.activation.max-distance"
        for ((value, message) in listOf(
            "5" to "$path must be an integer",
            5.5 to "$path must be an integer fitting in Int",
            Int.MAX_VALUE.toLong() + 1 to "$path must be an integer fitting in Int",
            Double.NaN to "$path must be an integer fitting in Int"
        )) {
            val invalid = YamlConfiguration().apply { set(path, value) }
            val failure = assertFailsWith<IllegalArgumentException> { AcidSettings.read(invalid) }
            assertEquals(message, failure.message)
            val plugin = PaperSettings.read(invalid)
            assertFalse(plugin.acid.enabled)
            assertEquals(message, plugin.acidConfigurationError)
        }
    }

    @Test
    fun `required acid fields do not inherit copied Bukkit defaults`() {
        val defaults = YamlConfiguration().apply {
            loadFromString("acid: {types: {gold: {material: GOLD_BLOCK, max-targets: 1, maturation-multiplier: 2}}}")
        }
        val configured = YamlConfiguration().apply {
            loadFromString("acid: {types: {gold: {material: GOLD_BLOCK, maturation-multiplier: 2}}}")
            setDefaults(defaults)
            options().copyDefaults(true)
        }
        val failure = assertFailsWith<IllegalArgumentException> { AcidSettings.read(configured) }
        assertEquals("acid.types.gold.max-targets must be an integer", failure.message)
    }

    private fun read(text: String) = AcidSettings.read(YamlConfiguration().apply { loadFromString(text) })
}
