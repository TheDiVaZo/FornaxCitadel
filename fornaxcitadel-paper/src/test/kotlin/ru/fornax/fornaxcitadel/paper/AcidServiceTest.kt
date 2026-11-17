package ru.fornax.fornaxcitadel.paper

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.health.AcidDestroyAction
import ru.fornax.fornaxcitadel.modules.health.DamageAction
import ru.fornax.fornaxcitadel.modules.health.Maturing
import ru.fornax.fornaxcitadel.modules.health.MaturationDefinition
import ru.fornax.fornaxcitadel.modules.health.MaturationMultiplier
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.material.MaterialAdapter
import ru.fornax.fornaxcitadel.paper.material.MaterialRegistry
import ru.fornax.fornaxcitadel.paper.modules.acid.*
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypesSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.CitadelTypeSettings
import ru.fornax.fornaxcitadel.paper.modules.placement.HealthSettings
import java.time.Duration
import kotlin.test.*

class AcidServiceTest {
    @Test
    fun `unowned acid permits activation while source veto permission and another owner preserve it`() {
        for (reason in listOf("success", "veto", "permission", "claimed")) fixture().use { f ->
            f.manager.removeComponent(CitadelOwner::class.java, f.worldId, 1, 64, 2)
            val target = reinforce(f, f.block().getRelative(BlockFace.UP))
            val owner = target.getComponent(CitadelOwner::class.java)?.playerId
            when (reason) {
                "veto" -> f.vetoAcid { it.consumingSource }
                "permission" -> f.installer.permissions.remove("fornaxcitadel.acid")
                "claimed" -> f.manager.putComponent(CitadelOwner::class.java, f.worldId, 1, 64, 2, CitadelOwner(f.stranger.id))
            }
            if (reason == "success") {
                assertEquals(AcidResult.Activated(1), activate(f))
                consumed(f)
                assertFalse(target.isCurrent)
            } else {
                assertIs<AcidResult.Failed>(activate(f))
                unchanged(f)
                assertTrue(target.isCurrent)
                assertEquals(owner, target.getComponent(CitadelOwner::class.java)?.playerId)
                assertEquals(if (reason == "claimed") f.stranger.id else null,
                    f.module.citadels.findProtecting(f.position)?.getComponent(CitadelOwner::class.java)?.playerId)
            }
        }
    }

    @Test
    fun `natural destruction runs once per source and target without Bukkit break events`() =
        fixture(Material.DIAMOND_BLOCK).use { f ->
            val source = assertNotNull(f.module.citadels.findProtecting(f.position))
            val blocks = listOf(f.block().getRelative(BlockFace.UP), f.block().getRelative(BlockFace.DOWN))
            val targets = blocks.map { reinforce(f, it) }
            f.beforeNaturalBreak = { block ->
                assertFalse(source.isCurrent)
                if (block === f.block()) assertTrue(targets.all { it.isCurrent })
                else assertFalse(targets[blocks.indexOf(block)].isCurrent)
            }
            assertEquals(AcidResult.Activated(2), activate(f))
            assertEquals((listOf(f.block()) + blocks).map { it to true }, f.naturalBreaks)
            assertTrue(f.calledEvents.isEmpty())
            assertTrue(f.playerBreaks.isEmpty())
        }

    @Test
    fun `invulnerable targets stay protected from high tier creative acid`() = fixture().use { f ->
        val source = assertNotNull(f.module.citadels.findProtecting(f.position))
        source.editComponent(CitadelKind::class.java) { it.definitionKey = Key("fornaxcitadel", "tier_1000") }
        f.installer.permissions.add("fornaxcitadel.admin")
        val target = reinforce(f, f.block().getRelative(BlockFace.UP), PaperCitadelTypes.INVULNERABLE)
        f.installer.gameMode = GameMode.CREATIVE
        assertEquals(AcidResult.Activated(0), activate(f))
        assertTrue(target.isCurrent)
        consumed(f)
    }

    @Test
    fun `natural provider failure stops remaining targets without rolling back completed destruction`() =
        fixture(Material.NETHERITE_BLOCK).use { f ->
            val first = reinforce(f, f.block().getRelative(BlockFace.UP))
            val block = f.block().getRelative(BlockFace.DOWN)
            val target = reinforce(f, block)
            val last = reinforce(f, f.block().getRelative(BlockFace.NORTH))
            val adapter = object : MaterialAdapter by TestMaterialAdapter(Key("broken", "stone"), Material.PAPER, block) {
                override fun breakNaturallyBlock(block: Block, triggerEffect: Boolean): Boolean = error("Provider failed")
            }
            f.materials.register("broken", TestMaterialRegistry(adapter))
            assertFailure(AcidFailure.ERROR, activate(f))
            assertFalse(first.isCurrent)
            assertFalse(target.isCurrent)
            assertFalse(block.isEmpty)
            assertTrue(last.isCurrent)
            consumed(f)
            assertEquals(listOf(f.block(), f.block().getRelative(BlockFace.UP)), f.naturalBreaks.map { it.first })
            assertEquals(listOf(Material.NETHERITE_BLOCK, Material.COBBLESTONE), f.droppedItems.map { it.second.type })
        }

