package ru.fornax.fornaxcitadel.core.storage;

import java.util.concurrent.atomic.AtomicInteger;

public final class CitadelEntity {

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private final int packedPosition;
    private final int id;

    CitadelEntity(int packedPosition) {
        this.packedPosition = packedPosition;
        this.id = nextId();
    }

    public int getId() {
        return id;
    }

    public int getPackedPosition() {
        return packedPosition;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CitadelEntity)) return false;

        CitadelEntity that = (CitadelEntity) other;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }

    @Override
    public String toString() {
        return "[Citadel: " + id + " at " + packedPosition + "]";
    }

    private static int nextId() {
        while (true) {
            int current = NEXT_ID.get();
            if (current == Integer.MAX_VALUE) {
                throw new IllegalStateException("Citadel runtime id space is exhausted");
            }
            if (NEXT_ID.compareAndSet(current, current + 1)) {
                return current;
            }
        }
    }
}
