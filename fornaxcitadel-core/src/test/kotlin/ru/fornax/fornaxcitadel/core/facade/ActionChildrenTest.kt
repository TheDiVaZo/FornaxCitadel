package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.CitadelStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.*
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID
import kotlin.test.*

class ActionChildrenTest {
    @Test
    fun `children validate and commit synchronously but all handlers see the completed parent`() {
        val f = Fixture()
        val trace = mutableListOf<String>()
        var finished = false
        f.validate { trace.add("validate:${it.type.key}"); ActionValidator.Result.Success }
        f.process(CHILD) { trace.add("child"); ActionResult.SUCCESS }
        f.process(ROOT) {
            trace.add("parent:start")
            assertEquals(ActionResult.SUCCESS, f.actions.executeChild(f.child))
            assertEquals(listOf("validate:root", "parent:start", "validate:child", "child"), trace)
            finished = true
            trace.add("parent:finish")
            ActionResult.SUCCESS
        }
        f.handle {
            assertTrue(finished)
            trace.add("notify:${it.type.key}")
        }
        assertEquals(ActionResult.SUCCESS, f.actions.execute(f.root))
        assertEquals(listOf("validate:root", "parent:start", "validate:child", "child", "parent:finish",
            "notify:child", "notify:root"), trace)
    }

    @Test
    fun `child metadata veto absence and processor failure prevent parent commit and notifications`() {
        for (reason in listOf("neutral", "veto", "missing", "denied", "validator exception", "processor exception")) {
            val f = Fixture()
            var changed = false
            var notified = false
            f.actions.actionMetaRegistry.register(CHILD, ActionMetaRegistry.ActionMeta(false))
            f.validate {
                if (it.type != CHILD) ActionValidator.Result.Success else when (reason) {
                    "neutral" -> ActionValidator.Result.Neutral
                    "veto" -> ActionValidator.Result.Rejected
                    "validator exception" -> error("validation failed")
                    else -> ActionValidator.Result.Success
                }
            }
            if (reason != "missing") f.process(CHILD) {
                if (reason == "processor exception") error("processor failed")
                ActionResult.DENIED
            }
            f.process(ROOT) {
                val result = f.actions.executeChild(f.child)
                if (result == ActionResult.SUCCESS) changed = true
                result
            }
            f.handle { notified = true }
            if (reason.endsWith("exception")) assertFailsWith<IllegalStateException>(reason) { f.actions.execute(f.root) }
            else assertEquals(ActionResult.DENIED, f.actions.execute(f.root), reason)
            assertFalse(changed, reason)
            assertFalse(notified, reason)
            f.process(ROOT) { ActionResult.SUCCESS }
            assertEquals(ActionResult.SUCCESS, f.actions.execute(f.root), reason)
        }
    }

    @Test
    fun `child execution is forbidden outside processors including child validators and all handlers`() {
        val f = Fixture()
        var checks = 0
        fun forbidden() {
            assertFailsWith<IllegalStateException> { f.actions.executeChild(f.child) }
            checks++
        }
        forbidden()
        f.validate { forbidden(); ActionValidator.Result.Success }
        f.process(CHILD) { ActionResult.SUCCESS }
        f.process(ROOT) {
            assertFailsWith<IllegalStateException> { f.actions.execute(f.child) }
            assertFailsWith<IllegalStateException> { f.actions.isAllowed(f.child) }
            assertFailsWith<IllegalStateException> { f.actions.drainPosted() }
            f.actions.executeChild(f.child)
        }
        f.handle { forbidden() }
        assertTrue(f.actions.isAllowed(f.root))
        assertEquals(ActionResult.SUCCESS, f.actions.execute(f.root))
        assertEquals(6, checks)
        assertEquals(ActionResult.SUCCESS, f.actions.executeBatch(listOf(f.root), {
            forbidden(); true
        }, { forbidden() }))
        assertEquals(11, checks)
        forbidden()
    }

