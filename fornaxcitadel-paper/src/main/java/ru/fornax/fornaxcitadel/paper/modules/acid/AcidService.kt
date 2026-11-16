package ru.fornax.fornaxcitadel.paper.modules.acid

import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.citadel.isOwnerOrUnowned
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.CitadelModule
import ru.fornax.fornaxcitadel.modules.health.AcidDestroyAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.breaking.PaperBreakingService
import ru.fornax.fornaxcitadel.paper.modules.resolver.MaterialPlacementService
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.logging.Level

/** No acid entities or timers: every operation resolves current material and existing components. */
class AcidService(
    private val plugin: Plugin,
    private val module: CitadelModule,
    private val citadelTypes: PaperCitadelTypes,
    private val materials: MaterialRegistry,
    private val placement: MaterialPlacementService,
    private val breaking: PaperBreakingService,
    val registry: AcidRegistry
) : LifecycleService {
    private val manager = module.actions.componentManager
    private var enabled = false
    private var busy = false
    private val scopedActions = Collections.newSetFromMap(IdentityHashMap<AcidDestroyAction, Boolean>())
    private val multiplier = AcidMaturationMultiplier(registry, plugin.server, manager)
    fun findTarget(player: Player): Block? {
        check(plugin.server.isPrimaryThread) { "Acid lookup requires the main server thread" }
        return LoadedBlockTarget.find(player, registry.settings.maxDistance, manager)
    }
    private val validator = object : ActionValidator<AcidDestroyAction> {
        override val actionClass = AcidDestroyAction::class.java
        override fun validate(action: AcidDestroyAction): ActionValidator.Result =
            if (enabled && busy && action in scopedActions) ActionValidator.Result.Success else ActionValidator.Result.Rejected
    }

    override fun enable() {
        if (enabled) return
        module.health.maturation.registerMultiplier(multiplier)
        module.actions.validatorCoordinator.registerValidator(validator)
        enabled = true
    }

    override fun disable() {
        module.actions.validatorCoordinator.unregisterValidator(validator)
        module.health.maturation.unregisterMultiplier(multiplier)
        scopedActions.clear()
        enabled = false
    }

    fun inspect(player: Player, block: Block?): AcidResult = operation {
        val acid = resolve(block)
        val candidates = candidates(player, acid)
        AcidResult.Information(acid.type, acid.citadel, acid.owner, acid.tier,
            module.health.maturation.isMature(acid.citadel), candidates.size,
            minOf(candidates.size, acid.type.maxTargets))
    }

    fun activate(player: Player, block: Block?): AcidResult = operation {
        val acid = resolve(block)
        checkActor(player, acid)
        if (!module.health.maturation.isMature(acid.citadel)) fail(AcidFailure.IMMATURE)
        val selected = candidates(player, acid).take(acid.type.maxTargets)
        val drops = registry.settings.drops
        if (breaking.destructionParts(acid.block, placement).mapNotNull {
            module.citadels.findProtecting(position(it))
        }.any { !same(it, acid.citadel) }) fail(AcidFailure.DENIED)
        val sourceAction = action(player, acid, acid.block, consuming = true)
        scopedActions.add(sourceAction)
        if (!module.actions.isAllowed(sourceAction)) fail(AcidFailure.DENIED)
        val event = AcidBlockActivateEvent(player, acid.citadel.position, acid.type, acid.citadel,
            selected.map { AcidTarget(position(it.block), it.citadel) })
        plugin.server.pluginManager.callEvent(event)
        if (event.isCancelled) fail(AcidFailure.CANCELLED)
        if (breaking.destroy(player, acid.block, listOf(acid.citadel), listOf(sourceAction), materials, placement,
                drops.acidBlock, drops.acidReinforcement, validate = { sourceIsCurrent(player, acid) }) != ActionResult.SUCCESS) {
            fail(AcidFailure.DENIED)
        }
        var destroyed = 0
        for (target in selected) {
            val result = breaking.destroy(player, target.block, target.reinforcements, target.actions, materials, placement,
                drops.targetBlock, drops.targetReinforcement, validate = {
                    enabled && target.reinforcements.all {
                        canDissolve(acid.tier, citadelTypes.level(it))
                    }
                }, createEvent = { part -> AcidBlockDissolveEvent(part, player, acid.citadel.position, acid.type) })
            if (result == ActionResult.SUCCESS) destroyed++
        }
        AcidResult.Activated(destroyed)
    }

    private fun sourceIsCurrent(player: Player, acid: AcidBlock): Boolean {
        checkActor(player, acid)
        return enabled && registry.find(acid.block.type) == acid.type && acid.citadel.isCurrent &&
            citadelTypes.level(acid.citadel) == acid.tier &&
            module.health.maturation.isMature(acid.citadel)
    }

    private fun checkActor(player: Player, acid: AcidBlock) {
        if (!player.isOnline || player.gameMode == GameMode.SPECTATOR || player.world.uid != acid.block.world.uid ||
            !player.hasPermission("fornaxcitadel.acid")) fail(AcidFailure.DENIED)
        if (!acid.citadel.isOwnerOrUnowned(player.uniqueId)) fail(AcidFailure.NOT_OWNER)
    }

    private fun resolve(block: Block?): AcidBlock {
        if (!enabled || !registry.settings.enabled) fail(AcidFailure.DISABLED)
        if (block == null) fail(AcidFailure.NOT_FOUND)
        loadedBlock(position(block))
        val citadel = module.citadels.findProtecting(position(block)) ?: run {
            if (registry.isDisabled(block.type)) fail(AcidFailure.DISABLED)
            fail(if (registry.find(block.type) != null) AcidFailure.NOT_REINFORCED else AcidFailure.NOT_FOUND)
        }
        val anchor = loadedBlock(citadel.position)
        val type = registry.find(anchor.type) ?: fail(if (registry.isDisabled(anchor.type)) AcidFailure.DISABLED else AcidFailure.NOT_FOUND)
        val tier = citadelTypes.level(citadel)
        return AcidBlock(anchor, citadel, type, citadel.getComponent(CitadelOwner::class.java)?.playerId, tier)
    }

    private fun candidates(player: Player, acid: AcidBlock): List<Target> {
        val targets = mutableListOf<Target>()
        val seen = mutableSetOf<Pair<CitadelPosition, Int>>()
        for (face in FACES) {
            val block = placement.findLoadedRelative(acid.block, face) ?: continue
            if (block.isEmpty) continue
            val citadel = module.citadels.findProtecting(position(block)) ?: continue
            if (same(citadel, acid.citadel) || citadel.position to citadel.runtimeId in seen) continue
            val parts = breaking.destructionParts(block, placement)
            val protected = parts.mapNotNull { part -> module.citadels.findProtecting(position(part))?.let { part to it } }
                .distinctBy { it.second.position to it.second.runtimeId }
            if (protected.any { same(it.second, acid.citadel) }) continue
            if (protected.any { !canDissolve(acid.tier, citadelTypes.level(it.second)) }) continue
            val actions = protected.map { action(player, acid, it.first) }
            scopedActions.addAll(actions)
            if (!actions.all(module.actions::isAllowed)) continue
            seen.addAll(protected.map { it.second.position to it.second.runtimeId })
            targets.add(Target(block, citadel, protected.map { it.second }, actions))
        }
        return targets
    }

    private fun action(player: Player, acid: AcidBlock, block: Block, consuming: Boolean = false) = AcidDestroyAction(
        ActionContext(player.uniqueId, PlayerSource(player, SOURCE)), position(block), acid.citadel.position,
        acid.citadel.runtimeId, consuming)

    private fun loadedBlock(position: CitadelPosition): Block = LoadedBlockTarget.blockAt(plugin.server, manager, position)

    private inline fun operation(body: () -> AcidResult): AcidResult {
        if (!plugin.server.isPrimaryThread) return AcidResult.Failed(AcidFailure.WRONG_THREAD)
        if (busy) return AcidResult.Failed(AcidFailure.BUSY)
        busy = true
        return try { body() }
        catch (failure: Refused) { AcidResult.Failed(failure.reason) }
        catch (_: CitadelResolutionUnavailableException) { AcidResult.Failed(AcidFailure.CHUNK_UNAVAILABLE) }
        catch (failure: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not complete acid operation", failure)
            AcidResult.Failed(AcidFailure.ERROR)
        } finally { scopedActions.clear(); busy = false }
    }

    private fun fail(reason: AcidFailure): Nothing = throw Refused(reason)
    private class Refused(val reason: AcidFailure) : RuntimeException()
    private fun canDissolve(source: Int?, target: Int?): Boolean = target == null || source != null && source >= target

    private data class AcidBlock(val block: Block, val citadel: CitadelHandle, val type: AcidType, val owner: UUID?, val tier: Int?)
    private data class Target(val block: Block, val citadel: CitadelHandle,
                              val reinforcements: List<CitadelHandle>, val actions: List<AcidDestroyAction>)

    companion object {
        private val SOURCE = Key("paper", "acid")
        private val FACES = listOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
        private fun position(block: Block) = CitadelPosition(block.world.uid, block.x, block.y, block.z)
        private fun same(left: CitadelHandle, right: CitadelHandle) = left.position == right.position && left.runtimeId == right.runtimeId
    }
}

enum class AcidFailure(val message: String) {
    NOT_FOUND("not-found"), NOT_REINFORCED("not-reinforced"), DISABLED("disabled"), NOT_OWNER("not-owner"),
    IMMATURE("immature"), CANCELLED("cancelled"),
    CHUNK_UNAVAILABLE("chunk-unavailable"), INVALID_REINFORCEMENT("invalid-reinforcement"),
    DENIED("denied"), BUSY("busy"), WRONG_THREAD("wrong-thread"), ERROR("error")
}

sealed interface AcidResult {
    data class Failed(val reason: AcidFailure) : AcidResult
    data class Activated(val targets: Int) : AcidResult
    data class Information(val type: AcidType, val reinforcement: CitadelHandle, val owner: UUID?, val tier: Int?,
                           val mature: Boolean, val availableTargets: Int, val selectedTargets: Int) : AcidResult
}
