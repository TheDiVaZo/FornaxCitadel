package ru.fornax.fornaxcitadel.modules.health;

import org.jetbrains.annotations.NotNull;
import ru.fornax.fornaxcitadel.core.citadel.CitadelPosition;
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext;
import ru.fornax.fornaxcitadel.core.systems.action.ActionData;
import ru.fornax.fornaxcitadel.core.util.Key;

import java.util.UUID;

public class DamageAction extends ActionData {
    public static final Key KEY = Key.key("health:damage");

    protected final double damage;

    public DamageAction(@NotNull ActionContext context, @NotNull CitadelPosition position, double damage) {
        super(KEY, position, context);
        if (!Double.isFinite(damage) || damage < 0.0) {
            throw new IllegalArgumentException("Damage must be finite and non-negative: " + damage);
        }
        this.damage = damage;
    }

    public DamageAction(@NotNull UUID citadelW, int citadelX, int citadelY, int citadelZ, double damage) {
        this(ActionContext.SYSTEM, new CitadelPosition(citadelW, citadelX, citadelY, citadelZ), damage);
    }

    public double getDamage() {
        return damage;
    }
}
