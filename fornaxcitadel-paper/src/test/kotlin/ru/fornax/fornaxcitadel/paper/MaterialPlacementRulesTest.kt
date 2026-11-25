package ru.fornax.fornaxcitadel.paper

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.modules.resolver.DefaultMaterialPlacementRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaterialPlacementRulesTest {
    @Test
    fun `doors use their lower block and beds their head in every direction`() {
        PaperTestFixture().use { fixture ->
            for (facing in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                for (material in listOf(Material.OAK_DOOR, Material.IRON_DOOR)) {
                    val (lower, upper) = fixture.door(facing = facing, material = material)
                    for (part in listOf(lower, upper)) {
                        assertEquals(lower.testPosition(), fixture.materialPlacement.getResponsiblePosition(part.testPosition()))
                    }
                    val rule = fixture.materialPlacement.getRule(Key("minecraft", material.name.lowercase()))
                    assertEquals(upper.testPosition(), rule.getRelatedBlock(lower)?.testPosition())
                    assertEquals(lower.testPosition(), rule.getRelatedBlock(upper)?.testPosition())
                }
                for (material in listOf(Material.RED_BED, Material.BLUE_BED)) {
                    val (foot, head) = fixture.bed(facing = facing, material = material)
                    for (part in listOf(foot, head)) {
                        assertEquals(head.testPosition(), fixture.materialPlacement.getResponsiblePosition(part.testPosition()))
                    }
                    val rule = fixture.materialPlacement.getRule(Key("minecraft", material.name.lowercase()))
                    assertEquals(head.testPosition(), rule.getRelatedBlock(foot)?.testPosition())
                    assertEquals(foot.testPosition(), rule.getRelatedBlock(head)?.testPosition())
                }
            }
        }
    }

    @Test
    fun `ordinary and invalid paired blocks do not acquire a neighbor's reinforcement`() {
        PaperTestFixture().use { fixture ->
            val ordinary = fixture.block(8, 64, 8)
            assertEquals(ordinary.testPosition(), fixture.materialPlacement.getResponsiblePosition(ordinary.testPosition()))
            assertNull(fixture.citadels.findProtecting(ordinary.testPosition()))

            val (lower, upper) = fixture.door()
            fixture.placeExact(lower)
            fixture.blockData(doorData(Bisected.Half.TOP, BlockFace.SOUTH), upper.testPosition())
            assertEquals(upper.testPosition(), fixture.materialPlacement.getResponsiblePosition(upper.testPosition()))
            assertNull(fixture.citadels.findProtecting(upper.testPosition()))

            val (foot, head) = fixture.bed()
            fixture.placeExact(head)
            fixture.material(Material.BLUE_BED, head.testPosition())
            assertEquals(foot.testPosition(), fixture.materialPlacement.getResponsiblePosition(foot.testPosition()))
            assertNull(fixture.citadels.findProtecting(foot.testPosition()))
        }
    }

    @Test
    fun `custom key resolves providers registered later and occupied rule keys cannot be replaced`() {
        PaperTestFixture().use { fixture ->
            val source = fixture.block()
            val target = fixture.block(3, 64, 2)
            val key = Key("thirdparty", "linked_stone")
            val rule = object : DefaultMaterialPlacementRule() {
                override fun getResponsibleBlock(block: Block): Block = target
                override fun getProtectionCandidates(block: Block): Sequence<Block> = sequenceOf(target, block)
            }
            assertTrue(fixture.materialFacade.registerPlacementRule(key, rule))
            assertFalse(fixture.materialFacade.registerPlacementRule(key, DefaultMaterialPlacementRule()))
            assertSame(rule, fixture.materialPlacement.getRule(key))
            assertEquals(source.testPosition(), fixture.materialPlacement.getResponsiblePosition(source.testPosition()))

            val adapter = TestMaterialAdapter(key, taggedBlock = source)
            assertTrue(fixture.materialFacade.registerMaterial("thirdparty", TestMaterialRegistry(adapter)))
            assertEquals(target.testPosition(), fixture.materialPlacement.getResponsiblePosition(source.testPosition()))
            val local = fixture.placeExact(source)
            assertEquals(local.runtimeId,
                assertNotNull(fixture.citadels.findProtecting(source.testPosition())).runtimeId)
            val placed = fixture.placeExact(target)
            assertEquals(placed.runtimeId,
                assertNotNull(fixture.citadels.findProtecting(source.testPosition())).runtimeId)
        }
    }

    @Test
    fun `local bed reinforcement wins while the partner chunk is unavailable`() {
        PaperTestFixture().use { fixture ->
            val (foot, _) = fixture.bed(x = 15)
            val local = fixture.placeExact(foot)

            assertEquals(local.runtimeId,
                assertNotNull(fixture.citadels.findProtecting(foot.testPosition())).runtimeId)
            assertFalse(1 to 0 in fixture.loadedWorldChunks)
            assertFalse(fixture.manager.isChunkLoaded(fixture.worldId, 1, 0))
        }
    }

    @Test
    fun `unavailable neighbor chunk differs from absent reinforcement and is never loaded implicitly`() {
        PaperTestFixture().use { fixture ->
            val (foot, head) = fixture.bed(x = 15)
            assertFailsWith<CitadelResolutionUnavailableException> {
                fixture.citadels.findProtecting(foot.testPosition())
            }
            assertFalse(fixture.manager.isChunkLoaded(fixture.worldId, 1, 0))

            fixture.loadedWorldChunks.add(1 to 0)
            assertFailsWith<CitadelResolutionUnavailableException> {
                fixture.materialPlacement.getResponsiblePosition(foot.testPosition())
            }
            fixture.loadChunk(1, 0)
            assertEquals(head.testPosition(), fixture.materialPlacement.getResponsiblePosition(foot.testPosition()))
            assertNull(fixture.citadels.findProtecting(foot.testPosition()))
        }
    }

    @Test
    fun `unavailable positions never reach Bukkit while boundary relatives remain absent`() {
        PaperTestFixture().use { fixture ->
            val initialLookups = fixture.blockLookups
            val invalid = listOf(
                fixture.position.copy(world = java.util.UUID(99, 99)),
                fixture.position.copy(y = -65), fixture.position.copy(y = 320),
                fixture.position.copy(x = Short.MIN_VALUE.toInt() * 16 - 1),
                fixture.position.copy(z = (Short.MAX_VALUE.toInt() + 1) * 16)
            )
            for (position in invalid) {
                assertFailsWith<CitadelResolutionUnavailableException> {
                    fixture.materialPlacement.getResponsiblePosition(position)
                }
            }
            fixture.worldChunkLoaded = false
            assertFailsWith<CitadelResolutionUnavailableException> {
                fixture.materialPlacement.getResponsiblePosition(fixture.position)
            }
            assertEquals(initialLookups, fixture.blockLookups)
            fixture.worldChunkLoaded = true

            assertNull(fixture.materialPlacement.findLoadedRelative(fixture.block(y = -64), BlockFace.DOWN))
            assertNull(fixture.materialPlacement.findLoadedRelative(fixture.block(y = 319), BlockFace.UP))
            assertEquals(initialLookups, fixture.blockLookups)

            fixture.loadChunk(-1, -1)
            val negative = CitadelPosition(fixture.worldId, -1, -64, -1)
            assertEquals(negative, fixture.materialPlacement.getResponsiblePosition(negative))
        }
    }
}
