package com.shadowphoenix.dietlog.profile;

import org.junit.Test;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class StartupRecoveryRequestTest {
    private final ManualWorker worker = new ManualWorker();
    private final Queue<Runnable> ui = new ArrayDeque<>();
    private final StartupRecoveryRequest request = new StartupRecoveryRequest(worker, ui::add);

    @Test public void restoredCompleteProfileSkipsSetupOnlyAfterMergeAndUiDelivery() {
        boolean[] complete = {false};
        List<String> events = new ArrayList<>();
        request.start(() -> { complete[0] = true; events.add("restored"); }, () -> {
            assertFalse(ProfileSetup.needsSetup(complete[0], true,
                    new ProfileSettings(), LocalDate.of(2026, 10, 8)));
            events.add("home");
        }, e -> fail(e.toString()));
        assertTrue(request.isRunning());
        assertTrue(events.isEmpty());
        worker.runAll();
        assertEquals(java.util.Collections.singletonList("restored"), events);
        assertTrue(request.isRunning());
        ui.remove().run();
        assertEquals(java.util.Arrays.asList("restored", "home"), events);
        assertFalse(request.isRunning());
    }

    @Test public void successfulCheckWithoutProfileStillRequiresSetup() {
        boolean[] setup = {false};
        request.start(() -> {}, () -> setup[0] = ProfileSetup.needsSetup(null, false,
                new ProfileSettings(), LocalDate.of(2026, 10, 8)), e -> fail(e.toString()));
        worker.runAll();
        ui.remove().run();
        assertTrue(setup[0]);
    }

    @Test public void failedCheckDoesNotNavigateAndCanBeRetried() {
        List<String> events = new ArrayList<>();
        request.start(() -> { throw new IOException("offline"); }, () -> events.add("home"),
                e -> events.add("retry"));
        worker.runAll();
        assertTrue(events.isEmpty());
        ui.remove().run();
        assertEquals(java.util.Collections.singletonList("retry"), events);
        request.start(() -> {}, () -> events.add("home"), e -> fail(e.toString()));
        worker.runAll();
        ui.remove().run();
        assertEquals(java.util.Arrays.asList("retry", "home"), events);
    }

    @Test public void repeatedAuthorizationDoesNotStartTwoRecoveries() {
        int[] merges = {0}, completions = {0};
        request.start(() -> merges[0]++, () -> completions[0]++, e -> fail(e.toString()));
        request.start(() -> merges[0]++, () -> completions[0]++, e -> fail(e.toString()));
        worker.runAll();
        assertEquals(1, ui.size());
        ui.remove().run();
        assertEquals(1, merges[0]);
        assertEquals(1, completions[0]);
    }

    @Test public void destroyedActivityDoesNotReceiveAnAlreadyQueuedResult() {
        int[] callbacks = {0};
        request.start(() -> {}, () -> callbacks[0]++, e -> callbacks[0]++);
        worker.runAll();
        request.close();
        ui.remove().run();
        assertEquals(0, callbacks[0]);
        assertFalse(request.isRunning());
    }

    @Test public void closingBeforeWorkStartsCancelsItAndRejectsFurtherRequests() {
        int[] merges = {0};
        request.start(() -> merges[0]++, () -> fail("obsolete Activity"), e -> fail(e.toString()));
        request.close();
        request.start(() -> merges[0]++, () -> fail("closed request"), e -> fail(e.toString()));
        worker.runAll();
        assertEquals(0, merges[0]);
        assertTrue(ui.isEmpty());
    }

    private static final class ManualWorker extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean closed;
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
        @Override public void execute(Runnable task) { tasks.add(task); }
        @Override public void shutdown() { closed = true; }
        @Override public List<Runnable> shutdownNow() {
            closed = true;
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }
        @Override public boolean isShutdown() { return closed; }
        @Override public boolean isTerminated() { return closed && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long time, TimeUnit unit) { return isTerminated(); }
    }
}
