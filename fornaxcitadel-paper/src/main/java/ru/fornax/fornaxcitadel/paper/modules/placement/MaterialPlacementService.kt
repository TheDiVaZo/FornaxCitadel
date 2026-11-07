package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.facade.CitadelResolver
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import java.util.Locale

/**
 * Paper's material-aware implementation of core reinforcement resolution.
 * Register rules and resolve positions only on the owning server thread. Material identities
 * are read on every lookup, so providers registered after this service immediately take effect.
 */
class MaterialPlacementService(
    private val server: Server,
    private val materials: MaterialRegistry,
    private val manager: ComponentManager
) : CitadelResolver {
    private val rules = mutableMapOf<Key, MaterialPlacementRule>()
    private val defaultRule = DefaultMaterialPlacementRule(::findExact)

    init {
        val doors = DoorMaterialPlacementRule(::findExact, ::findLoadedRelative)
        val beds = BedMaterialPlacementRule(::findExact, ::findLoadedRelative)
        for (material in Material.entries) {
            if (material.isLegacy) continue
            val rule = when {
                material.name.endsWith("_DOOR") -> doors
                material.name.endsWith("_BED") -> beds
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

    override fun getResponsiblePosition(position: CitadelPosition): CitadelPosition {
        val block = findLoadedBlock(position)
        val responsible = getRule(block).getResponsibleBlock(block)
        val resolved = positionOf(responsible)
        requireAvailable(resolved)
        return resolved
    }

    override fun getReinforcementProtecting(position: CitadelPosition): CitadelHandle? {
        val block = findLoadedBlock(position)
        val found = getRule(block).getReinforcementProtecting(block) ?: return null
        requireAvailable(found.position)
        check(manager.isCurrent(found)) { "Material rule returned an expired or foreign reinforcement" }
        return found
    }

    /** Exact lookup for custom rules; this method never follows material relationships. */
    fun findExact(block: Block): CitadelHandle? {
        val position = positionOf(block)
        requireAvailable(position)
        return manager.findCitadel(position.world, position.x, position.y, position.z)
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

    private fun requireAvailable(position: CitadelPosition): World {
        val world = server.getWorld(position.world)
            ?: throw CitadelResolutionUnavailableException(position)
        if (!CitadelPosition.isSupported(position.x, position.y, position.z) ||
            position.y !in world.minHeight until world.maxHeight ||
            !world.isChunkLoaded(position.x shr 4, position.z shr 4) ||
            !manager.isChunkLoaded(position.world, position.x shr 4, position.z shr 4)) {
            throw CitadelResolutionUnavailableException(position)
        }
        return world
    }

    private fun positionOf(block: Block): CitadelPosition =
        CitadelPosition(block.world.uid, block.x, block.y, block.z)
}
