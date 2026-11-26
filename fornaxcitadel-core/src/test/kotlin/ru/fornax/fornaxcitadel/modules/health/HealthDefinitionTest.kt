package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.util.Key
import kotlin.test.*

class HealthDefinitionTest {
    @Test
    fun `only invulnerable profiles can omit maximum health`() {
        assertFailsWith<IllegalArgumentException> { HealthDefinition(null) }
        val f = HealthFixture(createInitial = false)
        val initializer = HealthInitializer(f.service)
        val draft = f.draft()
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(null, invulnerable = true))
        initializer.initialize(draft)
        assertNull(draft.getComponent(Health::class.java))
        val citadel = f.publish(draft)
        assertTrue(f.service.isInvulnerable(HealthFixture.TYPE))
        assertNull(f.service.currentHealth(citadel))
        assertEquals(ActionResult.DENIED, f.service.damage(citadel, 0.0))
        assertTrue(citadel.isCurrent)
        f.service.unregisterDefinition(HealthFixture.TYPE)
        assertFalse(f.service.isInvulnerable(HealthFixture.TYPE))
        assertEquals(ActionResult.SUCCESS, f.service.damage(citadel, 1.0))
        assertFalse(citadel.isCurrent)
    }

    @Test
    fun `invulnerable profiles protect saved HP before running damage modifiers`() {
        val f = HealthFixture(createInitial = false)
        val citadel = f.create(health = Health(30.0))
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(80.0, invulnerable = true))
        f.service.registerModifier { _, _, _ -> error("Invulnerable targets must bypass modifiers") }
        f.chunk.resetDirty()
        assertEquals(ActionResult.DENIED, f.service.damage(citadel, 120.0))
        assertEquals(30.0, f.service.currentHealth(citadel))
        assertFalse(f.chunk.isDirty)
    }

    @Test
    fun `health serializes only actual HP`() {
        val bytes = Health(80.0).toByteArray()
        assertEquals(Double.SIZE_BYTES, bytes.size)
        val restored = Health(0.0).apply { safeRead(bytes) }
        assertEquals(80.0, restored.health)
    }

    @Test
    fun `new maximum applies after restore without changing HP until positive damage`() {
        val f = HealthFixture(createInitial = false)
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(100.0))
        f.create(health = Health(80.0))
        val mapper = ChunkCordSnapshotMapper(f.types)
        val bytes = mapper.snapshot(f.chunk).toByteArray()
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(50.0))
        f.storage.chunk = mapper.restore(ChunkCordSnapshot.fromByteArray(bytes))
        val restored = f.citadel
        assertEquals(50.0, f.service.maxHealth(restored))
        assertEquals(80.0, restored.getComponent(Health::class.java)?.health)
        assertFalse(f.chunk.isDirty)
        assertEquals(ActionResult.DENIED, f.service.damage(restored, 0.0))
        assertEquals(80.0, restored.getComponent(Health::class.java)?.health)
        assertFalse(f.chunk.isDirty)
        assertEquals(ActionResult.SUCCESS, f.service.damage(restored, 1.0))
        assertEquals(49.0, restored.getComponent(Health::class.java)?.health)
    }

    @Test
    fun `modifier failures and zero resulting damage never clamp HP`() {
        val f = HealthFixture(createInitial = false)
        val citadel = f.create(health = Health(80.0))
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(50.0))
        val zero = DamageModifier { _, _, _ -> 0.0 }
        f.service.registerModifier(zero)
        f.chunk.resetDirty()
        assertEquals(ActionResult.DENIED, f.service.damage(citadel, 1.0))
        f.service.unregisterModifier(zero)
        val failure = DamageModifier { _, _, _ -> error("modifier failed") }
        f.service.registerModifier(failure)
        assertFailsWith<IllegalStateException> { f.service.damage(citadel, 1.0) }
        f.service.unregisterModifier(failure)
        assertEquals(80.0, citadel.getComponent(Health::class.java)?.health)
        assertFalse(f.chunk.isDirty)
        f.service.registerModifier { _, _, damage -> damage * 2.0 }
        assertEquals(ActionResult.SUCCESS, f.service.damage(citadel, 1.0))
        assertEquals(48.0, citadel.getComponent(Health::class.java)?.health)
    }

    @Test
    fun `missing profiles and kinds keep current HP usable and increasing max never heals`() {
        val f = HealthFixture(createInitial = false)
        val citadel = f.create(health = Health(80.0))
        f.chunk.resetDirty()
        assertNull(f.service.maxHealth(citadel))
        val profile = HealthDefinition(200.0)
        f.service.registerDefinition(HealthFixture.TYPE, profile)
        assertSame(profile, f.service.findDefinition(HealthFixture.TYPE))
        assertEquals(200.0, f.service.maxHealth(citadel))
        assertEquals(80.0, citadel.getComponent(Health::class.java)?.health)
        assertFalse(f.chunk.isDirty)
        assertTrue(f.service.unregisterDefinition(HealthFixture.TYPE))
        assertFalse(f.service.unregisterDefinition(HealthFixture.TYPE))
        assertEquals(ActionResult.SUCCESS, f.service.damage(citadel, 1.0))
        assertEquals(79.0, citadel.getComponent(Health::class.java)?.health)
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(1.0))
        citadel.removeComponent(CitadelKind::class.java)
        assertNull(f.service.maxHealth(citadel))
        assertEquals(ActionResult.SUCCESS, f.service.damage(citadel, 1.0))
        assertEquals(78.0, citadel.getComponent(Health::class.java)?.health)
    }

    @Test
    fun `initializer reads current profile and leaves absent profiles without health`() {
        val f = HealthFixture(createInitial = false)
        val initializer = HealthInitializer(f.service)
        val absent = f.draft()
        initializer.initialize(absent)
        assertNull(absent.getComponent(Health::class.java))
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(100.0))
        val first = f.draft()
        initializer.initialize(first)
        f.service.registerDefinition(HealthFixture.TYPE, HealthDefinition(50.0))
        val next = f.draft(1)
        initializer.initialize(next)
        assertEquals(100.0, first.getComponent(Health::class.java)?.health)
        assertEquals(50.0, next.getComponent(Health::class.java)?.health)
        val unknown = f.draft(2, Key("test", "unknown"))
        initializer.initialize(unknown)
        assertNull(unknown.getComponent(Health::class.java))
    }
}
