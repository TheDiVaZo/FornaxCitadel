package ru.fornax.fornaxcitadel.core.pool

import ru.fornax.fornaxcitadel.core.storage.WXZCord
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class WXZCordPoolTest {
    @Test
    fun `equal world IDs and coordinates reuse the same instance`() {
        val pool = WXZCordPool()
        val world = UUID(1L, 2L)
        val cord = pool.get(world, 10, -20)

        assertEquals(WXZCord(world, 10, -20), cord)
        assertSame(cord, pool.get(UUID(1L, 2L), 10, -20))
    }

    @Test
    fun `different worlds have independent instances`() {
        val pool = WXZCordPool()
        val firstWorld = UUID(1L, 2L)
        val secondWorld = UUID(3L, 4L)
        val first = pool.get(firstWorld, 10, -20)
        val second = pool.get(secondWorld, 10, -20)

        assertNotSame(first, second)
        assertEquals(firstWorld, first.world)
        assertEquals(secondWorld, second.world)
        assertSame(first, pool.get(firstWorld, 10, -20))
        assertSame(second, pool.get(secondWorld, 10, -20))
    }

    @Test
    fun `signed coordinate combinations remain distinct`() {
        val pool = WXZCordPool()
        val world = UUID(1L, 2L)
        val coordinates = intArrayOf(-32768, -32767, -1, 0, 1, 32766, 32767)
        val instances = mutableListOf<WXZCord>()

        for (x in coordinates) {
            for (z in coordinates) {
                val cord = pool.get(world, x, z)
                assertEquals(WXZCord(world, x, z), cord)
                instances.add(cord)
            }
        }

        for (cord in instances) {
            assertSame(cord, pool.get(world, cord.x, cord.z))
        }
    }

    @Test
    fun `coordinates outside signed 16-bit range are rejected instead of aliasing`() {
        val pool = WXZCordPool()
        val world = UUID(1L, 2L)
        val origin = pool.get(world, 0, 0)
        val invalidCoordinates = intArrayOf(
            Int.MIN_VALUE, -65536, -32769, 32768, 65536, Int.MAX_VALUE
        )

        for (coordinate in invalidCoordinates) {
            assertFailsWith<IllegalArgumentException> { pool.get(world, coordinate, 0) }
            assertFailsWith<IllegalArgumentException> { pool.get(world, 0, coordinate) }
        }

        assertSame(origin, pool.get(world, 0, 0))
    }
}
