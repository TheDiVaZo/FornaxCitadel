package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key

class DamageProcessor(
    private val healthService: HealthService
) : ActionProcessor<DamageAction>() {
    override val actionClass: Class<DamageAction>
        get() = DamageAction::class.java

    override val actionKey: Key
        get() = DamageAction.KEY

    override fun execute(action: DamageAction): ActionResult {
        return healthService.onDamage(action)
    }
}
