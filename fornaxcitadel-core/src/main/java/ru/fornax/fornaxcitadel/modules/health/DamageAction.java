package ru.fornax.fornaxcitadel.modules.health;

import org.jetbrains.annotations.NotNull;
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition;
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext;
import ru.fornax.fornaxcitadel.core.systems.action.ActionData;
import ru.fornax.fornaxcitadel.core.util.Key;

import java.util.Objects;
import java.util.UUID;

public class DamageAction extends ActionData {
    public static final Key KEY = Key.key("health:damage");

    /**
     * DESTROY requests policy-authorized removal independently of Health.
     * Damage remains finite and non-negative and is available to validators, but is not subtracted.
     */
    public enum Mode { DAMAGE, DESTROY }

    protected final double damage;
    private final Mode mode;

    public DamageAction(@NotNull ActionContext context, @NotNull CitadelPosition position, double damage) {
        this(context, position, damage, Mode.DAMAGE);
    }

    public DamageAction(@NotNull ActionContext context, @NotNull CitadelPosition position,
                        double damage, @NotNull Mode mode) {
        super(KEY, position, context);
        if (!Double.isFinite(damage) || damage < 0.0) {
            throw new IllegalArgumentException("Damage must be finite and non-negative: " + damage);
        }
        this.damage = damage;
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public DamageAction(@NotNull UUID citadelW, int citadelX, int citadelY, int citadelZ, double damage) {
        this(ActionContext.SYSTEM, new CitadelPosition(citadelW, citadelX, citadelY, citadelZ), damage);
    }

    public double getDamage() {
        return damage;
    }

    @NotNull
    public Mode getMode() {
        return mode;
    }
}
