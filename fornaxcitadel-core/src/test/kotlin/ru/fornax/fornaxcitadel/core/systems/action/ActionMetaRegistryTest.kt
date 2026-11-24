package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.util.Key
import kotlin.test.Test
import kotlin.test.assertEquals

class ActionMetaRegistryTest {
    @Test
    fun `removing per-key metadata restores the current default`() {
        val key = Key("test", "override")
        val other = Key("test", "other")
        val registry = ActionMetaRegistry(ActionMeta.DEFAULT)

        for (allowsNeutral in listOf(false, true)) {
            val fallback = ActionMeta(allowsNeutral)
            val override = ActionMeta(!allowsNeutral)
            registry.defaultMeta = fallback
            registry.register(key, override)
            assertEquals(override, registry.getMetaOrDefault(key))
            assertEquals(fallback, registry.getMetaOrDefault(other))
            registry.unregister(key)
            assertEquals(fallback, registry.getMetaOrDefault(key))
        }
    }
}
