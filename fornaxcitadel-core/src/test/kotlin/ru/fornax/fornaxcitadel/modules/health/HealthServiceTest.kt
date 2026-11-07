package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleListener
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.RemovalCause
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.NamedActionSource
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HealthServiceTest {
    @Test
    fun `explicit destruction is denied by default without touching health`() {
        val fixture = HealthFixture(createInitial = false)
        val health = Health(20.0, 10.0)
        val citadel = fixture.create(health = health)
        fixture.service.registerPolicy(HealthFixture.TYPE, DamagePolicy.ALLOW)
        fixture.service.enable()
        fixture.chunk.resetDirty()

        assertEquals(ActionResult.DENIED, fixture.actions.execute(
            DamageAction(ActionContext.SYSTEM, citadel.position, 0.0, DamageAction.Mode.DESTROY)
        ))
        assertTrue(citadel.isCurrent)
        assertEquals(10.0, health.health)
        assertFalse(fixture.chunk.isDirty)
    }

    @Test
    fun `authorized destruction bypasses numeric modifiers and ordinary damage policy`() {
        val fixture = HealthFixture(createInitial = false, destructionPolicy = DamagePolicy.ALLOW)
        val health = Health(40.0, 40.0)
        val citadel = fixture.create(health = health, startedAtMillis = 1_000L, initialDamageMultiplier = 4.0)
        fixture.service.registerPolicy(HealthFixture.TYPE, DamagePolicy.DENY)
        fixture.service.registerModifier(DamageModifier { _, _, _ -> error("DESTROY must bypass modifiers") })
        fixture.service.enable()

        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(
            DamageAction(ActionContext.SYSTEM, citadel.position, 0.0, DamageAction.Mode.DESTROY)
        ))
        assertFalse(citadel.isCurrent)
        assertEquals(40.0, health.health)
    }

    @Test
    fun `external actions and direct damage apply maturation exactly once and keep their contexts`() {
        val fixture = HealthFixture(createInitial = false, clock = MutableHealthClock(1_500L))
        val externalHealth = Health(40.0, 40.0)
        val directHealth = Health(40.0, 40.0)
        val external = fixture.create(health = externalHealth, startedAtMillis = 1_000L, initialDamageMultiplier = 4.0)
        val direct = fixture.create(1, directHealth, startedAtMillis = 1_000L, initialDamageMultiplier = 4.0)
        val context = ActionContext(UUID(10L, 20L), NamedActionSource(Key("otherplugin", "damage")))
        val observed = mutableListOf<Pair<ActionContext, Double>>()
        fixture.service.registerModifier(DamageModifier { actualContext, _, amount ->
            observed.add(actualContext to amount)
            amount
        })
        fixture.service.registerPolicy(HealthFixture.TYPE, DamagePolicy.ALLOW)
        fixture.service.enable()

        assertEquals(ActionResult.SUCCESS, fixture.actions.execute(DamageAction(context, external.position, 5.0)))
        assertEquals(ActionResult.SUCCESS, fixture.service.damage(direct, 5.0))
        assertEquals(32.0, externalHealth.health)
        assertEquals(32.0, directHealth.health)
        assertEquals(40.0, externalHealth.maxHealth)
        assertEquals(listOf(context to 8.0, ActionContext.SYSTEM to 8.0), observed)

        assertEquals(ActionResult.SUCCESS, fixture.service.damage(direct, 5.0, context))
        assertEquals(24.0, directHealth.health)
        assertEquals(context to 8.0, observed.last())
    }

    @Test
    fun `modifiers run in registration order and repeated registration of one instance is idempotent`() {
        val fixture = HealthFixture(createInitial = false)
        val health = Health(100.0, 100.0)
        val citadel = fixture.create(health = health)
        val amounts = mutableListOf<Double>()
        val add = DamageModifier { _, target, amount ->
            assertSame(citadel, target)
            amounts.add(amount)
            amount + 2.0
        }
        val multiply = DamageModifier { _, _, amount ->
            amounts.add(amount)
            amount * 3.0
        }
        fixture.service.registerModifier(add)
        fixture.service.registerModifier(multiply)
        fixture.service.registerModifier(add)

        assertEquals(ActionResult.SUCCESS, fixture.service.damage(citadel, 3.0))
        assertEquals(listOf(3.0, 5.0), amounts)
        assertEquals(85.0, health.health)
        assertTrue(fixture.chunk.isDirty)
    }

    @Test
    fun `unregistering is idempotent and registering again appends the modifier`() {
        val fixture = HealthFixture(createInitial = false)
        val health = Health(100.0, 100.0)
        val citadel = fixture.create(health = health)
        val add = DamageModifier { _, _, amount -> amount + 2.0 }
        val multiply = DamageModifier { _, _, amount -> amount * 3.0 }
        assertFalse(fixture.service.unregisterModifier(add))
        fixture.service.registerModifier(add)
        fixture.service.registerModifier(multiply)

        assertEquals(ActionResult.SUCCESS, fixture.service.damage(citadel, 3.0))
        assertEquals(85.0, health.health)
        assertTrue(fixture.service.unregisterModifier(add))
        assertFalse(fixture.service.unregisterModifier(add))
        assertEquals(ActionResult.SUCCESS, fixture.service.damage(citadel, 3.0))
        assertEquals(76.0, health.health)

        fixture.service.registerModifier(add)
        assertEquals(ActionResult.SUCCESS, fixture.service.damage(citadel, 3.0))
        assertEquals(65.0, health.health)
    }

    @Test
    fun `unregistering compares instances rather than modifier equality`() {
        class EqualModifier(private val addition: Double) : DamageModifier {
            override fun getDamage(context: ActionContext, citadel: CitadelHandle, currentDamage: Double): Double =
                currentDamage + addition

            override fun equals(other: Any?): Boolean = other is EqualModifier
            override fun hashCode(): Int = 0
        }
        val fixture = HealthFixture(createInitial = false)
        val health = Health(100.0, 100.0)
        val citadel = fixture.create(health = health)
        val first = EqualModifier(2.0)
        val second = EqualModifier(10.0)
        fixture.service.registerModifier(first)
        fixture.service.registerModifier(second)

        assertFalse(fixture.service.unregisterModifier(EqualModifier(99.0)))
        assertEquals(ActionResult.SUCCESS, fixture.service.damage(citadel, 1.0))
        assertEquals(87.0, health.health)
        assertTrue(fixture.service.unregisterModifier(first))
        assertFalse(fixture.service.unregisterModifier(first))
        assertEquals(ActionResult.SUCCESS, fixture.service.damage(citadel, 1.0))
        assertEquals(76.0, health.health)
    }

    @Test
    fun `unregistering during calculation changes only subsequent damage chains`() {
        val fixture = HealthFixture(createInitial = false)
        val health = Health(100.0, 100.0)
        val citadel = fixture.create(health = health)
        val calls = mutableListOf<String>()
        val later = DamageModifier { _, _, amount ->
            calls.add("later")
            amount * 3.0
        }
        lateinit var first: DamageModifier
        first = DamageModifier { _, _, amount ->
            calls.add("first")
            assertTrue(fixture.service.unregisterModifier(first))
            assertTrue(fixture.service.unregisterModifier(later))
            amount + 2.0
        }
        fixture.service.registerModifier(first)
        fixture.service.registerModifier(later)

        assertEquals(ActionResult.SUCCESS, fixture.service.damage(citadel, 1.0))
        assertEquals(listOf("first", "later"), calls)
        assertEquals(91.0, health.health)

        assertEquals(ActionResult.SUCCESS, fixture.service.damage(citadel, 1.0))
        assertEquals(listOf("first", "later"), calls)
        assertEquals(90.0, health.health)
    }

    @Test
    fun `failed modifier calculations leave health and dirty state unchanged`() {
        val modifiers = listOf(
            DamageModifier { _, _, _ -> -1.0 },
            DamageModifier { _, _, _ -> Double.NaN },
            DamageModifier { _, _, _ -> Double.POSITIVE_INFINITY },
            DamageModifier { _, _, _ -> Double.NEGATIVE_INFINITY },
            DamageModifier { _, _, _ -> error("Calculation failed") }
        )
        for (modifier in modifiers) {
            val fixture = HealthFixture(createInitial = false)
            val health = Health(40.0, 30.0)
            val citadel = fixture.create(health = health)
            fixture.service.registerModifier(DamageModifier { _, _, amount -> amount * 2.0 })
            fixture.service.registerModifier(modifier)
            var reachedLastModifier = false
            fixture.service.registerModifier(DamageModifier { _, _, amount -> reachedLastModifier = true; amount })
            fixture.chunk.resetDirty()

            assertFailsWith<IllegalStateException> { fixture.service.damage(citadel, 5.0) }

            assertFalse(reachedLastModifier)
            assertEquals(30.0, health.health)
            assertEquals(40.0, health.maxHealth)
            assertTrue(citadel.isCurrent)
            assertFalse(fixture.chunk.isDirty)
        }
    }

    @Test
    fun `denied actions never execute damage modifiers`() {
        val fixture = HealthFixture(createInitial = false)
        val health = Health(20.0, 20.0)
        val citadel = fixture.create(health = health)
        fixture.service.registerPolicy(HealthFixture.TYPE, DamagePolicy.DENY)
        fixture.service.registerModifier(DamageModifier { _, _, _ -> error("Denied actions must not calculate damage") })
        fixture.service.enable()
        fixture.chunk.resetDirty()

        assertEquals(ActionResult.DENIED, fixture.actions.execute(DamageAction(ActionContext.SYSTEM, citadel.position, 5.0)))
        assertEquals(20.0, health.health)
        assertFalse(fixture.chunk.isDirty)
    }

    @Test
    fun `advancing time and reading components never changes stored health or dirties the chunk`() {
        val fixture = HealthFixture(createInitial = false)
        val health = Health(40.0, 15.0)
        val citadel = fixture.create(health = health, startedAtMillis = 1_000L, initialDamageMultiplier = 4.0)
        fixture.chunk.resetDirty()

        for (now in listOf(1_000L, 1_500L, 2_000L, 100_000L)) {
            fixture.clock.timeMillis = now
            val current = assertNotNull(citadel.getComponent(Health::class.java))
            assertSame(health, current)
            assertEquals(40.0, current.maxHealth)
            assertEquals(15.0, current.health)
            assertEquals(1_000L, assertNotNull(citadel.getComponent(Maturing::class.java)).startedAtMillis)
            assertFalse(fixture.chunk.isDirty)
        }
    }

    @Test
    fun `lethal damage removes the entire citadel once after health editing`() {
        val fixture = HealthFixture(createInitial = false)
        val component = Health(20.0, 20.0)
        val citadel = fixture.create(health = component)
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
        val livingHealth = Health(20.0, 10.0)
        val living = fixture.create(health = livingHealth)
        val dead = fixture.create(1, Health(20.0, 0.0))
        fixture.chunk.resetDirty()

        assertEquals(ActionResult.DENIED, fixture.service.damage(living, 0.0))
        assertEquals(10.0, livingHealth.health)
        assertFalse(fixture.chunk.isDirty)
        assertEquals(ActionResult.SUCCESS, fixture.service.damage(dead, 0.0))
        assertTrue(living.isCurrent)
        assertFalse(dead.isCurrent)
    }

    @Test
    fun `invalid incoming damage is rejected before calculating or changing anything`() {
        val fixture = HealthFixture(createInitial = false)
        val component = Health(20.0, 10.0)
        val citadel = fixture.create(health = component)
        fixture.service.registerModifier(DamageModifier { _, _, _ -> error("Invalid input reached a modifier") })
        fixture.chunk.resetDirty()

        for (amount in listOf(-1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { fixture.service.damage(citadel, amount) }
            assertFailsWith<IllegalArgumentException> { DamageAction(ActionContext.SYSTEM, citadel.position, amount) }
            assertEquals(10.0, component.health)
            assertFalse(fixture.chunk.isDirty)
        }
    }

    @Test
    fun `stale foreign and unloaded handles cannot calculate damage or change a current citadel`() {
        val fixture = HealthFixture(createInitial = false)
        val old = fixture.create(health = Health(20.0, 20.0))
        assertTrue(fixture.manager.removeCitadel(old))
        val replacementHealth = Health(20.0, 20.0)
        val replacement = fixture.create(health = replacementHealth)
        val otherFixture = HealthFixture(createInitial = false)
        val foreignHealth = Health(20.0, 20.0)
        val foreign = otherFixture.create(health = foreignHealth)
        fixture.service.registerModifier(DamageModifier { _, _, _ -> error("Invalid target reached a modifier") })
        val loadedChunk = fixture.chunk
        loadedChunk.resetDirty()
        otherFixture.chunk.resetDirty()

        assertEquals(ActionResult.DENIED, fixture.service.damage(old, 5.0))
        assertEquals(ActionResult.DENIED, fixture.service.damage(foreign, 5.0))
        fixture.storage.chunk = null
        assertEquals(ActionResult.DENIED, fixture.service.damage(replacement, 5.0))
        assertEquals(20.0, replacementHealth.health)
        assertEquals(20.0, foreignHealth.health)
        assertFalse(loadedChunk.isDirty)
        assertFalse(otherFixture.chunk.isDirty)
    }

    @Test
    fun `missing health rejects damage while static health needs no registered behavior`() {
        val fixture = HealthFixture(createInitial = false)
        val withoutHealth = fixture.create()
        val component = Health(20.0, 10.0)
        val withHealth = fixture.create(1, component)
        fixture.chunk.resetDirty()

        assertEquals(ActionResult.DENIED, fixture.service.damage(withoutHealth, 5.0))
        assertFalse(fixture.chunk.isDirty)
        assertEquals(ActionResult.SUCCESS, fixture.service.damage(withHealth, 5.0))
        assertEquals(5.0, component.health)
    }

    @Test
    fun `invalid existing health is rejected without changing the component or dirty state`() {
        val invalidStates = listOf(
            0.0 to 0.0, -20.0 to 5.0,
            Double.NaN to 5.0, Double.NEGATIVE_INFINITY to 5.0, Double.POSITIVE_INFINITY to 5.0,
            20.0 to -1.0, 20.0 to 21.0, 20.0 to Double.NaN,
            20.0 to Double.NEGATIVE_INFINITY, 20.0 to Double.POSITIVE_INFINITY
        )
        for ((maxHealth, health) in invalidStates) {
            val fixture = HealthFixture(createInitial = false)
            val component = Health(maxHealth, health)
            val citadel = fixture.create(health = component)
            fixture.chunk.resetDirty()

            assertFailsWith<IllegalStateException> { fixture.service.damage(citadel, 1.0) }
            assertEquals(maxHealth, component.maxHealth)
            assertEquals(health, component.health)
            assertFalse(fixture.chunk.isDirty)
        }
    }

    @Test
    fun `health initializer creates full static health and refuses duplicate initialization`() {
        val fixture = HealthFixture(createInitial = false)
        val draft = fixture.draft()
        HealthInitializer(100.0).initialize(ActionContext.SYSTEM, draft)
        val health = assertNotNull(draft.getComponent(Health::class.java))

        assertEquals(100.0, health.maxHealth)
        assertEquals(100.0, health.health)
        assertNull(draft.getComponent(Maturing::class.java))
        assertFails { HealthInitializer(200.0).initialize(ActionContext.SYSTEM, draft) }
        assertSame(health, draft.getComponent(Health::class.java))
        assertTrue(fixture.chunk.isEmpty)
        assertFalse(fixture.chunk.isDirty)

        for (maxHealth in listOf(0.0, -1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { HealthInitializer(maxHealth) }
        }
    }
}
