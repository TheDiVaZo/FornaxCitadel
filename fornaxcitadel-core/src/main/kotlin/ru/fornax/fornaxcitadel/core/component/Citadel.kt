package ru.fornax.fornaxcitadel.core.component

import java.lang.Long.bitCount
import java.util.*

data class Citadel(
    val id: UUID,
    private var componentsMask: Long,
    private var systemsMask: Long,
    private var enabled: Boolean,
    private var dirty: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Citadel) return false

        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    fun isDirty(): Boolean {
        return dirty
    }

    internal fun markAsDirty() {
        dirty = true
    }

    internal fun markAsClean() {
        dirty = false
    }

    fun isEnabled(): Boolean {
        return enabled
    }

    internal fun disable() {
        enabled = false
    }

    internal fun enable() {
        enabled = true
    }

    fun systemCount(): Long {
        return bitCount(systemsMask).toLong()
    }

    fun componentCount(): Long {
        return bitCount(componentsMask).toLong()
    }

    fun systems(): Long {
        return systemsMask
    }

    fun components(): Long {
        return componentsMask
    }

    internal fun inSystem(bit: Long): Boolean {
        return (systemsMask and bit) == bit
    }

    internal fun addSystem(bit: Long) {
        systemsMask = systemsMask or bit
    }

    internal fun setSystems(bits: Long) {
        systemsMask = bits
    }

    internal fun removeSystem(bit: Long) {
        systemsMask = systemsMask and bit.inv()
    }

    internal fun hasAnyComponent(): Boolean {
        return componentsMask > 0
    }

    internal fun hasComponent(bit: Long): Boolean {
        return (componentsMask and bit) == bit
    }

    internal fun addComponent(bit: Long) {
        componentsMask = componentsMask or bit
    }

    internal fun setComponents(bits: Long) {
        componentsMask = bits
    }

    internal fun removeComponent(bit: Long) {
        componentsMask = componentsMask and bit.inv()
    }
}
