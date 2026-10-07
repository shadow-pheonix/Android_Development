package com.shadowphoenix.dietlog.food;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Owns a serial database worker; callbacks and requests use the same UI executor. */
public final class LatestFoodRequest implements AutoCloseable {
    private final ExecutorService worker;
    private final Executor delivery;
    private volatile long generation;
    private volatile boolean closed;
    private Future<?> pending;

    public LatestFoodRequest(ExecutorService worker, Executor delivery) {
        this.worker = worker;
        this.delivery = delivery;
    }

    public synchronized <T> void submit(Callable<T> query, Consumer<T> ready, Consumer<Exception> failed) {
        if (closed) return;
        cancel();
        long token = generation;
        pending = worker.submit(() -> {
            if (!current(token)) return;
            try {
                T value = query.call();
                if (current(token)) delivery.execute(() -> {
                    if (current(token)) ready.accept(value);
                });
            } catch (Exception error) {
                if (current(token)) delivery.execute(() -> {
                    if (current(token)) failed.accept(error);
                });
            }
        });
    }

    private boolean current(long token) { return !closed && token == generation; }

    public synchronized void cancel() {
        generation++;
        // SQLite work already running may finish, but it cannot deliver an obsolete result.
        if (pending != null) pending.cancel(false);
        pending = null;
    }

    /** Cleanup runs after any in-flight read, so the database cannot close underneath it. */
    public synchronized void close(Runnable cleanup) {
        if (closed) return;
        closed = true;
        cancel();
        worker.execute(cleanup);
        worker.shutdown();
    }

    @Override public void close() { close(() -> {}); }
}
