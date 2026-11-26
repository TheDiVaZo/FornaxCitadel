package ru.fornax.fornaxcitadel.core.facade

import ru.fornax.fornaxcitadel.core.systems.action.Action
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionMeta
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator.Result
import ru.fornax.fornaxcitadel.core.util.Key
import java.lang.reflect.Modifier
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActionManagerTest {
    @Test
    fun `processors execute children synchronously and defer notifications until the root succeeds`() {
        val fixture = Fixture()
        val facade = ActionFacade(fixture.actions)
        val parent = fixture.action()
        val child = fixture.action(OTHER_KEY)
        val vetoed = fixture.action()
        val first = fixture.action()
        val second = fixture.action()
        val published = fixture.action()
        val notifications = mutableListOf<Action>()
        var batchCommitted = false
        var parentFinished = false
        facade.registerValidator(object : ActionValidator<Action> {
            override val actionClass = Action::class.java
            override fun validate(action: Action) = if (action === vetoed) Result.Rejected else Result.Neutral
        })
        facade.registerHandler(handler(Action::class.java) { action ->
            assertTrue(parentFinished)
            notifications.add(action)
        })
        fixture.process = { action ->
            when (action) {
                parent -> {
                    assertTrue(facade.isAllowed(child))
                    facade.publish(published)
                    assertEquals(ActionResult.SUCCESS, facade.execute(child))
                    assertFalse(facade.isAllowed(vetoed))
                    assertEquals(ActionResult.DENIED, facade.execute(vetoed))
                    assertTrue(batchCommitted)
                    assertTrue(notifications.isEmpty())
                    assertFailsWith<IllegalStateException> { fixture.actions.drainPosted() }
                    parentFinished = true
                }
                child -> assertEquals(ActionResult.SUCCESS, facade.executeBatch(listOf(first, second), { true }, {
                    batchCommitted = true
                }))
            }
            ActionResult.SUCCESS
        }
        fixture.actions.post(parent)

        assertEquals(1, fixture.actions.drainPosted(1))
        assertEquals(listOf(parent, child), fixture.executed)
        assertEquals(listOf(first, second, child, parent), notifications)
        assertEquals(1, fixture.actions.drainPosted(1))
        assertEquals(listOf(first, second, child, parent, published), notifications)
    }

    @Test
    fun `a parent can succeed after a child denies or throws and only successful branches notify`() {
        for (throws in listOf(false, true)) {
            val fixture = Fixture()
            val parent = fixture.action()
            val before = fixture.action()
            val rejectedBranch = fixture.action()
            val grandchild = fixture.action()
            val after = fixture.action()
            val committed = mutableListOf<Action>()
            val notifications = mutableListOf<Action>()
            fixture.actions.registerHandler(handler(Action::class.java, notifications::add))
            fixture.process = { action ->
                when (action) {
                    parent -> {
                        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(before))
                        if (throws) {
                            assertFailsWith<IllegalArgumentException> { fixture.actions.execute(rejectedBranch) }
                        } else {
                            assertEquals(ActionResult.DENIED, fixture.actions.execute(rejectedBranch))
                        }
                        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(after))
                        assertTrue(notifications.isEmpty())
                        ActionResult.SUCCESS
                    }
                    rejectedBranch -> {
                        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(grandchild))
                        if (throws) throw IllegalArgumentException("child failed after the grandchild committed")
                        ActionResult.DENIED
                    }
                    else -> {
                        committed.add(action)
                        ActionResult.SUCCESS
                    }
                }
            }

            assertEquals(ActionResult.SUCCESS, fixture.actions.execute(parent))
            assertEquals(listOf(before, grandchild, after), committed)
            assertEquals(listOf(before, after, parent), notifications)
        }
    }

    @Test
    fun `root denial or failure suppresses descendant notifications and keeps committed changes`() {
        for (throws in listOf(false, true)) {
            val fixture = Fixture()
            val parent = fixture.action()
            val child = fixture.action()
            var committed = 0
            val notifications = mutableListOf<Action>()
            fixture.actions.registerHandler(handler(Action::class.java, notifications::add))
            fixture.process = { action ->
                if (action === parent) {
                    assertEquals(ActionResult.SUCCESS, fixture.actions.execute(child))
                    if (throws) throw IllegalArgumentException("root failed after the child committed")
                    ActionResult.DENIED
                } else {
                    committed++
                    ActionResult.SUCCESS
                }
            }

            if (throws) {
                assertFailsWith<IllegalArgumentException> { fixture.actions.execute(parent) }
            } else {
                assertEquals(ActionResult.DENIED, fixture.actions.execute(parent))
            }
            assertEquals(1, committed)
            assertTrue(notifications.isEmpty())
            assertEquals(ActionResult.SUCCESS, fixture.actions.execute(child))
            assertEquals(listOf(child), notifications)
        }
    }

    @Test
    fun `public action API hides its collaborators and exposes no citadel operations`() {
        val type = ActionManager::class.java
        assertEquals(0, type.constructors.single().parameterCount)
        val fields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertTrue(fields.all { Modifier.isPrivate(it.modifiers) })
        val citadelTypes = setOf(CitadelFacade::class.java, ReadableCitadelFacade::class.java, ComponentManager::class.java)
        assertTrue(fields.none { it.type in citadelTypes })

        val collaborators = fields.map { it.type }.filterNot { it.isPrimitive }.toSet() + citadelTypes
        val methods = type.methods.filter { it.declaringClass == type }
        assertTrue(methods.none { it.returnType in collaborators })
        assertTrue(methods.none { method -> method.parameterTypes.any { it in collaborators } })
        val citadelMethods = CitadelFacade::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) }
        assertTrue(methods.none { method -> citadelMethods.any { it.name == method.name } })
    }

    @Test
    fun `validators handlers and batch callbacks cannot dispatch child actions`() {
        for (stage in listOf("validator", "handler", "prepare", "commit")) {
            val fixture = Fixture()
            val parent = fixture.action()
            val child = fixture.action()
            val nested = fixture.action(OTHER_KEY)
            var checks = 0
            fun checkNestedCalls() {
                assertFailsWith<IllegalStateException>(stage) { fixture.actions.execute(nested) }
                assertFailsWith<IllegalStateException>(stage) { fixture.actions.isAllowed(nested) }
                assertFailsWith<IllegalStateException>(stage) { fixture.actions.drainPosted() }
                assertFailsWith<IllegalStateException>(stage) {
                    fixture.actions.executeBatch(listOf(nested), { true }, { error("Nested commit") })
                }
                checks++
            }
            fixture.actions.registerValidator(object : ActionValidator<Action> {
                override val actionClass = Action::class.java
                override fun validate(action: Action): Result {
                    if (action === child && stage == "validator") checkNestedCalls()
                    return Result.Success
                }
            })
            fixture.actions.registerHandler(handler(Action::class.java) { action ->
                if (action === child && stage == "handler") checkNestedCalls()
            })
            fixture.process = { action ->
                if (action === parent) {
                    when (stage) {
                        "prepare", "commit" -> assertEquals(ActionResult.SUCCESS, fixture.actions.executeBatch(listOf(child), {
                            if (stage == "prepare") checkNestedCalls()
                            true
                        }, { if (stage == "commit") checkNestedCalls() }))
                        else -> assertEquals(ActionResult.SUCCESS, fixture.actions.execute(child))
                    }
                }
                ActionResult.SUCCESS
            }

            assertEquals(ActionResult.SUCCESS, fixture.actions.execute(parent))
            assertEquals(1, checks, stage)
            assertFalse(nested in fixture.executed, stage)
            assertTrue(fixture.actions.isAllowed(parent), stage)
        }
    }

    @Test
    fun `batch commit failure preserves changes but suppresses notifications and releases the guard`() {
        val fixture = Fixture()
        var committed = false
        val notifications = mutableListOf<Action>()
        fixture.actions.registerHandler(handler(Action::class.java, notifications::add))
        assertFailsWith<IllegalStateException> {
            fixture.actions.executeBatch(listOf(fixture.action()), { true }, {
                committed = true
                error("Unexpected commit failure")
            })
        }
        assertTrue(committed)
        assertTrue(notifications.isEmpty())
        val retry = fixture.action()
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(retry))
        assertEquals(listOf(retry), notifications)
    }

    @Test
    fun `batch validates all actions before prepare and notifies only after one commit`() {
        val fixture = Fixture()
        val first = fixture.action()
        val second = fixture.action(OTHER_KEY)
        var committed = false
        val notifications = mutableListOf<Action>()
        fixture.actions.registerHandler(handler(Action::class.java) {
            assertTrue(committed)
            notifications.add(it)
            assertFailsWith<IllegalStateException> { fixture.actions.execute(first) }
        })
        assertEquals(ActionResult.SUCCESS, fixture.actions.executeBatch(listOf(first, second), {
            assertFalse(committed)
            assertFailsWith<IllegalStateException> { fixture.actions.execute(first) }
            true
        }, { committed = true }))
        assertEquals(listOf(first, second), notifications)
        assertTrue(fixture.executed.isEmpty())
    }

    @Test
    fun `batch rejects before commit including authorization changed by preparation`() {
        for (rejectBefore in listOf(false, true)) {
            val fixture = Fixture()
            var allowed = !rejectBefore
            var preparations = 0
            fixture.validate { if (allowed) Result.Success else Result.Rejected }
            assertEquals(ActionResult.DENIED, fixture.actions.executeBatch(listOf(fixture.action()), {
                preparations++
                allowed = false
                true
            }, { error("A rejected batch must not commit") }))
            assertEquals(if (rejectBefore) 0 else 1, preparations)
            allowed = true
            assertTrue(fixture.actions.isAllowed(fixture.action()))
        }
    }

    @Test
    fun `preparation failure releases the execution guard without notifications`() {
        val fixture = Fixture()
        var notifications = 0
        fixture.actions.registerHandler(handler(Action::class.java) { notifications++ })
        assertFailsWith<IllegalArgumentException> {
            fixture.actions.executeBatch(listOf(fixture.action()), { throw IllegalArgumentException("preparation") }, { error("commit") })
        }
        assertEquals(0, notifications)
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(fixture.action()))
    }

    @Test
    fun `the default executes actions without validators or with only neutral validators`() {
        for (validatorCount in listOf(0, 2)) {
            val fixture = Fixture()
            var validations = 0
            repeat(validatorCount) {
                fixture.validate { validations++; Result.Neutral }
            }
            val action = fixture.action()

            assertEquals(ActionResult.SUCCESS, fixture.actions.execute(action))
            assertEquals(validatorCount, validations)
            assertEquals(listOf(action), fixture.executed)
        }
    }

    @Test
    fun `denying neutral execution skips the processor when no validator grants approval`() {
        for (validatorCount in listOf(0, 2)) {
            val fixture = Fixture()
            fixture.actions.registerActionMeta(PROBE_KEY, ActionMeta(false))
            var validations = 0
            repeat(validatorCount) {
                fixture.validate { validations++; Result.Neutral }
            }

            assertEquals(ActionResult.DENIED, fixture.actions.execute(fixture.action()))
            assertEquals(validatorCount, validations)
            assertTrue(fixture.executed.isEmpty())
        }
    }

    @Test
    fun `metadata applies only to the registered key`() {
        val fixture = Fixture()
        fixture.actions.registerActionMeta(PROBE_KEY, ActionMeta(false))
        val other = fixture.action(OTHER_KEY)
        assertEquals(ActionResult.DENIED, fixture.actions.execute(fixture.action()))
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(other))
        assertEquals(listOf(other), fixture.executed)
    }

    @Test
    fun `success grants approval even when neutral execution is disabled`() {
        val fixture = Fixture()
        fixture.actions.registerActionMeta(PROBE_KEY, ActionMeta(false))
        fixture.validate { Result.Neutral }
        fixture.validate { Result.Success }
        fixture.validate { Result.Neutral }
        val action = fixture.action()

        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(action))
        assertEquals(listOf(action), fixture.executed)
    }

    @Test
    fun `rejection prevents execution regardless of success and neutral metadata`() {
        for (allowsNeutral in listOf(false, true)) {
            val fixture = Fixture()
            fixture.actions.registerActionMeta(PROBE_KEY, ActionMeta(allowsNeutral))
            fixture.validate { Result.Success }
            fixture.validate { Result.Rejected }

            assertEquals(ActionResult.DENIED, fixture.actions.execute(fixture.action()))
            assertTrue(fixture.executed.isEmpty())
        }
    }

    @Test
    fun `posted actions use metadata at execution time`() {
        val fixture = Fixture()
        fixture.actions.post(fixture.action())
        fixture.actions.registerActionMeta(PROBE_KEY, ActionMeta(false))

        assertEquals(1, fixture.actions.drainPosted())
        assertTrue(fixture.executed.isEmpty())

        val allowed = fixture.action()
        fixture.actions.post(allowed)
        fixture.actions.registerActionMeta(PROBE_KEY, ActionMeta(true))

        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(listOf(allowed), fixture.executed)
    }

    @Test
    fun `posted actions validate current authorization when drained`() {
        val fixture = Fixture()
        fixture.actions.registerActionMeta(PROBE_KEY, ActionMeta(false))
        var authorized = true
        var validations = 0
        fixture.validate {
            validations++
            if (authorized) Result.Success else Result.Rejected
        }
        fixture.actions.post(fixture.action())
        assertEquals(0, validations)
        authorized = false

        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(1, validations)
        assertTrue(fixture.executed.isEmpty())

        val allowed = fixture.action()
        fixture.actions.post(allowed)
        authorized = true

        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(2, validations)
        assertEquals(listOf(allowed), fixture.executed)
    }

    @Test
    fun `drain limit leaves handler posted work for a subsequent drain`() {
        val fixture = Fixture()
        val first = fixture.action()
        val later = fixture.action(OTHER_KEY)
        val notifications = mutableListOf<Action>()
        fixture.actions.registerHandler(handler(Action::class.java) {
            notifications.add(it)
            if (it === first) fixture.actions.post(later)
        })
        fixture.actions.post(first)

        assertEquals(1, fixture.actions.drainPosted(1))
        assertEquals(listOf(first), fixture.executed)
        assertEquals(listOf(first), notifications)
        assertEquals(1, fixture.actions.drainPosted(1))
        assertEquals(listOf(first, later), fixture.executed)
        assertEquals(listOf(first, later), notifications)
        assertEquals(0, fixture.actions.drainPosted())
    }

    @Test
    fun `handlers receive the completed action in registration order including base class handlers`() {
        val fixture = Fixture()
        val calls = mutableListOf<String>()
        val received = mutableListOf<Action>()
        val action = SpecialAction()
        fixture.validate { calls.add("validate"); Result.Success }
        fixture.process = { calls.add("process"); ActionResult.SUCCESS }
        fixture.actions.registerHandler(handler(Action::class.java) {
            calls.add("base")
            received.add(it)
        })
        fixture.actions.registerHandler(handler(OtherAction::class.java) {
            calls.add("unrelated")
        })
        fixture.actions.registerHandler(handler(SpecialAction::class.java) {
            calls.add("specific")
            received.add(it)
        })

        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(action))
        assertEquals(listOf("validate", "process", "base", "specific"), calls)
        assertEquals(2, received.size)
        received.forEach { assertSame(action, it) }
    }

    @Test
    fun `denied actions do not invoke handlers regardless of where execution stops`() {
        val cases: List<(Fixture) -> Action> = listOf(
            { it.validate { Result.Rejected }; it.action() },
            { it.actions.registerActionMeta(PROBE_KEY, ActionMeta(false)); it.action() },
            { it.action(Key("test", "missing_processor")) },
            { it.process = { ActionResult.DENIED }; it.action() }
        )
        for (configure in cases) {
            val fixture = Fixture()
            val received = mutableListOf<Action>()
            fixture.actions.registerHandler(handler(Action::class.java, received::add))
            val action = configure(fixture)

            assertEquals(ActionResult.DENIED, fixture.actions.execute(action))
            assertTrue(received.isEmpty())
        }
    }

    @Test
    fun `validator and processor exceptions skip handlers and release the execution guard`() {
        for (failInValidator in listOf(true, false)) {
            val fixture = Fixture()
            val failure = IllegalStateException("Cannot finish action")
            var fail = true
            fixture.validate {
                if (fail && failInValidator) throw failure
                Result.Success
            }
            fixture.process = {
                if (fail && !failInValidator) throw failure
                ActionResult.SUCCESS
            }
            val received = mutableListOf<Action>()
            fixture.actions.registerHandler(handler(Action::class.java, received::add))

            assertSame(failure, assertFailsWith<IllegalStateException> { fixture.actions.execute(fixture.action()) })
            assertTrue(received.isEmpty())

            fail = false
            val retry = fixture.action()
            assertEquals(ActionResult.SUCCESS, fixture.actions.execute(retry))
            assertEquals(listOf(retry), received)
        }
    }

    @Test
    fun `handler exceptions preserve success and do not skip later handlers or subsequent actions`() {
        val fixture = Fixture()
        var failures = 0
        val received = mutableListOf<Action>()
        fixture.actions.registerHandler(handler(Action::class.java) {
            failures++
            error("Broken handler")
        })
        fixture.actions.registerHandler(handler(Action::class.java, received::add))
        val first = fixture.action()
        val second = fixture.action()

        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(first))
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(second))

        assertEquals(2, failures)
        assertEquals(listOf(first, second), received)
        assertEquals(received, fixture.executed)
    }

    @Test
    fun `registration and unregistration use identity even when handlers compare equal`() {
        val fixture = Fixture()
        val actions = fixture.actions
        val first = EqualHandler()
        val second = EqualHandler()
        actions.registerHandler(first)
        actions.registerHandler(first)
        actions.registerHandler(second)

        assertFalse(actions.unregisterHandler(EqualHandler()))
        fixture.actions.execute(fixture.action())
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)

        assertTrue(actions.unregisterHandler(first))
        assertFalse(actions.unregisterHandler(first))
        fixture.actions.execute(fixture.action())
        assertEquals(1, first.calls)
        assertEquals(2, second.calls)
    }

    @Test
    fun `handler registration changes take effect on the next dispatch`() {
        val fixture = Fixture()
        val actions = fixture.actions
        val calls = mutableListOf<String>()
        val later = handler(Action::class.java) { calls.add("later") }
        val added = handler(Action::class.java) { calls.add("added") }
        lateinit var first: ActionHandler<Action>
        first = handler(Action::class.java) {
            calls.add("first")
            actions.unregisterHandler(first)
            actions.unregisterHandler(later)
            actions.registerHandler(added)
        }
        actions.registerHandler(first)
        actions.registerHandler(later)

        fixture.actions.execute(fixture.action())
        assertEquals(listOf("first", "later"), calls)
        calls.clear()
        fixture.actions.execute(fixture.action())
        assertEquals(listOf("added"), calls)
    }

    @Test
    fun `handlers can post work but cannot execute or drain until dispatch finishes`() {
        val fixture = Fixture()
        val first = fixture.action()
        val posted = fixture.action(OTHER_KEY)
        val blocked = mutableListOf<Boolean>()
        val received = mutableListOf<Action>()
        fixture.actions.registerHandler(handler(Action::class.java) {
            if (it === first) {
                blocked.add(runCatching { fixture.actions.execute(posted) }.exceptionOrNull() is IllegalStateException)
                blocked.add(runCatching { fixture.actions.drainPosted() }.exceptionOrNull() is IllegalStateException)
                fixture.actions.post(posted)
            }
        })
        fixture.actions.registerHandler(handler(Action::class.java, received::add))

        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(first))
        assertEquals(listOf(true, true), blocked)
        assertEquals(listOf(first), received)
        assertEquals(listOf(first), fixture.executed)

        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(listOf(first, posted), received)
        assertEquals(received, fixture.executed)
        assertEquals(0, fixture.actions.drainPosted())
    }

    @Test
    fun `calling processors directly does not dispatch action handlers`() {
        val fixture = Fixture()
        val received = mutableListOf<Action>()
        fixture.actions.registerHandler(handler(Action::class.java, received::add))
        val action = fixture.action(Key("test", "direct"))

        val processor = object : ActionProcessor<Action>() {
            override val actionClass = Action::class.java
            override val actionKey = action.type
            override fun execute(action: Action): ActionResult {
                fixture.executed.add(action)
                return ActionResult.SUCCESS
            }
        }
        fixture.actions.registerProcessor(processor)
        assertEquals(ActionResult.SUCCESS, processor.execute(action))
        assertEquals(listOf(action), fixture.executed)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `batch notifications block execution and draining while allowing posted actions`() {
        val fixture = Fixture()
        val notification = fixture.action()
        val posted = fixture.action(OTHER_KEY)
        val blocked = mutableListOf<Boolean>()
        val received = mutableListOf<Action>()
        fixture.actions.registerHandler(handler(Action::class.java) {
            if (it === notification) {
                blocked.add(runCatching { fixture.actions.execute(posted) }.exceptionOrNull() is IllegalStateException)
                blocked.add(runCatching { fixture.actions.drainPosted() }.exceptionOrNull() is IllegalStateException)
                fixture.actions.post(posted)
            }
        })
        fixture.actions.registerHandler(handler(Action::class.java, received::add))

        assertEquals(ActionResult.SUCCESS, fixture.actions.executeBatch(listOf(notification), { true }, {}))

        assertEquals(listOf(true, true), blocked)
        assertEquals(listOf(notification), received)
        assertTrue(fixture.executed.isEmpty())

        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(listOf(posted), fixture.executed)
        assertEquals(listOf(notification, posted), received)
        assertEquals(0, fixture.actions.drainPosted())
    }

    @Test
    fun `single publication completes immediately and defers a publication from its handler`() {
        val fixture = Fixture()
        val facade = ActionFacade(fixture.actions)
        val first = fixture.action()
        val followUp = fixture.action()
        val notifications = mutableListOf<Action>()
        fixture.actions.registerHandler(handler(Action::class.java) {
            notifications.add(it)
            if (it === first) facade.publish(followUp)
        })

        facade.publish(first)
        assertEquals(listOf(first), fixture.executed)
        assertEquals(listOf(first), notifications)
        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(listOf(first, followUp), fixture.executed)
        assertEquals(fixture.executed, notifications)
    }

    @Test
    fun `single publication waits behind an older batch`() {
        val fixture = Fixture()
        val facade = ActionFacade(fixture.actions)
        val batchAction = fixture.action()
        val single = fixture.action()
        val notifications = mutableListOf<Action>()
        fixture.actions.registerHandler(handler(Action::class.java, notifications::add))

        facade.publish(listOf(batchAction), { true }, {})
        facade.publish(single)
        assertTrue(notifications.isEmpty())
        assertEquals(1, fixture.actions.drainPosted(1))
        assertEquals(listOf(batchAction), notifications)
        assertTrue(fixture.executed.isEmpty())
        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(listOf(batchAction, single), notifications)
        assertEquals(listOf(single), fixture.executed)
    }

    @Test
    fun `immediate denial and processor failure do not queue a retry`() {
        val fixture = Fixture()
        val facade = ActionFacade(fixture.actions)
        val denied = fixture.action()
        val failing = fixture.action()
        val failure = IllegalStateException("Could not finish action")

        fixture.process = { ActionResult.DENIED }
        facade.publish(denied)
        assertEquals(listOf(denied), fixture.executed)
        assertEquals(0, fixture.actions.drainPosted())

        fixture.process = { throw failure }
        assertSame(failure, assertFailsWith<IllegalStateException> { facade.publish(failing) })
        assertEquals(listOf(denied, failing), fixture.executed)
        assertEquals(0, fixture.actions.drainPosted())
    }

    @Test
    fun `handler publishes a batch snapshot as one FIFO queue entry`() {
        val fixture = Fixture()
        val facade = ActionFacade(fixture.actions)
        val trigger = fixture.action()
        val before = fixture.action()
        val first = fixture.action()
        val second = fixture.action()
        val after = fixture.action()
        val batch = mutableListOf(first, second)
        val notifications = mutableListOf<Action>()
        val stages = mutableListOf<String>()
        fixture.actions.registerHandler(handler(Action::class.java) { action ->
            notifications.add(action)
            if (action === trigger) {
                facade.publish(batch, { stages.add("prepare"); true }, { stages.add("commit") })
            }
            if (action === first) stages.add("first")
            if (action === second) stages.add("second")
        })

        fixture.actions.post(before)
        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(trigger))
        batch.clear()
        fixture.actions.post(after)
        assertEquals(listOf(trigger), notifications)
        assertTrue(stages.isEmpty())

        assertEquals(1, fixture.actions.drainPosted(1))
        assertEquals(listOf(trigger, before), notifications)
        assertTrue(stages.isEmpty())
        assertEquals(1, fixture.actions.drainPosted(1))
        assertEquals(listOf("prepare", "commit", "first", "second"), stages)
        assertEquals(listOf(trigger, before, first, second), notifications)
        assertEquals(1, fixture.actions.drainPosted())
        assertEquals(listOf(trigger, before, first, second, after), notifications)
        assertEquals(listOf(trigger, before, after), fixture.executed)
    }

    @Test
    fun `published batch uses current authorization before and after preparation`() {
        for (rejectBeforePrepare in listOf(true, false)) {
            val fixture = Fixture()
            val facade = ActionFacade(fixture.actions)
            val stages = mutableListOf<String>()
            var allowed = true
            fixture.validate {
                stages.add("validate")
                if (allowed) Result.Success else Result.Rejected
            }
            fixture.actions.registerHandler(handler(Action::class.java) { stages.add("notify") })
            facade.publish(listOf(fixture.action()), {
                stages.add("prepare")
                allowed = false
                true
            }, { stages.add("commit") })
            assertTrue(stages.isEmpty())

            allowed = !rejectBeforePrepare
            assertEquals(1, fixture.actions.drainPosted())
            val expected = if (rejectBeforePrepare) listOf("validate") else listOf("validate", "prepare", "validate")
            assertEquals(expected, stages)
        }
    }

    @Test
    fun `clearing pending work drops published batches and empty batches fail at submission`() {
        val manager = ActionManager()
        val facade = ActionFacade(manager)
        assertFailsWith<IllegalArgumentException> {
            facade.publish(emptyList(), { error("Unexpected preparation") }, { error("Unexpected commit") })
        }
        assertEquals(0, manager.drainPosted())
        facade.publish(listOf(SpecialAction()), { error("Unexpected preparation") }, { error("Unexpected commit") })
        manager.clearPosted()
        assertEquals(0, manager.drainPosted())
    }

    private fun <T : Action> handler(actionClass: Class<T>, callback: (T) -> Unit): ActionHandler<T> =
        object : ActionHandler<T> {
            override val actionClass = actionClass
            override fun handle(action: T) = callback(action)
        }

    private class SpecialAction : Action(PROBE_KEY, UUID(0, 1), 0, 0, 0)
    private class OtherAction : Action(PROBE_KEY, UUID(0, 1), 0, 0, 0)

    private class EqualHandler : ActionHandler<Action> {
        override val actionClass = Action::class.java
        var calls = 0
        override fun handle(action: Action) { calls++ }
        override fun equals(other: Any?) = other is EqualHandler
        override fun hashCode() = 0
    }

    private class Fixture {
        val actions = ActionManager()
        val executed = mutableListOf<Action>()
        var process: (Action) -> ActionResult = { ActionResult.SUCCESS }

        init {
            for (key in listOf(PROBE_KEY, OTHER_KEY)) {
                actions.registerProcessor(object : ActionProcessor<Action>() {
                    override val actionClass = Action::class.java
                    override val actionKey = key
                    override fun execute(action: Action): ActionResult {
                        executed.add(action)
                        return process(action)
                    }
                })
            }
        }

        fun action(key: Key = PROBE_KEY) = Action(key, UUID(0, 1), 0, 0, 0)

        fun validate(validation: () -> Result) {
            actions.registerValidator(object : ActionValidator<Action> {
                override val actionClass = Action::class.java
                override fun validate(action: Action) = validation()
            })
        }
    }

    private companion object {
        val PROBE_KEY = Key("test", "probe")
        val OTHER_KEY = Key("test", "other")
    }
}
