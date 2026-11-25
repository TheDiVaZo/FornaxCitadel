package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Material
import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.DamageModifier
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.modules.health.Health
import ru.fornax.fornaxcitadel.modules.health.HealthDefinition
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionPosition
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionSource
import ru.fornax.fornaxcitadel.paper.modules.health.PaperDamageValidator
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelDamagePolicy
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypeSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.HealthSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperDamageValidationTest {
    @Test
    fun `zero player damage uses normal block destruction unless explicitly invulnerable`() {
        for (protected in listOf(false, true)) PaperTestFixture(permissions = true).use { f ->
            val type = Key("fornaxcitadel", if (protected) "invulnerable" else "reinforced")
            f.installer.permissions.add("fornaxcitadel.admin")
            f.place(type)
            assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(Health::class.java)
            f.module.coreAddon.health.unregisterDefinition(type)
            f.playerDamage.register { _, _, _ -> 0.0 }
            assertTrue(f.breakingService.breakBlock(f.stranger.player, f.block()))
            assertEquals(protected, f.module.citadels.findProtecting(f.position) != null)
            assertEquals(if (protected) Material.STONE else Material.AIR, f.block().type)
            assertEquals(if (protected) 0 else 1, f.playerBreaks.size)
        }
    }

    @Test
    fun `unbounded healthless reinforcement is destroyed by player and explosion damage including zero`() {
        for (kind in listOf("missing", "unknown", "allow")) for (damage in listOf(0.0, 1.0)) {
            for (explosion in listOf(false, true)) PaperTestFixture(permissions = true).use { f ->
                f.place()
                val target = assertNotNull(f.module.citadels.findProtecting(f.position))
                assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(Health::class.java)
                when (kind) {
                    "missing" -> assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(CitadelKind::class.java)
                    "unknown" -> target.editComponent(CitadelKind::class.java) { it.definitionKey = Key("external", "unknown") }
                    "allow" -> f.module.coreAddon.health.unregisterDefinition(ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes.REINFORCED)
                }
                val context = if (explosion) ActionContext(null, ExplosionSource.Block(
                    ExplosionPosition(f.worldId, 1.0, 64.0, 2.0), 0.0f, Material.TNT)) else f.context(f.stranger)
                val action = DamageAction(context, f.position, damage)
                assertEquals(ActionResult.SUCCESS, f.module.actions.execute(action), "$kind $damage $explosion")
                assertFalse(target.isCurrent)
                assertEquals(ActionResult.DENIED, f.module.actions.execute(action))
            }
        }
    }

    @Test
    fun `no health does not bypass deny policy permissions or vetoes`() {
        for (reason in listOf("policy", "permission", "veto")) PaperTestFixture(permissions = true).use { f ->
            val type = Key("fornaxcitadel", if (reason == "policy") "invulnerable" else "reinforced")
            f.installer.permissions.add("fornaxcitadel.admin")
            f.place(type)
            assertNotNull(f.manager.findCitadel(f.worldId, 1, 64, 2)).removeComponent(Health::class.java)
            f.module.coreAddon.health.unregisterDefinition(type)
            if (reason == "permission") f.stranger.permissions.remove("fornaxcitadel.damage")
            f.module.actions.registerValidator(object : ActionValidator<DamageAction> {
                override val actionClass = DamageAction::class.java
                override fun validate(action: DamageAction) = if (reason == "veto") ActionValidator.Result.Rejected else ActionValidator.Result.Success
            })
            f.manager.saveAll().join()
            val saves = f.backend.saves
            assertEquals(ActionResult.DENIED, f.module.actions.execute(DamageAction(f.context(f.stranger), f.position, 0.0)), reason)
            val target = assertNotNull(f.module.citadels.findProtecting(f.position))
            assertNull(target.getComponent(Health::class.java))
            f.manager.saveAll().join()
            assertEquals(saves, f.backend.saves)
        }
    }

    @Test
    fun `unknown types with a live maximum accept first player and explosion damage without saved health`() {
        for (explosion in listOf(false, true)) PaperTestFixture(permissions = true).use { f ->
            val type = Key("external", "lazy_health")
            f.module.registerDefinition(CitadelDefinition(type, emptyList()))
            f.place(type)
            val target = assertNotNull(f.module.citadels.findProtecting(f.position))
            val context = if (explosion) ActionContext(null, ExplosionSource.Block(
                ExplosionPosition(f.worldId, 1.0, 64.0, 2.0), 4.0f, Material.TNT)) else f.context(f.stranger)
            val action = DamageAction(context, f.position, 10.0)
            assertNull(target.getComponent(Health::class.java))
            f.module.coreAddon.health.registerDefinition(type, HealthDefinition(100.0))
            assertTrue(f.module.actions.isAllowed(action))
            assertNull(target.getComponent(Health::class.java))
            assertEquals(ActionResult.SUCCESS, f.module.actions.execute(action))
            assertEquals(90.0, target.getComponent(Health::class.java)?.health)
        }
    }

    @Test
    fun `damage policy vetoes first health creation even with a maximum and an external grant`() {
        PaperTestFixture(permissions = true).use { f ->
            val type = ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes.INVULNERABLE
            f.installer.permissions.add("fornaxcitadel.admin")
            f.place(type)
            val target = assertNotNull(f.module.citadels.findProtecting(f.position))
            f.module.coreAddon.health.registerDefinition(type, HealthDefinition(100.0))
            f.module.actions.registerValidator(object : ActionValidator<DamageAction> {
                override val actionClass = DamageAction::class.java
                override fun validate(action: DamageAction) = ActionValidator.Result.Success
            })
            f.manager.saveAll().join()
            val saves = f.backend.saves
            assertEquals(100.0, f.module.coreAddon.health.currentHealth(target))
            assertEquals(ActionResult.DENIED, f.module.actions.execute(DamageAction(f.context(f.stranger), f.position, 10.0)))
            assertNull(target.getComponent(Health::class.java))
            f.manager.saveAll().join()
            assertEquals(saves, f.backend.saves)
        }
    }

    @Test
    fun `unknown types with saved HP accept player and explosion damage without a maximum`() {
        PaperTestFixture(permissions = true).use { fixture ->
            val type = Key("external", "reinforcement")
            fixture.module.registerDefinition(CitadelDefinition(type, listOf(CitadelInitializer { _, draft -> draft.addComponent(Health::class.java, Health(100.0)) })))
            fixture.place(type)
            val ordinary = DamageAction(fixture.context(fixture.stranger), fixture.position, 10.0)
            val source = ExplosionSource.Block(ExplosionPosition(fixture.worldId, 1.0, 64.0, 2.0),
                4.0f, Material.TNT)
            val explosion = DamageAction(ActionContext(null, source), fixture.position, 10.0)

            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(ordinary))
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(explosion))
            assertEquals(80.0, fixture.health())

            fixture.module.actions.registerValidator(object : ActionValidator<DamageAction> {
                override val actionClass = DamageAction::class.java
                override fun validate(action: DamageAction): ActionValidator.Result {
                    val kind = fixture.module.citadels.findProtecting(action.position)
                        ?.getComponent(CitadelKind::class.java)
                    return if (action.mode == DamageAction.Mode.DAMAGE && kind?.definitionKey == type)
                        ActionValidator.Result.Success else ActionValidator.Result.Neutral
                }
            })

            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(ordinary))
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(explosion))
            assertEquals(60.0, fixture.health())
        }
    }

    @Test
    fun `configured damage denial vetoes ordinary damage but allows authorized creative destruction even with health`() {
        val settings = CitadelTypesSettings(mapOf("protected" to CitadelTypeSettings(
            Key("minecraft", "nether_star"), 999, CitadelDamagePolicy.DENY, HealthSettings(100.0))))
        PaperTestFixture(permissions = true, citadelSettings = settings).use { fixture ->
            fixture.installer.permissions.add("fornaxcitadel.admin")
            fixture.place(Key("fornaxcitadel", "protected"))
            fixture.module.actions.registerValidator(object : ActionValidator<DamageAction> {
                override val actionClass = DamageAction::class.java
                override fun validate(action: DamageAction) = ActionValidator.Result.Success
            })

            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(
                DamageAction(fixture.context(fixture.stranger), fixture.position, 10.0)))
            assertEquals(100.0, fixture.health())

            val target = checkNotNull(fixture.module.citadels.findProtecting(fixture.position))
            fixture.module.coreAddon.health.registerModifier(DamageModifier { _, _, _ ->
                error("Creative destruction must not calculate damage")
            })
            fixture.stranger.gameMode = GameMode.CREATIVE
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(
                DamageAction(fixture.context(fixture.stranger), fixture.position, 0.0, DamageAction.Mode.DESTROY)))
            assertFalse(target.isCurrent)
        }
    }

    @Test
    fun `damage authorization lifecycle preserves external validators and is independent of breaking listeners`() {
        PaperTestFixture(permissions = true).use { fixture ->
            fixture.place()
            val action = DamageAction(fixture.context(fixture.stranger), fixture.position, 10.0)
            val actions = fixture.module.actions
            val external = object : ActionValidator<DamageAction> {
                override val actionClass = DamageAction::class.java
                override fun validate(action: DamageAction) = ActionValidator.Result.Neutral
            }
            actions.registerValidator(external)
            fixture.breakingService.disable()
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(action))

            repeat(2) {
                fixture.damageValidationService.disable()
                fixture.damageValidationService.disable()
                assertFalse(actions.isAllowed(action))
                assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))
                fixture.damageValidationService.enable()
                fixture.damageValidationService.enable()
                assertTrue(actions.isAllowed(action))
            }

            assertTrue(actions.unregisterValidator(external))
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(action))
            assertEquals(80.0, fixture.health())
        }
    }

    @Test
    fun `damage validation rejects missing and unloaded targets without loading chunks`() {
        PaperTestFixture(load = false).use { fixture ->
            val validator = PaperDamageValidator(fixture.module.citadels, fixture.citadelTypes, true)
            val action = DamageAction(fixture.context(fixture.stranger), fixture.position, 10.0)
            assertSame(ActionValidator.Result.Rejected, validator.validate(action))
            assertFalse(fixture.manager.isChunkLoaded(fixture.worldId, 0, 0))

            fixture.loadChunk(0, 0)
            assertSame(ActionValidator.Result.Rejected, validator.validate(action))
            fixture.place()
            assertSame(ActionValidator.Result.Success, validator.validate(action))
            fixture.manager.unloadChunk(fixture.worldId, 0, 0).join()
            assertSame(ActionValidator.Result.Rejected, validator.validate(action))
            assertFalse(fixture.manager.isChunkLoaded(fixture.worldId, 0, 0))
        }
    }
}
