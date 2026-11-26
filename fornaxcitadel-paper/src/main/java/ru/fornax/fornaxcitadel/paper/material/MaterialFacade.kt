package ru.fornax.fornaxcitadel.paper.material

import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperPlacementService
import ru.fornax.fornaxcitadel.paper.modules.resolver.MaterialPlacementRule
import ru.fornax.fornaxcitadel.paper.modules.resolver.MaterialPlacementService

/** Looks up materials and registers providers, block rules and installation items on the server's main thread. */
class MaterialFacade(
    private val materials: CombineMaterialRegistry,
    private val materialPlacement: MaterialPlacementService,
    private val placement: PaperPlacementService
) {
    fun registerMaterial(namespace: String, registry: MaterialRegistry): Boolean {
        return materials.register(namespace, registry)
    }

    /** Returns false when [material] already has a placement rule. */
    fun registerPlacementRule(material: Key, rule: MaterialPlacementRule): Boolean =
        materialPlacement.register(material, rule)

    /** Adds an alternate installation item for a registered citadel type with a primary item. */
    fun registerPlacementDefinition(materialKey: Key, definitionKey: Key) =
        placement.registerMaterial(materialKey, definitionKey)
}
