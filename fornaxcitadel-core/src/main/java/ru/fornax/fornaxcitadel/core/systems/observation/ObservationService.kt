package ru.fornax.fornaxcitadel.core.systems.observation

import ru.fornax.fornaxcitadel.core.component.Component
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService
import ru.fornax.fornaxcitadel.core.scheduler.ScheduledTask
import ru.fornax.fornaxcitadel.core.scheduler.Scheduler
import java.util.ArrayDeque
import java.util.Collections
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Combines full publications into citadel audiences and periodically invokes matching systems.
 * Observation controls discovery; this service processes every active citadel each [intervalTicks].
 * There is one observation subscription per viewer UUID. Closing and registering it again creates
 * a new session, even for the same viewer object. All access is on the owning game thread.
 *
 * Systems may be registered before enable. Viewers may register only while enabled. Disable
 * releases viewers and runtime resources but retains system registrations for the next enable.
 * Discovery implementations, platform listeners and persistent component registration are external.
 */
class ObservationService<V> @JvmOverloads constructor(
    private val componentManager: ComponentManager,
    private val scheduler: Scheduler,
    private val intervalTicks: Long = 1,
    private val failureHandler: (Exception) -> Unit = DEFAULT_FAILURE_HANDLER
) : LifecycleService {
    private val viewers = LinkedHashMap<UUID, ViewerRegistration>()
    private val targets = LinkedHashMap<ObservationTarget, TargetEntry>()
    private val systems = mutableListOf<SystemRegistration>()
    // Callbacks may change registrations; each traversal keeps its captured snapshot.
    private var systemsSnapshot: List<SystemRegistration> = emptyList()
    private val changes = ArrayDeque<() -> Unit>()
    private val dirtySlots = HashMap<ObservationTarget, DirtySlot>()
    private var pendingDirty = ArrayList<DirtySlot>()
    private var processingDirty = ArrayList<DirtySlot>()
    private val enteredDuringFlush = HashSet<EnterAttempt>()
    private var run: Run? = null
    private var dispatching = false
    private var flushing = false

    val isEnabled: Boolean get() = run != null

    init {
        require(intervalTicks > 0) { "Observation interval must be positive" }
    }

    override fun enable() {
        if (run != null) return
        val started = Run()
        run = started
        try {
            started.task = scheduler.runTaskTimer(Runnable {
                if (run === started) tick(started)
            }, intervalTicks, intervalTicks)
        } catch (failure: Throwable) {
            run = null
            started.task?.cancel()
            throw failure
        }
    }

    override fun disable() {
        val stopped = run ?: return
        run = null
        val registrations = viewers.values.toList()
        val stoppedSystems = systemsSnapshot
        viewers.clear()
        registrations.forEach { it.active = false }
        enqueue {
            safely({ "cancel timer" }) { stopped.task?.cancel() }
            for (registration in registrations) releaseViewer(registration)
            // A callback may already have enabled a new run. Its subscriptions must not reuse
            // runtime resources from the stopped run, even if they observe the same components.
            for (system in stoppedSystems) detachSystem(system)
            markAllTargetsDirty()
        }
    }

    /** Copies the requirements. Registering the same system instance twice is a no-op. */
    fun registerSystem(system: ObservationSystem<V>) {
        if (systems.any { it.system === system }) return
        val registration = SystemRegistration(system).also(systems::add)
        systemsSnapshot = systems.toList()
        enqueue {
            if (registration.active) {
                registration.ready = true
                markAllTargetsDirty()
            }
        }
    }

    /** Removes only this system instance and releases all its existing display sessions. */
    fun unregisterSystem(system: ObservationSystem<V>): Boolean {
        val registration = systems.find { it.system === system } ?: return false
        registration.active = false
        systems.remove(registration)
        systemsSnapshot = systems.toList()
        enqueue { detachSystem(registration) }
        return true
    }

    /**
     * Starts one observation. Close the returned registration before reusing [viewerId].
     * Failed startup revokes the publisher and does not expose its buffered publications to systems.
     */
    fun registerViewer(
        viewerId: UUID,
        viewer: V,
        observation: Observation<V>
    ): ObservationSubscription {
        val currentRun = checkNotNull(run) { "Observation service is disabled" }
        require(!viewers.containsKey(viewerId)) { "Viewer $viewerId is already registered" }
        val registration = ViewerRegistration(viewerId, viewer, currentRun)
        viewers[viewerId] = registration
        try {
            registration.subscription = observation.observe(viewer, ObservationPublisher { citadels ->
                publish(registration, citadels)
            })
            registration.starting = false
            if (isActive(registration)) {
                schedulePublication(registration)
            } else {
                enqueue { releaseViewer(registration) }
            }
        } catch (failure: Throwable) {
            registration.active = false
            registration.starting = false
            viewers.remove(viewerId, registration)
            enqueue { releaseViewer(registration) }
            throw failure
        }
        return registration
    }

    private fun publish(registration: ViewerRegistration, citadels: Iterable<CitadelHandle>) {
        if (!isActive(registration)) return
        // Materialize before changing anything: iterators can fail or refer to a mutable collection.
        val snapshot = LinkedHashMap<ObservationTarget, CitadelHandle>()
        for (citadel in citadels) {
            if (componentManager.isCurrent(citadel)) {
                snapshot.putIfAbsent(ObservationTarget(citadel.position, citadel.runtimeId), citadel)
            }
        }
        if (!isActive(registration)) return
        registration.pending = snapshot
        if (!registration.starting) schedulePublication(registration)
    }

    private fun schedulePublication(registration: ViewerRegistration) {
        if (registration.publicationQueued || registration.pending == null) return
        registration.publicationQueued = true
        enqueue {
            registration.publicationQueued = false
            val snapshot = registration.pending
            registration.pending = null
            if (snapshot != null && isActive(registration)) replaceTargets(registration, snapshot)
        }
    }

    private fun replaceTargets(
        registration: ViewerRegistration,
        snapshot: Map<ObservationTarget, CitadelHandle>
    ) {
        for (key in registration.observed.keys) {
            if (key !in snapshot) {
                targets[key]?.viewers?.remove(registration.id, registration)
                markDirty(key)
            }
        }
        registration.observed.clear()
        for ((key, handle) in snapshot) {
            // A deferred publication may have become stale since it was copied.
            if (!isCurrent(handle)) {
                targets[key]?.viewers?.remove(registration.id, registration)
                markDirty(key)
                continue
            }
            registration.observed[key] = handle
            val target = targets.getOrPut(key) { TargetEntry(key, handle) }
            target.viewers[registration.id] = registration
            markDirty(target.dirtySlot)
        }
    }

    private fun releaseViewer(registration: ViewerRegistration) {
        registration.pending = null
        val subscription = registration.subscription
        registration.subscription = null
        safely({ "close observation for ${registration.id}" }) { subscription?.close() }
        for (key in registration.observed.keys) {
            targets[key]?.viewers?.remove(registration.id, registration)
            markDirty(key)
        }
        registration.observed.clear()
    }

    private fun isActive(registration: ViewerRegistration): Boolean =
        registration.active && run === registration.run && viewers[registration.id] === registration

    private fun enqueue(change: () -> Unit) {
        changes.addLast(change)
        if (!dispatching && !flushing) flushChanges()
    }

    private fun dirtySlotFor(key: ObservationTarget): DirtySlot =
        dirtySlots.getOrPut(key) { DirtySlot(key) }

    private fun markDirty(key: ObservationTarget) {
        // A missing target may be recreated by a later queued change. Preserve its queue position.
        markDirty(dirtySlotFor(key))
    }

    private fun markDirty(slot: DirtySlot) {
        if (slot.queued) return
        pendingDirty.add(slot)
        slot.queued = true
    }

    private fun markAllTargetsDirty() {
        for (target in targets.values) markDirty(target.dirtySlot)
    }

    /** Applies publications after callbacks, never while their audiences are being traversed. */
    private fun flushChanges() {
        if (dispatching || flushing) return
        flushing = true
        try {
            while (changes.isNotEmpty() || pendingDirty.isNotEmpty()) {
                while (changes.isNotEmpty()) changes.removeFirst().invoke()
                val batch = pendingDirty
                pendingDirty = processingDirty
                processingDirty = batch
                try {
                    // Reset the whole batch before callbacks so even a later target can be requeued.
                    for (index in batch.indices) batch[index].queued = false
                    for (index in batch.indices) targets[batch[index].key]?.let(::refreshTarget)
                } finally {
                    for (index in batch.indices) {
                        val slot = batch[index]
                        // Requeued slots belong to the next batch, including after a callback error.
                        if (!slot.queued && !targets.containsKey(slot.key)) dirtySlots.remove(slot.key, slot)
                    }
                    batch.clear()
                }
            }
        } finally {
            enteredDuringFlush.clear()
            flushing = false
        }
    }

    private fun refreshTarget(target: TargetEntry) {
        if (!isCurrent(target.handle)) {
            for (viewer in target.viewers.values) viewer.observed.remove(target.key)
            target.viewers.clear()
        } else {
            val iterator = target.viewers.values.iterator()
            while (iterator.hasNext()) {
                if (!isActive(iterator.next())) iterator.remove()
            }
        }
        val registeredSystems = systemsSnapshot
        for (system in registeredSystems) refreshSystem(system, target)
        if (target.viewers.isEmpty()) targets.remove(target.key, target)
    }

    private fun refreshSystem(system: SystemRegistration, target: TargetEntry) {
        if (!system.active || !system.ready) return
        var binding = system.bindings[target.key]
        if (binding != null) {
            if (target.viewers.values.none(::isActive) || !matchesBinding(system, target.handle, binding)) {
                detach(system, target.key, binding)
                binding = null
            } else {
                var departed: ArrayList<ViewerRegistration>? = null
                for (viewer in binding.viewers.values) {
                    if (target.viewers[viewer.id] !== viewer || !isActive(viewer)) {
                        if (departed == null) departed = ArrayList()
                        departed.add(viewer)
                    }
                }
                // Capture all departures before callbacks, which may close additional viewers.
                if (departed != null) {
                    for (viewer in departed) {
                        binding.cachedAudience = null
                        binding.viewers.remove(viewer.id)
                        safely({ "viewer leave for ${target.key}" }) {
                            system.system.onViewerLeave(target.key, viewer.viewer)
                        }
                    }
                }
            }
        }
        // Membership changes from callbacks are queued until this refresh finishes.
        for (viewer in target.viewers.values) {
            if (!system.active) return
            if (!isActive(viewer)) continue
            // Cleanup and previous callbacks may have changed any of the required components.
            if (binding != null && !matchesBinding(system, target.handle, binding)) {
                markDirty(target.dirtySlot)
                return
            }
            val current = binding ?: createBinding(system, target.handle) ?: return
            if (current.viewers[viewer.id] === viewer) continue
            // Do not recursively enter again when a callback replaces a required component or
            // republishes membership. A later reconciliation may enter again; timer passes
            // always reconsider every active target even without another publication.
            if (!enteredDuringFlush.add(EnterAttempt(system.system, viewer, target.key))) continue
            if (binding == null) {
                system.bindings[target.key] = current
                binding = current
            }
            // Track before the callback: even an unsuccessful enter may have acquired resources.
            current.cachedAudience = null
            current.viewers[viewer.id] = viewer
            safely({ "viewer enter for ${target.key}" }) {
                system.system.onViewerEnter(target.handle, target.key, viewer.viewer)
            }
        }
        binding?.let { current ->
            if (current.viewers.isEmpty()) detach(system, target.key, current)
            else if (system.active && !matchesBinding(system, target.handle, current)) {
                markDirty(target.dirtySlot)
            }
        }
    }

    private fun detach(
        system: SystemRegistration,
        key: ObservationTarget,
        binding: SystemBinding
    ) {
        if (!system.bindings.remove(key, binding)) return
        val audience = binding.viewers.values.toList()
        binding.cachedAudience = null
        binding.viewers.clear()
        for (viewer in audience) {
            safely({ "viewer leave for $key" }) { system.system.onViewerLeave(key, viewer.viewer) }
        }
        safely({ "detach system from $key" }) { system.system.onDetach(key) }
    }

    private fun detachSystem(system: SystemRegistration) {
        for ((key, binding) in system.bindings.toMap()) detach(system, key, binding)
    }

    private fun tick(expected: Run) {
        if (run !== expected || dispatching || flushing) return
        markAllTargetsDirty()
        flushChanges()
        if (run !== expected) return
        dispatching = true
        try {
            val registeredSystems = systemsSnapshot
            // Structural target changes are deferred until dispatch completes.
            for (target in targets.values) {
                for (index in registeredSystems.indices) {
                    if (run !== expected) return
                    tickSystem(registeredSystems[index], target)
                }
            }
        } finally {
            dispatching = false
            // Gameplay callbacks may have changed components or removed other observed citadels.
            markAllTargetsDirty()
            flushChanges()
        }
    }

    private fun tickSystem(system: SystemRegistration, target: TargetEntry) {
        if (!system.active || !system.ready) return
        val binding = system.bindings[target.key] ?: return
        if (!matchesBinding(system, target.handle, binding)) return
        val audience = audienceFor(binding)
        if (audience.isEmpty()) return
        safely({ "tick system at ${target.key}" }) {
            system.system.tick(target.handle, target.key, audience)
        }
    }

    private fun audienceFor(binding: SystemBinding): List<V> {
        val cached = binding.cachedAudience
        // Closing a subscription revokes it immediately, before deferred membership cleanup.
        if (cached != null && binding.viewers.values.all(::isActive)) return cached

        val audience = ArrayList<V>(binding.viewers.size)
        for (registration in binding.viewers.values) {
            if (isActive(registration)) audience.add(registration.viewer)
        }
        // Once exposed to a system, a snapshot's backing list must never be changed or reused.
        val snapshot = if (audience.isEmpty()) emptyList() else Collections.unmodifiableList(audience)
        binding.cachedAudience = snapshot
        return snapshot
    }

    private fun createBinding(system: SystemRegistration, handle: CitadelHandle): SystemBinding? {
        if (!isCurrent(handle)) return null
        val components = system.componentBuffer
        // Binding creation runs only during flushing, which prevents nested use of this buffer.
        try {
            for (index in system.requiredComponents.indices) {
                components.add(readComponent(handle, system.requiredComponents[index]) ?: return null)
            }
            return SystemBinding(components.toList())
        } finally {
            components.clear()
        }
    }

    private fun matchesBinding(
        system: SystemRegistration, handle: CitadelHandle, binding: SystemBinding
    ): Boolean {
        // Empty requirements still need a live entity; equality of component values is insufficient.
        if (!isCurrent(handle)) return false
        for (index in system.requiredComponents.indices) {
            if (readComponent(handle, system.requiredComponents[index]) !== binding.components[index]) return false
        }
        return true
    }

    private fun readComponent(handle: CitadelHandle, componentClass: Class<out Component>): Component? {
        return try {
            handle.getComponent(componentClass)
        } catch (failure: Exception) {
            report("read ${componentClass.name} at ${handle.position}", failure)
            null
        }
    }

    private fun isCurrent(handle: CitadelHandle): Boolean = try {
        componentManager.isCurrent(handle)
    } catch (failure: Exception) {
        report("check citadel at ${handle.position}", failure)
        false
    }

    private inline fun safely(operation: () -> String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            report(operation(), failure)
        }
    }

    private fun report(operation: String, failure: Exception) {
        val contextual = IllegalStateException("Could not $operation", failure)
        try {
            failureHandler(contextual)
        } catch (reportFailure: Exception) {
            logger.log(Level.SEVERE, contextual.message, failure)
            logger.log(Level.SEVERE, "Observation failure handler failed", reportFailure)
        }
    }

    private inner class ViewerRegistration(val id: UUID, val viewer: V, val run: Run) : ObservationSubscription {
        var active = true
        var starting = true
        var subscription: ObservationSubscription? = null
        var pending: Map<ObservationTarget, CitadelHandle>? = null
        var publicationQueued = false
        val observed = LinkedHashMap<ObservationTarget, CitadelHandle>()

        override fun close() {
            if (!active) return
            active = false
            viewers.remove(id, this)
            enqueue { releaseViewer(this) }
        }
    }

    private inner class TargetEntry(val key: ObservationTarget, val handle: CitadelHandle) {
        val dirtySlot = dirtySlotFor(key)
        val viewers = LinkedHashMap<UUID, ViewerRegistration>()
    }

    private inner class SystemRegistration(val system: ObservationSystem<V>) {
        // A private ordered copy keeps the requirements and bound instances aligned.
        val requiredComponents = system.requiredComponents.toList()
        val componentBuffer = ArrayList<Component>(requiredComponents.size)
        var active = true
        var ready = false
        val bindings = LinkedHashMap<ObservationTarget, SystemBinding>()
    }

    private inner class SystemBinding(val components: List<Component>) {
        val viewers = LinkedHashMap<UUID, ViewerRegistration>()
        var cachedAudience: List<V>? = null
    }

    private class DirtySlot(val key: ObservationTarget) {
        var queued = false
    }

    private class Run {
        var task: ScheduledTask? = null
    }

    private class EnterAttempt(val system: Any, val viewer: Any, val target: ObservationTarget) {
        override fun equals(other: Any?): Boolean = other is EnterAttempt &&
            system === other.system && viewer === other.viewer && target == other.target

        override fun hashCode(): Int =
            (31 * System.identityHashCode(system) + System.identityHashCode(viewer)) * 31 + target.hashCode()
    }

    companion object {
        private val logger = Logger.getLogger(ObservationService::class.java.name)

        @JvmField
        val DEFAULT_FAILURE_HANDLER: (Exception) -> Unit = { failure ->
            logger.log(Level.WARNING, "Observation callback failed", failure)
        }
    }
}
