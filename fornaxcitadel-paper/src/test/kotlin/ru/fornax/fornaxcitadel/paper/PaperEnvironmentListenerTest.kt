package ru.fornax.fornaxcitadel.paper

import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockSpreadEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaperEnvironmentListenerTest {
    @Test
    fun `spread cannot replace a protected block but ordinary positions remain available`() {
        val fixture = PaperTestFixture()
        fixture.place()
        val listener = fixture.environmentListener
        val state = paperProxy<BlockState>("spread state") { method, _ -> unsupported(method) }
        val protected = BlockSpreadEvent(fixture.block(), fixture.block(0, 64, 2), state)
        val ordinary = BlockSpreadEvent(fixture.block(3, 64, 2), fixture.block(0, 64, 2), state)

        dispatch(listener, protected, fixture.plugin)
        dispatch(listener, ordinary, fixture.plugin)

        assertTrue(protected.isCancelled)
        assertFalse(ordinary.isCancelled)
        assertEquals(100.0, fixture.health())
    }

    @Test
    fun `piston movement cannot move a protected block or replace a protected destination`() {
        val fixture = PaperTestFixture()
        fixture.place()
        val listener = fixture.environmentListener
        val piston = fixture.block(1, 64, 0)
        val protectedSource = BlockPistonExtendEvent(piston, listOf(fixture.block()), BlockFace.SOUTH)
        val protectedDestination = BlockPistonExtendEvent(piston, listOf(fixture.block(1, 64, 1)), BlockFace.SOUTH)
        val retractDestination = BlockPistonRetractEvent(piston, listOf(fixture.block(1, 64, 3)), BlockFace.SOUTH)

        dispatch(listener, protectedSource, fixture.plugin)
        dispatch(listener, protectedDestination, fixture.plugin)
        dispatch(listener, retractDestination, fixture.plugin)

        assertTrue(protectedSource.isCancelled)
        assertTrue(protectedDestination.isCancelled)
        assertTrue(retractDestination.isCancelled)
        val ordinary = BlockPistonExtendEvent(fixture.block(5, 64, 5), listOf(fixture.block(6, 64, 5)), BlockFace.EAST)
        dispatch(listener, ordinary, fixture.plugin)
        assertFalse(ordinary.isCancelled)
    }
}
