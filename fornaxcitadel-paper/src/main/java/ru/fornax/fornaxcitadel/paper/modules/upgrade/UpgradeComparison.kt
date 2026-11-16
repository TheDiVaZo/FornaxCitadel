package ru.fornax.fornaxcitadel.paper.modules.upgrade

/** Only known source values participate. A missing maximum is not treated as infinite health. */
internal data class UpgradeComparison(
    val oldLevel: Int?,
    val oldHealth: Double?,
    val oldInvulnerable: Boolean,
    val newLevel: Int,
    val newHealth: Double?,
    val newInvulnerable: Boolean
) {
    fun isImprovement(): Boolean {
        if (oldHealth != null && (!oldHealth.isFinite() || oldHealth < 0.0)) return false
        if (oldInvulnerable && !newInvulnerable) return false
        if (newHealth == null) return newInvulnerable && oldLevel != null && newLevel > oldLevel
        if (oldLevel != null && newLevel < oldLevel) return false
        if (oldHealth != null && newHealth < oldHealth) return false
        return oldLevel != null && newLevel > oldLevel || oldHealth != null && newHealth > oldHealth
    }
}
