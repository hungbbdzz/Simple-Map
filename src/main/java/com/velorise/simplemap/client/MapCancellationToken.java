package com.velorise.simplemap.client;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Cooperative cancellation checked between expensive map pipeline stages. */
public final class MapCancellationToken implements BooleanSupplier {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final BooleanSupplier generationValid;

    public MapCancellationToken(BooleanSupplier generationValid) {
        this.generationValid = generationValid == null ? () -> true : generationValid;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get() || !generationValid.getAsBoolean();
    }

    @Override
    public boolean getAsBoolean() {
        return !isCancelled();
    }

    public void checkpoint(String stage) {
        if (isCancelled()) {
            throw new CancellationException("Map task cancelled at " + stage);
        }
    }
}
