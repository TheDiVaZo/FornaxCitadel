package ru.fornax.fornaxcitadel.paper.modules.dismantle

/** Refund probability for damaged citadels; full health or no Health component guarantees a refund. */
data class DismantleSettings(val refundChance: Double = 0.5) {
    init {
        require(refundChance.isFinite() && refundChance in 0.0..1.0) {
            "dismantle.refund-chance must be finite and between 0 and 1"
        }
    }
}
