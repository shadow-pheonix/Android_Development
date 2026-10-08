package com.shadowphoenix.dietlog.profile;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Finishes the startup backup check before delivering navigation, without stale Activity callbacks. */
public final class StartupRecoveryRequest implements AutoCloseable {
    public interface Work { void run() throws Exception; }

    private final ExecutorService worker;
    private final Executor delivery;
    private Future<?> future;
    private boolean running, closed;
    private int generation;

    public StartupRecoveryRequest(ExecutorService worker, Executor delivery) {
        this.worker = worker;
        this.delivery = delivery;
    }

    public synchronized boolean isRunning() { return running; }

    public synchronized void start(Work work, Runnable success, Consumer<Exception> failure) {
        if (closed || running) return;
        running = true;
        int request = ++generation;
        future = worker.submit(() -> {
            Exception error = null;
            try { work.run(); } catch (Exception e) { error = e; }
            Exception result = error;
            delivery.execute(() -> {
                synchronized (StartupRecoveryRequest.this) {
                    if (closed || request != generation) return;
                    running = false;
                    future = null;
                }
                if (result == null) success.run();
                else failure.accept(result);
            });
        });
    }

    @Override public synchronized void close() {
        closed = true;
        running = false;
        generation++;
        if (future != null) future.cancel(true);
        future = null;
    }
}
