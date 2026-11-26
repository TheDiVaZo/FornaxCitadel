package ru.fornax.fornaxcitadel.modules.health

import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.facade.MaturationFacade
import ru.fornax.fornaxcitadel.core.facade.ReadableHandle
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.util.Key
import java.time.Duration
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.*

class MaturationServiceTest {
    @Test
    fun `duration multiplier affects shared progress damage and exact maturity without changing saved values`() {
        val fixture = HealthFixture(createInitial = false)
        val citadel = fixture.create(health = Health(100.0))
        citadel.putComponent(Maturing::class.java, Maturing(1_000L))
        val maturation = MaturationFacade(fixture.maturation)
        val profile = MaturationDefinition(Duration.ofSeconds(1), 4.0)
        maturation.registerDefinition(HealthFixture.TYPE, profile)
        assertSame(profile, maturation.findDefinition(HealthFixture.TYPE))
        maturation.registerMultiplier { 2.0 }
        fixture.clock.timeMillis = 2_000L
        assertEquals(0.5, maturation.progress(citadel))
        assertFalse(maturation.isMature(citadel))
        fixture.service.damage(citadel, 10.0)
        assertEquals(84.0, citadel.getComponent(Health::class.java)?.health)
        fixture.clock.timeMillis = 2_999L
        assertFalse(maturation.isMature(citadel))
        fixture.clock.timeMillis = 3_000L
        assertTrue(maturation.isMature(citadel))
        assertEquals(1_000L, citadel.getComponent(Maturing::class.java)?.startedAtMillis)
    }

    @Test
    fun `missing maturation is already mature and changed multiplier is live`() {
        val fixture = HealthFixture(createInitial = false)
        val plain = fixture.create()
        val timed = fixture.create(1, startedAtMillis = 1_000L)
        val service = fixture.maturation
        fixture.clock.timeMillis = 2_000L
        assertTrue(service.isMature(plain))
        assertTrue(service.isMature(timed))
        service.registerMultiplier { 3.0 }
        assertFalse(service.isMature(timed))
        assertTrue(service.isMature(plain))
        fixture.clock.timeMillis = 4_000L
        assertTrue(service.isMature(timed))
    }

    @Test
    fun `large multipliers never wrap into instant maturity`() {
        val fixture = HealthFixture(createInitial = false, clock = MutableHealthClock(Long.MAX_VALUE))
        val citadel = fixture.create(startedAtMillis = Long.MIN_VALUE, maturationDurationMillis = Long.MAX_VALUE)
        val service = fixture.maturation
        val multiplier = MaturationMultiplier { Double.MAX_VALUE }
        service.registerMultiplier(multiplier)
        assertEquals(0.0, service.progress(citadel))
        assertFalse(service.isMature(citadel))
        service.unregisterMultiplier(multiplier)
        assertTrue(service.isMature(citadel))
        assertTrue(service.unregisterDefinition(HealthFixture.TYPE))
        service.registerDefinition(HealthFixture.TYPE, MaturationDefinition(Duration.ofSeconds(Long.MAX_VALUE)))
        assertTrue(service.progress(citadel) in 0.001..0.003)
    }

    @Test
    fun `component payload contains only start time`() {
        val bytes = Maturing(123_456L).toByteArray()
        assertEquals(Long.SIZE_BYTES, bytes.size)
        val restored = Maturing().apply { safeRead(bytes) }
        assertEquals(123_456L, restored.startedAtMillis)
    }

    @Test
    fun `only the registered profile instance can be registered again`() {
        val fixture = HealthFixture(createInitial = false)
        val citadel = fixture.create(startedAtMillis = 1_000L, initialDamageMultiplier = 4.0)
        fixture.clock.timeMillis = 1_500L
        fixture.chunk.resetDirty()
        val key = HealthFixture.TYPE
        val maturation = MaturationFacade(fixture.maturation)
        val modifier = MaturingDamageModifier(fixture.maturation)
        val first = assertNotNull(maturation.findDefinition(key))
        maturation.registerDefinition(key, first)

        for (conflict in listOf(first.copy(), MaturationDefinition(Duration.ofSeconds(5), 2.0))) {
            val error = assertFailsWith<IllegalArgumentException> { maturation.registerDefinition(key, conflict) }
            assertContains(error.message.orEmpty(), key.asString())
            assertSame(first, maturation.findDefinition(key))
            assertEquals(0.5, maturation.progress(citadel))
            assertEquals(8.0, modifier.getDamage(ActionContext.SYSTEM, citadel, 5.0))
        }
        assertFalse(fixture.chunk.isDirty)
    }

