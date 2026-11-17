package ru.fornax.fornaxcitadel.paper

import org.bukkit.Material
import org.bukkit.block.Block
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.material.BukkitMaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.BukkitMaterialRegistry.SimpleMaterialAdapter
import ru.fornax.fornaxcitadel.paper.material.CombineMaterialRegistry
import ru.fornax.fornaxcitadel.paper.material.MaterialAdapter
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaterialRegistryTest {
    @Test
    fun `vanilla adapter refuses a block with another material`() {
        val block = paperProxy<Block>("mismatched material") { method, _ ->
            when (method.name) {
                "getType" -> Material.STONE
                else -> unsupported(method)
            }
        }
        for (effect in listOf(false, true)) {
            assertFalse(SimpleMaterialAdapter(Material.DIRT).breakNaturallyBlock(block, effect))
        }
        assertEquals(Material.STONE, block.type)
    }

    @Test
    fun `vanilla adapter delegates the effect flag once and returns the native result without inspecting state`() {
        for (effect in listOf(false, true)) for (nativeResult in listOf(false, true)) {
            val calls = mutableListOf<Boolean>()
            val block = paperProxy<Block>("natural destruction target") { method, args ->
                when (method.name) {
                    "getType" -> Material.STONE
                    "breakNaturally" -> {
                        calls.add(args.single() as Boolean)
                        nativeResult
                    }
                    else -> unsupported(method)
                }
            }

            assertEquals(nativeResult, SimpleMaterialAdapter(Material.STONE).breakNaturallyBlock(block, effect))
            assertEquals(listOf(effect), calls)
            assertEquals(Material.STONE, block.type)
        }
    }

    @Test
    fun `negative hardness blocks resolve to the same refusing adapter through every lookup`() {
        for (material in listOf(Material.BEDROCK, Material.BARRIER, Material.END_PORTAL_FRAME,
            Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.END_PORTAL, Material.END_GATEWAY,
            Material.NETHER_PORTAL, Material.LIGHT, Material.MOVING_PISTON)) {
            val block = paperProxy<Block>("protected $material") { method, _ ->
                when (method.name) {
                    "getType" -> material
                    else -> unsupported(method)
                }
            }
            val adapter = BukkitMaterialRegistry[material]
            assertTrue(material.isBlock && material.hardness < 0, material.name)
            assertSame(adapter, BukkitMaterialRegistry.getType(adapter.getKey()), material.name)
            assertSame(adapter, BukkitMaterialRegistry.getTypeOrNull(block), material.name)
            assertSame(adapter, BukkitMaterialRegistry.getTypeOrNull(TestItemStack(material, 1)), material.name)

            for (effect in listOf(false, true)) {
                assertFalse(adapter.breakNaturallyBlock(block, effect), material.name)
            }
            assertEquals(material, block.type)
        }
    }

    @Test
    fun `zero and positive hardness vanilla blocks remain breakable`() {
        for (material in listOf(Material.TNT, Material.STONE)) {
            val calls = mutableListOf<Boolean>()
            val block = paperProxy<Block>("breakable $material") { method, args ->
                when (method.name) {
                    "getType" -> material
                    "breakNaturally" -> { calls.add(args.single() as Boolean); true }
                    else -> unsupported(method)
                }
            }
            assertTrue(material.hardness >= 0, material.name)
            assertTrue(BukkitMaterialRegistry[material].breakNaturallyBlock(block, false), material.name)
            assertEquals(listOf(false), calls)
        }
    }

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
        assertFalse(wrapper.breakNaturallyBlock(fixture.block(), true))
        assertEquals(0, fixture.vanillaBlockChanges)
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
    fun `wrapper forwards natural breaking flags and provider results and refuses an absent provider`() {
        val block = paperProxy<Block>("custom natural target") { method, _ ->
            when (method.name) {
                "getType" -> Material.STONE
                else -> unsupported(method)
            }
        }
        val key = Key("custom", "natural")
        val calls = mutableListOf<Pair<Block, Boolean>>()
        var providerResult = false
        val material = object : MaterialAdapter by TestMaterialAdapter(key, taggedBlock = block) {
            override fun breakNaturallyBlock(block: Block, triggerEffect: Boolean): Boolean {
                calls.add(block to triggerEffect)
                return providerResult
            }
        }
        val registry = CombineMaterialRegistry()
        val wrapper = MaterialAdapter.Wrapper(key, registry)
        for (effect in listOf(false, true)) assertFalse(wrapper.breakNaturallyBlock(block, effect))
        assertTrue(calls.isEmpty())
        assertTrue(registry.register(key.namespace, TestMaterialRegistry(material)))

        for (effect in listOf(false, true)) for (result in listOf(false, true)) {
            calls.clear()
            providerResult = result
            assertEquals(result, wrapper.breakNaturallyBlock(block, effect))
            assertEquals(listOf(block to effect), calls)
            assertEquals(Material.STONE, block.type)
        }
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
    fun `default registry supplies only vanilla materials until an external provider registers`() {
        val fixture = PaperTestFixture(initializeSystems = false)
        assertNull(fixture.materials.getType(PaperCitadelTypes.REINFORCED))
        assertSame(BukkitMaterialRegistry[Material.AMETHYST_SHARD],
            fixture.materials.getTypeOrNull(TestItemStack(Material.AMETHYST_SHARD, 1)))
        assertSame(BukkitMaterialRegistry[Material.STONE], fixture.materials.getTypeOrNull(fixture.block()))

        val custom = TestMaterialAdapter(PaperCitadelTypes.REINFORCED)
        assertTrue(fixture.materials.register("fornaxcitadel", TestMaterialRegistry(custom)))
        assertSame(custom, fixture.materials.getType(PaperCitadelTypes.REINFORCED))
        assertSame(custom, fixture.materials.getTypeOrNull(custom.createItemStack(1)))
    }
}
