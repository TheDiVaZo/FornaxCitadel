package ru.fornax.fornaxcitadel.modules.health;

import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind;
import ru.fornax.fornaxcitadel.core.citadel.ReadableCitadelKind;
import ru.fornax.fornaxcitadel.core.facade.CitadelFacade;
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle;
import ru.fornax.fornaxcitadel.core.facade.ReadableHandle;
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext;
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult;
import ru.fornax.fornaxcitadel.core.util.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Applies authorized, modified damage on the owning game thread. Use currentHealth for read-only HP queries.
 * Direct calls do not notify action handlers.
 */
public final class HealthService {
    private final CitadelFacade citadels;
    private final Map<Key, HealthDefinition> definitions = new HashMap<>();
    private List<DamageModifier> modifiers = List.of();

    public HealthService(@NotNull CitadelFacade citadels) {
        this.citadels = Objects.requireNonNull(citadels, "citadels");
    }

    /** Replaces live settings without changing saved HP or marking any chunks dirty. */
    public void registerDefinition(@NotNull Key key, @NotNull HealthDefinition definition) {
        definitions.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(definition, "definition"));
    }

    @Nullable
    public HealthDefinition findDefinition(@NotNull Key key) { return definitions.get(key); }

    public boolean unregisterDefinition(@NotNull Key key) { return definitions.remove(key) != null; }

    public boolean isInvulnerable(@NotNull Key key) {
        HealthDefinition definition = findDefinition(key);
        return definition != null && definition.getInvulnerable();
    }

    public boolean isInvulnerable(@NotNull ReadableHandle citadel) {
        HealthDefinition definition = definitionOf(citadel);
        return definition != null && definition.getInvulnerable();
    }

    @Nullable
    public Double maxHealth(@NotNull ReadableHandle citadel) {
        HealthDefinition definition = definitionOf(citadel);
        return definition == null ? null : definition.getMaxHealth();
    }

    /**
     * Returns saved HP, or the current maximum when Health has not been created yet.
     * Does not create a component, clamp saved HP or dirty storage. Unavailable/foreign handles return null.
     */
    @Nullable
    public Double currentHealth(@NotNull ReadableHandle citadel) {
        if (!citadels.isCurrent(citadel)) return null;
        ReadableHealth component = citadel.getComponent(Health.class);
        if (component != null) return component.getHealth();
        return maxHealth(citadel);
    }

    /**
     * Appends a read-only modifier. Duplicate instances are ignored.
     * A damage calculation uses the registration snapshot from its start.
     */
    public void registerModifier(@NotNull DamageModifier modifier) {
        addModifier(modifier, modifiers.size());
    }

    /** Places the core maturation modifier ahead of previously registered damage rules. */
    public void registerFirstModifier(@NotNull DamageModifier modifier) {
        addModifier(modifier, 0);
    }

    private void addModifier(DamageModifier modifier, int index) {
        Objects.requireNonNull(modifier, "modifier");
        for (DamageModifier existing : modifiers) {
            if (existing == modifier) return;
        }
        List<DamageModifier> updated = new ArrayList<>(modifiers);
        updated.add(index, modifier);
        modifiers = List.copyOf(updated);
    }

    /**
     * Removes only the supplied instance, returning false if it is not registered.
     * Calculations already in progress retain their original chain; later calculations see the removal.
     * Registering the instance again appends it to the end of the chain.
     */
    public boolean unregisterModifier(@NotNull DamageModifier modifier) {
        Objects.requireNonNull(modifier, "modifier");
        for (int i = 0; i < modifiers.size(); i++) {
            if (modifiers.get(i) == modifier) {
                List<DamageModifier> updated = new ArrayList<>(modifiers);
                updated.remove(i);
                modifiers = List.copyOf(updated);
                return true;
            }
        }
        return false;
    }

    /**
     * Applies already-authorized damage with the system context, including all common modifiers.
     * External requests must pass through the action pipeline.
     * Destruction cannot be cancelled by another validator after calculation succeeds.
     */
    @NotNull
    public ActionResult damage(@NotNull CitadelHandle citadel, double damage) {
        return damage(citadel, damage, ActionContext.SYSTEM);
    }

    /** Calculates all modifiers before editing health; calculation failures leave storage untouched. */
    @NotNull
    public ActionResult damage(@NotNull CitadelHandle citadel, double damage, @NotNull ActionContext context) {
        Objects.requireNonNull(citadel, "citadel");
        Objects.requireNonNull(context, "context");
        if (!Double.isFinite(damage) || damage < 0.0) {
            throw new IllegalArgumentException("Damage must be finite and non-negative: " + damage);
        }
        if (!citadels.isCurrent(citadel)) return ActionResult.DENIED;
        HealthDefinition definition = definitionOf(citadel);
        if (definition != null && definition.getInvulnerable()) return ActionResult.DENIED;
        ReadableHealth component = citadel.getComponent(Health.class);
        Double maximum = definition == null ? null : definition.getMaxHealth();
        Double initialHealth = component == null ? maximum : Double.valueOf(component.getHealth());
        if (initialHealth != null) requireValidHealth(initialHealth);

        double modifiedDamage = damage;
        List<DamageModifier> currentModifiers = modifiers;
        for (DamageModifier modifier : currentModifiers) {
            modifiedDamage = modifier.getDamage(context, citadel, modifiedDamage);
            if (!Double.isFinite(modifiedDamage) || modifiedDamage < 0.0) {
                throw new IllegalStateException("Damage modifier " + modifier.getClass().getName()
                        + " returned invalid damage: " + modifiedDamage);
            }
        }
        // An authorized hit also removes a reinforcement with no health or configured maximum.
        if (initialHealth == null || initialHealth == 0.0) {
            return removeCitadel(citadel);
        }
        if (modifiedDamage == 0.0) return ActionResult.DENIED;
        double damageableHealth = maximum == null ? initialHealth : Math.min(initialHealth, maximum);
        double remainingHealth = Math.max(0.0, damageableHealth - modifiedDamage);
        if (remainingHealth == initialHealth) return ActionResult.DENIED;
        if (component == null) {
            if (remainingHealth == 0.0) return removeCitadel(citadel);
            citadel.putComponent(Health.class, new Health(remainingHealth));
            return ActionResult.SUCCESS;
        }
        boolean edited = citadel.editComponent(Health.class, health -> {
            health.setHealth(remainingHealth);
            return Unit.INSTANCE;
        });
        if (!edited) return ActionResult.DENIED;
        if (remainingHealth == 0.0) {
            return removeCitadel(citadel);
        }
        return ActionResult.SUCCESS;
    }

    /** Executes a validated action with an available target; validators must leave gameplay state unchanged. */
    @NotNull
    protected ActionResult onDamage(@NotNull DamageAction action) {
        CitadelHandle citadel = Objects.requireNonNull(
                citadels.findProtecting(action.getPosition()), "Validated damage target is unavailable");
        if (action.getMode() == DamageAction.Mode.DESTROY) {
            return removeCitadel(citadel);
        }
        return damage(citadel, action.getDamage(), action.getContext());
    }

    private ActionResult removeCitadel(CitadelHandle citadel) {
        return citadels.removeCitadel(citadel) ? ActionResult.SUCCESS : ActionResult.DENIED;
    }

    @Nullable
    private HealthDefinition definitionOf(ReadableHandle citadel) {
        ReadableCitadelKind kind = citadel.getComponent(CitadelKind.class);
        return kind == null ? null : findDefinition(kind.getDefinitionKey());
    }

    private static void requireValidHealth(double health) {
        if (!Double.isFinite(health) || health < 0.0) {
            throw new IllegalStateException("Health must be finite and non-negative");
        }
    }
}