    @Test
    fun `disabling acid keeps saved reinforcement data and restores ordinary maturity`() =
        fixture(sourceType = PaperCitadelTypes.MATURING).use { f ->
            val source = assertNotNull(f.module.citadels.findProtecting(f.position))
            val startedAt = System.currentTimeMillis() - 900_000
            source.editComponent(Maturing::class.java) { it.startedAtMillis = startedAt }
            assertFalse(f.module.health.maturation.isMature(source))
            f.acidService!!.disable()
            val disabled = AcidService(f.plugin, f.module, f.citadelTypes, f.dismantleMaterials, f.materialPlacement, f.breakingService,
                AcidRegistry(AcidSettings(enabled = false)))
            disabled.enable()
            try {
                assertTrue(f.module.health.maturation.isMature(source))
                assertFailure(AcidFailure.DISABLED, disabled.activate(f.installer.player, f.block()))
                f.manager.unloadChunk(f.worldId, 0, 0).join()
                f.manager.loadChunk(f.worldId, 0, 0).join()
                val restored = assertNotNull(f.module.citadels.findProtecting(f.position))
                assertEquals(source.position, restored.position)
                assertEquals(startedAt, restored.getComponent(Maturing::class.java)?.startedAtMillis)
                unchanged(f)
            } finally { disabled.disable() }
        }

    @Test
    fun `provider chunk failure during execution is an error and stops remaining targets`() = fixture(Material.DIAMOND_BLOCK).use { f ->
        val block = f.block().getRelative(BlockFace.UP)
        val first = reinforce(f, block)
        val second = reinforce(f, f.block().getRelative(BlockFace.DOWN))
        val adapter = object : MaterialAdapter by TestMaterialAdapter(Key("broken", "stone"), Material.PAPER, block) {
            override fun breakNaturallyBlock(block: Block, triggerEffect: Boolean): Boolean {
                throw ru.fornax.fornaxcitadel.core.resolver.CitadelResolutionUnavailableException(block.testPosition())
            }
        }
        f.materials.register("broken", TestMaterialRegistry(adapter))

        assertFailure(AcidFailure.ERROR, activate(f))
        consumed(f)
        assertFalse(first.isCurrent) // Unexpected execution failures do not roll back completed removals.
        assertFalse(block.isEmpty)
        assertTrue(second.isCurrent)
        assertEquals(listOf(f.block() to true), f.naturalBreaks)
        assertEquals(listOf(Material.DIAMOND_BLOCK), f.droppedItems.map { it.second.type })
    }

    @Test
    fun `missing or refusing physical providers remove reinforcements and continue activation`() {
        for (missing in listOf(false, true)) for (retainSource in listOf(false, true)) {
            fixture(Material.DIAMOND_BLOCK).use { f ->
                val source = assertNotNull(f.module.citadels.findProtecting(f.position))
                val firstBlock = f.block().getRelative(BlockFace.UP)
                val secondBlock = f.block().getRelative(BlockFace.DOWN)
                val first = reinforce(f, firstBlock)
                val second = reinforce(f, secondBlock)
                val retained = if (retainSource) f.block() else firstBlock
                val retainedType = retained.type
                var attempts = 0
                val refusing = object : MaterialAdapter by assertNotNull(f.dismantleMaterials.getTypeOrNull(retained)) {
                    override fun breakNaturallyBlock(block: Block, triggerEffect: Boolean): Boolean {
                        assertSame(retained, block)
                        attempts++
                        return false
                    }
                    override fun createItemStack(amount: Int): org.bukkit.inventory.ItemStack =
                        error("A retained block must not create an item")
                }
                val materials = object : MaterialRegistry by f.dismantleMaterials {
                    override fun getTypeOrNull(block: Block): MaterialAdapter? =
                        if (block === retained) refusing.takeUnless { missing }
                        else f.dismantleMaterials.getTypeOrNull(block)
                }
                withMaterials(f, materials) { acid ->
                    assertEquals(AcidResult.Activated(2), acid.activate(f.installer.player, f.block()))

                    assertFalse(source.isCurrent || first.isCurrent || second.isCurrent)
                    assertEquals(retainedType, retained.type)
                    assertEquals(retainSource, firstBlock.isEmpty)
                    assertTrue(secondBlock.isEmpty)
                    assertEquals(!retainSource, f.block().isEmpty)
                    assertEquals(if (missing) 0 else 1, attempts)
                    assertEquals(2, f.vanillaBlockChanges)
                    assertEquals((listOf(f.block(), firstBlock, secondBlock) - retained).map { it to true }, f.naturalBreaks)
                    val items = f.droppedItems.map { it.second.type }
                    assertFalse(Material.IRON_INGOT in items)
                    assertEquals(if (retainSource) 2 else 1, items.count { it == Material.COBBLESTONE })
                    assertEquals(if (retainSource) 0 else 1, items.count { it == Material.DIAMOND_BLOCK })
                    val drops = f.droppedItems.size
                    assertFailure(if (retainSource) AcidFailure.NOT_REINFORCED else AcidFailure.NOT_FOUND,
                        acid.activate(f.installer.player, f.block()))
                    assertEquals(drops, f.droppedItems.size)
                    assertEquals(2, f.vanillaBlockChanges)
                }
            }
        }
    }

