package ru.fornax.fornaxcitadel.paper;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.fornax.fornaxcitadel.addon.Addon;
import ru.fornax.fornaxcitadel.addon.ComponentRegistration;
import ru.fornax.fornaxcitadel.core.util.Key;
import ru.fornax.fornaxcitadel.modules.health.Health;
import ru.fornax.fornaxcitadel.core.facade.HealthFacade;
import ru.fornax.fornaxcitadel.core.facade.MaturationFacade;

import java.util.List;
import java.util.Objects;

/** Compile-time coverage for a JavaPlugin implementing the addon API. */
public final class JavaAddonPlugin extends JavaPlugin implements Addon {
    public static final Key SHIELD_KEY = new Key("test", "shield");

    @Override
    public List<ComponentRegistration> defaultComponents() {
        return List.of(new ComponentRegistration(SHIELD_KEY, Shield.class));
    }

    @Override
    public void onEnable() {
        FornaxCitadelAPI api = Objects.requireNonNull(getServer().getServicesManager().load(FornaxCitadelAPI.class));
        HealthFacade health = api.getHealth();
        MaturationFacade maturation = api.getMaturation();
        maturation.registerMultiplier(citadel -> citadel.getComponent(Shield.class) != null ? 2.0 : 1.0);
        health.registerModifier((context, citadel, damage) ->
                citadel.getComponent(Shield.class) != null && maturation.isMature(citadel) ? damage / 2.0 : damage);
    }

    @Override
    public @NotNull Key getKey() {
        return Key.key("shield", "shield");
    }

    public static final class Shield extends Health {}
}
