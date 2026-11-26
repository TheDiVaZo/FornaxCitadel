package ru.fornax.fornaxcitadel.core.util

/** Keeps the active registration order unchanged until its current traversal finishes. */
internal class DeferredRegistrationList<T : Any> {
    private val active = ArrayList<T>()
    private val pending = ArrayList<Change<T>>()
    private var isIterating = false

    fun register(value: T) {
        if (containsEffective(value)) return
        if (isIterating) pending.add(Change(value, add = true))
        else active.add(value)
    }

    fun unregister(value: T): Boolean {
        if (!containsEffective(value)) return false
        if (isIterating) pending.add(Change(value, add = false))
        else active.removeAt(activeIndex(value))
        return true
    }

    /** The caller must finish in finally, including after early returns and callback failures. */
    fun beginIteration(): List<T> {
        check(!isIterating) { "Nested coordinator traversal is not allowed" }
        isIterating = true
        return active
    }

    fun endIteration() {
        try {
            var index = 0
            while (index < pending.size) {
                val change = pending[index++]
                if (change.add) active.add(change.value)
                else active.removeAt(activeIndex(change.value))
            }
        } finally {
            pending.clear()
            isIterating = false
        }
    }

    private fun containsEffective(value: T): Boolean {
        var index = pending.lastIndex
        while (index >= 0) {
            val change = pending[index--]
            if (change.value === value) return change.add
        }
        return activeIndex(value) >= 0
    }

    private fun activeIndex(value: T): Int {
        var index = 0
        while (index < active.size) {
            if (active[index] === value) return index
            index++
        }
        return -1
    }

    private class Change<T>(val value: T, val add: Boolean)
}
