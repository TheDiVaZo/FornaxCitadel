package ru.fornax.fornaxcitadel.modules.health;

import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.fornax.fornaxcitadel.core.citadel.CitadelKind;
import ru.fornax.fornaxcitadel.core.citadel.RemovalCause;
import ru.fornax.fornaxcitadel.core.facade.ActionManager;
import ru.fornax.fornaxcitadel.core.facade.CitadelHandle;
import ru.fornax.fornaxcitadel.core.facade.ComponentManager;
import ru.fornax.fornaxcitadel.core.lifecycle.LifecycleService;
import ru.fornax.fornaxcitadel.core.systems.action.ActionContext;
import ru.fornax.fornaxcitadel.core.systems.action.ActionResult;
import ru.fornax.fornaxcitadel.core.util.Key;
import ru.fornax.fornaxcitadel.modules.remove.RemoveService;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Health operations on the owning game thread. Register damage policies and behaviors before handling actions. */
public final class HealthService implements LifecycleService {
    private final ActionManager actions;
    private final ComponentManager componentManager;
    private final RemoveService removeService;
    private final Map<Key, HealthBehavior> behaviors = new HashMap<>();
    private final Map<Key, DamagePolicy> policies = new HashMap<>();
    private final DamageProcessor processor = new DamageProcessor(this);
    private final DamagePolicyValidator damageValidator;

    public HealthService(@NotNull ActionManager actions, @NotNull RemoveService removeService) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.componentManager = actions.getComponentManager();
        this.removeService = Objects.requireNonNull(removeService, "removeService");
        this.damageValidator = new DamagePolicyValidator(componentManager, this);
        componentManager.registerComponent(Health.KEY.getNamespace(), Health.KEY.getKey(), Health.class);
        componentManager.registerComponent(Maturing.KEY.getNamespace(), Maturing.KEY.getKey(), Maturing.class);
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

    public void registerBehavior(@NotNull Key definitionKey, @NotNull HealthBehavior behavior) {
        Objects.requireNonNull(definitionKey, "definitionKey");
        Objects.requireNonNull(behavior, "behavior");
        HealthBehavior existing = behaviors.putIfAbsent(definitionKey, behavior);
        if (existing != null && existing != behavior) {
            throw new IllegalArgumentException("Health behavior is already registered for " + definitionKey);
        }
    }

    @Nullable
    public HealthBehavior findBehavior(@NotNull Key definitionKey) {
        return behaviors.get(Objects.requireNonNull(definitionKey, "definitionKey"));
    }

    @Nullable
    public Double getCurrentHealth(@NotNull UUID world, int x, int y, int z) {
        Health component = refresh(componentManager.findCitadel(world, x, y, z));
        return component == null ? null : component.health;
    }

    @Nullable
    public Double getCurrentMaxHealth(@NotNull UUID world, int x, int y, int z) {
        Health component = refresh(componentManager.findCitadel(world, x, y, z));
        return component == null ? null : component.maxHealth;
    }

    /**
     * Applies already-authorized damage. External requests must pass through the action pipeline.
     * Destruction is completed after editing health and cannot be cancelled by another validator.
     */
    @NotNull
    public ActionResult damage(@NotNull CitadelHandle citadel, double damage) {
        Objects.requireNonNull(citadel, "citadel");
        removeService.checkMutationAllowed();
        if (!Double.isFinite(damage) || damage < 0.0) {
            throw new IllegalArgumentException("Damage must be finite and non-negative: " + damage);
        }
        if (!componentManager.isCurrent(citadel)) return ActionResult.DENIED;
        Health component = citadel.getComponent(Health.class);
        if (component == null) return ActionResult.DENIED;
        HealthBehavior behavior = behaviorFor(citadel);
        if (behavior == null) return ActionResult.DENIED;

        double previousHealth = component.health;
        double previousMaxHealth = component.maxHealth;
        boolean edited = citadel.editComponent(Health.class, health -> {
            behavior.updateHealth(health, citadel);
            requireValidHealth(health);
            health.health = Math.max(0.0, health.health - damage);
            return Unit.INSTANCE;
        });
        if (!edited || citadel.getComponent(Health.class) != component) return ActionResult.DENIED;
        if (component.health == 0.0) {
            return removeService.remove(citadel, RemovalCause.DESTROYED);
        }
        return changed(previousHealth, component.health) || changed(previousMaxHealth, component.maxHealth)
                ? ActionResult.SUCCESS : ActionResult.DENIED;
    }

    @NotNull
    protected ActionResult onDamage(@NotNull DamageAction action) {
        if (!componentManager.isChunkLoaded(action.getCitadelW(), action.getCitadelX() >> 4, action.getCitadelZ() >> 4)) {
            return ActionResult.DENIED;
        }
        CitadelHandle citadel = componentManager.findCitadel(
                action.getCitadelW(), action.getCitadelX(), action.getCitadelY(), action.getCitadelZ());
        return citadel == null ? ActionResult.DENIED : damage(citadel, action.getDamage());
    }

    @Nullable
    private Health refresh(@Nullable CitadelHandle citadel) {
        if (citadel == null) return null;
        Health component = citadel.getComponent(Health.class);
        if (component == null) return null;
        HealthBehavior behavior = behaviorFor(citadel);
        if (behavior == null) {
            throw new IllegalStateException("The citadel has health but no registered health behavior");
        }

        double previousHealth = component.health;
        double previousMaxHealth = component.maxHealth;
        boolean completed = false;
        boolean reportedChange = false;
        try {
            reportedChange = behavior.updateHealth(component, citadel);
            requireValidHealth(component);
            completed = true;
        } finally {
            if (!completed || reportedChange || changed(previousHealth, component.health)
                    || changed(previousMaxHealth, component.maxHealth)) {
                citadel.markDirty(Health.class, component);
            }
        }
        return citadel.getComponent(Health.class) == component ? component : null;
    }

    @Nullable
    private HealthBehavior behaviorFor(CitadelHandle citadel) {
        CitadelKind kind = citadel.getComponent(CitadelKind.class);
        return kind == null ? null : behaviors.get(kind.getDefinitionKey());
    }

    private static boolean changed(double previous, double current) {
        return Double.doubleToLongBits(previous) != Double.doubleToLongBits(current);
    }

    private static void requireValidHealth(Health component) {
        if (!Double.isFinite(component.maxHealth) || component.maxHealth <= 0.0
                || !Double.isFinite(component.health) || component.health < 0.0
                || component.health > component.maxHealth) {
            throw new IllegalStateException("Health must be finite and between zero and its positive maximum");
        }
    }
}