    @Test
    fun `missing physical provider does not bypass a core target veto`() = fixture().use { f ->
        val targetBlock = f.block().getRelative(BlockFace.UP)
        val target = reinforce(f, targetBlock)
        val materials = object : MaterialRegistry by f.dismantleMaterials {
            override fun getTypeOrNull(block: Block): MaterialAdapter? =
                if (block === targetBlock) null else f.dismantleMaterials.getTypeOrNull(block)
        }
        f.vetoAcid { !it.consumingSource }
        withMaterials(f, materials) { acid ->
            assertEquals(AcidResult.Activated(0), acid.activate(f.installer.player, f.block()))
        }
        consumed(f)
        assertTrue(target.isCurrent)
        assertEquals(Material.STONE, targetBlock.type)
        assertEquals(listOf(f.block() to true), f.naturalBreaks)
        assertEquals(listOf(Material.GOLD_BLOCK), f.droppedItems.map { it.second.type })
    }

    @Test
    fun `acid never requests block item factories or reinforcement refunds`() = fixture().use { f ->
        val target = f.block().getRelative(BlockFace.UP)
        reinforce(f, target)
        val materials = object : MaterialRegistry by f.dismantleMaterials {
            override fun getType(key: Key): MaterialAdapter? = error("Acid must not resolve reinforcement item adapters")
            override fun getTypeOrNull(block: Block): MaterialAdapter? = f.dismantleMaterials.getTypeOrNull(block)?.let {
                object : MaterialAdapter by it {
                    override fun createItemStack(amount: Int): org.bukkit.inventory.ItemStack = error("Native breaking owns drops")
                }
            }
        }
        withMaterials(f, materials) { acid ->
            assertEquals(AcidResult.Activated(1), acid.activate(f.installer.player, f.block()))
        }
        consumed(f)
        assertTrue(target.isEmpty)
        assertEquals(listOf(f.block() to true, target to true), f.naturalBreaks)
        assertEquals(listOf(Material.GOLD_BLOCK, Material.COBBLESTONE), f.droppedItems.map { it.second.type })
    }

    @Test
    fun `gold accepts each of the six directions independently`() {
        for (face in faces) fixture().use { f ->
            val target = f.block().getRelative(face)
            val reinforcement = reinforce(f, target)
            assertEquals(AcidResult.Activated(1), activate(f), face.name)
            assertFalse(reinforcement.isCurrent)
            assertTrue(target.isEmpty)
            assertTrue(f.block().isEmpty)
            assertEquals(2, f.vanillaBlockChanges)
        }
    }

    @Test
    fun `every pair of directions is available to diamond`() {
        for (first in faces.indices) for (second in first + 1 until faces.size) fixture(Material.DIAMOND_BLOCK).use { f ->
            reinforce(f, f.block().getRelative(faces[first]))
            reinforce(f, f.block().getRelative(faces[second]))
            assertEquals(AcidResult.Activated(2), activate(f))
        }
    }

    @Test
    fun `limits and deterministic ordering leave remaining blocks and diagonals unchanged`() {
        for ((material, limit) in listOf(Material.GOLD_BLOCK to 1, Material.DIAMOND_BLOCK to 2, Material.NETHERITE_BLOCK to 6)) {
            repeat(2) {
                fixture(material).use { f ->
                    val neighbors = faces.map { f.block().getRelative(it) }
                    val handles = neighbors.map { reinforce(f, it) }
                    val diagonal = reinforce(f, f.block(2, 65, 2))
                    val info = assertIs<AcidResult.Information>(f.acidService!!.inspect(f.installer.player, f.block()))
                    assertEquals(6, info.availableTargets)
                    assertEquals(limit, info.selectedTargets)
                    assertTrue(f.calledEvents.isEmpty())
                    assertEquals(0, f.vanillaBlockChanges)
                    assertEquals(AcidResult.Activated(limit), activate(f))
                    assertEquals(List(6) { it >= limit }, handles.map { it.isCurrent })
                    assertTrue(diagonal.isCurrent)
                    assertEquals((listOf(f.block()) + neighbors.take(limit)).map { it to true }, f.naturalBreaks)
                }
            }
        }
    }

