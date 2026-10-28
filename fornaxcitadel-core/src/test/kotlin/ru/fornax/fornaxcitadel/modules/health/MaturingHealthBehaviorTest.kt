package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaturingHealthBehaviorTest {
    @Test
    fun `initialization prepares the current epoch milliseconds without publishing a partial citadel`() {
        val fixture = HealthFixture(createInitial = false)
        val clock = MutableHealthClock(1_793_204_123_456L)
        val component = Health(0.0, 0.0)
        val other = fixture.create(1, startedAtMillis = 42L)
        val otherMaturing = assertNotNull(other.getComponent(Maturing::class.java))
        fixture.chunk.resetDirty()
        val draft = fixture.draft()

        behavior(clock).initializeHealth(component, draft)

        assertEquals(clock.timeMillis, assertNotNull(draft.getComponent(Maturing::class.java)).startedAtMillis)
        assertEquals(20.0, component.maxHealth)
        assertEquals(20.0, component.health)
        assertSame(otherMaturing, other.getComponent(Maturing::class.java))
        assertEquals(42L, otherMaturing.startedAtMillis)
        assertNull(fixture.manager.findCitadel(draft.world, draft.x, draft.y, draft.z))
        assertFalse(fixture.chunk.isDirty)

        draft.addComponent(Health::class.java, component)
        val citadel = fixture.publish(draft)
        assertSame(component, citadel.getComponent(Health::class.java))
        assertTrue(fixture.chunk.isDirty)
    }

    @Test
    fun `newly initialized health is full and matures from its committed start`() {
        val fixture = HealthFixture(createInitial = false)
        val clock = MutableHealthClock(2_000L)
        val behavior = behavior(clock)
        val draft = fixture.draft()

        HealthInitializer(behavior).initialize(ActionContext.SYSTEM, draft)
        val component = assertNotNull(draft.getComponent(Health::class.java))
        val citadel = fixture.publish(draft)

        assertEquals(2_000L, assertNotNull(citadel.getComponent(Maturing::class.java)).startedAtMillis)
        assertEquals(20.0, component.maxHealth)
        assertEquals(20.0, component.health)
        assertFalse(behavior.updateHealth(component, citadel))
        clock.timeMillis = 2_500L
        assertTrue(behavior.updateHealth(component, citadel))
        assertEquals(60.0, component.health)
    }

    @Test
    fun `maturation preserves the damaged fraction without changing its start or repeating mutations`() {
        val fixture = HealthFixture()
        val clock = MutableHealthClock(1_500L)
        val behavior = behavior(clock)
        val component = Health(20.0, 5.0)
        val maturing = assertNotNull(fixture.citadel.getComponent(Maturing::class.java))

        assertTrue(behavior.updateHealth(component, fixture.citadel))
        assertEquals(60.0, component.maxHealth)
        assertEquals(15.0, component.health)
        assertFalse(behavior.updateHealth(component, fixture.citadel))

        clock.timeMillis = 2_000L
        assertTrue(behavior.updateHealth(component, fixture.citadel))
        assertEquals(100.0, component.maxHealth)
        assertEquals(25.0, component.health)

        clock.timeMillis = 5_000L
        assertFalse(behavior.updateHealth(component, fixture.citadel))
        assertEquals(25.0, component.health)
        assertSame(maturing, fixture.citadel.getComponent(Maturing::class.java))
        assertEquals(1_000L, maturing.startedAtMillis)
    }

    @Test
    fun `full and overfull components track the new maximum exactly`() {
        val fixture = HealthFixture()
        val behavior = behavior()

        for (health in listOf(20.0, 25.0, 20.0 - 5e-10)) {
            val component = Health(20.0, health)

            assertTrue(behavior.updateHealth(component, fixture.citadel))
            assertEquals(60.0, component.maxHealth)
            assertEquals(60.0, component.health)
            assertFalse(behavior.updateHealth(component, fixture.citadel))
        }
    }

    @Test
    fun `near-full health is normalized even when the maximum is unchanged`() {
        val fixture = HealthFixture()
        val behavior = behavior(MutableHealthClock(1_000L))
        val component = Health(20.0, 20.0 - 5e-10)

        assertTrue(behavior.updateHealth(component, fixture.citadel))
        assertEquals(20.0, component.health)
        assertFalse(behavior.updateHealth(component, fixture.citadel))

        val damaged = Health(20.0, 20.0 - 2e-9)
        assertFalse(behavior.updateHealth(damaged, fixture.citadel))
        assertEquals(20.0 - 2e-9, damaged.health)
    }

    @Test
    fun `maturation never revives destroyed components even below the full health tolerance`() {
        val fixture = HealthFixture()
        val clock = MutableHealthClock(1_500L)

        for ((initialMax, matureMax) in listOf(20.0 to 100.0, 1e-12 to 1e-10)) {
            val behavior = MaturingHealthBehavior(initialMax, matureMax, 1_000L, clock)
            val component = Health(initialMax, 0.0)

            assertTrue(behavior.updateHealth(component, fixture.citadel))
            assertTrue(component.maxHealth > initialMax)
            assertEquals(0.0, component.health)
            assertFalse(behavior.updateHealth(component, fixture.citadel))
        }
    }

    @Test
    fun `future and completed maturation clamp the maximum while preserving damage`() {
        val fixture = HealthFixture(startedAtMillis = 3_000L)
        val completedCitadel = fixture.create(1, startedAtMillis = 0L)
        val behavior = behavior(MutableHealthClock(2_000L))
        val future = Health(60.0, 30.0)
        val completed = Health(60.0, 30.0)

        assertTrue(behavior.updateHealth(future, fixture.citadel))
        assertEquals(20.0, future.maxHealth)
        assertEquals(10.0, future.health)

        assertTrue(behavior.updateHealth(completed, completedCitadel))
        assertEquals(100.0, completed.maxHealth)
        assertEquals(50.0, completed.health)
    }

    @Test
    fun `each initialized citadel keeps its own maturation start`() {
        val fixture = HealthFixture(createInitial = false)
        val clock = MutableHealthClock(1_000L)
        val behavior = behavior(clock)
        val olderDraft = fixture.draft()
        HealthInitializer(behavior).initialize(ActionContext.SYSTEM, olderDraft)
        val olderCitadel = fixture.publish(olderDraft)
        clock.timeMillis = 1_500L
        val newerDraft = fixture.draft(1)
        HealthInitializer(behavior).initialize(ActionContext.SYSTEM, newerDraft)
        val newerCitadel = fixture.publish(newerDraft)
        val older = assertNotNull(olderCitadel.getComponent(Health::class.java))
        val newer = assertNotNull(newerCitadel.getComponent(Health::class.java))

        clock.timeMillis = 1_750L
        behavior.updateHealth(older, olderCitadel)
        behavior.updateHealth(newer, newerCitadel)

        assertEquals(80.0, older.maxHealth)
        assertEquals(80.0, older.health)
        assertEquals(40.0, newer.maxHealth)
        assertEquals(40.0, newer.health)
    }

    @Test
    fun `equal initial and mature maxima do not repair a damaged component`() {
        val fixture = HealthFixture()
        val clock = MutableHealthClock(1_500L)
        val behavior = MaturingHealthBehavior(20.0, 20.0, 1_000L, clock)
        val component = Health(20.0, 10.0)

        assertFalse(behavior.updateHealth(component, fixture.citadel))
        clock.timeMillis = 5_000L
        assertFalse(behavior.updateHealth(component, fixture.citadel))
        assertEquals(20.0, component.maxHealth)
        assertEquals(10.0, component.health)
    }

    @Test
    fun `missing maturation is rejected without inventing a start time or changing health`() {
        val fixture = HealthFixture(startedAtMillis = null)
        val component = Health(20.0, 10.0)

        assertFailsWith<IllegalStateException> {
            behavior().updateHealth(component, fixture.citadel)
        }

        assertEquals(20.0, component.maxHealth)
        assertEquals(10.0, component.health)
        assertNull(fixture.citadel.getComponent(Maturing::class.java))
        assertFalse(fixture.chunk.isDirty)
    }

    @Test
    fun `duplicate maturation initialization fails without changing health or live storage`() {
        val fixture = HealthFixture(createInitial = false)
        val draft = fixture.draft()
        draft.addComponent(Maturing::class.java, Maturing(42L))
        val component = Health(100.0, 25.0)

        assertFailsWith<IllegalArgumentException> {
            behavior().initializeHealth(component, draft)
        }

        assertEquals(100.0, component.maxHealth)
        assertEquals(25.0, component.health)
        assertEquals(42L, assertNotNull(draft.getComponent(Maturing::class.java)).startedAtMillis)
        assertTrue(fixture.chunk.isEmpty)
        assertFalse(fixture.chunk.isDirty)
    }

    @Test
    fun `serialized citadel restores the original timestamp and continues maturation after reload`() {
        val fixture = HealthFixture(createInitial = false)
        val startedAt = 1_793_204_123_456L
        val clock = MutableHealthClock(startedAt)
        val behavior = behavior(clock)
        val draft = fixture.draft()
        HealthInitializer(behavior).initialize(ActionContext.SYSTEM, draft)
        fixture.publish(draft)
        val original = assertNotNull(fixture.citadel.getComponent(Health::class.java))
        original.health = 5.0
        val originalMaturing = assertNotNull(fixture.citadel.getComponent(Maturing::class.java))
        val mapper = ChunkCordSnapshotMapper(fixture.types)
        val bytes = mapper.snapshot(fixture.chunk).toByteArray()

        fixture.storage.chunk = mapper.restore(ChunkCordSnapshot.fromByteArray(bytes))

        val restored = assertNotNull(fixture.citadel.getComponent(Health::class.java))
        val restoredMaturing = assertNotNull(fixture.citadel.getComponent(Maturing::class.java))
        assertNotSame(original, restored)
        assertNotSame(originalMaturing, restoredMaturing)
        assertEquals(startedAt, restoredMaturing.startedAtMillis)
        assertEquals(20.0, restored.maxHealth)
        assertEquals(5.0, restored.health)
        assertFalse(fixture.chunk.isDirty)

        clock.timeMillis = startedAt + 500L
        val reloadedBehavior = this.behavior(clock)
        assertTrue(reloadedBehavior.updateHealth(restored, fixture.citadel))
        assertEquals(60.0, restored.maxHealth)
        assertEquals(15.0, restored.health)
        assertEquals(startedAt, restoredMaturing.startedAtMillis)
    }

    @Test
    fun `invalid maturation settings are rejected`() {
        for (initialMax in listOf(0.0, -1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { MaturingHealthBehavior(initialMax, 100.0, 1_000L) }
        }
        for (matureMax in listOf(19.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { MaturingHealthBehavior(20.0, matureMax, 1_000L) }
        }
        for (duration in listOf(0L, -1L)) {
            assertFailsWith<IllegalArgumentException> { MaturingHealthBehavior(20.0, 100.0, duration) }
        }
    }

    @Test
    fun `invalid existing health is rejected without partially changing the component`() {
        val fixture = HealthFixture()
        val behavior = behavior()
        val invalidStates = listOf(
            0.0 to 0.0, -20.0 to 5.0,
            Double.NaN to 5.0, Double.NEGATIVE_INFINITY to 5.0, Double.POSITIVE_INFINITY to 5.0,
            20.0 to -1.0, 20.0 to Double.NaN, 20.0 to Double.NEGATIVE_INFINITY, 20.0 to Double.POSITIVE_INFINITY
        )

        for ((maxHealth, health) in invalidStates) {
            val component = Health(maxHealth, health)
            assertFailsWith<IllegalArgumentException> { behavior.updateHealth(component, fixture.citadel) }
            assertEquals(maxHealth, component.maxHealth)
            assertEquals(health, component.health)
        }
    }

    private fun behavior(clock: Clock = MutableHealthClock(1_500L)): MaturingHealthBehavior =
        MaturingHealthBehavior(20.0, 100.0, 1_000L, clock)

}
