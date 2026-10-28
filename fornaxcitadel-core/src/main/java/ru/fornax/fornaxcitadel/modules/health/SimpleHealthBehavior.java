package ru.fornax.fornaxcitadel.modules.health;

import org.jetbrains.annotations.NotNull;
import ru.fornax.fornaxcitadel.core.citadel.CitadelDraft;

public class SimpleHealthBehavior implements HealthBehavior {
    private final double maxHealth;

    public SimpleHealthBehavior(double maxHealth) {
        if (!Double.isFinite(maxHealth) || maxHealth <= 0.0) {
            throw new IllegalArgumentException("Maximum health must be finite and positive: " + maxHealth);
        }
        this.maxHealth = maxHealth;
    }

    @Override
    public void initializeHealth(@NotNull Health component, @NotNull CitadelDraft draft) {
        component.health = maxHealth;
        component.maxHealth = maxHealth;
    }
}
