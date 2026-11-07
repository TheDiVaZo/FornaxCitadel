package ru.fornax.fornaxcitadel.modules.health;

import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind;
import ru.fornax.fornaxcitadel.core.citadel.RemovalCause;
import ru.fornax.fornaxcitadel.core.facade.ActionManager;
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle;
import ru.fornax.fornaxcitadel.core.facade.CitadelResolutionUnavailableException;
import ru.fornax.fornaxcitadel.core.facade.ComponentManager;
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService;
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext;
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult;
import ru.fornax.fornaxcitadel.core.util.Key;
import ru.fornax.fornaxcitadel.modules.remove.RemoveService;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies authorized, modified damage on the owning game thread. Read health directly from its component. */
public final class HealthService implements LifecycleService {
    private final ActionManager actions;
    private final ComponentManager componentManager;
    private final RemoveService removeService;
    private final DamagePolicy destructionPolicy;
    private List<DamageModifier> modifiers = List.of();
    private final Map<Key, DamagePolicy> policies = new HashMap<>();
    private final DamageProcessor processor = new DamageProcessor(this);
    private final DamagePolicyValidator damageValidator;

    public HealthService(@NotNull ActionManager actions, @NotNull RemoveService removeService) {
        this(actions, removeService, DamagePolicy.DENY);
    }

    public HealthService(@NotNull ActionManager actions, @NotNull RemoveService removeService,
                         @NotNull DamagePolicy destructionPolicy) {
        this(actions, removeService, destructionPolicy, Clock.systemUTC());
    }

    public HealthService(@NotNull ActionManager actions, @NotNull RemoveService removeService,
                         @NotNull DamagePolicy destructionPolicy, @NotNull Clock clock) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.componentManager = actions.getComponentManager();
        this.removeService = Objects.requireNonNull(removeService, "removeService");
        this.destructionPolicy = Objects.requireNonNull(destructionPolicy, "destructionPolicy");
        this.damageValidator = new DamagePolicyValidator(actions.getCitadels(), this);
        componentManager.registerComponent(Health.KEY, Health.class);
        componentManager.registerComponent(Maturing.KEY, Maturing.class);
        registerModifier(new MaturingDamageModifier(Objects.requireNonNull(clock, "clock")));
    }

    @Override
    public void enable() {
        actions.getProcessorCoordinator().registerProcessor(processor);
        actions.getValidatorCoordinator().registerValidator(damageValidator);
    }

    @Override
    public void disable() {
        actions.getValidatorCoordinator().unregisterValidator(damageValidator);
        actions.getProcessorCoordinator().unregisterProcessor(processor);
    }

    public void registerPolicy(@NotNull Key definitionKey, @NotNull DamagePolicy policy) {
        Objects.requireNonNull(definitionKey, "definitionKey");
        Objects.requireNonNull(policy, "policy");
        DamagePolicy existing = policies.putIfAbsent(definitionKey, policy);
        if (existing != null && existing != policy) {
            throw new IllegalArgumentException("Damage policy is already registered for " + definitionKey);
        }
    }

    @Nullable
    public DamagePolicy findPolicy(@NotNull Key definitionKey) {
        return policies.get(Objects.requireNonNull(definitionKey, "definitionKey"));
    }

    public boolean allowsDamage(@NotNull ActionContext context, @NotNull CitadelHandle citadel) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(citadel, "citadel");
        CitadelKind kind = citadel.getComponent(CitadelKind.class);
        DamagePolicy policy = kind == null ? null : policies.get(kind.getDefinitionKey());
        return policy != null && policy.allowsDamage(context, citadel);
    }

    /** Checks explicit destruction authorization independently of type damage rules and health. */
    public boolean allowsDestruction(@NotNull ActionContext context, @NotNull CitadelHandle citadel) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(citadel, "citadel");
        return componentManager.isCurrent(citadel) && destructionPolicy.allowsDamage(context, citadel);
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
     * Destruction is completed after editing health and cannot be cancelled by another validator.
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
        removeService.checkMutationAllowed();
        if (!Double.isFinite(damage) || damage < 0.0) {
            throw new IllegalArgumentException("Damage must be finite and non-negative: " + damage);
        }
        if (!componentManager.isCurrent(citadel)) return ActionResult.DENIED;
        Health component = citadel.getComponent(Health.class);
        if (component == null) return ActionResult.DENIED;
        requireValidHealth(component);

        double modifiedDamage = damage;
        List<DamageModifier> currentModifiers = modifiers;
        for (DamageModifier modifier : currentModifiers) {
            modifiedDamage = modifier.getDamage(context, citadel, modifiedDamage);
            if (!Double.isFinite(modifiedDamage) || modifiedDamage < 0.0) {
                throw new IllegalStateException("Damage modifier " + modifier.getClass().getName()
                        + " returned invalid damage: " + modifiedDamage);
            }
        }
        if (!componentManager.isCurrent(citadel) || citadel.getComponent(Health.class) != component) {
            return ActionResult.DENIED;
        }
        requireValidHealth(component);
        if (component.health == 0.0) {
            return removeService.remove(citadel, RemovalCause.DESTROYED);
        }
        double remainingHealth = Math.max(0.0, component.health - modifiedDamage);
        if (remainingHealth == component.health) return ActionResult.DENIED;
        boolean edited = citadel.editComponent(Health.class, health -> {
            health.health = remainingHealth;
            return Unit.INSTANCE;
        });
        if (!edited || citadel.getComponent(Health.class) != component) return ActionResult.DENIED;
        if (component.health == 0.0) {
            return removeService.remove(citadel, RemovalCause.DESTROYED);
        }
        return ActionResult.SUCCESS;
    }

    @NotNull
    protected ActionResult onDamage(@NotNull DamageAction action) {
        CitadelHandle citadel;
        try {
            citadel = actions.getCitadels().findProtecting(action.getPosition());
        } catch (CitadelResolutionUnavailableException unavailable) {
            return ActionResult.DENIED;
        }
        if (citadel == null) return ActionResult.DENIED;
        if (action.getMode() == DamageAction.Mode.DESTROY) {
            // Other validators may have changed live authorization since policy validation.
            if (!allowsDestruction(action.getContext(), citadel)) return ActionResult.DENIED;
            return removeService.remove(citadel, RemovalCause.DESTROYED);
        }
        // A later validator may have changed the resolved reinforcement or its policy.
        if (!allowsDamage(action.getContext(), citadel)) return ActionResult.DENIED;
        return damage(citadel, action.getDamage(), action.getContext());
    }

    private static void requireValidHealth(Health component) {
        if (!Double.isFinite(component.maxHealth) || component.maxHealth <= 0.0
                || !Double.isFinite(component.health) || component.health < 0.0
                || component.health > component.maxHealth) {
            throw new IllegalStateException("Health must be finite and between zero and its positive maximum");
        }
    }
}
