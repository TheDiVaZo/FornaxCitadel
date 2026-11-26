package ru.fornax.fornaxcitadel.paper.material

import org.bukkit.block.Block
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolver
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperPlacementService
import ru.fornax.fornaxcitadel.paper.modules.resolver.MaterialPlacementRule
import ru.fornax.fornaxcitadel.paper.modules.resolver.MaterialPlacementService

/** Resolves materials and placements and registers providers, rules and installation items on the main thread. */
class MaterialFacade(
    private val materials: CombineMaterialRegistry,
    private val materialPlacement: MaterialPlacementService,
    private val placement: PaperPlacementService
) : MaterialRegistry by materials, CitadelResolver by materialPlacement {
    fun registerMaterial(namespace: String, registry: MaterialRegistry): Boolean {
        require(registry !== this) { "A material facade cannot register itself as a provider" }
        return materials.register(namespace, registry)
    }

    fun getRule(material: Key): MaterialPlacementRule = materialPlacement.getRule(material)

    fun getRule(block: Block): MaterialPlacementRule = materialPlacement.getRule(block)

    /** Returns false when [material] already has a placement rule. */
    fun registerPlacementRule(material: Key, rule: MaterialPlacementRule): Boolean =
        materialPlacement.register(material, rule)

    /** Adds an alternate installation item for a registered citadel type with a primary item. */
    fun registerPlacementDefinition(materialKey: Key, definitionKey: Key) =
        placement.registerMaterial(materialKey, definitionKey)
}
