package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleListener
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.RemovalCause
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HealthServiceTest {
    @Test
    fun `both health getters mark maturation changes dirty but leave unchanged reads clean`() {
        for (readMaxHealth in listOf(false, true)) {
            val fixture = HealthFixture(createInitial = false)
            val component = Health(20.0, 10.0)
            fixture.create(health = component, startedAtMillis = 0L)
            fixture.chunk.resetDirty()
            fixture.service.registerBehavior(
                HealthFixture.TYPE,
                MaturingHealthBehavior(20.0, 100.0, 10_000L, MutableHealthClock(5_000L))
            )
            val read = {
                if (readMaxHealth) fixture.service.getCurrentMaxHealth(HealthFixture.WORLD, 0, 0, 0)
                else fixture.service.getCurrentHealth(HealthFixture.WORLD, 0, 0, 0)
            }
            val expected = if (readMaxHealth) 60.0 else 30.0

            assertEquals(expected, read())
            assertEquals(60.0, component.maxHealth)
            assertEquals(30.0, component.health)
            assertTrue(fixture.chunk.isDirty)

            fixture.chunk.resetDirty()
            assertEquals(expected, read())
            assertFalse(fixture.chunk.isDirty)
            assertNull(fixture.service.getCurrentHealth(HealthFixture.WORLD, 4, 5, 6))
            assertNull(fixture.service.getCurrentMaxHealth(HealthFixture.WORLD, 4, 5, 6))
            assertFalse(fixture.chunk.isDirty)
        }
    }

    @Test
    fun `one service selects each citadel health behavior by definition key`() {
        val fixture = HealthFixture(createInitial = false)
        val otherType = Key("test", "static")
        fixture.create(health = Health(20.0, 10.0), startedAtMillis = 0L)
        fixture.create(1, Health(100.0, 50.0), definitionKey = otherType)
        fixture.service.registerBehavior(HealthFixture.TYPE, MaturingHealthBehavior(20.0, 100.0, 10_000L, MutableHealthClock(5_000L)))
        val simple = SimpleHealthBehavior(100.0)
        fixture.service.registerBehavior(otherType, simple)
        fixture.service.registerBehavior(otherType, simple)

        assertEquals(30.0, fixture.service.getCurrentHealth(HealthFixture.WORLD, 0, 0, 0))
        assertEquals(50.0, fixture.service.getCurrentHealth(HealthFixture.WORLD, 1, 0, 0))
        assertSame(simple, fixture.service.findBehavior(otherType))
        assertFailsWith<IllegalArgumentException> {
            fixture.service.registerBehavior(otherType, SimpleHealthBehavior(50.0))
        }
        assertSame(simple, fixture.service.findBehavior(otherType))
    }

    @Test
    fun `lethal damage removes the entire citadel once after health editing`() {
        val fixture = HealthFixture(createInitial = false)
        val component = Health(20.0, 20.0)
        val citadel = fixture.create(health = component, startedAtMillis = 0L)
        fixture.service.registerBehavior(HealthFixture.TYPE, SimpleHealthBehavior(20.0))
        var removals = 0
        fixture.notifications.addListener(object : CitadelLifecycleListener {
            override fun onRemoved(position: CitadelPosition, runtimeId: Int, cause: RemovalCause) {
                removals++
                assertEquals(RemovalCause.DESTROYED, cause)
                assertEquals(citadel.runtimeId, runtimeId)
                assertNull(fixture.manager.findCitadel(position.world, position.x, position.y, position.z))
            }
        })

        assertEquals(ActionResult.SUCCESS, fixture.service.damage(citadel, 5.0))
        assertEquals(15.0, component.health)
        assertEquals(ActionResult.SUCCESS, fixture.service.damage(citadel, 100.0))
        assertEquals(0.0, component.health)
        assertEquals(1, removals)
        assertTrue(fixture.chunk.isEmpty)
        assertTrue(fixture.chunk.isDirty)
        assertEquals(ActionResult.DENIED, fixture.service.damage(citadel, 1.0))
        assertEquals(1, removals)
    }

    @Test
    fun `removal observers cannot damage another citadel before the nested mutation is rejected`() {
        val fixture = HealthFixture(createInitial = false)
        val destroyed = fixture.create(health = Health(20.0, 20.0))
        val survivingHealth = Health(20.0, 20.0)
        val survivor = fixture.create(1, survivingHealth)
        fixture.service.registerBehavior(HealthFixture.TYPE, SimpleHealthBehavior(20.0))
        var observed = false
        fixture.notifications.addListener(object : CitadelLifecycleListener {
            override fun onRemoved(position: CitadelPosition, runtimeId: Int, cause: RemovalCause) {
                observed = true
                assertFailsWith<IllegalStateException> { fixture.service.damage(survivor, 100.0) }
                assertEquals(20.0, survivingHealth.health)
            }
        })

        assertEquals(ActionResult.SUCCESS, fixture.service.damage(destroyed, 20.0))
        assertTrue(observed)
        assertFalse(destroyed.isCurrent)
        assertTrue(survivor.isCurrent)
        assertEquals(20.0, survivingHealth.health)
    }

    @Test
    fun `zero damage preserves living health and still completes an already dead citadel`() {
        val fixture = HealthFixture(createInitial = false)
        val living = fixture.create(health = Health(20.0, 10.0))
        val dead = fixture.create(1, Health(20.0, 0.0))
        fixture.service.registerBehavior(HealthFixture.TYPE, SimpleHealthBehavior(20.0))

        assertEquals(ActionResult.DENIED, fixture.service.damage(living, 0.0))
        assertEquals(ActionResult.SUCCESS, fixture.service.damage(dead, 0.0))
        assertTrue(living.isCurrent)
        assertFalse(dead.isCurrent)
    }

    @Test
    fun `invalid damage is rejected before changing health or storage`() {
        val fixture = HealthFixture(createInitial = false)
        val component = Health(20.0, 10.0)
        val citadel = fixture.create(health = component)
        fixture.service.registerBehavior(HealthFixture.TYPE, SimpleHealthBehavior(20.0))
        fixture.chunk.resetDirty()

        for (amount in listOf(-1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { fixture.service.damage(citadel, amount) }
            assertFailsWith<IllegalArgumentException> { DamageAction(ActionContext.SYSTEM, citadel.position, amount) }
            assertEquals(10.0, component.health)
            assertFalse(fixture.chunk.isDirty)
        }
    }

    @Test
    fun `partial behavior mutations remain dirty when getters or damage throw`() {
        for (operation in 0..2) {
            val fixture = HealthFixture(createInitial = false)
            val component = Health(20.0, 10.0)
            val citadel = fixture.create(health = component)
            val failure = IllegalStateException("Failed after mutation")
            fixture.service.registerBehavior(HealthFixture.TYPE, object : HealthBehavior {
                override fun initializeHealth(component: Health, draft: CitadelDraft) = Unit
                override fun updateHealth(component: Health, citadel: CitadelHandle): Boolean {
                    component.health = 7.0
                    throw failure
                }
            })
            fixture.chunk.resetDirty()

            val caught = assertFailsWith<IllegalStateException> {
                when (operation) {
                    0 -> fixture.service.getCurrentHealth(HealthFixture.WORLD, 0, 0, 0)
                    1 -> fixture.service.getCurrentMaxHealth(HealthFixture.WORLD, 0, 0, 0)
                    else -> fixture.service.damage(citadel, 1.0)
                }
            }

            assertSame(failure, caught)
            assertEquals(7.0, component.health)
            assertTrue(fixture.chunk.isDirty)
            assertTrue(citadel.isCurrent)
        }
    }

    @Test
    fun `getter detects changed health even if a behavior forgets its change flag`() {
        val fixture = HealthFixture(createInitial = false)
        fixture.create(health = Health(20.0, 10.0))
        fixture.service.registerBehavior(HealthFixture.TYPE, object : HealthBehavior {
            override fun initializeHealth(component: Health, draft: CitadelDraft) = Unit
            override fun updateHealth(component: Health, citadel: CitadelHandle): Boolean {
                component.health = 9.0
                return false
            }
        })
        fixture.chunk.resetDirty()

        assertEquals(9.0, fixture.service.getCurrentHealth(HealthFixture.WORLD, 0, 0, 0))
        assertTrue(fixture.chunk.isDirty)
    }

    @Test
    fun `stale and foreign handles cannot damage a current citadel`() {
        val fixture = HealthFixture(createInitial = false)
        val old = fixture.create(health = Health(20.0, 20.0))
        fixture.service.registerBehavior(HealthFixture.TYPE, SimpleHealthBehavior(20.0))
        assertTrue(fixture.manager.removeCitadel(old))
        val replacement = Health(20.0, 20.0)
        fixture.create(health = replacement)
        fixture.chunk.resetDirty()
        val otherFixture = HealthFixture(createInitial = false)
        val foreignHealth = Health(20.0, 20.0)
        val foreign = otherFixture.create(health = foreignHealth)
        otherFixture.chunk.resetDirty()

        assertEquals(ActionResult.DENIED, fixture.service.damage(old, 5.0))
        assertEquals(ActionResult.DENIED, fixture.service.damage(foreign, 5.0))
        assertEquals(20.0, replacement.health)
        assertEquals(20.0, foreignHealth.health)
        assertFalse(fixture.chunk.isDirty)
        assertFalse(otherFixture.chunk.isDirty)
    }

    @Test
    fun `missing components and behavior do not silently accept damage`() {
        val fixture = HealthFixture(createInitial = false)
        val withoutHealth = fixture.create()
        val component = Health(20.0, 10.0)
        val withoutBehavior = fixture.create(1, component)
        fixture.chunk.resetDirty()

        assertEquals(ActionResult.DENIED, fixture.service.damage(withoutHealth, 5.0))
        assertEquals(ActionResult.DENIED, fixture.service.damage(withoutBehavior, 5.0))
        assertEquals(10.0, component.health)
        assertFalse(fixture.chunk.isDirty)
    }

    @Test
    fun `simple behavior rejects invalid maxima and initializer rejects invalid produced health`() {
        for (maxHealth in listOf(0.0, -1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { SimpleHealthBehavior(maxHealth) }
        }
        val fixture = HealthFixture(createInitial = false)
        val draft = fixture.draft()
        val initializer = HealthInitializer(object : HealthBehavior {
            override fun initializeHealth(component: Health, draft: CitadelDraft) {
                component.maxHealth = 20.0
                component.health = Double.NaN
            }
        })

        assertFailsWith<IllegalArgumentException> { initializer.initialize(ActionContext.SYSTEM, draft) }
        assertNull(draft.getComponent(Health::class.java))
        assertTrue(fixture.chunk.isEmpty)
        assertFalse(fixture.chunk.isDirty)
    }
}
