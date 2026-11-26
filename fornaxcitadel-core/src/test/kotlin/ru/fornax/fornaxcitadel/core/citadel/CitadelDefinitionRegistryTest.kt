package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.util.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class CitadelDefinitionRegistryTest {
    @Test
    fun `definition and installation keys require a namespace and value`() {
        val definition = CitadelDefinition(Key("addon", "guard"), emptyList(), itemKey = Key("minecraft", "iron_ingot"))
        val types = CitadelDefinitionRegistry().apply { register(definition) }
        for (invalid in listOf(Key("", "guard"), Key("addon", ""))) {
            assertFailsWith<IllegalArgumentException> { CitadelDefinition(invalid, emptyList()) }
            assertFailsWith<IllegalArgumentException> { CitadelDefinition(definition.key, emptyList(), itemKey = invalid) }
            assertFailsWith<IllegalArgumentException> { types.registerItemAlias(invalid, definition.key) }
            assertNull(types.findByItem(invalid))
        }
    }

    @Test
    fun `canonical items and aliases reject conflicts without partially registering a type`() {
        val types = CitadelDefinitionRegistry()
        val iron = Key("minecraft", "iron_ingot")
        val alias = Key("addon", "token")
        val definition = CitadelDefinition(Key("fornaxcitadel", "guard"), emptyList(), 2, iron)
        types.register(definition)
        types.registerItemAlias(alias, definition.key)
        types.registerItemAlias(alias, definition.key)
        types.register(definition)

        val other = Key("addon", "guard")
        for (item in listOf(iron, alias)) {
            assertFailsWith<IllegalArgumentException> {
                types.register(CitadelDefinition(other, emptyList(), itemKey = item))
            }
            assertNull(types.find(other))
            assertSame(definition, types.findByItem(item))
        }
        assertFailsWith<IllegalArgumentException> {
            types.register(CitadelDefinition(definition.key, emptyList(), itemKey = other))
        }
        assertNull(types.findByItem(other))
        val second = CitadelDefinition(other, emptyList(), itemKey = other)
        types.register(second)
        assertFailsWith<IllegalArgumentException> { types.registerItemAlias(alias, other) }
        assertEquals(listOf(definition.key, other), types.keys)
        assertEquals(iron, types.itemKey(definition.key))
        assertNull(types.level(other))
    }
}
