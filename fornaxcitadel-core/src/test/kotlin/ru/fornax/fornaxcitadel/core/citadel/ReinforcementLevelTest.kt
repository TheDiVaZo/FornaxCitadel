package ru.fornax.fornaxcitadel.core.citadel

import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.serialization.ComponentSerializer
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReinforcementLevelTest {
    @Test
    fun `positive levels round trip through component serialization`() {
        val type = TypeManager().registerType(ReinforcementLevel.KEY, ReinforcementLevel::class.java)
        for (lvl in listOf(1, 999, Int.MAX_VALUE)) {
            val snapshot = ComponentSerializer.encode(type, ReinforcementLevel(lvl))
            assertEquals(lvl, ComponentSerializer.decode(snapshot, type).lvl)
        }
    }

    @Test
    fun `nonpositive levels cannot be created assigned or deserialized`() {
        val component = ReinforcementLevel(2)
        for (lvl in listOf(0, -1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { ReinforcementLevel(lvl) }
            assertFailsWith<IllegalArgumentException> { component.lvl = lvl }
            assertFailsWith<IllegalArgumentException> {
                component.safeRead(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(lvl).array())
            }
            assertEquals(2, component.lvl)
        }
    }
}
