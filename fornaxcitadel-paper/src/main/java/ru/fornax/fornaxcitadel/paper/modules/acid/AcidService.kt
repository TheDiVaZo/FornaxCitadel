package ru.fornax.fornaxcitadel.paper.modules.acid

import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinitionRegistry
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.isOwnerOrUnowned
import ru.fornax.fornaxcitadel.core.facade.ActionFacade
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.MaturationFacade
import ru.fornax.fornaxcitadel.core.facade.ReadableHandle
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.acid.AcidAction
import ru.fornax.fornaxcitadel.paper.action.PlayerSource
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.ChunkLoadCheck
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionSource
import ru.fornax.fornaxcitadel.paper.modules.resolver.MaterialPlacementService
import java.util.UUID
import java.util.logging.Level

/** No acid entities or timers: every operation resolves current material and existing components. */
class AcidService(
    private val plugin: Plugin,
    private val actions: ActionFacade,
    private val citadels: CitadelFacade,
    private val maturation: MaturationFacade,
    private val definitions: CitadelDefinitionRegistry,
    private val materials: MaterialRegistry,
    private val placement: MaterialPlacementService,
    val registry: AcidRegistry
) : ActionProcessor<AcidAction>(), LifecycleService {
    override val actionClass = AcidAction::class.java
    override val actionKey = AcidAction.KEY
    private var enabled = false
    private var busy = false
    internal val activationEnabled get() = enabled && registry.settings.enabled
    private val isChunkLoaded: ChunkLoadCheck = citadels::isChunkLoaded
    private val multiplier = AcidMaturationMultiplier(registry, plugin.server, isChunkLoaded)

    init {
        actions.registerAction(this)
        actions.registerValidator(object : ActionValidator<AcidAction> {
            override val actionClass = AcidAction::class.java
            override fun validate(action: AcidAction): ActionValidator.Result = operation({ failure ->
                (action as? AcidActivationRequest)?.result = failure
                ActionValidator.Result.Rejected
            }) {
                activationSource(action)
                ActionValidator.Result.Neutral
            }
        })
    }

    fun findTarget(player: Player): Block? {
        check(plugin.server.isPrimaryThread) { "Acid lookup requires the main server thread" }
        return LoadedBlockTarget.find(player, registry.settings.maxDistance, isChunkLoaded)
    }

    override fun enable() {
        if (enabled) return
        maturation.registerMultiplier(multiplier)
        enabled = true
    }

    override fun disable() {
        enabled = false
    }

    fun inspect(player: Player, block: Block?): AcidResult = operation({ it }) {
        val acid = resolve(block?.let(::position))
        val candidates = candidates(acid)
        val owner = acid.citadel.getComponent(CitadelOwner::class.java)?.playerId
        AcidResult.Information(acid.type, acid.citadel, owner, definitions.level(acid.citadel),
            maturation.isMature(acid.citadel), candidates.size,
            minOf(candidates.size, acid.type.maxTargets))
    }

    fun activate(player: Player, block: Block?): AcidResult {
        if (!plugin.server.isPrimaryThread) return AcidResult.Failed(AcidFailure.WRONG_THREAD)
        if (busy) return AcidResult.Failed(AcidFailure.BUSY)
        if (!activationEnabled) return AcidResult.Failed(AcidFailure.DISABLED)
        if (block == null) return AcidResult.Failed(AcidFailure.NOT_FOUND)
        val request = AcidActivationRequest(acidPlayerContext(player), position(block))
        actions.execute(request)
        return request.result
    }

    override fun execute(action: AcidAction): ActionResult {
        val outcome = operation({ it }) { activate(action) }
        (action as? AcidActivationRequest)?.result = outcome
        return if (outcome is AcidResult.Activated) ActionResult.SUCCESS else ActionResult.DENIED
    }

    private fun activate(action: AcidAction): AcidResult.Activated {
        val acid = activationSource(action)
        val selected = candidates(acid).take(acid.type.maxTargets)
        val source = Target(acid.block, mapOf(acid.citadel.runtimeId to acid.citadel))
        if (!destroy(source)) fail(AcidFailure.DENIED)
        val destroyed = selected.count { target ->
            try {
                destroy(target)
            } catch (_: CitadelResolutionUnavailableException) {
                false
            }
        }
        return AcidResult.Activated(destroyed)
    }

    private fun destroy(target: Target): Boolean {
        if (!enabled || reinforcementsAt(target.block).keys != target.reinforcements.keys) return false
        try {
            val material = materials.getTypeOrNull(target.block)
            for (citadel in target.reinforcements.values) {
                check(citadels.removeCitadel(citadel)) { "Destruction target expired" }
            }
            material?.breakNaturallyBlock(target.block, triggerEffect = true)
        } catch (failure: CitadelResolutionUnavailableException) {
            throw IllegalStateException("Chunk became unavailable during destruction", failure)
        }
        return true
    }

    private fun activationSource(action: AcidAction): AcidBlock {
        val acid = resolve(action.position)
        checkActor(action.context, acid)
        if (!maturation.isMature(acid.citadel)) fail(AcidFailure.IMMATURE)
        return acid
    }

    private fun checkActor(context: ActionContext, acid: AcidBlock) {
        val source = context.source
        if (source is ExplosionSource && source.position.worldId != acid.block.world.uid) fail(AcidFailure.DENIED)
        val player = (source as? PlayerSource)?.player ?: return
        if (!player.isOnline || player.gameMode == GameMode.SPECTATOR || player.world.uid != acid.block.world.uid ||
            !player.hasPermission("fornaxcitadel.acid")) fail(AcidFailure.DENIED)
        if (!acid.citadel.isOwnerOrUnowned(player.uniqueId)) fail(AcidFailure.NOT_OWNER)
    }

    private fun resolve(position: CitadelPosition?): AcidBlock {
        if (!activationEnabled) fail(AcidFailure.DISABLED)
        if (position == null) fail(AcidFailure.NOT_FOUND)
        val block = loadedBlock(position)
        val citadel = citadels.findProtecting(position) ?: run {
            if (registry.isDisabled(block.type)) fail(AcidFailure.DISABLED)
            fail(if (registry.find(block.type) != null) AcidFailure.NOT_REINFORCED else AcidFailure.NOT_FOUND)
        }
        val anchor = if (position == citadel.position) block else loadedBlock(citadel.position)
        val type = registry.find(anchor.type) ?: fail(if (registry.isDisabled(anchor.type)) AcidFailure.DISABLED else AcidFailure.NOT_FOUND)
        return AcidBlock(anchor, citadel, type)
    }

    private fun candidates(acid: AcidBlock): List<Target> {
        val targets = mutableListOf<Target>()
        val seen = mutableSetOf(acid.citadel.runtimeId)
        for (face in FACES) {
            val block = placement.findLoadedRelative(acid.block, face) ?: continue
            if (block.isEmpty) continue
            val reinforcements = reinforcementsAt(block)
            if (reinforcements.isEmpty() || reinforcements.keys.any { it in seen }) continue
            seen.addAll(reinforcements.keys)
            targets.add(Target(block, reinforcements))
        }
        return targets
    }

    private fun reinforcementsAt(block: Block): Map<Int, CitadelHandle> {
        val primary = citadels.findProtecting(position(block)) ?: return emptyMap()
        if (block.isEmpty) return emptyMap()
        val reinforcements = mutableMapOf(primary.runtimeId to primary)
        val partner = placement.getDestructionPartner(block)
        if (partner != null) {
            if (partner.isEmpty) return emptyMap()
            citadels.findProtecting(position(partner))?.let { reinforcements[it.runtimeId] = it }
        }
        return reinforcements
    }

    private fun loadedBlock(position: CitadelPosition): Block = LoadedBlockTarget.blockAt(plugin.server, isChunkLoaded, position)

    private inline fun <T> operation(onFailure: (AcidResult.Failed) -> T, body: () -> T): T {
        if (!plugin.server.isPrimaryThread) return onFailure(AcidResult.Failed(AcidFailure.WRONG_THREAD))
        if (busy) return onFailure(AcidResult.Failed(AcidFailure.BUSY))
        busy = true
        return try { body() }
        catch (failure: Refused) { onFailure(AcidResult.Failed(failure.reason)) }
        catch (_: CitadelResolutionUnavailableException) { onFailure(AcidResult.Failed(AcidFailure.CHUNK_UNAVAILABLE)) }
        catch (failure: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not complete acid operation", failure)
            onFailure(AcidResult.Failed(AcidFailure.ERROR))
        } finally { busy = false }
    }

    private fun fail(reason: AcidFailure): Nothing = throw Refused(reason)
    private class Refused(val reason: AcidFailure) : RuntimeException()

    private data class AcidBlock(val block: Block, val citadel: CitadelHandle, val type: AcidType)
    private data class Target(val block: Block, val reinforcements: Map<Int, CitadelHandle>)

    companion object {
        private val FACES = listOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
        private fun position(block: Block) = CitadelPosition(block.world.uid, block.x, block.y, block.z)
    }
}

private val ACID_SOURCE = Key("paper", "acid")
internal fun acidPlayerContext(player: Player) = ActionContext(player.uniqueId, PlayerSource(player, ACID_SOURCE))

enum class AcidFailure(val message: String) {
    NOT_FOUND("not-found"), NOT_REINFORCED("not-reinforced"), DISABLED("disabled"), NOT_OWNER("not-owner"),
    IMMATURE("immature"),
    CHUNK_UNAVAILABLE("chunk-unavailable"), INVALID_REINFORCEMENT("invalid-reinforcement"),
    DENIED("denied"), BUSY("busy"), WRONG_THREAD("wrong-thread"), ERROR("error")
}

sealed interface AcidResult {
    data class Failed(val reason: AcidFailure) : AcidResult
    data class Activated(val targets: Int) : AcidResult
    data class Information(val type: AcidType, val reinforcement: ReadableHandle, val owner: UUID?, val tier: Int?,
                           val mature: Boolean, val availableTargets: Int, val selectedTargets: Int) : AcidResult
}
