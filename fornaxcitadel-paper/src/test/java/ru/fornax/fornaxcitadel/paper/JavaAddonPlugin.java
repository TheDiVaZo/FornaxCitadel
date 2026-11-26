package ru.fornax.fornaxcitadel.paper;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.fornax.fornaxcitadel.addon.Addon;
import ru.fornax.fornaxcitadel.addon.ComponentRegistration;
import ru.fornax.fornaxcitadel.core.util.Key;
import ru.fornax.fornaxcitadel.modules.health.Health;

import java.util.List;

/** Compile-time coverage for a JavaPlugin implementing the addon API. */
public final class JavaAddonPlugin extends JavaPlugin implements Addon {
    public static final Key SHIELD_KEY = new Key("test", "shield");

    @Override
    public List<ComponentRegistration> defaultComponents() {
        return List.of(new ComponentRegistration(SHIELD_KEY, Shield.class));
    }

    @Override
    public @NotNull Key getKey() {
        return Key.key("shield", "shield");
    }

    public static final class Shield extends Health {}
}
