package dev.nitromap;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

final class Evictor<K, V> implements AutoCloseable {

    private final NitroMap<K, V> map;
    private final long maximum;
    private final long target;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(this::thread);
    private final AtomicBoolean scheduled = new AtomicBoolean();

    private volatile RuntimeException failure;
    private volatile boolean closed;

    Evictor(NitroMap<K, V> map, int maximum) {
        this.map = map;
        this.maximum = maximum;
        target = maximum - Math.max(1, maximum / 10);
    }

    void signal() {
        checkFailure();
        if (closed || map.mappingCount() <= maximum) return;
        if (scheduled.compareAndSet(false, true)) submit();
    }

    void flush() {
        do {
            signal();
            while (scheduled.get()) LockSupport.parkNanos(100_000);
        } while (!closed && map.mappingCount() > maximum);
        checkFailure();
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdown();
        await();
    }

    private void submit() {
        try {
            executor.execute(this::run);
        } catch (RejectedExecutionException exception) {
            rejected(exception);
        }
    }

    private void run() {
        try {
            evict();
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            reschedule();
        }
    }

    private void evict() {
        var entries = map.entrySet().iterator();
        while (active(entries.hasNext())) remove(entries.next());
    }

    private boolean active(boolean hasNext) {
        return !closed && hasNext && map.mappingCount() > target;
    }

    private void remove(Map.Entry<K, V> entry) {
        map.remove(entry.getKey(), entry.getValue());
    }

    private void reschedule() {
        scheduled.set(false);
        if (failure == null) signal();
    }

    private void rejected(RejectedExecutionException exception) {
        scheduled.set(false);
        if (!closed) throw exception;
    }

    private void checkFailure() {
        if (failure != null) throw failure;
    }

    private Thread thread(Runnable task) {
        Thread thread = new Thread(task, "nitromap-evictor");
        thread.setDaemon(true);
        return thread;
    }

    private void await() {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
