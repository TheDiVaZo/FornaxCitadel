package ru.fornax.fornaxcitadel.paper

import org.bukkit.Material
import org.bukkit.NamespacedKey
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.material.BukkitMaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.CombineMaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.MaterialAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaterialRegistryTest {
    @Test
    fun `vanilla identity resolves consistently by key item and block`() {
        val fixture = PaperTestFixture(initializeSystems = false)
        val stone = BukkitMaterialRegistry[Material.STONE]
        val stack = TestItemStack(Material.STONE, 2)

        assertEquals(Key("minecraft", "stone"), stone.getKey())
        assertSame(stone, BukkitMaterialRegistry.getType(stone.getKey()))
        assertSame(stone, BukkitMaterialRegistry.getTypeOrNull(stack))
        assertSame(stone, BukkitMaterialRegistry.getTypeOrNull(fixture.block()))
        assertTrue(stone.isConsists(stack))
        assertTrue(stone.isConsists(fixture.block()))
        assertFalse(stone.isConsists(TestItemStack(Material.AMETHYST_SHARD, 1)))
        assertNull(BukkitMaterialRegistry.getType(Key("unknown", "stone")))
        assertNull(BukkitMaterialRegistry.getType(Key("minecraft", "missing_material")))
    }

    @Test
    fun `empty air and legacy stacks never resolve as usable materials`() {
        val combined = CombineMaterialRegistry()
        for (stack in listOf(
            TestItemStack(Material.STONE, 0),
            TestItemStack(Material.AIR, 1),
            TestItemStack(Material.CAVE_AIR, 1),
            TestItemStack(Material.VOID_AIR, 1)
        )) {
            assertNull(BukkitMaterialRegistry.getTypeOrNull(stack))
            assertNull(combined.getTypeOrNull(stack))
        }
        @Suppress("DEPRECATION")
        for (material in Material.entries.filter { it.isLegacy }) {
            assertNull(BukkitMaterialRegistry.getTypeOrNull(TestItemStack(material, 1)))
        }
    }

    @Test
    fun `custom providers precede vanilla and retain their registration order`() {
        val fixture = PaperTestFixture(initializeSystems = false)
        val first = TestMaterialAdapter(Key("first", "reinforcement"), taggedBlock = fixture.block())
        val second = TestMaterialAdapter(Key("second", "reinforcement"), taggedBlock = fixture.block(),
            itemTag = first.getKey().asString())
        val combined = CombineMaterialRegistry(
            "first" to TestMaterialRegistry(first), "second" to TestMaterialRegistry(second)
        )

        assertSame(first, combined.getTypeOrNull(first.createItemStack(1)))
        assertSame(first, combined.getTypeOrNull(fixture.block()))
        assertSame(first, combined.getType(first.getKey()))
        assertSame(second, combined.getType(second.getKey()))
        assertSame(BukkitMaterialRegistry[Material.AMETHYST_SHARD],
            combined.getTypeOrNull(TestItemStack(Material.AMETHYST_SHARD, 1)))
        assertSame(BukkitMaterialRegistry[Material.STONE], combined.getTypeOrNull(fixture.block(3, 64, 2)))
        assertSame(BukkitMaterialRegistry[Material.STONE], combined.getType(Key("minecraft", "stone")))
    }

    @Test
    fun `duplicate namespace registration preserves its original provider and minecraft stays reserved`() {
        val original = TestMaterialAdapter(Key("thirdparty", "original"))
        val replacement = TestMaterialAdapter(Key("thirdparty", "replacement"))
        val registry = TestMaterialRegistry(original)
        val combined = CombineMaterialRegistry("thirdparty" to registry)

        assertFalse(combined.register("thirdparty", TestMaterialRegistry(replacement)))
        assertSame(original, combined.getType(original.getKey()))
        assertNull(combined.getType(replacement.getKey()))
        assertFailsWith<IllegalArgumentException> { combined.register("minecraft", registry) }
        assertFailsWith<IllegalArgumentException> {
            CombineMaterialRegistry("thirdparty" to registry, "thirdparty" to TestMaterialRegistry(replacement))
        }
        assertSame(BukkitMaterialRegistry[Material.STONE], combined.getType(Key("minecraft", "stone")))
    }

    @Test
    fun `wrapper resolves a provider registered after the wrapper was created`() {
        val fixture = PaperTestFixture(initializeSystems = false)
        val material = TestMaterialAdapter(Key("late", "reinforcement"), taggedBlock = fixture.block())
        val combined = CombineMaterialRegistry()
        val wrapper = MaterialAdapter.Wrapper(material.getKey(), combined)
        val sample = material.createItemStack(2)

        assertEquals(material.getKey(), wrapper.getKey())
        assertFalse(wrapper.isConsists(sample))
        assertFalse(wrapper.isConsists(fixture.block()))
        assertFailsWith<IllegalStateException> { wrapper.createItemStack(1) }

        assertTrue(combined.register("late", TestMaterialRegistry(material)))

        assertTrue(wrapper.isConsists(sample))
        assertTrue(wrapper.isConsists(fixture.block()))
        val created = wrapper.createItemStack(5)
        assertEquals(5, created.amount)
        assertTrue(material.isConsists(created))
        assertFailsWith<IllegalArgumentException> { wrapper.createItemStack(0) }
    }

    @Test
    fun `registry convenience methods report unsupported values consistently`() {
        val fixture = PaperTestFixture(initializeSystems = false)
        val material = TestMaterialAdapter(Key("thirdparty", "reinforcement"), taggedBlock = fixture.block())
        val registry = TestMaterialRegistry(material)
        val supported = material.createItemStack(1)
        val ordinary = TestItemStack(Material.AMETHYST_SHARD, 1)

        assertTrue(registry.isSupportType(supported))
        assertTrue(registry.isSupportType(fixture.block()))
        assertSame(material, registry.getType(supported))
        assertSame(material, registry.getType(fixture.block()))
        assertFalse(registry.isSupportType(ordinary))
        assertFalse(registry.isSupportType(fixture.block(3, 64, 2)))
        assertFailsWith<IllegalArgumentException> { registry.getType(ordinary) }
        assertFailsWith<IllegalArgumentException> { registry.getType(fixture.block(3, 64, 2)) }
    }

    @Test
    fun `citadel items are a tagged registry that never claims ordinary blocks or unmarked shards`() {
        val fixture = PaperTestFixture(initializeSystems = false)
        val marked = fixture.markedStack()
        val material = assertNotNull(fixture.items.getType(PaperCitadelTypes.REINFORCED))

        assertSame(material, fixture.items.getTypeOrNull(marked))
        assertSame(material, fixture.materials.getTypeOrNull(marked))
        assertEquals(PaperCitadelTypes.REINFORCED, material.getKey())
        assertEquals(PaperCitadelTypes.REINFORCED, fixture.items.typeOf(marked))
        assertTrue(material.isConsists(marked))
        assertFalse(material.isConsists(fixture.block()))
        assertNull(fixture.items.getTypeOrNull(fixture.block()))
        assertNull(fixture.items.getTypeOrNull(TestItemStack(Material.AMETHYST_SHARD, 1)))
        assertNull(fixture.items.getTypeOrNull(fixture.markedStack(amount = 0)))
        assertNull(fixture.items.getType(Key("other", "reinforced")))
        marked.data.values[NamespacedKey(fixture.plugin, "citadel_type")] = "other:reinforced"
        assertNull(fixture.items.getTypeOrNull(marked))
        assertNull(fixture.items.typeOf(marked))
    }
}
