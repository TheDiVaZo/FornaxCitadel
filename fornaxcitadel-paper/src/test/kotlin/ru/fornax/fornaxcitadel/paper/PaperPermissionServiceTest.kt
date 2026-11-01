package ru.fornax.fornaxcitadel.paper

import ru.fornax.fornaxcitadel.core.systems.action.ActionValidator
import ru.fornax.fornaxcitadel.modules.place.PlaceAction
import ru.fornax.fornaxcitadel.paper.modules.permission.PaperPermissionService
import ru.fornax.fornaxcitadel.paper.modules.permission.PaperPermissionValidator
import ru.fornax.fornaxcitadel.paper.modules.placement.PaperCitadelTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperPermissionServiceTest {
    @Test
    fun `permission lifecycle owns the supplied validator and leaves external checks registered`() {
        PaperTestFixture(initializeSystems = false).use { fixture ->
            val validator = PaperPermissionValidator(fixture.server, fixture.citadelTypes)
            val service = PaperPermissionService(fixture.module.actions, validator)
            val coordinator = fixture.module.actions.validatorCoordinator
            val action = PlaceAction(fixture.context(fixture.installer), fixture.position, PaperCitadelTypes.REINFORCED)
            var externalCalls = 0
            val external = object : ActionValidator<PlaceAction> {
                override val actionClass = PlaceAction::class.java
                override fun validate(action: PlaceAction): ActionValidator.Result {
                    externalCalls++
                    return ActionValidator.Result.Neutral
                }
            }
            coordinator.registerValidator(external)
            fixture.installer.permissions.clear()

            service.disable()
            service.enable()
            service.enable()
            assertSame(ActionValidator.Result.Rejected, coordinator.validate(action))
            service.disable()
            service.disable()
            assertSame(ActionValidator.Result.Neutral, coordinator.validate(action))
            assertEquals(2, externalCalls)

            service.enable()
            assertSame(ActionValidator.Result.Rejected, coordinator.validate(action))
            fixture.installer.permissions.add("fornaxcitadel.place")
            assertSame(ActionValidator.Result.Success, coordinator.validate(action))
            assertTrue(coordinator.unregisterValidator(validator))
            assertSame(ActionValidator.Result.Neutral, coordinator.validate(action))
            service.enable()
            assertSame(ActionValidator.Result.Success, coordinator.validate(action))
            service.disable()
            assertSame(ActionValidator.Result.Neutral, coordinator.validate(action))
            assertTrue(coordinator.unregisterValidator(external))
        }
    }
}
