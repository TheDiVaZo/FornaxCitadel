package ru.fornax.fornaxcitadel.modules.breaking

import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleNotifications
import ru.fornax.fornaxcitadel.core.citadel.RemovalCause
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.SimpleComponentManager
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.HealthService
import ru.fornax.fornaxcitadel.modules.remove.RemoveService

class BreakService(
    private val manager: SimpleComponentManager,
    private val health: HealthService,
    private val remove: RemoveService,
    private val notifications: CitadelLifecycleNotifications
) {
    private val policies = mutableMapOf<Key, BreakPolicy>()

    fun registerPolicy(key: Key, policy: BreakPolicy) {
        val existing = policies[key]
        require(existing == null || existing === policy) { "Break policy already registered: $key" }
        policies[key] = policy
    }

    fun findPolicy(key: Key): BreakPolicy? = policies[key]

    fun allowsDamage(context: ActionContext, citadel: CitadelHandle): Boolean {
        val kind = citadel.getComponent(CitadelKind::class.java) ?: return false
        val policy = policies[kind.definitionKey] ?: return false
        return policy.decide(context, citadel) != BreakDecision.DENY
    }

    fun breakCitadel(action: BreakAction): ActionResult {
        notifications.checkMutationAllowed()
        if (!manager.isChunkLoaded(action.citadelW, action.citadelX shr 4, action.citadelZ shr 4)) {
            return ActionResult.DENIED
        }
        val citadel = manager.findCitadel(action.citadelW, action.citadelX, action.citadelY, action.citadelZ)
            ?: return ActionResult.DENIED
        val kind = citadel.getComponent(CitadelKind::class.java) ?: return ActionResult.DENIED
        val policy = policies[kind.definitionKey] ?: return ActionResult.DENIED
        return when (policy.decide(action.context, citadel)) {
            BreakDecision.DENY -> ActionResult.DENIED
            BreakDecision.REMOVE -> remove.remove(citadel, RemovalCause.OWNER_DISMANTLE)
            BreakDecision.DAMAGE -> health.damage(citadel, action.damage)
        }
    }
}