    @Test
    fun `grandchildren notify in completion order and cannot use ordinary nested execution`() {
        val f = Fixture()
        val grandchild = f.action(Key("test", "grandchild"))
        val notifications = mutableListOf<ActionData>()
        f.process(grandchild.type) {
            assertFailsWith<IllegalStateException> { f.actions.execute(f.root) }
            ActionResult.SUCCESS
        }
        f.process(CHILD) { f.actions.executeChild(grandchild) }
        f.process(ROOT) { f.actions.executeChild(f.child) }
        f.handle(notifications::add)
        assertEquals(ActionResult.SUCCESS, f.actions.execute(f.root))
        assertEquals(listOf(grandchild, f.child, f.root), notifications)
    }

    @Test
    fun `parent failure after committed child is exceptional and clears deferred notifications without rollback`() {
        for (throwing in listOf(false, true)) {
            val f = Fixture()
            var childCommits = 0
            val notifications = mutableListOf<ActionData>()
            f.process(CHILD) { childCommits++; ActionResult.SUCCESS }
            f.process(ROOT) {
                assertEquals(ActionResult.SUCCESS, f.actions.executeChild(f.child))
                if (throwing) error("unexpected failure after commit")
                ActionResult.DENIED
            }
            f.handle(notifications::add)
            assertFailsWith<IllegalStateException> { f.actions.execute(f.root) }
            assertEquals(1, childCommits)
            assertTrue(notifications.isEmpty())
            f.process(ROOT) { ActionResult.SUCCESS }
            assertEquals(ActionResult.SUCCESS, f.actions.execute(f.root))
            assertEquals(listOf(f.root), notifications)
        }
    }

    @Test
    fun `posted parent includes its children in the same drain and handlers can only post subsequent work`() {
        val f = Fixture()
        val later = f.action(Key("test", "later"))
        val notifications = mutableListOf<ActionData>()
        f.process(CHILD) { ActionResult.SUCCESS }
        f.process(ROOT) { f.actions.executeChild(f.child) }
        f.process(later.type) { ActionResult.SUCCESS }
        f.handle {
            notifications.add(it)
            if (it === f.child) f.actions.post(later)
        }
        f.actions.post(f.root)
        assertEquals(1, f.actions.drainPosted(1))
        assertEquals(listOf(f.child, f.root), notifications)
        assertEquals(1, f.actions.drainPosted(1))
        assertEquals(listOf(f.child, f.root, later), notifications)
    }

    private class Fixture {
        val actions = ActionManager(ComponentManager(UnusedStorage, TypeManager()))
        val root = action(ROOT)
        val child = action(CHILD)
        fun action(key: Key) = ActionData(key, UUID(0, 1), 0, 0, 0)
        fun process(key: Key, block: (ActionData) -> ActionResult) {
            actions.processorCoordinator.getProcessor(key)?.let(actions.processorCoordinator::unregisterProcessor)
            actions.processorCoordinator.registerProcessor(object : ActionProcessor<ActionData>() {
                override val actionClass = ActionData::class.java
                override val actionKey = key
                override fun execute(action: ActionData) = block(action)
            })
        }
        fun validate(block: (ActionData) -> ActionValidator.Result) {
            actions.validatorCoordinator.registerValidator(object : ActionValidator<ActionData> {
                override val actionClass = ActionData::class.java
                override fun validate(action: ActionData) = block(action)
            })
        }
        fun handle(block: (ActionData) -> Unit) {
            actions.handlerCoordinator.registerHandler(object : ActionHandler<ActionData> {
                override val actionClass = ActionData::class.java
                override fun handle(action: ActionData) = block(action)
            })
        }
    }

    private object UnusedStorage : CitadelStorage {
        override fun getIfChunkLoaded(location: WXZCord): ChunkCord? = null
        override fun loadChunk(location: WXZCord): Nothing = error("Unexpected chunk load")
        override fun unloadChunk(location: WXZCord): Nothing = error("Unexpected chunk unload")
        override fun updateOpaque(chunk: ChunkCord): Nothing = error("Unexpected opaque update")
        override fun updateOpaque(key: Key): Nothing = error("Unexpected opaque update")
        override fun saveAll(): Nothing = error("Unexpected save")
        override fun closeAndSaveAll(): Nothing = error("Unexpected close")
    }

    private companion object {
        val ROOT = Key("test", "root")
        val CHILD = Key("test", "child")
    }
}
