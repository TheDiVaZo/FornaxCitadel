package ru.fornax.fornaxcitadel.paper

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.Chest
import org.bukkit.block.data.type.Door
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.MutableCitadelDraft
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.HealthInitializer
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal fun Block.testPosition() = CitadelPosition(world.uid, x, y, z)

internal fun PaperTestFixture.door(
    x: Int = 1,
    y: Int = 64,
    z: Int = 2,
    facing: BlockFace = BlockFace.NORTH,
    material: Material = Material.OAK_DOOR
): Pair<Block, Block> {
    val lower = block(x, y, z)
    val upper = block(x, y + 1, z)
    for ((part, half) in listOf(lower to Bisected.Half.BOTTOM, upper to Bisected.Half.TOP)) {
        material(material, part.testPosition())
        blockData(doorData(half, facing, material), part.testPosition())
    }
    return lower to upper
}

internal fun doorData(half: Bisected.Half, facing: BlockFace, material: Material = Material.OAK_DOOR): Door =
    paperProxy("door $half $facing") { method, _ ->
        when (method.name) {
            "getHalf" -> half
            "getFacing" -> facing
            "getMaterial" -> material
            "getHinge" -> Door.Hinge.LEFT
            "isOpen", "isPowered" -> false
            else -> unsupported(method)
        }
    }

internal fun PaperTestFixture.bed(
    x: Int = 4,
    y: Int = 64,
    z: Int = 4,
    facing: BlockFace = BlockFace.EAST,
    material: Material = Material.RED_BED
): Pair<Block, Block> {
    val foot = block(x, y, z)
    val head = block(x + facing.modX, y, z + facing.modZ)
    for ((part, bedPart) in listOf(foot to Bed.Part.FOOT, head to Bed.Part.HEAD)) {
        material(material, part.testPosition())
        blockData(paperProxy<Bed>("bed $bedPart $facing") { method, _ ->
            when (method.name) {
                "getPart" -> bedPart
                "getFacing" -> facing
                "getMaterial" -> material
                "isOccupied" -> false
                else -> unsupported(method)
            }
        }, part.testPosition())
    }
    return foot to head
}

internal val copperChestMaterials = listOf(
    Material.COPPER_CHEST, Material.EXPOSED_COPPER_CHEST,
    Material.WEATHERED_COPPER_CHEST, Material.OXIDIZED_COPPER_CHEST,
    Material.WAXED_COPPER_CHEST, Material.WAXED_EXPOSED_COPPER_CHEST,
    Material.WAXED_WEATHERED_COPPER_CHEST, Material.WAXED_OXIDIZED_COPPER_CHEST
)

internal fun PaperTestFixture.doubleChest(
    x: Int = 5,
    y: Int = 64,
    z: Int = 5,
    facing: BlockFace = BlockFace.NORTH,
    leftMaterial: Material = Material.CHEST,
    rightMaterial: Material = leftMaterial
): Pair<Block, Block> {
    // Explicit coordinates independently describe Minecraft's LEFT/RIGHT convention.
    val rightOffset = when (facing) {
        BlockFace.NORTH -> 1 to 0
        BlockFace.EAST -> 0 to 1
        BlockFace.SOUTH -> -1 to 0
        BlockFace.WEST -> 0 to -1
        else -> error("A chest must face horizontally")
    }
    val left = block(x, y, z)
    val right = block(x + rightOffset.first, y, z + rightOffset.second)
    material(leftMaterial, left.testPosition())
    blockData(chestData(Chest.Type.LEFT, facing, leftMaterial), left.testPosition())
    material(rightMaterial, right.testPosition())
    blockData(chestData(Chest.Type.RIGHT, facing, rightMaterial), right.testPosition())
    return left to right
}

internal fun chestData(type: Chest.Type, facing: BlockFace, material: Material = Material.CHEST): Chest =
    paperProxy("chest $type $facing $material") { method, _ ->
        when (method.name) {
            "getType" -> type
            "getFacing" -> facing
            "getMaterial" -> material
            "isWaterlogged" -> false
            else -> unsupported(method)
        }
    }

/** Simulates persisted legacy records independently of the current placement resolver. */
internal fun PaperTestFixture.placeExact(
    block: Block,
    type: Key = PaperCitadelTypes.REINFORCED
): CitadelHandle {
    val position = block.testPosition()
    val draft = MutableCitadelDraft(position)
    draft.addComponent(CitadelKind::class.java, CitadelKind(type))
    HealthInitializer(100.0).initialize(context(installer), draft)
    assertEquals(ActionResult.SUCCESS, manager.createCitadel(draft))
    return assertNotNull(manager.findCitadel(worldId, block.x, block.y, block.z))
}
