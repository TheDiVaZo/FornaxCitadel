package ru.fornax.fornaxcitadel.modules.health;

import org.jspecify.annotations.NonNull;
import ru.fornax.fornaxcitadel.core.component.Component;
import ru.fornax.fornaxcitadel.core.util.Key;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Persisted current HP. Maximum health is resolved from the type's live profile. */
public class Health extends Component<ReadableHealth> implements ReadableHealth {
    public static final Key KEY = new Key("fornaxcitadel", "health");

    protected Health() {}

    public Health(double health) {
        this.health = health;
    }

    private double health;

    @Override
    public double getHealth() {
        return health;
    }

    public void setHealth(double health) {
        this.health = health;
    }

    @Override
    public @NonNull ReadableHealth getReadable() {
        return this;
    }

    @Override
    protected void write(@NonNull DataOutput output) throws IOException {
        output.writeDouble(health);
    }

    @Override
    protected void read(@NonNull DataInput input) throws IOException {
        health = input.readDouble();
    }
}
