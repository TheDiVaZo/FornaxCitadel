package ru.fornax.fornaxcitadel.core.component

import java.util.*
import kotlin.properties.Delegates

open class CSystem {
    private var systemBits by Delegates.notNull<Long>()
    private var processing: Boolean = false;
    private var activated: Boolean = false;

    private val itr = { citadel: Citadel -> processCitadel(citadel) }

    private val group: ComponentGroup;

    private val entities: MutableSet<Citadel> = HashSet()
    private val waitToAdd: Deque<Citadel> = ArrayDeque()
    private val waitToRemove: Deque<Citadel> = ArrayDeque()

    constructor(group: ComponentGroup) {
        this.group = group
    }

    internal fun setSystemBits(bits: Long) {
        this.systemBits = bits
    }

    fun process(): Boolean {
        if (activated && !processing) {
            clean()
            begin()
            processing = true
            entities.forEach(itr)
            processing = false
            handleWaiting()
            end()
            return true
        }
        return false
    }

    private fun handleWaiting() {
        while (waitToAdd.isNotEmpty()) {
            val e: Citadel = waitToAdd.pop()
            if (e.inSystem(systemBits)) {
                entities.add(e)
                entityAdded(e)
            }
        }
        while (waitToRemove.isNotEmpty()) {
            val e: Citadel = waitToRemove.pop()
            if (!e.inSystem(systemBits)) {
                entities.remove(e)
                entityRemoved(e)
            }
        }
    }

    private fun clean() {
        //TODO: Clean in dirty entities in EntityManager
    }

    private fun revalidate(e: Citadel) {
        val inSystem: Boolean = e.inSystem(systemBits)
        if (e.isEnabled()) {
            if (inSystem == group.containsAll(e.components())) return
            if (inSystem) removeEntity(e)
            else addEntity(e)
        } else if (inSystem) removeEntity(e)
    }

    private fun addEntity(e: Citadel) {
        e.addSystem(systemBits)
        if (processing) waitToAdd.add(e)
        else {
            entities.add(e)
            entityAdded(e)
        }
    }

    private fun removeEntity(e: Citadel) {
        e.removeSystem(systemBits)
        if (processing) waitToRemove.add(e)
        else {
            entities.remove(e)
            entityRemoved(e)
        }
    }

    protected fun processCitadel(citadel: Citadel) {}

    protected fun begin() {}

    protected fun end() {}

    protected fun entityAdded(e: Citadel) {}

    protected fun entityRemoved(e: Citadel) {}

    fun activate() {
        activated = true
    }

    fun deactivate() {
        activated = false
    }

    fun isActivated(): Boolean {
        return activated
    }

    fun isProcessing(): Boolean {
        return processing
    }

    fun getSystemBits(): Long {
        return systemBits
    }

    fun getGroup(): ComponentGroup {
        return group
    }

}