package jetbrains.buildServer.webhook.async;

import jetbrains.buildServer.util.ThreadUtil;
import jetbrains.buildServer.webhook.async.events.AsyncEvent;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class OrderedExecutorTest {

    private OrderedExecutor executor;
    private CopyOnWriteArrayList<Long> completedTasks = new CopyOnWriteArrayList<>();
    private final String KEY = "key";

    private final BiConsumer<AsyncEvent, Integer> asyncEventConsumer = (event, timeout) -> {
        ThreadUtil.sleep(timeout);
        completedTasks.add(event.getObjectId());
    };

    @After
    public void cleanUp() {
        completedTasks.clear();
    }

    @Test
    public void executeInOrderWithOneThreadInternalExecutor() {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        this.executor = new OrderedExecutor(executor, 10);

        this.executor.execute(e -> asyncEventConsumer.accept(e, 300), new AsyncEvent("1", 1L), KEY);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 50), new AsyncEvent("2", 2L), KEY);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 1), new AsyncEvent("3", 3L), KEY);

        ThreadUtil.sleep(600);

        assertEquals(1, completedTasks.get(0));
        assertEquals(2, completedTasks.get(1));
        assertEquals(3, completedTasks.get(2));
    }

    @Test
    public void executeInOrderWithFewThreadsInternalExecutor() {
        executor = new OrderedExecutor(Executors.newFixedThreadPool(3), 10);

        executor.execute(e -> asyncEventConsumer.accept(e, 1000), new AsyncEvent("1", 1L), KEY);
        executor.execute(e -> asyncEventConsumer.accept(e, 500), new AsyncEvent("2", 2L), KEY);
        executor.execute(e -> asyncEventConsumer.accept(e, 5), new AsyncEvent("3", 3L), KEY);

        ThreadUtil.sleep(2000);

        assertEquals(1, completedTasks.get(0));
        assertEquals(2, completedTasks.get(1));
        assertEquals(3, completedTasks.get(2));
    }

    @Test
    public void executeEvenIfInternalExecutorQueueIsTooSmall() {
        final ThreadPoolExecutor internalExecutor = new ThreadPoolExecutor(1, 1, 3, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        this.executor = new OrderedExecutor(internalExecutor, 10);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 100), new AsyncEvent("1", 1L), KEY);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 100), new AsyncEvent("2", 2L), 1);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 100), new AsyncEvent("3", 3L), 2);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 100), new AsyncEvent("4", 4L), 3);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 100), new AsyncEvent("5", 5L), 4);

        ThreadUtil.sleep(1000);

        assertEquals(1, completedTasks.get(0));
        assertEquals(2, completedTasks.get(1));
        assertEquals(3, completedTasks.get(2));
        assertEquals(4, completedTasks.get(3));
        assertEquals(5, completedTasks.get(4));
    }

    @Test
    public void executeAllTasksInInternalExecutorBeforeShutdown() {
        this.executor = new OrderedExecutor();
        this.executor.execute(e -> asyncEventConsumer.accept(e, 1000), new AsyncEvent("1", 1L), KEY);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 1000), new AsyncEvent("2", 2L), 1);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 1000), new AsyncEvent("3", 3L), 2);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 1000), new AsyncEvent("4", 4L), 3);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 1000), new AsyncEvent("5", 5L), 4);

        executor.shutdown();

        while (!executor.isTerminated()){ }

        assertEquals(1, completedTasks.get(0));
        assertEquals(2, completedTasks.get(1));
        assertEquals(3, completedTasks.get(2));
        assertEquals(4, completedTasks.get(3));
        assertEquals(5, completedTasks.get(4));
    }

    @Test
    public void doNotLostEventsDuringShutdown() {//All events should be processed or available throw OrderedExecutor#getUnprocessedEvents method
        this.executor = new OrderedExecutor();
        this.executor.execute(e -> asyncEventConsumer.accept(e, 500), new AsyncEvent("1", 1L), KEY);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 500), new AsyncEvent("2", 2L), 1);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 500), new AsyncEvent("3", 3L), KEY);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 500), new AsyncEvent("4", 4L), 1);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 500), new AsyncEvent("5", 5L), KEY);

        executor.shutdown();
        while (!executor.isTerminated()){ }

        completedTasks.addAll(executor.getUnprocessedEvents().stream().map(AsyncEvent::getObjectId).collect(Collectors.toList()));

        assertEquals(1, completedTasks.get(0));
        assertEquals(2, completedTasks.get(1));
        assertEquals(3, completedTasks.get(2));
        assertEquals(4, completedTasks.get(3));
        assertEquals(5, completedTasks.get(4));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectIfInternalAndOrderedExecutorQueuesAreTooSmall() {
        final ThreadPoolExecutor internalExecutor = new ThreadPoolExecutor(1, 1, 3, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        this.executor = new OrderedExecutor(internalExecutor, 1);

        this.executor.execute(e -> asyncEventConsumer.accept(e, 100), new AsyncEvent("1", 1L), KEY);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 100), new AsyncEvent("2", 2L), KEY);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 100), new AsyncEvent("3", 3L), KEY);
        this.executor.execute(e -> asyncEventConsumer.accept(e, 100), new AsyncEvent("4", 4L), KEY);
    }

    @Test(expected = RejectedExecutionException.class)
    public void rejectAfterExecutorShutdown() {
        executor = new OrderedExecutor(Executors.newSingleThreadExecutor(), 2);

        executor.execute(e -> asyncEventConsumer.accept(e, 100), new AsyncEvent("1", 1L), KEY);
        executor.shutdown();
        executor.execute(e -> asyncEventConsumer.accept(e, 100), new AsyncEvent("2", 2L), KEY);
    }
}