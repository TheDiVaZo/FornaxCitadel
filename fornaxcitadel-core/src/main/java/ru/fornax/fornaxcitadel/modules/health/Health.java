package ru.fornax.fornaxcitadel.modules.health;

import org.jspecify.annotations.NonNull;
import ru.fornax.fornaxcitadel.core.component.Component;
import ru.fornax.fornaxcitadel.core.util.Key;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Authoritative current and maximum health; directly readable without a service refresh. */
public class Health extends Component {
    public static final Key KEY = new Key("fornaxcitadel", "health");

    protected Health() {}

    protected Health(double maxHealth, double health) {
        this.maxHealth = maxHealth;
        this.health = health;
    }

    public double maxHealth;
    public double health;

    @Override
    protected void write(@NonNull DataOutput output) throws IOException {
        output.writeDouble(maxHealth);
        output.writeDouble(health);
    }

    @Override
    protected void read(@NonNull DataInput input) throws IOException {
        maxHealth = input.readDouble();
        health = input.readDouble();
    }
}
