package ru.fornax.fornaxcitadel.paper

import org.bukkit.configuration.file.YamlConfiguration
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.MaturingHealthBehavior
import ru.fornax.fornaxcitadel.modules.health.SimpleHealthBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CitadelTypesSettingsTest {
    @Test
    fun `missing catalog provides all seven defaults and ignores obsolete health settings`() {
        val config = YamlConfiguration().apply {
            set("health.reinforced", 123.0)
            set("health.dev", -1.0)
        }
        val types = CitadelTypesSettings.read(config).types

        assertEquals(linkedMapOf(
            "reinforced_light" to CitadelTypeSettings(Key("fornaxcitadel", "reinforced_light"),
                health = HealthBehaviorSettings.Simple(10.0)),
            "reinforced" to CitadelTypeSettings(Key("fornaxcitadel", "reinforced"),
                health = HealthBehaviorSettings.Simple(20.0)),
            "reinforced_heavy" to CitadelTypeSettings(Key("fornaxcitadel", "reinforced_heavy"),
                health = HealthBehaviorSettings.Simple(30.0)),
            "maturing_fast" to CitadelTypeSettings(Key("fornaxcitadel", "maturing_fast"),
                health = HealthBehaviorSettings.Maturing(10.0, 20.0, 300)),
            "maturing" to CitadelTypeSettings(Key("fornaxcitadel", "maturing"),
                health = HealthBehaviorSettings.Maturing(10.0, 40.0, 600)),
            "maturing_strong" to CitadelTypeSettings(Key("fornaxcitadel", "maturing_strong"),
                health = HealthBehaviorSettings.Maturing(20.0, 80.0, 1800)),
            "invulnerable" to CitadelTypeSettings(Key("fornaxcitadel", "invulnerable"), CitadelBreakPolicy.UNBREAKABLE)
        ), types)
    }

    @Test
    fun `custom catalog selects health behavior and permits health on an unbreakable type`() {
        val config = yaml("""
            citadel-types:
              copper_wall:
                item: diamond
                health: {behavior: simple, max: 35.5}
              growing-wall:
                item: minecraft:emerald
                break-policy: damage
                health: {behavior: maturing, initial: 4, mature: 12, duration-seconds: 90}
              protected_wall:
                item: custommaterials:wand/defender
                break-policy: unbreakable
                health: {behavior: simple, max: 100}
              protected_marker:
                item: fornaxcitadel:invulnerable
                break-policy: unbreakable
        """)
        val types = CitadelTypesSettings.read(config).types

        assertEquals(setOf("copper_wall", "growing-wall", "protected_wall", "protected_marker"), types.keys)
        assertEquals(CitadelTypeSettings(Key("minecraft", "diamond"), health = HealthBehaviorSettings.Simple(35.5)),
            types["copper_wall"])
        assertEquals(CitadelTypeSettings(Key("minecraft", "emerald"), health = HealthBehaviorSettings.Maturing(4.0, 12.0, 90)),
            types["growing-wall"])
        assertEquals(CitadelTypeSettings(Key("custommaterials", "wand/defender"),
            CitadelBreakPolicy.UNBREAKABLE, HealthBehaviorSettings.Simple(100.0)),
            types["protected_wall"])
        assertEquals(CitadelTypeSettings(Key("fornaxcitadel", "invulnerable"), CitadelBreakPolicy.UNBREAKABLE),
            types["protected_marker"])
        assertIs<SimpleHealthBehavior>(types.getValue("copper_wall").health!!.createBehavior())
        assertIs<MaturingHealthBehavior>(types.getValue("growing-wall").health!!.createBehavior())
    }

    @Test
    fun `explicit catalogs and empty catalogs do not merge bundled defaults`() {
        val bundled = yaml("""
            citadel-types:
              reinforced:
                item: fornaxcitadel:reinforced
                health: {behavior: simple, max: 20}
              bundled_only:
                item: fornaxcitadel:bundled_only
                break-policy: unbreakable
        """)
        val custom = yaml("""
            citadel-types:
              reinforced:
                item: diamond
                break-policy: unbreakable
        """).apply {
            setDefaults(bundled)
            options().copyDefaults(true)
        }
        assertEquals(mapOf("reinforced" to CitadelTypeSettings(Key("minecraft", "diamond"), CitadelBreakPolicy.UNBREAKABLE)),
            CitadelTypesSettings.read(custom).types)

        val empty = yaml("citadel-types: {}").apply {
            setDefaults(bundled)
            options().copyDefaults(true)
        }
        assertTrue(CitadelTypesSettings.read(empty).types.isEmpty())
        assertEquals(CitadelTypesSettings.DEFAULT_TYPES,
            CitadelTypesSettings.read(YamlConfiguration().apply { setDefaults(bundled) }).types)

        custom.set("citadel-types.reinforced.break-policy", "damage")
        val exception = assertFailsWith<IllegalArgumentException> { CitadelTypesSettings.read(custom) }
        assertTrue(exception.message.orEmpty().contains("citadel-types.reinforced.health"))
    }

    @Test
    fun `item must be an explicit valid key and is never inferred from the type or bundled defaults`() {
        val bundled = yaml("""
            citadel-types:
              reinforced:
                item: fornaxcitadel:reinforced
                health: {behavior: simple, max: 20}
        """)
        val invalidItems = listOf(
            null, "", " ", "DIAMOND", "Minecraft:diamond", "minecraft:DIAMOND",
            ":diamond", "minecraft:", "custom:extra:separator", "custom:invalid key", "diamond ",
            true, 42, listOf("diamond"), mapOf("name" to "diamond")
        )
        for (item in invalidItems) {
            val config = yaml("citadel-types: {reinforced: {health: {behavior: simple, max: 20}}}").apply {
                set("citadel-types.reinforced.item", item)
            }
            for (withDefaults in listOf(false, true)) {
                if (withDefaults) {
                    config.setDefaults(bundled)
                    config.options().copyDefaults(true)
                }
                val exception = assertFailsWith<IllegalArgumentException>("item=$item, defaults=$withDefaults") {
                    CitadelTypesSettings.read(config)
                }
                assertTrue(exception.message.orEmpty().contains("citadel-types.reinforced.item"), exception.message)
            }
        }
    }

    @Test
    fun `the same normalized item cannot select two citadel types`() {
        val config = yaml("""
            citadel-types:
              first:
                item: diamond
                break-policy: unbreakable
              second:
                item: minecraft:diamond
                break-policy: unbreakable
        """)
        val exception = assertFailsWith<IllegalArgumentException> { CitadelTypesSettings.read(config) }
        assertTrue(exception.message.orEmpty().contains("citadel-types.first.item"), exception.message)
        assertTrue(exception.message.orEmpty().contains("citadel-types.second.item"), exception.message)
        assertTrue(exception.message.orEmpty().contains("minecraft:diamond"), exception.message)

        val item = Key("custommaterials", "reinforcement")
        assertFailsWith<IllegalArgumentException> {
            CitadelTypesSettings(mapOf(
                "first" to CitadelTypeSettings(item, CitadelBreakPolicy.UNBREAKABLE),
                "second" to CitadelTypeSettings(item, health = HealthBehaviorSettings.Simple(20.0))
            ))
        }
    }

    @Test
    fun `invalid structures identifiers and behavior choices report their configuration path`() {
        val invalid = listOf(
            "citadel-types: []" to "citadel-types",
            "citadel-types: {bad: 123}" to "citadel-types.bad",
            "citadel-types: {Uppercase: {item: diamond, break-policy: unbreakable}}" to "citadel-types",
            "citadel-types: {'with.dot': {item: diamond, break-policy: unbreakable}}" to "citadel-types",
            "citadel-types: {bad: {item: diamond, break-policy: []}}" to "citadel-types.bad.break-policy",
            "citadel-types: {bad: {item: diamond, break-policy: unknown}}" to "citadel-types.bad.break-policy",
            "citadel-types: {bad: {item: diamond}}" to "citadel-types.bad.health",
            "citadel-types: {bad: {item: diamond, health: []}}" to "citadel-types.bad.health",
            "citadel-types: {bad: {item: diamond, health: {max: 10}}}" to "citadel-types.bad.health.behavior",
            "citadel-types: {bad: {item: diamond, health: {behavior: other, max: 10}}}" to "citadel-types.bad.health.behavior"
        )
        for ((source, path) in invalid) {
            val exception = assertFailsWith<IllegalArgumentException>(source) { CitadelTypesSettings.read(yaml(source)) }
            assertTrue(exception.message.orEmpty().contains(path), exception.message)
        }
    }

    @Test
    fun `health values and duration must be finite positive and representable`() {
        val cases = listOf(
            "max: 0" to "max",
            "max: -1" to "max",
            "max: .nan" to "max",
            "max: .inf" to "max",
            "max: '20'" to "max",
            "max: true" to "max",
            "" to "max"
        )
        for ((properties, field) in cases) {
            val config = yaml("citadel-types: {bad: {item: diamond, health: {behavior: simple, $properties}}}")
            val exception = assertFailsWith<IllegalArgumentException> { CitadelTypesSettings.read(config) }
            assertTrue(exception.message.orEmpty().contains("citadel-types.bad.health.$field"), exception.message)
        }
        val maturingCases = listOf(
            "initial" to 0,
            "initial" to Double.NaN,
            "mature" to 1,
            "mature" to Double.POSITIVE_INFINITY,
            "duration-seconds" to 0,
            "duration-seconds" to -1,
            "duration-seconds" to 0.5,
            "duration-seconds" to "10",
            "duration-seconds" to Double.POSITIVE_INFINITY,
            "duration-seconds" to Long.MAX_VALUE,
            "duration-seconds" to Long.MAX_VALUE / 1000 + 1
        )
        for ((field, value) in maturingCases) {
            val config = yaml("citadel-types: {bad: {item: diamond, health: {behavior: maturing, initial: 2, mature: 4, duration-seconds: 10}}}")
            config.set("citadel-types.bad.health.$field", value)
            val exception = assertFailsWith<IllegalArgumentException>("$field=$value") { CitadelTypesSettings.read(config) }
            assertTrue(exception.message.orEmpty().contains("citadel-types.bad.health.$field"), exception.message)
        }
        val maximum = HealthBehaviorSettings.Maturing(1.0, 1.0, Long.MAX_VALUE / 1000)
        assertIs<MaturingHealthBehavior>(maximum.createBehavior())
    }

    @Test
    fun `programmatic settings preserve the same invariants`() {
        assertFailsWith<IllegalArgumentException> { CitadelTypeSettings(Key("minecraft", "diamond")) }
        assertFailsWith<IllegalArgumentException> { CitadelTypeSettings(Key("", "diamond"), CitadelBreakPolicy.UNBREAKABLE) }
        assertFailsWith<IllegalArgumentException> { CitadelTypeSettings(Key("minecraft", ""), CitadelBreakPolicy.UNBREAKABLE) }
        assertFailsWith<IllegalArgumentException> { HealthBehaviorSettings.Simple(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { HealthBehaviorSettings.Simple(0.0) }
        assertFailsWith<IllegalArgumentException> { HealthBehaviorSettings.Maturing(2.0, 1.0, 10) }
        assertFailsWith<IllegalArgumentException> { HealthBehaviorSettings.Maturing(1.0, 2.0, Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> {
            CitadelTypesSettings(mapOf("invalid.id" to CitadelTypeSettings(Key("minecraft", "diamond"), CitadelBreakPolicy.UNBREAKABLE)))
        }
    }

    private fun yaml(source: String): YamlConfiguration = YamlConfiguration().apply { loadFromString(source.trimIndent()) }
}
