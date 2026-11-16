package ru.fornax.fornaxcitadel.paper.modules.placement

import org.bukkit.Server
import org.bukkit.block.Block
import ru.fornax.fornaxcitadel.core.facade.ReadableCitadelFacade
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.ActionData
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.modules.upgrade.UpgradeAction
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry

/** Applies a list to resolved material identities, including materials supplied by other plugins. */
class PlacementBlockListValidator @JvmOverloads constructor(
    private val server: Server,
    private val materials: MaterialRegistry,
    val listType: BlockListType,
    blocks: Set<Key>,
    private val citadels: ReadableCitadelFacade? = null
) : ActionValidator<ActionData> {
    private val blocks = blocks.toSet()
    override val actionClass = ActionData::class.java

    override fun validate(action: ActionData): ActionValidator.Result {
        if (action !is PlaceAction && action !is UpgradeAction) return ActionValidator.Result.Neutral
        val block = server.findLoadedPlacementBlock(action, citadels) ?: return ActionValidator.Result.Rejected
        if (!isAllowed(block)) return ActionValidator.Result.Rejected
        return if (action is PlaceAction) ActionValidator.Result.Success else ActionValidator.Result.Neutral
    }

    fun isAllowed(block: Block): Boolean {
        val listed = materials.getTypeOrNull(block)?.getKey() in blocks
        return when (listType) {
            BlockListType.BLACKLIST -> !listed
            BlockListType.WHITELIST -> listed
        }
    }
}
