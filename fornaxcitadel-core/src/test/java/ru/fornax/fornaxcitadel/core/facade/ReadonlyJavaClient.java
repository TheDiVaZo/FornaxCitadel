package ru.fornax.fornaxcitadel.core.facade;

import kotlin.Unit;
import ru.fornax.fornaxcitadel.core.component.Component;
import ru.fornax.fornaxcitadel.core.component.ReadableComponent;
import ru.fornax.fornaxcitadel.modules.health.Health;
import ru.fornax.fornaxcitadel.modules.health.ReadableHealth;

/** Compiled Java consumer of the Kotlin component access contract. */
final class ReadonlyJavaClient {
    private ReadonlyJavaClient() {}

    static ReadableHealth readHealth(ReadableHandle handle) {
        return handle.getComponent(Health.class);
    }

    static ReadableComponent readUnknown(ReadableHandle handle, Class<? extends Component<?>> type) {
        return handle.getComponent(type);
    }

    static boolean editHealth(CitadelHandle handle, double value) {
        return handle.editComponent(Health.class, health -> {
            health.setHealth(value);
            return Unit.INSTANCE;
        });
    }

    static Health putHealth(CitadelHandle handle, Health health) {
        return handle.putComponent(Health.class, health);
    }

    static Health removeHealth(CitadelHandle handle) {
        return handle.removeComponent(Health.class);
    }
}