    @Test
    fun `current kind selects the profile and profile replacement updates progress and damage`() {
        val fixture = HealthFixture(createInitial = false)
        val citadel = fixture.create(startedAtMillis = 1_000L, initialDamageMultiplier = 4.0)
        val service = fixture.maturation
        val modifier = MaturingDamageModifier(service)
        fixture.clock.timeMillis = 2_000L
        assertTrue(service.isMature(citadel))

        assertTrue(service.unregisterDefinition(HealthFixture.TYPE))
        service.registerDefinition(HealthFixture.TYPE, MaturationDefinition(Duration.ofSeconds(2), 5.0))
        fixture.chunk.resetDirty()
        assertEquals(0.5, service.progress(citadel))
        assertEquals(10.0, modifier.getDamage(ActionContext.SYSTEM, citadel, 6.0))
        assertEquals(1_000L, citadel.getComponent(Maturing::class.java)?.startedAtMillis)
        assertFalse(fixture.chunk.isDirty)

        val other = Key("test", "other")
        service.registerDefinition(other, MaturationDefinition(Duration.ofSeconds(4), 2.0))
        citadel.editComponent(CitadelKind::class.java) { it.definitionKey = other }
        assertEquals(0.25, service.progress(citadel))
        assertEquals(8.0, modifier.getDamage(ActionContext.SYSTEM, citadel, 5.0))
    }

    @Test
    fun `restored start time and kind use the new service definitions`() {
        val fixture = HealthFixture(createInitial = false)
        fixture.create(startedAtMillis = 1_000L, initialDamageMultiplier = 4.0)
        val mapper = ChunkCordSnapshotMapper(fixture.types)
        val bytes = mapper.snapshot(fixture.chunk).toByteArray()
        fixture.storage.chunk = mapper.restore(ChunkCordSnapshot.fromByteArray(bytes))
        fixture.clock.timeMillis = 2_000L

        val service = MaturationService(fixture.clock)
        service.registerDefinition(HealthFixture.TYPE, MaturationDefinition(Duration.ofSeconds(4), 5.0))
        val restored = fixture.citadel
        assertEquals(HealthFixture.TYPE, restored.getComponent(CitadelKind::class.java)?.definitionKey)
        assertEquals(1_000L, restored.getComponent(Maturing::class.java)?.startedAtMillis)
        assertEquals(0.25, service.progress(restored))
        assertEquals(10.0, MaturingDamageModifier(service).getDamage(ActionContext.SYSTEM, restored, 4.0))
        assertFalse(fixture.chunk.isDirty)
    }

    @Test
    fun `zero duration is mature before start time and increasing it uses original start`() {
        val fixture = HealthFixture(createInitial = false)
        val citadel = fixture.create(startedAtMillis = 1_000L, maturationDurationMillis = 0)
        val service = fixture.maturation
        val unexpected = MaturationMultiplier { error("Instant types need no multipliers") }
        service.registerMultiplier(unexpected)
        for (time in listOf(500L, 1_000L, 1_500L)) {
            fixture.clock.timeMillis = time
            assertTrue(service.isMature(citadel))
        }
        service.unregisterMultiplier(unexpected)
        assertTrue(service.unregisterDefinition(HealthFixture.TYPE))
        service.registerDefinition(HealthFixture.TYPE, MaturationDefinition(Duration.ofSeconds(2)))
        assertEquals(0.25, service.progress(citadel))
        assertEquals(1_000L, citadel.getComponent(Maturing::class.java)?.startedAtMillis)
    }

