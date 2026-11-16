package ru.fornax.fornaxcitadel.modules.health;

import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind;
import ru.fornax.fornaxcitadel.core.facade.ActionManager;
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle;
import ru.fornax.fornaxcitadel.core.facade.ComponentManager;
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService;
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext;
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult;
import ru.fornax.fornaxcitadel.core.util.Key;
import ru.fornax.fornaxcitadel.modules.remove.RemoveService;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Applies authorized, modified damage on the owning game thread. Use currentHealth for read-only HP queries.
 * Enabling registers damage prerequisites. Configure action metadata and authorization when assembled without CitadelModule.
 * Direct calls do not notify action handlers.
 */
public final class HealthService implements LifecycleService {
    private final ActionManager actions;
    private final ComponentManager componentManager;
    private final RemoveService removeService;
    private final MaturationService maturation;
    private final Map<Key, HealthDefinition> definitions = new HashMap<>();
    private List<DamageModifier> modifiers = List.of();
    private final DamageProcessor processor = new DamageProcessor(this);
    private final DamageActionValidator validator;

    public HealthService(@NotNull ActionManager actions, @NotNull RemoveService removeService) {
        this(actions, removeService, Clock.systemUTC());
    }

    public HealthService(@NotNull ActionManager actions, @NotNull RemoveService removeService,
                         @NotNull Clock clock) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.validator = new DamageActionValidator(actions.getCitadels());
        this.componentManager = actions.getComponentManager();
        this.removeService = Objects.requireNonNull(removeService, "removeService");
        componentManager.registerComponent(Health.KEY, Health.class);
        componentManager.registerComponent(Maturing.KEY, Maturing.class);
        maturation = new MaturationService(Objects.requireNonNull(clock, "clock"));
        registerModifier(new MaturingDamageModifier(maturation));
    }

    @NotNull
    public MaturationService getMaturation() { return maturation; }

    /** Replaces live settings without changing saved HP or marking any chunks dirty. */
    public void registerDefinition(@NotNull Key key, @NotNull HealthDefinition definition) {
        definitions.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(definition, "definition"));
    }

    @Nullable
    public HealthDefinition findDefinition(@NotNull Key key) { return definitions.get(key); }

    public boolean unregisterDefinition(@NotNull Key key) { return definitions.remove(key) != null; }

    @Nullable
    public Double maxHealth(@NotNull CitadelHandle citadel) {
        Key key = definitionKey(citadel);
        HealthDefinition definition = key == null ? null : findDefinition(key);
        return definition == null ? null : definition.getMaxHealth();
    }

    /**
     * Returns saved HP, or the current maximum when Health has not been created yet.
     * Does not create a component, clamp saved HP or dirty storage. Unavailable/foreign handles return null.
     */
    @Nullable
    public Double currentHealth(@NotNull CitadelHandle citadel) {
        if (!componentManager.isCurrent(citadel)) return null;
        Health component = citadel.getComponent(Health.class);
        if (component != null) return component.health;
        return maxHealth(citadel);
    }

    @Override
    public void enable() {
        actions.getProcessorCoordinator().registerProcessor(processor);
        try {
            actions.getValidatorCoordinator().registerValidator(validator);
        } catch (Throwable failure) {
            actions.getValidatorCoordinator().unregisterValidator(validator);
            actions.getProcessorCoordinator().unregisterProcessor(processor);
            throw failure;
        }
    }

    @Override
    public void disable() {
        actions.getValidatorCoordinator().unregisterValidator(validator);
        actions.getProcessorCoordinator().unregisterProcessor(processor);
    }

    /**
     * Appends a read-only modifier after the built-in maturation modifier. Duplicate instances are ignored.
     * A damage calculation uses the registration snapshot from its start.
     */
    public void registerModifier(@NotNull DamageModifier modifier) {
        Objects.requireNonNull(modifier, "modifier");
        for (DamageModifier existing : modifiers) {
            if (existing == modifier) return;
        }
        List<DamageModifier> updated = new ArrayList<>(modifiers);
        updated.add(modifier);
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
        if (!componentManager.isCurrent(citadel)) return ActionResult.DENIED;
        Health component = citadel.getComponent(Health.class);
        Double initialHealth = currentHealth(citadel);
        if (initialHealth != null) requireValidHealth(initialHealth);
        Key originalKey = definitionKey(citadel);
        Double maximum = maxHealth(citadel);

        double modifiedDamage = damage;
        List<DamageModifier> currentModifiers = modifiers;
        for (DamageModifier modifier : currentModifiers) {
            modifiedDamage = modifier.getDamage(context, citadel, modifiedDamage);
            if (!Double.isFinite(modifiedDamage) || modifiedDamage < 0.0) {
                throw new IllegalStateException("Damage modifier " + modifier.getClass().getName()
                        + " returned invalid damage: " + modifiedDamage);
            }
        }
        if (!componentManager.isCurrent(citadel) || citadel.getComponent(Health.class) != component
                || !Objects.equals(originalKey, definitionKey(citadel))
                || !Objects.equals(maximum, maxHealth(citadel))
                || !Objects.equals(initialHealth, currentHealth(citadel))) {
            return ActionResult.DENIED;
        }
        // An authorized hit also removes a reinforcement with no health or configured maximum.
        if (initialHealth == null || initialHealth == 0.0) {
            return removeService.remove(citadel);
        }
        if (modifiedDamage == 0.0) return ActionResult.DENIED;
        double damageableHealth = maximum == null ? initialHealth : Math.min(initialHealth, maximum);
        double remainingHealth = Math.max(0.0, damageableHealth - modifiedDamage);
        if (remainingHealth == initialHealth) return ActionResult.DENIED;
        if (component == null) {
            if (remainingHealth == 0.0) return removeService.remove(citadel);
            componentManager.putComponent(Health.class, citadel.getWorld(), citadel.getX(), citadel.getY(), citadel.getZ(),
                    new Health(remainingHealth));
            return ActionResult.SUCCESS;
        }
        boolean edited = citadel.editComponent(Health.class, health -> {
            health.health = remainingHealth;
            return Unit.INSTANCE;
        });
        if (!edited || citadel.getComponent(Health.class) != component) return ActionResult.DENIED;
        if (component.health == 0.0) {
            return removeService.remove(citadel);
        }
        return ActionResult.SUCCESS;
    }

    /** Executes a validated action with an available target; validators must leave gameplay state unchanged. */
    @NotNull
    protected ActionResult onDamage(@NotNull DamageAction action) {
        CitadelHandle citadel = Objects.requireNonNull(
                actions.getCitadels().findProtecting(action.getPosition()), "Validated damage target is unavailable");
        if (action.getMode() == DamageAction.Mode.DESTROY) {
            return removeService.remove(citadel);
        }
        return damage(citadel, action.getDamage(), action.getContext());
    }

    @Nullable
    private static Key definitionKey(CitadelHandle citadel) {
        CitadelKind kind = citadel.getComponent(CitadelKind.class);
        return kind == null ? null : kind.getDefinitionKey();
    }

    private static void requireValidHealth(double health) {
        if (!Double.isFinite(health) || health < 0.0) {
            throw new IllegalStateException("Health must be finite and non-negative");
        }
    }
}
