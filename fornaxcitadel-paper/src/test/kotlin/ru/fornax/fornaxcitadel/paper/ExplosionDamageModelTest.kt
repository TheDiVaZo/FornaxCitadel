package ru.fornax.fornaxcitadel.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockState
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionPosition
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionSource
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionPowerSettings
import ru.fornax.fornaxcitadel.paper.modules.explosion.PaperExplosionService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExplosionDamageModelTest {
    @Test
    fun `damage retains quadratic distance falloff and vanishes at the radius`() {
        val fixture = PaperTestFixture()
        val center = ExplosionPosition(fixture.worldId, 0.0, 64.0, 0.0)

        assertEquals(40.0, PaperExplosionService.calculateDamage(4.0f, center, center))
        assertEquals(10.0, PaperExplosionService.calculateDamage(4.0f, center, center.copy(x = 4.0)))
        assertEquals(0.0, PaperExplosionService.calculateDamage(4.0f, center, center.copy(x = 8.0)))
        assertEquals(0.0, PaperExplosionService.calculateDamage(4.0f, center, center.copy(x = 9.0)))
        assertEquals(0.0, PaperExplosionService.calculateDamage(0.0f, center, center))
        assertEquals(12.5, PaperExplosionService.calculateDamage(5.0f, center, center.copy(x = 3.0, y = 68.0)))
    }

    @Test
    fun `defaults cover every bed and anchor while explicit settings control fallbacks`() {
        val defaults = ExplosionPowerSettings()
        val beds = setOf(
            Material.WHITE_BED, Material.ORANGE_BED, Material.MAGENTA_BED, Material.LIGHT_BLUE_BED,
            Material.YELLOW_BED, Material.LIME_BED, Material.PINK_BED, Material.GRAY_BED,
            Material.LIGHT_GRAY_BED, Material.CYAN_BED, Material.PURPLE_BED, Material.BLUE_BED,
            Material.BROWN_BED, Material.GREEN_BED, Material.RED_BED, Material.BLACK_BED, Material.STRAW_BED
        )
        assertEquals(beds + Material.RESPAWN_ANCHOR, defaults.blockPowers.keys)
        assertTrue(defaults.blockPowers.values.all { it == 5.0f })
        assertEquals(4.0f, defaults.defaultEntityPower)
        assertEquals(4.0f, defaults.getBlockPower(Material.STONE))

        val custom = ExplosionPowerSettings(2.0f, 3.0f,
            mapOf(Material.STONE to 7.0f, Material.RESPAWN_ANCHOR to 0.0f))
        assertEquals(2.0f, custom.defaultEntityPower)
        assertEquals(7.0f, custom.getBlockPower(Material.STONE))
        assertEquals(0.0f, custom.getBlockPower(Material.RESPAWN_ANCHOR))
        assertEquals(3.0f, custom.getBlockPower(Material.COBBLESTONE))
    }

    @Test
    fun `paper settings load explosion powers and merge partial material overrides`() {
        val shipped = checkNotNull(javaClass.getResourceAsStream("/config.yml")).bufferedReader().use {
            YamlConfiguration.loadConfiguration(it)
        }
        assertEquals(ExplosionPowerSettings(), PaperSettings.read(shipped).explosionPowers)
        val config = YamlConfiguration().apply {
            loadFromString("""
                placement:
                  block-list:
                    type: WHITELIST
                    blocks: [STONE]
                explosions:
                  default-entity-power: 2.5
                  default-block-power: 3.0
                  block-powers:
                    red_bed: 6.5
                    STONE: 8.0
            """.trimIndent())
        }

        val settings = PaperSettings.read(config).explosionPowers

        assertEquals(2.5f, settings.defaultEntityPower)
        assertEquals(3.0f, settings.getBlockPower(Material.COBBLESTONE))
        assertEquals(6.5f, settings.getBlockPower(Material.RED_BED))
        assertEquals(8.0f, settings.getBlockPower(Material.STONE))
        assertEquals(5.0f, settings.getBlockPower(Material.BLUE_BED))
        assertEquals(5.0f, settings.getBlockPower(Material.RESPAWN_ANCHOR))
    }

    @Test
    fun `invalid explosion powers and material mappings are rejected instead of using defaults`() {
        val paths = listOf("explosions.default-entity-power", "explosions.default-block-power",
            "explosions.block-powers.STONE")
        for (path in paths) {
            for (value in listOf("strong", -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
                val config = YamlConfiguration().apply { set(path, value) }
                assertFailsWith<IllegalArgumentException>("$path=$value") { ExplosionPowerSettings.read(config) }
            }
        }
        val unknown = YamlConfiguration().apply { set("explosions.block-powers.NOT_A_MATERIAL", 5.0) }
        assertFailsWith<IllegalArgumentException> { ExplosionPowerSettings.read(unknown) }
        val wrongShape = YamlConfiguration().apply { set("explosions.block-powers", "STONE") }
        assertFailsWith<IllegalArgumentException> { ExplosionPowerSettings.read(wrongShape) }
        assertFailsWith<IllegalArgumentException> { ExplosionPowerSettings(defaultEntityPower = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { ExplosionPowerSettings(defaultBlockPower = -1.0f) }
        assertFailsWith<IllegalArgumentException> {
            ExplosionPowerSettings(blockPowers = mapOf(Material.STONE to Float.POSITIVE_INFINITY))
        }
    }

    @Test
    fun `queued damage retains explosion snapshots after live source data changes`() {
        val fixture = PaperTestFixture()
        fixture.place()
        val center = Location(fixture.world, 1.0, 64.0, 2.0)
        val originalEntityId = UUID(10L, 20L)
        var entityId = originalEntityId
        var entityType = EntityType.CREEPER
        var material = Material.TNT
        val entity = paperProxy<Entity>("source entity") { method, _ ->
            when (method.name) {
                "getUniqueId" -> entityId
                "getType" -> entityType
                else -> unsupported(method)
            }
        }
        val blockState = paperProxy<BlockState>("source block") { method, _ ->
            when (method.name) {
                "getLocation" -> center
                "getType" -> material
                else -> unsupported(method)
            }
        }
        val sources = listOf(
            ExplosionSource.fromEntity(entity, center, 4.0f),
            ExplosionSource.fromBlock(blockState, 5.0f)
        )
        val observed = mutableListOf<DamageAction>()
        fixture.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun validate(action: DamageAction): ActionValidator.Result {
                observed.add(action)
                return ActionValidator.Result.Neutral
            }
        })
        for (source in sources) {
            fixture.module.actions.post(DamageAction(ActionContext(fixture.installer.id, source), fixture.position, 12.0))
        }

        center.x = 1000.0
        center.world = null
        entityId = UUID(30L, 40L)
        entityType = EntityType.COW
        material = Material.AIR

        assertEquals(2, fixture.module.actions.drainPosted())
        assertEquals(76.0, fixture.health())
        assertEquals(2, observed.size)
        assertTrue(observed.all { it.javaClass == DamageAction::class.java && it.type == DamageAction.KEY })
        assertTrue(observed.all { it.context.actorId == fixture.installer.id })
        val entitySource = assertIs<ExplosionSource.Entity>(observed[0].context.source)
        val blockSource = assertIs<ExplosionSource.Block>(observed[1].context.source)
        assertEquals(originalEntityId, entitySource.entityId)
        assertEquals(EntityType.CREEPER, entitySource.entityType)
        assertEquals(Material.TNT, blockSource.material)
        assertEquals(listOf(4.0f, 5.0f), sources.map { it.power })
        assertTrue(sources.all { it.key == Key("paper", "explosion") })
        assertTrue(sources.all { it.position == ExplosionPosition(fixture.worldId, 1.0, 64.0, 2.0) })
    }

    @Test
    fun `explosion snapshots reject invalid strength and positions before actions are created`() {
        val fixture = PaperTestFixture()
        val center = ExplosionPosition(fixture.worldId, 1.0, 64.0, 2.0)
        for (power in listOf(-1.0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                ExplosionSource.Entity(center, power, UUID(10L, 20L), EntityType.CREEPER)
            }
            assertFailsWith<IllegalArgumentException> {
                ExplosionSource.Block(center, power, Material.TNT)
            }
        }
        for (invalidCenter in listOf(
            Location(fixture.world, Double.NaN, 64.0, 2.0),
            Location(fixture.world, 1.0, Double.POSITIVE_INFINITY, 2.0),
            Location(fixture.world, 1.0, 64.0, Double.NEGATIVE_INFINITY),
            Location(null, 1.0, 64.0, 2.0)
        )) {
            assertFailsWith<IllegalArgumentException> {
                ExplosionPosition.from(invalidCenter)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            center.copy(x = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            PaperExplosionService.calculateDamage(4.0f, center, center.copy(worldId = UUID(9L, 10L)))
        }
    }
}
