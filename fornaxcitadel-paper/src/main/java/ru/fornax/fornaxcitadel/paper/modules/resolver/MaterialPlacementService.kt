package ru.fornax.fornaxcitadel.paper.modules.resolver

import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.Door
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolver
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.ChunkLoadCheck
import ru.fornax.fornaxcitadel.paper.modules.LoadedBlocks
import java.util.Locale

/**
 * Paper's material-aware implementation of core reinforcement resolution.
 * Register rules and resolve positions only on the owning server thread. Material identities
 * are read on every lookup, so providers registered after this service immediately take effect.
 */
class MaterialPlacementService(
    private val server: Server,
    private val materials: MaterialRegistry,
    private val isChunkLoaded: ChunkLoadCheck
) : CitadelResolver {
    private val rules = mutableMapOf<Key, MaterialPlacementRule>()
    private val defaultRule = DefaultMaterialPlacementRule()

    init {
        val doors = DoorMaterialPlacementRule(::findLoadedRelative)
        val beds = BedMaterialPlacementRule(::findLoadedRelative)
        val chests = ChestMaterialPlacementRule(::findLoadedRelative)
        for (material in Material.entries) {
            if (material.isLegacy) continue
            val rule = when {
                material.name.endsWith("_DOOR") -> doors
                material.name.endsWith("_BED") -> beds
                ChestMaterialPlacementRule.supports(material) -> chests
                else -> continue
            }
            rules[Key("minecraft", material.name.lowercase(Locale.ROOT))] = rule
        }
    }

    /** Refuses replacement of an existing rule. Call on the server's main thread. */
    fun register(material: Key, rule: MaterialPlacementRule): Boolean =
        rules.putIfAbsent(material, rule) == null

    fun getRule(material: Key): MaterialPlacementRule = rules[material] ?: defaultRule

    fun getRule(block: Block): MaterialPlacementRule {
        requireAvailable(positionOf(block))
        return materials.getTypeOrNull(block)?.getKey()?.let(::getRule) ?: defaultRule
    }

    /** Doors and beds break as one block. A double chest breaks one half at a time. */
    internal fun getDestructionPartner(block: Block): Block? {
        val related = getRule(block).getRelatedBlock(block) ?: return null
        requireAvailable(positionOf(related))
        return related.takeIf { block.blockData is Door || block.blockData is Bed }
    }

    override fun getResponsiblePosition(position: CitadelPosition): CitadelPosition {
        val block = findLoadedBlock(position)
        val responsible = getRule(block).getResponsibleBlock(block)
        val resolved = positionOf(responsible)
        requireAvailable(resolved)
        return resolved
    }

    override fun getProtectionCandidates(position: CitadelPosition): Sequence<CitadelPosition> {
        val block = findLoadedBlock(position)
        return getRule(block).getProtectionCandidates(block).map { candidate ->
            val resolved = positionOf(candidate)
            requireAvailable(resolved)
            resolved
        }
    }

    /**
     * Looks up an adjacent block only after checking both world and reinforcement chunk data.
     * Outside world/storage bounds there cannot be a partner; unloaded data is an error instead.
     */
    fun findLoadedRelative(block: Block, direction: BlockFace): Block? {
        val source = positionOf(block)
        val world = requireAvailable(source)
        val related = CitadelPosition(source.world,
            source.x + direction.modX, source.y + direction.modY, source.z + direction.modZ)
        if (!CitadelPosition.isSupported(related.x, related.y, related.z) ||
            related.y !in world.minHeight until world.maxHeight) return null
        return findLoadedBlock(related)
    }

    private fun findLoadedBlock(position: CitadelPosition): Block =
        requireAvailable(position).getBlockAt(position.x, position.y, position.z)

    private fun requireAvailable(position: CitadelPosition): World =
        LoadedBlocks.requireAvailable(server, isChunkLoaded, position)

    private fun positionOf(block: Block): CitadelPosition =
        CitadelPosition(block.world.uid, block.x, block.y, block.z)
}
