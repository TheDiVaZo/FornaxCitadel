package ru.fornax.fornaxcitadel.core.systems.action

import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.util.Key
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ActionTest {
    @Test
    fun `constructors retain the supplied position or build it from coordinates`() {
        val type = Key("test", "action")
        val world = UUID(1L, 2L)
        val position = CitadelPosition(world, -17, 64, 3)

        val fromPosition = Action(type, position)
        val fromCoordinates = Action(type, world, -17, 64, 3)

        assertSame(position, fromPosition.position)
        assertEquals(position, fromCoordinates.position)
        assertSame(ActionContext.SYSTEM, fromPosition.context)
        assertSame(ActionContext.SYSTEM, fromCoordinates.context)
    }
}
