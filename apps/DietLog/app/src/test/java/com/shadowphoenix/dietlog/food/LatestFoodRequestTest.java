package com.shadowphoenix.dietlog.food;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class LatestFoodRequestTest {
    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("Worker timed out", latch.await(3, TimeUnit.SECONDS));
    }

    private static Runnable callback(BlockingQueue<Runnable> callbacks) throws InterruptedException {
        Runnable result = callbacks.poll(3, TimeUnit.SECONDS);
        assertNotNull("Callback timed out", result);
        return result;
    }

    @Test public void requestReturnsWhileDatabaseReadIsBlockedAndOnlyLatestResultIsDelivered() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        LatestFoodRequest requests = new LatestFoodRequest(worker, callbacks::add);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Thread> queryThread = new AtomicReference<>();
        List<String> results = new ArrayList<>();
        try {
            requests.submit(() -> {
                queryThread.set(Thread.currentThread());
                started.countDown();
                await(release);
                return "old";
            }, results::add, error -> fail(error.toString()));
            await(started);
            assertNotSame(Thread.currentThread(), queryThread.get());
            requests.submit(() -> "new", results::add, error -> fail(error.toString()));
            release.countDown();
            callback(callbacks).run();
            assertEquals(java.util.Collections.singletonList("new"), results);
            assertTrue(callbacks.isEmpty());
        } finally {
            release.countDown(); requests.close();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test public void cancellingAfterReadSuppressesAlreadyQueuedCallback() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        LatestFoodRequest requests = new LatestFoodRequest(worker, callbacks::add);
        List<String> results = new ArrayList<>();
        try {
            requests.submit(() -> "stale", results::add, error -> fail(error.toString()));
            Runnable queued = callback(callbacks);
            requests.cancel();
            queued.run();
            assertTrue(results.isEmpty());
        } finally { requests.close(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test public void latestFailureIsDeliveredButCancelledFailureIsSuppressed() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        LatestFoodRequest requests = new LatestFoodRequest(worker, callbacks::add);
        List<Exception> errors = new ArrayList<>();
        try {
            requests.submit(() -> { throw new IllegalStateException("stale"); }, value -> fail(), errors::add);
            Runnable stale = callback(callbacks);
            requests.cancel(); stale.run();
            requests.submit(() -> { throw new IllegalStateException("current"); }, value -> fail(), errors::add);
            callback(callbacks).run();
            assertEquals(1, errors.size());
            assertEquals("current", errors.get(0).getMessage());
        } finally { requests.close(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test public void closeWaitsForInFlightReadBeforeCleanupAndNeverDeliversAfterClose() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        LatestFoodRequest requests = new LatestFoodRequest(worker, callbacks::add);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), cleaned = new CountDownLatch(1);
        List<String> results = new ArrayList<>();
        try {
            requests.submit(() -> { started.countDown(); await(release); return "old"; },
                    results::add, error -> fail(error.toString()));
            await(started);
            requests.close(cleaned::countDown);
            assertEquals(1, cleaned.getCount());
            requests.submit(() -> "after close", results::add, error -> fail());
            release.countDown();
            await(cleaned);
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(callbacks.isEmpty());
            assertTrue(results.isEmpty());
        } finally { release.countDown(); requests.close(); worker.shutdownNow(); }
    }
}
