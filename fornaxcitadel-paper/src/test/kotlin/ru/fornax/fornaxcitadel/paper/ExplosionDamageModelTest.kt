package ru.fornax.fornaxcitadel.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockState
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionDamageAction
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionDamageAction.ExplosionSource
import ru.fornax.fornaxcitadel.paper.modules.explosion.ExplosionPowerSettings
import ru.fornax.fornaxcitadel.paper.modules.explosion.PaperExplosionService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExplosionDamageModelTest {
    @Test
    fun `damage retains quadratic distance falloff and vanishes at the radius`() {
        val fixture = PaperTestFixture()
        val center = Location(fixture.world, 0.0, 64.0, 0.0)

        assertEquals(40.0, PaperExplosionService.calculateDamage(4.0f, center, center))
        assertEquals(10.0, PaperExplosionService.calculateDamage(4.0f, center, center.clone().add(4.0, 0.0, 0.0)))
        assertEquals(0.0, PaperExplosionService.calculateDamage(4.0f, center, center.clone().add(8.0, 0.0, 0.0)))
        assertEquals(0.0, PaperExplosionService.calculateDamage(4.0f, center, center.clone().add(9.0, 0.0, 0.0)))
        assertEquals(0.0, PaperExplosionService.calculateDamage(0.0f, center, center))
        assertEquals(12.5, PaperExplosionService.calculateDamage(5.0f, center, center.clone().add(3.0, 4.0, 0.0)))
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
    fun `explosion actions retain core damage routing and isolate each source location`() {
        val fixture = PaperTestFixture()
        val entity = paperProxy<Entity>("source entity") { method, _ -> unsupported(method) }
        val blockState = paperProxy<BlockState>("source block") { method, _ -> unsupported(method) }
        val sources = listOf(
            ExplosionSource.Entity(Location(fixture.world, 1.0, 64.0, 2.0), 4.0f, entity),
            ExplosionSource.Block(Location(fixture.world, 1.0, 64.0, 2.0), 5.0f, blockState)
        )
        for (source in sources) {
            val first = ExplosionDamageAction(fixture.position, source, 12.0)
            val second = ExplosionDamageAction(fixture.position, source, 6.0)

            source.centerLocation.x = 100.0
            assertEquals(1.0, first.source.centerLocation.x)
            first.source.centerLocation.x = 200.0
            assertEquals(1.0, second.source.centerLocation.x)
            assertEquals(DamageAction.KEY, first.type)
            assertEquals(12.0, first.damage)
            assertNull(first.context.actorId)
            assertEquals(Key("paper", "explosion"), first.context.source)
        }
    }

    @Test
    fun `explosion actions reject invalid source strength coordinates and world`() {
        val fixture = PaperTestFixture()
        val entity = paperProxy<Entity>("source entity") { method, _ -> unsupported(method) }
        val center = Location(fixture.world, 1.0, 64.0, 2.0)
        for (power in listOf(-1.0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                ExplosionDamageAction(fixture.position, ExplosionSource.Entity(center, power, entity), 1.0)
            }
        }
        for (invalidCenter in listOf(
            Location(fixture.world, Double.NaN, 64.0, 2.0),
            Location(fixture.world, 1.0, Double.POSITIVE_INFINITY, 2.0),
            Location(fixture.world, 1.0, 64.0, Double.NEGATIVE_INFINITY),
            Location(null, 1.0, 64.0, 2.0)
        )) {
            assertFailsWith<IllegalArgumentException> {
                ExplosionDamageAction(fixture.position, ExplosionSource.Entity(invalidCenter, 4.0f, entity), 1.0)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ExplosionDamageAction(fixture.position.copy(world = UUID(9L, 10L)),
                ExplosionSource.Entity(center, 4.0f, entity), 1.0)
        }
    }
}
