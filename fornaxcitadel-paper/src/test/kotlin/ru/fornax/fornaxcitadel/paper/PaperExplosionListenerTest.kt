package ru.fornax.fornaxcitadel.paper

import org.bukkit.ExplosionResult
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.Entity
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.ExplosionPrimeEvent
import ru.fornax.fornaxcitadel.core.citadel.CitadelLifecycleListener
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.citadel.RemovalCause
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionDamageAction
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionDamageAction.ExplosionSource
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionPowerSettings
import java.util.UUID
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperExplosionListenerTest {
    @Test
    fun `entity snapshot damage uses the block position without adding a block center offset`() {
        val fixture = PaperTestFixture()
        fixture.place()
        val fractional = fixture.block().location.add(0.5, 0.5, 0.5)
        dispatch(fixture.explosionListener, fixture.entityExplosion(fixture.block(), center = fractional), fixture.plugin)
        val expectedDamage = 40.0 * (1.0 - sqrt(0.75) / 8.0) * (1.0 - sqrt(0.75) / 8.0)
        assertEquals(100.0 - expectedDamage, fixture.health(), 1.0e-10)
    }

    @Test
    fun `entity event and block snapshot locations produce typed damage while shielding reinforced blocks`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.place()
        val ordinary = fixture.block(3, 64, 2)
        val center = Location(fixture.world, 1.0, 64.0, 0.0)
        val entity = explodingEntity()
        val source = fixture.snapshot(center = center)
        val entityEvent = fixture.entityExplosion(fixture.block(), ordinary, sourceEntity = entity, center = center)
        val blockEvent = fixture.blockExplosion(fixture.block(), ordinary, source = source)
        val actions = mutableListOf<ExplosionDamageAction>()
        fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun validate(action: DamageAction): ActionValidator.Result {
                actions.add(assertIs<ExplosionDamageAction>(action))
                return ActionValidator.Result.Neutral
            }
        })

        dispatch(fixture.explosionListener, entityEvent, fixture.plugin)
        dispatch(fixture.explosionListener, blockEvent, fixture.plugin)

        assertEquals(listOf(ordinary), entityEvent.blockList())
        assertEquals(listOf(ordinary), blockEvent.blockList())
        assertEquals(55.0, fixture.health())
        assertEquals(listOf(22.5, 22.5), actions.map { it.damage })
        assertSame(entity, assertIs<ExplosionSource.Entity>(actions[0].source).entity)
        assertSame(source, assertIs<ExplosionSource.Block>(actions[1].source).blockState)
        assertTrue(actions.all { it.context.actorId == null && it.source.power == 4.0f })
        assertEquals(0, fixture.vanillaBlockChanges)
    }

    @Test
    fun `prime radius overrides fallback and is forgotten after normal cancelled or cancelled prime events`() {
        val fixture = PaperTestFixture(explosionPowers = ExplosionPowerSettings(defaultEntityPower = 3.0f))
        fixture.place()
        val entity = explodingEntity()
        dispatch(fixture.explosionListener, ExplosionPrimeEvent(entity, 2.0f, false), fixture.plugin)
        assertEquals(2.0f, fixture.explosionService.getEntityExplosionPower(entity.uniqueId))

        dispatch(fixture.explosionListener, fixture.entityExplosion(fixture.block(), sourceEntity = entity), fixture.plugin)

        assertEquals(80.0, fixture.health())
        assertEquals(3.0f, fixture.explosionService.getEntityExplosionPower(entity.uniqueId))
        dispatch(fixture.explosionListener, ExplosionPrimeEvent(entity, 7.0f, false), fixture.plugin)
        val cancelled = fixture.entityExplosion(fixture.block(), sourceEntity = entity).apply { isCancelled = true }
        dispatch(fixture.explosionListener, cancelled, fixture.plugin)
        assertEquals(listOf(fixture.block()), cancelled.blockList())
        assertEquals(80.0, fixture.health())
        assertEquals(3.0f, fixture.explosionService.getEntityExplosionPower(entity.uniqueId))

        dispatch(fixture.explosionListener, ExplosionPrimeEvent(entity, 4.0f, false), fixture.plugin)
        dispatch(fixture.explosionListener, ExplosionPrimeEvent(entity, 6.0f, false).apply { isCancelled = true }, fixture.plugin)
        assertEquals(3.0f, fixture.explosionService.getEntityExplosionPower(entity.uniqueId))
        dispatch(fixture.explosionListener, fixture.entityExplosion(fixture.block(), sourceEntity = entity), fixture.plugin)
        assertEquals(50.0, fixture.health())
    }

    @Test
    fun `orphaned prime power expires next tick and older cleanup cannot erase a newer prime`() {
        val fixture = PaperTestFixture(explosionPowers = ExplosionPowerSettings(defaultEntityPower = 3.0f))
        val entity = explodingEntity()
        dispatch(fixture.explosionListener, ExplosionPrimeEvent(entity, 6.0f, false), fixture.plugin)
        val olderCleanup = fixture.scheduledTasks.removeAt(0)
        dispatch(fixture.explosionListener, ExplosionPrimeEvent(entity, 6.0f, false), fixture.plugin)

        olderCleanup.action.run()

        assertEquals(6.0f, fixture.explosionService.getEntityExplosionPower(entity.uniqueId))
        fixture.runScheduledTasks()
        assertEquals(3.0f, fixture.explosionService.getEntityExplosionPower(entity.uniqueId))
    }

    @Test
    fun `block power overrides and zero fallbacks still preserve reinforced physical blocks`() {
        val fixture = PaperTestFixture(explosionPowers = ExplosionPowerSettings(
            defaultEntityPower = 0.0f, defaultBlockPower = 0.0f, blockPowers = mapOf(Material.TNT to 2.0f)
        ))
        fixture.place()
        val powered = fixture.blockExplosion(fixture.block(), source = fixture.snapshot(Material.TNT))
        dispatch(fixture.explosionListener, powered, fixture.plugin)
        assertEquals(80.0, fixture.health())
        assertTrue(powered.blockList().isEmpty())

        val zeroEntity = fixture.entityExplosion(fixture.block())
        val zeroBlock = fixture.blockExplosion(fixture.block())
        dispatch(fixture.explosionListener, zeroEntity, fixture.plugin)
        dispatch(fixture.explosionListener, zeroBlock, fixture.plugin)
        assertEquals(80.0, fixture.health())
        assertTrue(zeroEntity.blockList().isEmpty())
        assertTrue(zeroBlock.blockList().isEmpty())
        assertEquals(0, fixture.vanillaBlockChanges)
    }

    @Test
    fun `cancelled ordinary block explosions neither damage nor reconcile intact reinforcement`() {
        val fixture = PaperTestFixture()
        fixture.place()
        val ordinary = fixture.block(3, 64, 2)
        val event = fixture.blockExplosion(fixture.block(), ordinary).apply { isCancelled = true }

        dispatch(fixture.explosionListener, event, fixture.plugin)

        assertEquals(listOf(fixture.block(), ordinary), event.blockList())
        assertEquals(100.0, fixture.health())
    }

    @Test
    fun `unavailable core chunks are shielded but unsupported coordinates retain vanilla behavior`() {
        val fixture = PaperTestFixture(load = false)
        val unsupported = fixture.block(x = Int.MAX_VALUE)
        val entityEvent = fixture.entityExplosion(fixture.block(), unsupported)
        val blockEvent = fixture.blockExplosion(fixture.block(), unsupported)

        dispatch(fixture.explosionListener, entityEvent, fixture.plugin)
        dispatch(fixture.explosionListener, blockEvent, fixture.plugin)

        assertEquals(listOf(unsupported), entityEvent.blockList())
        assertEquals(listOf(unsupported), blockEvent.blockList())
        fixture.manager.loadChunk(fixture.worldId, 0, 0).join()
        val loaded = fixture.entityExplosion(fixture.block())
        dispatch(fixture.explosionListener, loaded, fixture.plugin)
        assertEquals(listOf(fixture.block()), loaded.blockList())
    }

    @Test
    fun `only typed environmental damage bypasses player permission and world checks still apply`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.place()
        val source = ExplosionSource.Entity(fixture.block().location, 4.0f, explodingEntity())
        val typed = ExplosionDamageAction(fixture.position, source, 10.0)
        val spoofed = DamageAction(ActionContext(null, Key("paper", "explosion")), fixture.position, 10.0)

        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(spoofed))
        assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(typed))
        assertEquals(90.0, fixture.health())
        fixture.worldChunkLoaded = false
        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(typed))
        fixture.worldChunkLoaded = true
        fixture.worldAvailable = false
        assertEquals(ActionResult.DENIED, fixture.module.actions.execute(typed))
        assertEquals(90.0, fixture.health())
    }

    @Test
    fun `common damage validators and invulnerable types can reject explosions without allowing vanilla destruction`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.place()
        val ordinary = fixture.block(3, 64, 2)
        val event = fixture.entityExplosion(fixture.block(), ordinary)
        var validated = false
        fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun validate(action: DamageAction): ActionValidator.Result {
                validated = true
                assertEquals(listOf(ordinary), event.blockList())
                return ActionValidator.Result.Rejected
            }
        })

        dispatch(fixture.explosionListener, event, fixture.plugin)

        assertTrue(validated)
        assertEquals(100.0, fixture.health())
        assertEquals(listOf(ordinary), event.blockList())

        val invulnerable = PaperTestFixture(permissions = true)
        invulnerable.installer.permissions.add("fornaxcitadel.admin")
        invulnerable.place(PaperCitadelTypes.INVULNERABLE)
        val invulnerableEvent = invulnerable.entityExplosion(invulnerable.block())
        dispatch(invulnerable.explosionListener, invulnerableEvent, invulnerable.plugin)
        assertNotNull(invulnerable.manager.findCitadel(invulnerable.worldId, 1, 64, 2))
        assertTrue(invulnerableEvent.blockList().isEmpty())
    }

    @Test
    fun `all targets are shielded before callbacks and one failing target does not prevent a later lethal hit`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.place()
        val second = fixture.block(3, 64, 2)
        fixture.placeAt(second)
        val ordinary = fixture.block(5, 64, 2)
        val entity = explodingEntity()
        dispatch(fixture.explosionListener, ExplosionPrimeEvent(entity, 20.0f, false), fixture.plugin)
        val event = fixture.entityExplosion(fixture.block(), second, ordinary,
            sourceEntity = entity, center = Location(fixture.world, 2.0, 64.0, 2.0))
        val visited = mutableListOf<Int>()
        fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun validate(action: DamageAction): ActionValidator.Result {
                assertEquals(listOf(ordinary), event.blockList())
                visited.add(action.citadelX)
                if (action.citadelX == 1) error("Protection lookup failed for the first target")
                return ActionValidator.Result.Neutral
            }
        })

        dispatch(fixture.explosionListener, event, fixture.plugin)

        assertEquals(listOf(1, 3), visited)
        assertEquals(100.0, fixture.health())
        assertNull(fixture.manager.findCitadel(fixture.worldId, 3, 64, 2))
        assertEquals(listOf(ordinary), event.blockList())
        assertEquals(4.0f, fixture.explosionService.getEntityExplosionPower(entity.uniqueId))
        assertEquals(0, fixture.vanillaBlockChanges)
    }

    @Test
    fun `mutating one action source cannot move the explosion center for a later target`() {
        val fixture = PaperTestFixture(permissions = true)
        fixture.place()
        val second = fixture.block(3, 64, 2)
        fixture.placeAt(second)
        val center = Location(fixture.world, 2.0, 64.0, 2.0)
        fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<ExplosionDamageAction> {
            override val actionClass = ExplosionDamageAction::class.java
            override fun validate(action: ExplosionDamageAction): ActionValidator.Result {
                assertEquals(center, action.source.centerLocation)
                if (action.citadelX == 1) action.source.centerLocation.x = 1000.0
                return ActionValidator.Result.Neutral
            }
        })

        dispatch(fixture.explosionListener, fixture.entityExplosion(fixture.block(), second, center = center), fixture.plugin)

        assertEquals(69.375, fixture.health())
        val secondHandle = assertNotNull(fixture.manager.findCitadel(fixture.worldId, 3, 64, 2))
        assertEquals(69.375, secondHandle.getComponent(Health::class.java)?.health)
        assertEquals(2.0, center.x)
    }

    @Test
    fun `cancelled bed and anchor explosions reconcile removed sources with world edit cause`() {
        for ((material, part) in listOf(
            Material.RESPAWN_ANCHOR to null,
            Material.RED_BED to Bed.Part.FOOT,
            Material.BLUE_BED to Bed.Part.HEAD
        )) {
            val fixture = PaperTestFixture(permissions = true)
            fixture.placeAt(fixture.block())
            val partner = part?.let { fixture.block(1, 64, if (it == Bed.Part.HEAD) 1 else 3) }
            if (partner != null) fixture.placeAt(partner)
            val ordinaryTarget = fixture.block(3, 64, 2)
            fixture.placeAt(ordinaryTarget)
            val removed = mutableListOf<Pair<CitadelPosition, RemovalCause>>()
            fixture.module.lifecycle.addListener(object : CitadelLifecycleListener {
                override fun onRemoved(position: CitadelPosition, runtimeId: Int, cause: RemovalCause) {
                    removed.add(position to cause)
                }
            })
            val event = fixture.blockExplosion(ordinaryTarget,
                source = fixture.snapshot(material, bedPart = part)).apply { isCancelled = true }

            dispatch(fixture.explosionListener, event, fixture.plugin)

            assertNull(fixture.manager.findCitadel(fixture.worldId, 1, 64, 2))
            if (partner != null) assertNull(fixture.manager.findCitadel(fixture.worldId, partner.x, partner.y, partner.z))
            assertEquals(if (partner == null) 1 else 2, removed.size)
            assertTrue(removed.all { it.second == RemovalCause.WORLD_EDIT })
            val intact = assertNotNull(fixture.manager.findCitadel(fixture.worldId, 3, 64, 2))
            assertEquals(100.0, intact.getComponent(Health::class.java)?.health)
            assertEquals(listOf(ordinaryTarget), event.blockList())
            assertEquals(0, fixture.vanillaBlockChanges)
        }
    }

    private fun explodingEntity(id: UUID = UUID(10L, 20L)): Entity = paperProxy("exploded entity") { method, _ ->
        // EntityExplodeEvent retains a snapshot location; the removed entity cannot supply one.
        if (method.name == "getUniqueId") id else unsupported(method)
    }

    private fun PaperTestFixture.entityExplosion(
        vararg blocks: Block,
        sourceEntity: Entity = explodingEntity(),
        center: Location = block().location
    ): EntityExplodeEvent = EntityExplodeEvent(sourceEntity, center, blocks.toMutableList(), 1.0f, ExplosionResult.DESTROY)

    private fun PaperTestFixture.blockExplosion(
        vararg blocks: Block,
        source: BlockState = snapshot()
    ): BlockExplodeEvent = BlockExplodeEvent(source.block, source, blocks.toMutableList(), 1.0f, ExplosionResult.DESTROY)

    private fun PaperTestFixture.snapshot(
        material: Material = Material.STONE,
        center: Location = block().location,
        bedPart: Bed.Part? = null
    ): BlockState {
        val data: BlockData = if (bedPart == null) {
            paperProxy("ordinary block data") { method, _ -> unsupported(method) }
        } else {
            paperProxy<Bed>("exploded bed data") { method, _ ->
                when (method.name) {
                    "getPart" -> bedPart
                    "getFacing" -> BlockFace.SOUTH
                    else -> unsupported(method)
                }
            }
        }
        return paperProxy("exploded block snapshot") { method, _ ->
            when (method.name) {
                "getType" -> material
                "getBlockData" -> data
                "getLocation" -> center.clone()
                "getBlock" -> block(center.blockX, center.blockY, center.blockZ)
                else -> unsupported(method)
            }
        }
    }

    private fun PaperTestFixture.placeAt(block: Block) {
        // Source cleanup also covers old data on beds/anchors, outside today's placement allowlist.
        assertEquals(ActionResult.SUCCESS, module.place.place(PlaceAction(context(installer),
            CitadelPosition(worldId, block.x, block.y, block.z), PaperCitadelTypes.REINFORCED)))
    }
}