    @Test
    fun `unknown keys warn once and become mature until registered`() {
        val fixture = HealthFixture()
        val records = mutableListOf<LogRecord>()
        val logger = Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(object : Handler() {
                override fun publish(record: LogRecord) { records.add(record) }
                override fun flush() {}
                override fun close() {}
            })
        }
        val service = MaturationService(fixture.clock, logger)
        repeat(3) { assertTrue(service.isMature(fixture.citadel)) }
        assertEquals(5.0, MaturingDamageModifier(service).getDamage(ActionContext.SYSTEM, fixture.citadel, 5.0))
        assertEquals(1, records.size)
        assertEquals(Level.WARNING, records.single().level)
        assertTrue(records.single().message.contains(HealthFixture.TYPE.toString()))
        assertNull(service.findDefinition(HealthFixture.TYPE))

        val profile = MaturationDefinition(Duration.ofSeconds(1))
        service.registerDefinition(HealthFixture.TYPE, profile)
        assertSame(profile, service.findDefinition(HealthFixture.TYPE))
        assertFalse(service.isMature(fixture.citadel))
        assertTrue(service.unregisterDefinition(HealthFixture.TYPE))
        assertFalse(service.unregisterDefinition(HealthFixture.TYPE))
        assertTrue(service.isMature(fixture.citadel))
        assertEquals(2, records.size)

        fixture.citadel.removeComponent(CitadelKind::class.java)
        repeat(3) { assertTrue(service.isMature(fixture.citadel)) }
        assertEquals(3, records.size)
        assertTrue(records.last().message.contains("missing CitadelKind"))
    }

    @Test
    fun `multipliers compose and registration and removal use instance identity`() {
        data class Factor(val value: Double) : MaturationMultiplier {
            override fun getMultiplier(citadel: ReadableHandle) = value
        }
        val fixture = HealthFixture()
        fixture.clock.timeMillis = 2_000L
        val service = fixture.maturation
        val first = Factor(2.0)
        val second = Factor(2.0)
        service.registerMultiplier(first)
        service.registerMultiplier(first)
        assertEquals(0.5, service.progress(fixture.citadel))
        service.registerMultiplier(second)
        assertEquals(0.25, service.progress(fixture.citadel))
        assertTrue(service.unregisterMultiplier(first))
        assertFalse(service.unregisterMultiplier(first))
        assertEquals(0.5, service.progress(fixture.citadel))
        assertTrue(service.unregisterMultiplier(second))
        assertTrue(service.isMature(fixture.citadel))
    }

    @Test
    fun `calculation retains multiplier snapshot and reads live factors next time`() {
        val fixture = HealthFixture()
        fixture.clock.timeMillis = 2_000L
        val service = fixture.maturation
        var factor = 2.0
        val second = MaturationMultiplier { 3.0 }
        service.registerMultiplier { service.unregisterMultiplier(second); factor }
        service.registerMultiplier(second)
        assertEquals(1.0 / 6.0, service.progress(fixture.citadel))
        factor = 4.0
        assertEquals(0.25, service.progress(fixture.citadel))
    }

    @Test
    fun `invalid factors are rejected while positive fractions and submillisecond durations work`() {
        val fixture = HealthFixture()
        val service = fixture.maturation
        for (factor in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val multiplier = MaturationMultiplier { factor }
            service.registerMultiplier(multiplier)
            assertFailsWith<IllegalArgumentException> { service.progress(fixture.citadel) }
            service.unregisterMultiplier(multiplier)
        }
        service.registerMultiplier { 0.5 }
        fixture.clock.timeMillis = 1_250L
        assertEquals(0.5, service.progress(fixture.citadel))
        assertTrue(service.unregisterDefinition(HealthFixture.TYPE))
        service.registerDefinition(HealthFixture.TYPE, MaturationDefinition(Duration.ofNanos(500_000)))
        fixture.clock.timeMillis = 1_000L
        assertEquals(0.0, service.progress(fixture.citadel))
        fixture.clock.timeMillis = 1_001L
        assertTrue(service.isMature(fixture.citadel))

        service.registerMultiplier { Double.MIN_VALUE }
        fixture.clock.timeMillis = 999L
        assertFalse(service.isMature(fixture.citadel))
        fixture.clock.timeMillis = 1_000L
        assertFalse(service.isMature(fixture.citadel))
        fixture.clock.timeMillis = 1_001L
        assertTrue(service.isMature(fixture.citadel))
    }
}
