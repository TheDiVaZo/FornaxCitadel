package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.validateDamage
import kotlin.test.*

class LazyHealthTest {
    @Test
    fun `without health or maximum every valid hit removes once including zero after modifiers`() {
        for (damage in listOf(0.0, 1.0)) for (zeroModifier in listOf(false, true)) {
            val f = HealthFixture(createInitial = false)
            val citadel = f.create()
            var calculations = 0
            f.service.registerModifier { _, _, amount ->
                calculations++
                if (zeroModifier) 0.0 else amount
            }
            assertNull(f.service.currentHealth(citadel))
            assertEquals(ActionResult.SUCCESS, f.service.damage(citadel, damage))
            assertFalse(citadel.isCurrent)
            assertEquals(1, calculations)
            assertEquals(ActionResult.DENIED, f.service.damage(citadel, damage))
            assertEquals(1, calculations)
        }
    }

    @Test
    fun `without HP invalid calculations still prevent removal`() {
        for (modifier in listOf(
            DamageModifier { _, _, _ -> error("modifier failed") },
            DamageModifier { _, _, _ -> Double.NaN }
        )) {
            val f = HealthFixture(createInitial = false)
            val target = f.create()
            f.service.registerModifier(modifier)
            f.chunk.resetDirty()
            assertFailsWith<IllegalStateException> { f.service.damage(target, 0.0) }
            assertTrue(target.isCurrent)
            assertFalse(f.chunk.isDirty)
        }
    }

