package ru.fornax.fornaxcitadel.modules

import ru.fornax.fornaxcitadel.core.citadel.CitadelDefinition
import ru.fornax.fornaxcitadel.core.citadel.CitadelInitializer
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind
import ru.fornax.fornaxcitadel.core.citadel.CitadelOwner
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition
import ru.fornax.fornaxcitadel.core.component.TypeManager
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle
import ru.fornax.fornaxcitadel.core.facade.ComponentManager
import ru.fornax.fornaxcitadel.core.scheduler.ManualScheduler
import ru.fornax.fornaxcitadel.core.serialization.ChunkCordSnapshotMapper
import ru.fornax.fornaxcitadel.core.storage.ChunkBackendLifecycleCoordinator
import ru.fornax.fornaxcitadel.core.storage.ChunkCord
import ru.fornax.fornaxcitadel.core.storage.ChunkCordSnapshot
import ru.fornax.fornaxcitadel.core.storage.CitadelSnapshot
import ru.fornax.fornaxcitadel.core.storage.ComponentSnapshot
import ru.fornax.fornaxcitadel.core.storage.DBStorage
import ru.fornax.fornaxcitadel.core.storage.WXZCord
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext
import ru.fornax.fornaxcitadel.core.systems.action.ActionHandler
import ru.fornax.fornaxcitadel.core.systems.action.ActionProcessor
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult
import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.core.systems.action.NamedActionSource
import ru.fornax.fornaxcitadel.core.util.Key
import ru.fornax.fornaxcitadel.modules.dismantle.DismantleAction
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacementMetadataTest {
    @Test
    fun `owner and kind are ready before initialization and notification and survive reload`() {
        var initializerCalls = 0
        Fixture(initializers = listOf(CitadelInitializer { context, draft ->
            initializerCalls++
            assertEquals(PLAYER, context.actorId)
            assertEquals(PLAYER, draft.getComponent(CitadelOwner::class.java)?.playerId)
            assertEquals(TYPE, draft.getComponent(CitadelKind::class.java)?.definitionKey)
        })).use { fixture ->
            var observerCalls = 0
            var observedOwner: UUID? = null
            var observedKind: Key? = null
            fixture.module.actions.registerHandler(object : ActionHandler<PlaceAction> {
                override val actionClass = PlaceAction::class.java
                override fun handle(action: PlaceAction) {
                    observerCalls++
                    val handle = fixture.handle(action.position)
                    observedOwner = handle.getComponent(CitadelOwner::class.java)?.playerId
                    observedKind = handle.getComponent(CitadelKind::class.java)?.definitionKey
                }
            })

            assertEquals(ActionResult.SUCCESS, fixture.place())

            val original = fixture.handle()
            assertEquals(1, initializerCalls)
            assertEquals(1, observerCalls)
            assertEquals(PLAYER, observedOwner)
            assertEquals(TYPE, observedKind)
            assertMetadata(original, PLAYER)
            fixture.reload()

            assertFalse(original.isCurrent)
            assertMetadata(fixture.handle(), PLAYER)
            assertEquals(1, initializerCalls)
            assertEquals(1, observerCalls)
            assertFalse(fixture.chunk().isDirty)
        }
    }

    @Test
    fun `vetoed and failed placement never publishes owner or kind metadata`() {
        for (initializerFails in listOf(false, true)) {
            val initializers = if (initializerFails) listOf(CitadelInitializer { _, draft ->
                assertEquals(PLAYER, draft.getComponent(CitadelOwner::class.java)?.playerId)
                assertEquals(TYPE, draft.getComponent(CitadelKind::class.java)?.definitionKey)
                error("Initialization failed")
            }) else emptyList()
            Fixture(initializers).use { fixture ->
                var notifications = 0
                fixture.module.actions.registerHandler(object : ActionHandler<PlaceAction> {
                    override val actionClass = PlaceAction::class.java
                    override fun handle(action: PlaceAction) { notifications++ }
                })
                if (initializerFails) {
                    assertFailsWith<IllegalStateException> { fixture.place() }
                } else {
                    fixture.module.actions.registerValidator(object : ActionValidator<PlaceAction> {
                        override val actionClass = PlaceAction::class.java
                        override fun validate(action: PlaceAction) = ActionValidator.Result.Rejected
                    })
                    assertEquals(ActionResult.DENIED, fixture.place())
                }

                assertNull(fixture.find())
                assertTrue(fixture.chunk().isEmpty)
                assertFalse(fixture.chunk().isDirty)
                assertEquals(0, notifications)
            }
        }
    }

    @Test
    fun `occupied placement preserves the original owner and kind`() {
        Fixture().use { fixture ->
            assertEquals(ActionResult.SUCCESS, fixture.place())
            val original = fixture.handle()
            fixture.save()
            fixture.chunk().resetDirty()

            assertEquals(ActionResult.DENIED, fixture.place(
                context = ActionContext(UUID(8L, 9L), SOURCE)
            ))

            assertTrue(original.isCurrent)
            assertMetadata(original, PLAYER)
            assertFalse(fixture.chunk().isDirty)
            fixture.reload()
            assertMetadata(fixture.handle(), PLAYER)
        }
    }

    @Test
    fun `actor metadata is optional and stay absent after reload`() {
        Fixture().use { fixture ->
            val cases = listOf(ActionContext.SYSTEM, CONTEXT)
            for ((index, context) in cases.withIndex()) {
                val position = POSITION.copy(x = POSITION.x + index)
                assertEquals(ActionResult.SUCCESS, fixture.place(context, position))
                assertMetadata(fixture.handle(position), context.actorId)
            }
            fixture.reload()
            for ((index, context) in cases.withIndex()) {
                assertMetadata(fixture.handle(POSITION.copy(x = POSITION.x + index)), context.actorId)
            }
            assertFalse(fixture.chunk().isDirty)
        }
    }

    @Test
    fun `legacy snapshots do not acquire invented owner or kind metadata`() {
        val legacy = ChunkCordSnapshot(CHUNK, listOf(CitadelSnapshot(
            ChunkCord.packPosition(POSITION.x, POSITION.y, POSITION.z),
            listOf(ComponentSnapshot(CitadelKind.KEY, CitadelKind(TYPE).toByteArray()))
        )))
        Fixture(snapshot = legacy).use { fixture ->
            assertEquals(TYPE, fixture.handle().getComponent(CitadelKind::class.java)?.definitionKey)
            assertMetadata(fixture.handle(), null)
            assertFalse(fixture.chunk().isDirty)

            fixture.reload()

            assertMetadata(fixture.handle(), null)
            assertFalse(fixture.chunk().isDirty)
        }
    }

    @Test
    fun `dismantle requires approval and permits another validator to grant it after neutral`() {
        Fixture().use { fixture ->
            assertEquals(ActionResult.SUCCESS, fixture.place())
            var executed = 0
            fixture.module.actions.registerProcessor(object : ActionProcessor<DismantleAction>() {
                override val actionClass = DismantleAction::class.java
                override val actionKey = DismantleAction.KEY
                override fun execute(action: DismantleAction): ActionResult {
                    executed++
                    return ActionResult.SUCCESS
                }
            })
            val action = DismantleAction(CONTEXT, POSITION)
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))
            fixture.validateDismantle(ActionValidator.Result.Neutral)
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))
            assertEquals(0, executed)

            fixture.validateDismantle(ActionValidator.Result.Success)
            assertEquals(ActionResult.SUCCESS, fixture.module.actions.execute(action))
            assertEquals(1, executed)

            fixture.validateDismantle(ActionValidator.Result.Rejected)
            assertEquals(ActionResult.DENIED, fixture.module.actions.execute(action))
            assertEquals(1, executed)
        }
    }

    private fun assertMetadata(handle: CitadelHandle, playerId: UUID?) {
        val owner = handle.getComponent(CitadelOwner::class.java)
        if (playerId == null) assertNull(owner) else assertEquals(playerId, assertNotNull(owner).playerId)
        assertEquals(TYPE, handle.getComponent(CitadelKind::class.java)?.definitionKey)
    }

    private class Fixture(
        initializers: List<CitadelInitializer> = emptyList(),
        snapshot: ChunkCordSnapshot? = null
    ) : AutoCloseable {
        private val types = TypeManager()
        private val scheduler = ManualScheduler()
        private val storage = ChunkBackendLifecycleCoordinator(
            MemoryBackend(snapshot), scheduler, ChunkCordSnapshotMapper(types), Executor(Runnable::run)
        )
        private val manager = ComponentManager(storage, types)
        val module = CitadelModule(manager, scheduler)

        init {
            module.registerDefinition(CitadelDefinition(TYPE, initializers))
            module.enable()
            manager.loadChunk(WORLD, 0, 0).join()
        }

        fun place(context: ActionContext = CONTEXT, position: CitadelPosition = POSITION) =
            module.actions.execute(PlaceAction(context, position, TYPE))

        fun find(position: CitadelPosition = POSITION) =
            manager.findCitadel(position.world, position.x, position.y, position.z)

        fun handle(position: CitadelPosition = POSITION) = assertNotNull(find(position))
        fun chunk() = assertNotNull(storage.getIfChunkLoaded(CHUNK))

        fun save() = manager.saveAll().join()

        fun reload() {
            manager.unloadChunk(WORLD, 0, 0).join()
            manager.loadChunk(WORLD, 0, 0).join()
        }

        fun validateDismantle(result: ActionValidator.Result) {
            module.actions.registerValidator(object : ActionValidator<DismantleAction> {
                override val actionClass = DismantleAction::class.java
                override fun validate(action: DismantleAction) = result
            })
        }

        override fun close() {
            module.disable()
            manager.closeAndSaveAll().join()
        }
    }

    private class MemoryBackend(snapshot: ChunkCordSnapshot?) : DBStorage {
        private val persisted = mutableMapOf<WXZCord, ChunkCordSnapshot>().apply {
            snapshot?.let { put(it.location, ChunkCordSnapshot.fromByteArray(it.toByteArray())) }
        }

        override fun loadChunk(location: WXZCord): CompletableFuture<ChunkCordSnapshot?> =
            CompletableFuture.completedFuture(persisted[location])

        override fun saveChunk(snapshot: ChunkCordSnapshot): CompletableFuture<Void> {
            persisted[snapshot.location] = ChunkCordSnapshot.fromByteArray(snapshot.toByteArray())
            return CompletableFuture.completedFuture(null)
        }
    }

    private companion object {
        val WORLD = UUID(1L, 2L)
        val CHUNK = WXZCord(WORLD, 0, 0)
        val POSITION = CitadelPosition(WORLD, 1, 64, 2)
        val PLAYER = UUID(3L, 4L)
        val SOURCE = NamedActionSource(Key("test", "player"))
        val CONTEXT = ActionContext(PLAYER, SOURCE)
        val TYPE = Key("test", "reinforcement")
    }
}
