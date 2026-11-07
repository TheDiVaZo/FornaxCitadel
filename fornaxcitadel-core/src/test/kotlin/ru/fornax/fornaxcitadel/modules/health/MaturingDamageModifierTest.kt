package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaturingDamageModifierTest {
    @Test
    fun `initialization records independent maturation parameters without publishing a partial citadel`() {
        val fixture = HealthFixture(createInitial = false)
        val clock = MutableHealthClock(1_793_204_123_456L)
        val initializer = MaturingInitializer(600_000L, 4.0, clock)
        val draft = fixture.draft()
        HealthInitializer(40.0).initialize(ActionContext.SYSTEM, draft)

        initializer.initialize(ActionContext.SYSTEM, draft)

        val maturation = assertNotNull(draft.getComponent(Maturing::class.java))
        assertEquals(clock.timeMillis, maturation.startedAtMillis)
        assertEquals(600_000L, maturation.maturationDurationMillis)
        assertEquals(4.0, maturation.initialDamageMultiplier)
        assertEquals(40.0, assertNotNull(draft.getComponent(Health::class.java)).health)
        assertNull(fixture.manager.findCitadel(draft.world, draft.x, draft.y, draft.z))
        assertFalse(fixture.chunk.isDirty)

        val older = fixture.publish(draft)
        clock.timeMillis += 5_000L
        val laterDraft = fixture.draft(1)
        initializer.initialize(ActionContext.SYSTEM, laterDraft)
        val newer = fixture.publish(laterDraft)

        assertSame(maturation, older.getComponent(Maturing::class.java))
        assertEquals(clock.timeMillis - 5_000L, maturation.startedAtMillis)
        assertEquals(clock.timeMillis, assertNotNull(newer.getComponent(Maturing::class.java)).startedAtMillis)
    }

    @Test
    fun `damage follows the old effective durability curve while stored components stay unchanged`() {
        val fixture = HealthFixture(createInitial = false)
        val health = Health(40.0, 15.0)
        val citadel = fixture.create(health = health, startedAtMillis = 1_000L, initialDamageMultiplier = 4.0)
        val maturation = assertNotNull(citadel.getComponent(Maturing::class.java))
        val modifier = MaturingDamageModifier(fixture.clock)
        fixture.chunk.resetDirty()

        for ((now, expectedDamage) in listOf(500L to 20.0, 1_000L to 20.0, 1_500L to 8.0, 2_000L to 5.0, 20_000L to 5.0)) {
            fixture.clock.timeMillis = now
            assertEquals(expectedDamage, modifier.getDamage(ActionContext.SYSTEM, citadel, 5.0), 1e-12)
            assertEquals(40.0, health.maxHealth)
            assertEquals(15.0, health.health)
            assertEquals(1_000L, maturation.startedAtMillis)
            assertEquals(1_000L, maturation.maturationDurationMillis)
            assertEquals(4.0, maturation.initialDamageMultiplier)
            assertFalse(fixture.chunk.isDirty)
        }
    }

    @Test
    fun `missing maturation and unit multiplier preserve incoming damage`() {
        val fixture = HealthFixture(createInitial = false)
        val plain = fixture.create(health = Health(40.0, 10.0))
        val unit = fixture.create(1, Health(40.0, 10.0), startedAtMillis = 1_000L, initialDamageMultiplier = 1.0)
        val modifier = MaturingDamageModifier(fixture.clock)
        fixture.chunk.resetDirty()

        for (amount in listOf(0.0, 2.5, Double.MAX_VALUE)) {
            assertEquals(amount, modifier.getDamage(ActionContext.SYSTEM, plain, amount))
            assertEquals(amount, modifier.getDamage(ActionContext.SYSTEM, unit, amount))
        }
        assertFalse(fixture.chunk.isDirty)
    }

    @Test
    fun `zero is preserved and amplified damage saturates at the finite maximum`() {
        val fixture = HealthFixture(createInitial = false)
        val citadel = fixture.create(startedAtMillis = 1_000L, initialDamageMultiplier = Double.MAX_VALUE)
        val modifier = MaturingDamageModifier(fixture.clock)

        assertEquals(0.0, modifier.getDamage(ActionContext.SYSTEM, citadel, 0.0))
        assertEquals(Double.MAX_VALUE, modifier.getDamage(ActionContext.SYSTEM, citadel, 2.0))
        assertEquals(Double.MAX_VALUE, modifier.getDamage(ActionContext.SYSTEM, citadel, Double.MAX_VALUE))

        fixture.clock.timeMillis = 1_500L
        assertEquals(2.0, modifier.getDamage(ActionContext.SYSTEM, citadel, 1.0), 1e-12)
        fixture.clock.timeMillis = 2_000L
        assertEquals(1.0, modifier.getDamage(ActionContext.SYSTEM, citadel, 1.0))
    }

    @Test
    fun `extreme timestamps clamp without overflowing elapsed time`() {
        val fixture = HealthFixture(createInitial = false, clock = MutableHealthClock(Long.MAX_VALUE))
        val past = fixture.create(startedAtMillis = Long.MIN_VALUE, maturationDurationMillis = Long.MAX_VALUE,
            initialDamageMultiplier = 4.0)
        val future = fixture.create(1, startedAtMillis = Long.MAX_VALUE, initialDamageMultiplier = 4.0)
        val modifier = MaturingDamageModifier(fixture.clock)

        assertEquals(5.0, modifier.getDamage(ActionContext.SYSTEM, past, 5.0))
        fixture.clock.timeMillis = Long.MIN_VALUE
        assertEquals(20.0, modifier.getDamage(ActionContext.SYSTEM, future, 5.0))
    }

    @Test
    fun `saved damaged health and maturation parameters survive reload and elapsed offline time`() {
        val fixture = HealthFixture(createInitial = false)
        val startedAt = 1_793_204_123_456L
        val originalHealth = Health(40.0, 15.0)
        val original = fixture.create(health = originalHealth, startedAtMillis = startedAt,
            maturationDurationMillis = 600_000L, initialDamageMultiplier = 4.0)
        val originalMaturing = assertNotNull(original.getComponent(Maturing::class.java))
        val mapper = ChunkCordSnapshotMapper(fixture.types)
        val bytes = mapper.snapshot(fixture.chunk).toByteArray()

        fixture.storage.chunk = mapper.restore(ChunkCordSnapshot.fromByteArray(bytes))

        val restored = fixture.citadel
        val restoredHealth = assertNotNull(restored.getComponent(Health::class.java))
        val restoredMaturing = assertNotNull(restored.getComponent(Maturing::class.java))
        assertNotSame(originalHealth, restoredHealth)
        assertNotSame(originalMaturing, restoredMaturing)
        assertEquals(40.0, restoredHealth.maxHealth)
        assertEquals(15.0, restoredHealth.health)
        assertEquals(startedAt, restoredMaturing.startedAtMillis)
        assertEquals(600_000L, restoredMaturing.maturationDurationMillis)
        assertEquals(4.0, restoredMaturing.initialDamageMultiplier)

        val modifier = MaturingDamageModifier(fixture.clock)
        fixture.clock.timeMillis = startedAt + 300_000L
        assertEquals(8.0, modifier.getDamage(ActionContext.SYSTEM, restored, 5.0), 1e-12)
        fixture.clock.timeMillis = startedAt + 900_000L
        assertEquals(5.0, modifier.getDamage(ActionContext.SYSTEM, restored, 5.0))
        assertEquals(15.0, restoredHealth.health)
        assertFalse(fixture.chunk.isDirty)
    }

    @Test
    fun `damage sequence across maturation preserves the former health fractions`() {
        val fixture = HealthFixture(createInitial = false)
        val health = Health(100.0, 100.0)
        val citadel = fixture.create(health = health, startedAtMillis = 1_000L, initialDamageMultiplier = 5.0)

        fixture.service.damage(citadel, 2.0)
        assertEquals(90.0, health.health)
        fixture.clock.timeMillis = 1_500L
        fixture.service.damage(citadel, 6.0)
        assertEquals(80.0, health.health, 1e-12)
        fixture.clock.timeMillis = 2_000L
        fixture.service.damage(citadel, 10.0)
        assertEquals(70.0, health.health, 1e-12)
        assertEquals(100.0, health.maxHealth)
    }

    @Test
    fun `duplicate maturation initialization preserves the original component`() {
        val fixture = HealthFixture(createInitial = false)
        val draft = fixture.draft()
        val original = Maturing(42L, 100L, 2.0)
        draft.addComponent(Maturing::class.java, original)

        assertFails { MaturingInitializer(1_000L, 4.0).initialize(ActionContext.SYSTEM, draft) }
        assertSame(original, draft.getComponent(Maturing::class.java))
        assertEquals(42L, original.startedAtMillis)
        assertTrue(fixture.chunk.isEmpty)
        assertFalse(fixture.chunk.isDirty)
    }

    @Test
    fun `invalid maturation component parameters and initializer settings are rejected`() {
        for (duration in listOf(0L, -1L)) {
            assertFailsWith<IllegalArgumentException> { Maturing(0L, duration, 4.0) }
            assertFailsWith<IllegalArgumentException> { MaturingInitializer(duration, 4.0) }
        }
        for (multiplier in listOf(0.0, 0.5, -1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { Maturing(0L, 1_000L, multiplier) }
            assertFailsWith<IllegalArgumentException> { MaturingInitializer(1_000L, multiplier) }
        }
    }
}
