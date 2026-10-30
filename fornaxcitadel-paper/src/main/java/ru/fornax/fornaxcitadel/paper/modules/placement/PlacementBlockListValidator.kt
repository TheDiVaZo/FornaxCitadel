package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.Server
import org.bukkit.block.Block
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry

/** Applies a list to resolved material identities, including materials supplied by other plugins. */
class PlacementBlockListValidator(
    private val server: Server,
    private val materials: MaterialRegistry,
    val listType: BlockListType,
    blocks: Set<Key>
) : ActionValidator<PlaceAction> {
    private val blocks = blocks.toSet()
    override val actionClass = PlaceAction::class.java

    override fun validate(action: PlaceAction): ActionValidator.Result {
        val block = server.findLoadedPlacementBlock(action) ?: return ActionValidator.Result.Rejected
        return if (isAllowed(block)) ActionValidator.Result.Success else ActionValidator.Result.Rejected
    }

    fun isAllowed(block: Block): Boolean {
        val listed = materials.getTypeOrNull(block)?.getKey() in blocks
        return when (listType) {
            BlockListType.BLACKLIST -> !listed
            BlockListType.WHITELIST -> listed
        }
    }
}