    @Test
    fun `read-only health follows the current maximum until damage materializes HP`() {
        val f = HealthFixture(createInitial = false)
        val citadel = f.create()
        f.chunk.resetDirty()
        assertNull(f.service.currentHealth(citadel))
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(100.0))
        assertEquals(100.0, f.service.currentHealth(citadel))
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(200.0))
        assertEquals(200.0, f.service.currentHealth(citadel))
        assertNull(citadel.getComponent(Health::class.java))
        assertFalse(f.chunk.isDirty)

        assertEquals(ActionResult.SUCCESS, f.service.damage(citadel, 10.0))
        val component = assertNotNull(citadel.getComponent(Health::class.java))
        assertEquals(190.0, component.health)
        assertTrue(f.chunk.isDirty)
        f.chunk.resetDirty()
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(300.0))
        assertEquals(190.0, f.service.currentHealth(citadel))
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(50.0))
        assertEquals(190.0, f.service.currentHealth(citadel))
        assertSame(component, citadel.getComponent(Health::class.java))
        assertFalse(f.chunk.isDirty)
        assertEquals(ActionResult.DENIED, f.service.damage(citadel, 0.0))
        assertEquals(190.0, component.health)
        assertEquals(ActionResult.SUCCESS, f.service.damage(citadel, 1.0))
        assertEquals(49.0, component.health)
    }

    @Test
    fun `stored citadel without health takes its first modified damage once and persists the result`() {
        val f = HealthFixture(createInitial = false, clock = MutableHealthClock(1_500L))
        f.create(startedAtMillis = 1_000L, initialDamageMultiplier = 4.0)
        val mapper = ChunkCordSnapshotMapper(f.types)
        val saved = mapper.snapshot(f.chunk).toByteArray()
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(100.0))
        f.storage.chunk = mapper.restore(ChunkCordSnapshot.fromByteArray(saved))
        val restored = f.citadel
        f.actions.validateDamage(f.citadels, HealthFixture.TYPE)
        val amounts = mutableListOf<Double>()
        f.service.registerModifier { _, target, amount ->
            assertNull(target.getComponent(Health::class.java))
            assertEquals(100.0, f.service.currentHealth(target))
            assertFalse(f.chunk.isDirty)
            amounts.add(amount)
            amount * 2.0
        }
        var handled = 0
        f.actions.registerHandler(object : ActionHandler<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun handle(action: DamageAction) {
                assertEquals(84.0, restored.getComponent(Health::class.java)?.health)
                handled++
            }
        })
        assertEquals(ActionResult.SUCCESS, f.actions.execute(DamageAction(ActionContext.SYSTEM, restored.position, 5.0)))
        assertEquals(listOf(8.0), amounts)
        assertEquals(1, handled)
        assertEquals(1_000L, restored.getComponent(Maturing::class.java)?.startedAtMillis)
        val after = mapper.snapshot(f.chunk).toByteArray()
        f.storage.chunk = mapper.restore(ChunkCordSnapshot.fromByteArray(after))
        assertEquals(84.0, f.service.currentHealth(f.citadel))
        assertEquals(84.0, f.citadel.getComponent(Health::class.java)?.health)
        assertFalse(f.chunk.isDirty)
    }

    @Test
    fun `zero ineffective and cancelled damage leave missing health and clean storage unchanged`() {
        val f = HealthFixture(createInitial = false)
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(100.0))
        val citadel = f.create()
        f.chunk.resetDirty()
        assertEquals(ActionResult.DENIED, f.service.damage(citadel, 0.0))
        assertEquals(ActionResult.DENIED, f.service.damage(citadel, Double.MIN_VALUE))
        val zero = DamageModifier { _, _, _ -> 0.0 }
        f.service.registerModifier(zero)
        assertEquals(ActionResult.DENIED, f.service.damage(citadel, 100.0))
        f.service.unregisterModifier(zero)
        f.service.registerModifier { _, _, _ -> error("Cancelled action must not calculate damage") }
        f.actions.validateDamage(f.citadels, HealthFixture.TYPE) { _, _ -> false }
        assertEquals(ActionResult.DENIED, f.actions.execute(DamageAction(ActionContext.SYSTEM, citadel.position, 5.0)))
        assertNull(citadel.getComponent(Health::class.java))
        assertEquals(100.0, f.service.currentHealth(citadel))
        assertFalse(f.chunk.isDirty)
    }

    @Test
    fun `failed calculations never materialize missing health`() {
        for (modifier in listOf(
            DamageModifier { _, _, _ -> error("Failed calculation") },
            DamageModifier { _, _, _ -> Double.NaN },
            DamageModifier { _, _, _ -> Double.POSITIVE_INFINITY },
            DamageModifier { _, _, _ -> -1.0 }
        )) {
            val f = HealthFixture(createInitial = false)
            f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(100.0))
            val citadel = f.create()
            f.service.registerModifier(modifier)
            f.chunk.resetDirty()
            assertFailsWith<IllegalStateException> { f.service.damage(citadel, 1.0) }
            assertNull(citadel.getComponent(Health::class.java))
            assertFalse(f.chunk.isDirty)
        }
    }

    @Test
    fun `lethal first damage removes reinforcement without creating health`() {
        for (damage in listOf(100.0, 200.0)) {
            val f = HealthFixture(createInitial = false)
            val citadel = f.create()
            f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(100.0))
            f.service.registerModifier { _, target, amount ->
                assertNull(target.getComponent(Health::class.java))
                amount
            }
            assertEquals(ActionResult.SUCCESS, f.service.damage(citadel, damage))
            assertFalse(citadel.isCurrent)
            assertTrue(f.chunk.isEmpty)
            assertNull(f.service.currentHealth(citadel))
            assertEquals(ActionResult.DENIED, f.service.damage(citadel, damage))
        }
    }

    @Test
    fun `unknown or missing kind without HP is destroyed and existing zero health is never replaced by the maximum`() {
        val f = HealthFixture(createInitial = false)
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(100.0))
        val unknown = f.create(definitionKey = Key("test", "missing"))
        val noKind = f.create(1)
        noKind.removeComponent(CitadelKind::class.java)
        val dead = f.create(2, Health(0.0))
        f.chunk.resetDirty()
        for (target in listOf(unknown, noKind)) {
            assertNull(f.service.currentHealth(target))
            assertEquals(ActionResult.SUCCESS, f.service.damage(target, 1.0))
            assertFalse(target.isCurrent)
            assertNull(target.getComponent(Health::class.java))
        }
        assertEquals(0.0, f.service.currentHealth(dead))
        assertTrue(f.chunk.isDirty)
        assertEquals(ActionResult.SUCCESS, f.service.damage(dead, 0.0))
        assertFalse(dead.isCurrent)
    }

    @Test
    fun `stale foreign and unloaded handles cannot read defaults or create components`() {
        val f = HealthFixture(createInitial = false)
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(100.0))
        val old = f.create()
        f.manager.removeCitadel(old)
        val current = f.create()
        val foreign = HealthFixture(createInitial = false).create()
        val chunk = f.chunk
        chunk.resetDirty()
        for (target in listOf(old, foreign)) {
            assertNull(f.service.currentHealth(target))
            assertEquals(ActionResult.DENIED, f.service.damage(target, 1.0))
        }
        f.storage.chunk = null
        assertNull(f.service.currentHealth(current))
        assertEquals(ActionResult.DENIED, f.service.damage(current, 1.0))
        assertFalse(chunk.isDirty)
    }

}