    @Test
    fun `air unreinforced higher tiers and vetoed targets do not consume the limit`() = fixture(Material.DIAMOND_BLOCK).use { f ->
        val high = reinforce(f, f.block().getRelative(BlockFace.UP))
        high.editComponent(CitadelKind::class.java) { it.definitionKey = Key("fornaxcitadel", "reinforced_heavy") }
        val veto = f.block().getRelative(BlockFace.NORTH)
        reinforce(f, veto)
        f.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun validate(action: DamageAction) = if (action.position == veto.testPosition())
                ActionValidator.Result.Rejected else ActionValidator.Result.Neutral
        })
        val air = f.block().getRelative(BlockFace.SOUTH)
        val stale = reinforce(f, air)
        f.material(Material.AIR, air.testPosition())
        val accepted = reinforce(f, f.block().getRelative(BlockFace.WEST))
        assertEquals(AcidResult.Activated(1), activate(f))
        assertFalse(accepted.isCurrent)
        assertTrue(high.isCurrent)
        assertTrue(stale.isCurrent)
        assertFalse(veto.isEmpty)
    }

    @Test
    fun `empty activation consumes acid unless its core action is vetoed including higher tier neighbors`() {
        for (high in listOf(false, true)) for (vetoed in listOf(false, true)) fixture().use { f ->
            val target = if (high) reinforce(f, f.block().getRelative(BlockFace.UP)).also {
                it.editComponent(CitadelKind::class.java) { kind -> kind.definitionKey = Key("fornaxcitadel", "reinforced_heavy") }
            } else null
            if (vetoed) f.vetoAcid { it.consumingSource }
            if (vetoed) {
                assertFailure(AcidFailure.DENIED, activate(f))
                unchanged(f)
                assertTrue(f.droppedItems.isEmpty())
            } else {
                assertEquals(AcidResult.Activated(0), activate(f))
                consumed(f)
                assertEquals(listOf(Material.GOLD_BLOCK), f.droppedItems.map { it.second.type })
            }
            assertTrue(f.calledEvents.isEmpty())
            if (target != null) assertTrue(target.isCurrent)
        }
    }

    @Test
    fun `material and reinforcement are both required and disabled types preserve data`() {
        fixture().use { f ->
            f.material(Material.STONE)
            assertFailure(AcidFailure.NOT_FOUND, activate(f))
            f.material(Material.GOLD_BLOCK)
            val own = assertNotNull(f.module.citadels.findProtecting(f.position))
            f.module.remove.remove(own)
            assertFailure(AcidFailure.NOT_REINFORCED, activate(f))
            assertEquals(Material.GOLD_BLOCK, f.block().type)
        }
        fixture(settings = AcidSettings(types = listOf(AcidType("off", Material.GOLD_BLOCK, 1, 2.0, false)))).use { f ->
            assertFailure(AcidFailure.DISABLED, activate(f))
            unchanged(f)
        }
    }

    @Test
    fun `only reinforcement owner can activate including administrators`() = fixture().use { f ->
        reinforce(f, f.block().getRelative(BlockFace.UP))
        f.stranger.permissions.add("fornaxcitadel.admin")
        assertFailure(AcidFailure.NOT_OWNER, f.acidService!!.activate(f.stranger.player, f.block()))
        unchanged(f)
        val acid = assertNotNull(f.module.citadels.findProtecting(f.position))
        acid.editComponent(CitadelOwner::class.java) { it.playerId = f.stranger.id }
        assertFailure(AcidFailure.NOT_OWNER, activate(f))
        assertEquals(AcidResult.Activated(1), f.acidService.activate(f.stranger.player, f.block()))
    }

    @Test
    fun `own and foreign targets are equally eligible`() {
        for (owner in listOf(false, true)) fixture().use { f ->
            reinforce(f, f.block().getRelative(BlockFace.UP), player = if (owner) f.installer else f.stranger)
            assertEquals(AcidResult.Activated(1), activate(f))
        }
    }

    @Test
    fun `maturation uses live profile and persisted start time`() =
        fixture(sourceType = PaperCitadelTypes.MATURING).use { f ->
            val acid = assertNotNull(f.module.citadels.findProtecting(f.position))
            val maturity = assertNotNull(acid.getComponent(Maturing::class.java))
            reinforce(f, f.block().getRelative(BlockFace.UP))
            acid.editComponent(Maturing::class.java) { it.startedAtMillis = System.currentTimeMillis() - 900_000 }
            assertEquals(Duration.ofMinutes(10), f.module.health.maturation.findDefinition(PaperCitadelTypes.MATURING)?.duration)
            assertFalse(f.module.health.maturation.isMature(acid))
            assertFailure(AcidFailure.IMMATURE, activate(f))
            unchanged(f)
            val incoming = f.module.health.maturation.progress(acid)
            assertTrue(incoming in 0.74..0.76)
            f.manager.unloadChunk(f.worldId, 0, 0).join()
            f.manager.loadChunk(f.worldId, 0, 0).join()
            val restored = assertNotNull(f.module.citadels.findProtecting(f.position))
            assertEquals(maturity.startedAtMillis, restored.getComponent(Maturing::class.java)?.startedAtMillis)
            assertFailure(AcidFailure.IMMATURE, activate(f))
            restored.editComponent(Maturing::class.java) { it.startedAtMillis = 0 }
            assertEquals(AcidResult.Activated(1), activate(f))
        }

    @Test
    fun `source veto preserves acid while late target vetoes skip selected targets without replacement`() {
        for (mask in 0..7) fixture(Material.DIAMOND_BLOCK).use { f ->
            val first = reinforce(f, f.block().getRelative(BlockFace.UP))
            val second = reinforce(f, f.block().getRelative(BlockFace.DOWN))
            val extra = reinforce(f, f.block().getRelative(BlockFace.NORTH))
            var consumedSource = false
            f.afterNaturalBreak = { if (it === f.block()) consumedSource = true }
            f.vetoAcid { action ->
                if (action.consumingSource) mask and 1 != 0
                else consumedSource && ((action.position == first.position && mask and 2 != 0) ||
                    (action.position == second.position && mask and 4 != 0))
            }
            if (mask and 1 != 0) {
                assertFailure(AcidFailure.DENIED, activate(f))
                assertTrue(first.isCurrent && second.isCurrent)
                unchanged(f)
                assertTrue(f.droppedItems.isEmpty())
            } else {
                val count = 2 - (if (mask and 2 != 0) 1 else 0) - (if (mask and 4 != 0) 1 else 0)
                assertEquals(AcidResult.Activated(count), activate(f))
                assertEquals(mask and 2 != 0, first.isCurrent)
                assertEquals(mask and 4 != 0, second.isCurrent)
                consumed(f)
                assertEquals(count + 1, f.naturalBreaks.size)
                assertEquals(count, f.droppedItems.count { it.second.type == Material.COBBLESTONE })
            }
            assertTrue(extra.isCurrent)
        }
    }

    @Test
    fun `late core veto skips only that target and notifies only completed actions`() = fixture(Material.DIAMOND_BLOCK).use { f ->
        val first = reinforce(f, f.block().getRelative(BlockFace.UP))
        val second = reinforce(f, f.block().getRelative(BlockFace.DOWN))
        var veto = false
        f.module.actions.validatorCoordinator.registerValidator(object : ActionValidator<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun validate(action: DamageAction) = if (veto && action.position == first.position)
                ActionValidator.Result.Rejected else ActionValidator.Result.Neutral
        })
        val notified = mutableListOf<ru.fornax.fornaxcitadel.core.citadel.CitadelPosition>()
        f.module.actions.handlerCoordinator.registerHandler(object : ActionHandler<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun handle(action: DamageAction) { notified.add(action.position) }
        })
        f.afterNaturalBreak = { if (it === f.block()) veto = true }

        assertEquals(AcidResult.Activated(1), activate(f))
        consumed(f)
        assertTrue(first.isCurrent)
        assertFalse(second.isCurrent)
        assertEquals(listOf(f.position, second.position), notified)
    }

    @Test
    fun `reinforcement replaced after selection is not destroyed and other selected targets continue`() = fixture(Material.DIAMOND_BLOCK).use { f ->
        val firstBlock = f.block().getRelative(BlockFace.UP)
        val first = reinforce(f, firstBlock)
        val second = reinforce(f, f.block().getRelative(BlockFace.DOWN))
        var replacement: CitadelHandle? = null
        f.afterNaturalBreak = { if (it === f.block()) {
            assertEquals(ActionResult.SUCCESS, f.module.remove.remove(first))
            replacement = f.placeExact(firstBlock)
        } }

        assertEquals(AcidResult.Activated(1), activate(f))
        consumed(f)
        assertTrue(assertNotNull(replacement).isCurrent)
        assertFalse(firstBlock.isEmpty)
        assertFalse(second.isCurrent)
        assertEquals(listOf(f.position, second.position), f.naturalBreaks.map { it.first.testPosition() })
    }

    @Test
    fun `core target veto never invokes its natural material provider`() = fixture().use { f ->
        val block = f.block().getRelative(BlockFace.UP)
        val target = reinforce(f, block)
        val adapter = object : MaterialAdapter by TestMaterialAdapter(Key("custom", "stone"), Material.PAPER, block) {
            override fun breakNaturallyBlock(block: Block, triggerEffect: Boolean): Boolean = error("Vetoed target must remain")
        }
        f.materials.register("custom", TestMaterialRegistry(adapter))
        f.vetoAcid { !it.consumingSource }
        assertEquals(AcidResult.Activated(0), activate(f))
        consumed(f)
        assertTrue(target.isCurrent)
        assertEquals(listOf(Material.GOLD_BLOCK), f.droppedItems.map { it.second.type })
    }

    @Test
    fun `repeat nested and forged acid actions cannot reuse the source`() = fixture().use { f ->
        val target = f.block().getRelative(BlockFace.UP)
        reinforce(f, target)
        val acid = assertNotNull(f.module.citadels.findProtecting(f.position))
        val forged = AcidDestroyAction(f.context(f.installer), target.testPosition(), f.position, acid.runtimeId)
        assertEquals(ActionResult.DENIED, f.module.actions.execute(forged))
        f.beforeNaturalBreak = { assertFailure(AcidFailure.BUSY, activate(f)) }
        assertEquals(AcidResult.Activated(1), activate(f))
        val drops = f.droppedItems.size
        assertIs<AcidResult.Failed>(activate(f))
        assertEquals(drops, f.droppedItems.size)
        assertEquals(2, f.vanillaBlockChanges)
    }

    @Test
    fun `destroying another acid never activates it`() = fixture().use { f ->
        val other = f.block().getRelative(BlockFace.UP)
        f.material(Material.NETHERITE_BLOCK, other.testPosition())
        reinforce(f, other)
        val distant = reinforce(f, f.block(1, 66, 2))
        assertEquals(AcidResult.Activated(1), activate(f))
        assertTrue(distant.isCurrent)
        assertEquals(listOf(f.block() to true, other to true), f.naturalBreaks)
        assertTrue(f.calledEvents.isEmpty())
    }

    @Test
    fun `natural drops belong to the provider and never include reinforcement refunds`() {
        for (mode in listOf(GameMode.SURVIVAL, GameMode.CREATIVE)) fixture().use { f ->
            val target = f.block().getRelative(BlockFace.UP)
            reinforce(f, target)
            f.installer.gameMode = mode
            assertEquals(AcidResult.Activated(1), activate(f))
            assertEquals(listOf(Material.GOLD_BLOCK, Material.COBBLESTONE), f.droppedItems.map { it.second.type })
            assertTrue(f.playerBreaks.isEmpty())
            assertEquals(listOf(f.block() to true, target to true), f.naturalBreaks)
        }
    }

    @Test
    fun `missing neighbor data aborts even when the first target already fills the limit`() = fixture().use { f ->
        val acidBlock = f.block(15, 64, 2)
        f.material(Material.GOLD_BLOCK, acidBlock.testPosition())
        reinforce(f, acidBlock)
        val target = reinforce(f, acidBlock.getRelative(BlockFace.UP))
        assertFailure(AcidFailure.CHUNK_UNAVAILABLE, f.acidService!!.activate(f.installer.player, acidBlock))
        assertTrue(target.isCurrent)
        assertEquals(0, f.vanillaBlockChanges)
        assertTrue(f.calledEvents.isEmpty())
        f.loadedWorldChunks.add(1 to 0) // World loaded but reinforcement storage absent is equally unknown.
        assertFailure(AcidFailure.CHUNK_UNAVAILABLE, f.acidService.activate(f.installer.player, acidBlock))
    }

    @Test
    fun `target tier is checked again after native source destruction`() = fixture().use { f ->
        val block = f.block().getRelative(BlockFace.UP)
        val target = reinforce(f, block)
        f.afterNaturalBreak = { if (it === f.block()) {
            target.editComponent(CitadelKind::class.java) { kind -> kind.definitionKey = Key("fornaxcitadel", "tier_1000") }
        } }
        assertEquals(AcidResult.Activated(0), activate(f))
        assertTrue(target.isCurrent)
        consumed(f)
        assertEquals(listOf(f.block() to true), f.naturalBreaks)
        assertEquals(listOf(Material.GOLD_BLOCK), f.droppedItems.map { it.second.type })
    }

    @Test
    fun `main thread requirement and unknown owner fail closed`() = fixture().use { f ->
        reinforce(f, f.block().getRelative(BlockFace.UP))
        f.mainThread = false
        assertFailure(AcidFailure.WRONG_THREAD, activate(f))
        f.mainThread = true
        val acid = assertNotNull(f.module.citadels.findProtecting(f.position))
        acid.editComponent(CitadelOwner::class.java) { it.playerId = java.util.UUID(0, 0) }
        assertFailure(AcidFailure.NOT_OWNER, activate(f))
        unchanged(f)
    }

    @Test
    fun `damage notifications observe each completed construction starting with source consumption`() = fixture(Material.DIAMOND_BLOCK).use { f ->
        val targets = listOf(reinforce(f, f.block().getRelative(BlockFace.UP)), reinforce(f, f.block().getRelative(BlockFace.DOWN)))
        val notified = mutableListOf<AcidDestroyAction>()
        val remaining = mutableListOf<List<Boolean>>()
        val physical = mutableListOf<Boolean>()
        f.module.actions.handlerCoordinator.registerHandler(object : ActionHandler<DamageAction> {
            override val actionClass = DamageAction::class.java
            override fun handle(action: DamageAction) {
                if (action is AcidDestroyAction) {
                    notified.add(action)
                    remaining.add(targets.map { it.isCurrent })
                    physical.add(f.block(action.citadelX, action.citadelY, action.citadelZ).isEmpty)
                }
            }
        })
        assertEquals(AcidResult.Activated(2), activate(f))
        assertEquals(3, notified.size)
        assertEquals(1, notified.count { it.consumingSource })
        assertTrue(notified.first().consumingSource)
        assertEquals(listOf(listOf(true, true), listOf(false, true), listOf(false, false)), remaining)
        assertEquals(listOf(true, true, true), physical)
    }

    @Test
    fun `custom natural provider owns its loot and receives one effect-enabled call`() = fixture().use { f ->
        val block = f.block().getRelative(BlockFace.UP)
        reinforce(f, block)
        var breaks = 0
        val adapter = object : MaterialAdapter by TestMaterialAdapter(Key("custom", "stone"), Material.PAPER, block) {
            override fun createItemStack(amount: Int): org.bukkit.inventory.ItemStack = error("Acid must not create custom block items")
            override fun breakNaturallyBlock(block: Block, triggerEffect: Boolean): Boolean {
                breaks++
                assertTrue(triggerEffect)
                assertSame(f.block().getRelative(BlockFace.UP), block)
                block.setType(Material.AIR, false)
                block.world.dropItemNaturally(block.location, TestItemStack(Material.PAPER, 2))
                return true
            }
        }
        f.materials.register("custom", TestMaterialRegistry(adapter))
        assertIs<AcidResult.Information>(f.acidService!!.inspect(f.installer.player, f.block()))
        assertEquals(0, breaks)
        assertEquals(AcidResult.Activated(1), activate(f))
        assertEquals(1, breaks)
        assertEquals(2, f.droppedItems.single { it.second.type == Material.PAPER }.second.amount)
        assertEquals(2, f.droppedItems.size)
        assertEquals(listOf(f.block() to true), f.naturalBreaks)
    }

    @Test
    fun `new definitions apply to persisted reinforcements without rewriting maturation`() =
        fixture(sourceType = PaperCitadelTypes.MATURING).use { f ->
            val original = assertNotNull(f.module.citadels.findProtecting(f.position))
            val startedAt = System.currentTimeMillis() - 900_000
            original.editComponent(Maturing::class.java) { it.startedAtMillis = startedAt }
            reinforce(f, f.block().getRelative(BlockFace.UP))
            reinforce(f, f.block().getRelative(BlockFace.DOWN))
            f.acidService!!.disable()
            f.manager.unloadChunk(f.worldId, 0, 0).join()
            f.manager.loadChunk(f.worldId, 0, 0).join()
            val settings = AcidSettings(types = listOf(AcidType("changed", Material.GOLD_BLOCK, 2, 1.0)))
            val service = AcidService(f.plugin, f.module, f.citadelTypes, f.dismantleMaterials, f.materialPlacement, f.breakingService, AcidRegistry(settings))
            service.enable()
            try {
                val info = assertIs<AcidResult.Information>(service.inspect(f.installer.player, f.block()))
                assertTrue(info.mature)
                assertEquals(2, info.selectedTargets)
                assertEquals("changed", info.type.id)
                assertEquals(startedAt, info.reinforcement.getComponent(Maturing::class.java)?.startedAtMillis)
                assertEquals(AcidResult.Activated(2), service.activate(f.installer.player, f.block()))
            } finally { service.disable() }
        }

    @Test
    fun `live base duration changes maturity of saved acid without rewriting placement time`() =
        fixture(sourceType = PaperCitadelTypes.MATURING).use { f ->
            val source = assertNotNull(f.module.citadels.findProtecting(f.position))
            val startedAt = System.currentTimeMillis() - 900_000
            source.editComponent(Maturing::class.java) { it.startedAtMillis = startedAt }
            val maturation = f.module.health.maturation
            assertEquals(0.75, maturation.progress(source), 0.01)
            f.manager.unloadChunk(f.worldId, 0, 0).join()
            maturation.registerDefinition(PaperCitadelTypes.MATURING,
                MaturationDefinition(Duration.ofMinutes(20), 8.0))
            f.manager.loadChunk(f.worldId, 0, 0).join()

            val restored = assertNotNull(f.module.citadels.findProtecting(f.position))
            assertEquals(0.375, maturation.progress(restored), 0.01)
            assertFailure(AcidFailure.IMMATURE, activate(f))
            maturation.registerDefinition(PaperCitadelTypes.MATURING,
                MaturationDefinition(Duration.ofMinutes(5), 2.0))
            assertTrue(assertIs<AcidResult.Information>(f.acidService!!.inspect(f.installer.player, f.block())).mature)
            assertEquals(startedAt, restored.getComponent(Maturing::class.java)?.startedAtMillis)
            unchanged(f)
        }

    @Test
    fun `instant reinforcement keeps placement time when its profile gains a duration`() = fixture().use { f ->
        val source = assertNotNull(f.module.citadels.findProtecting(f.position))
        val startedAt = assertNotNull(source.getComponent(Maturing::class.java)).startedAtMillis
        assertTrue(startedAt > 0)
        assertTrue(f.module.health.maturation.isMature(source))
        f.manager.unloadChunk(f.worldId, 0, 0).join()
        f.module.health.maturation.registerDefinition(PaperCitadelTypes.REINFORCED,
            MaturationDefinition(Duration.ofDays(1), 4.0))
        f.manager.loadChunk(f.worldId, 0, 0).join()
        val restored = assertNotNull(f.module.citadels.findProtecting(f.position))
        assertEquals(startedAt, restored.getComponent(Maturing::class.java)?.startedAtMillis)
        assertFalse(f.module.health.maturation.isMature(restored))
        assertFailure(AcidFailure.IMMATURE, activate(f))
        unchanged(f)
    }

    @Test
    fun `acid lifecycle preserves other maturation rules and never registers its factor twice`() =
        fixture(sourceType = PaperCitadelTypes.MATURING).use { f ->
            val source = assertNotNull(f.module.citadels.findProtecting(f.position))
            source.editComponent(Maturing::class.java) { it.startedAtMillis = System.currentTimeMillis() - 900_000 }
            val maturation = f.module.health.maturation
            val acid = f.acidService!!
            val otherRule = MaturationMultiplier { 2.0 }
            maturation.registerMultiplier(otherRule)
            assertEquals(0.375, maturation.progress(source), 0.01)
            acid.enable()
            assertEquals(0.375, maturation.progress(source), 0.01)
            acid.disable()
            acid.disable()
            assertEquals(0.75, maturation.progress(source), 0.01)
            acid.enable()
            assertEquals(0.375, maturation.progress(source), 0.01)
            assertTrue(maturation.unregisterMultiplier(otherRule))
            assertEquals(0.75, maturation.progress(source), 0.01)
        }

    private fun fixture(material: Material = Material.GOLD_BLOCK, settings: AcidSettings = AcidSettings(),
                        sourceType: Key = PaperCitadelTypes.REINFORCED) = PaperTestFixture(permissions = true, acidSettings = settings, citadelSettings = CitadelTypesSettings(
                            CitadelTypesSettings.DEFAULT_TYPES + ("tier_1000" to CitadelTypeSettings(
                                Key("minecraft", "gold_ingot"), 1000, health = HealthSettings(100.0))))).apply {
        material(material)
        place(sourceType)
    }

    private fun reinforce(f: PaperTestFixture, block: Block, type: Key = PaperCitadelTypes.REINFORCED,
                          player: TestPlayer = f.installer): CitadelHandle {
        assertEquals(ActionResult.SUCCESS, f.module.actions.execute(PlaceAction(f.context(player), block.testPosition(), type)))
        return assertNotNull(f.module.citadels.findProtecting(block.testPosition()))
    }

    private fun PaperTestFixture.vetoAcid(predicate: (AcidDestroyAction) -> Boolean) {
        module.actions.validatorCoordinator.registerValidator(object : ActionValidator<AcidDestroyAction> {
            override val actionClass = AcidDestroyAction::class.java
            override fun validate(action: AcidDestroyAction) = if (predicate(action))
                ActionValidator.Result.Rejected else ActionValidator.Result.Neutral
        })
    }

    private fun withMaterials(f: PaperTestFixture, materials: MaterialRegistry, body: (AcidService) -> Unit) {
        val original = assertNotNull(f.acidService)
        original.disable()
        val service = AcidService(f.plugin, f.module, f.citadelTypes, materials, f.materialPlacement,
            f.breakingService, original.registry)
        service.enable()
        try { body(service) } finally { service.disable() }
    }

    private fun activate(f: PaperTestFixture) = f.acidService!!.activate(f.installer.player, f.block())
    private fun assertFailure(reason: AcidFailure, result: AcidResult) { assertEquals(AcidResult.Failed(reason), result) }
    private fun unchanged(f: PaperTestFixture) {
        assertEquals(0, f.vanillaBlockChanges)
        assertFalse(f.block().isEmpty)
        assertNotNull(f.module.citadels.findProtecting(f.position))
    }
    private fun consumed(f: PaperTestFixture) {
        assertTrue(f.block().isEmpty)
        assertNull(f.module.citadels.findProtecting(f.position))
    }
    private val faces = listOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
}
