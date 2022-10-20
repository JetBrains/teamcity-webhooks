package jetbrains.buildServer.webhook.async;

import jetbrains.buildServer.util.ThreadUtil;
import jetbrains.buildServer.webhook.async.events.AsyncEvent;
import org.jetbrains.annotations.NotNull;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;


@Test
public class OrderedExecutorTest {

    private OrderedExecutor<AsyncEventHandlingTask> executor;
    private CopyOnWriteArrayList<Long> completedTasks;
    private final String KEY = "key";

    @BeforeMethod
    public void cleanUp() {
        completedTasks = new CopyOnWriteArrayList<>();
    }

    @Test
    public void executeInOrderWithOneThreadInternalExecutor() {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        this.executor = new OrderedExecutor<>(executor, 10);

        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(300), new AsyncEvent("1", 1L)), KEY);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(50), new AsyncEvent("2", 2L)), KEY);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(1), new AsyncEvent("3", 3L)), KEY);

        ThreadUtil.sleep(600);

        assertEquals(1, completedTasks.get(0).longValue());
        assertEquals(2, completedTasks.get(1).longValue());
        assertEquals(3, completedTasks.get(2).longValue());
    }

    @Test
    public void executeInOrderWithFewThreadsInternalExecutor() {
        executor = new OrderedExecutor<>(Executors.newFixedThreadPool(3), 10);

        executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(1000), new AsyncEvent("1", 1L)), KEY);
        executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(500), new AsyncEvent("2", 2L)), KEY);
        executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(5), new AsyncEvent("3", 3L)), KEY);

        ThreadUtil.sleep(2000);

        assertEquals(1, completedTasks.get(0).longValue());
        assertEquals(2, completedTasks.get(1).longValue());
        assertEquals(3, completedTasks.get(2).longValue());
    }

    @Test
    public void executeEvenIfInternalExecutorQueueIsTooSmall() {
        final ThreadPoolExecutor internalExecutor = new ThreadPoolExecutor(1, 1, 3, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        this.executor = new OrderedExecutor<>(internalExecutor, 10);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(100), new AsyncEvent("1", 1L)), KEY);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(100), new AsyncEvent("2", 2L)), 1);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(100), new AsyncEvent("3", 3L)), 2);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(100), new AsyncEvent("4", 4L)), 3);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(100), new AsyncEvent("5", 5L)), 4);

        ThreadUtil.sleep(1000);

        assertEquals(1, completedTasks.get(0).longValue());
        assertEquals(2, completedTasks.get(1).longValue());
        assertEquals(3, completedTasks.get(2).longValue());
        assertEquals(4, completedTasks.get(3).longValue());
        assertEquals(5, completedTasks.get(4).longValue());
    }

    @Test
    public void executeAllTasksInInternalExecutorBeforeShutdown() {
        this.executor = new OrderedExecutor<>();
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(1000), new AsyncEvent("1", 1L)), KEY);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(1000), new AsyncEvent("2", 2L)), 1);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(1000), new AsyncEvent("3", 3L)), 2);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(1000), new AsyncEvent("4", 4L)), 3);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(1000), new AsyncEvent("5", 5L)), 4);

        executor.shutdown();

        while (!executor.isTerminated()){ }

        assertEquals(1, completedTasks.get(0).longValue());
        assertEquals(2, completedTasks.get(1).longValue());
        assertEquals(3, completedTasks.get(2).longValue());
        assertEquals(4, completedTasks.get(3).longValue());
        assertEquals(5, completedTasks.get(4).longValue());
    }

    @Test
    public void doNotLostEventsDuringShutdown() {//All events should be processed or available throw OrderedExecutor#getUnprocessedEvents method
        this.executor = new OrderedExecutor<>();
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(500), new AsyncEvent("1", 1L)), KEY);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(500), new AsyncEvent("2", 2L)), 1);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(500), new AsyncEvent("3", 3L)), KEY);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(500), new AsyncEvent("4", 4L)), 1);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(500), new AsyncEvent("5", 5L)), KEY);

        executor.shutdown();
        while (!executor.isTerminated()){ }

        completedTasks.addAll(executor.getUnprocessedTasks().stream().map(t -> t.getEvent().getObjectId()).collect(Collectors.toList()));

        assertEquals(1, completedTasks.get(0).longValue());
        assertEquals(2, completedTasks.get(1).longValue());
        assertEquals(3, completedTasks.get(2).longValue());
        assertEquals(4, completedTasks.get(3).longValue());
        assertEquals(5, completedTasks.get(4).longValue());
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void rejectIfInternalAndOrderedExecutorQueuesAreTooSmall() {
        final ThreadPoolExecutor internalExecutor = new ThreadPoolExecutor(1, 1, 3, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        this.executor = new OrderedExecutor<>(internalExecutor, 1);

        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(100), new AsyncEvent("1", 1L)), KEY);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(100), new AsyncEvent("2", 2L)), KEY);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(100), new AsyncEvent("3", 3L)), KEY);
        this.executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(100), new AsyncEvent("4", 4L)), KEY);
    }

    @Test(expectedExceptions = RejectedExecutionException.class)
    public void rejectAfterExecutorShutdown() {
        executor = new OrderedExecutor<>(Executors.newSingleThreadExecutor(), 2);

        executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(100), new AsyncEvent("1", 1L)), KEY);
        executor.shutdown();
        executor.execute(new AsyncEventHandlingTask(createListenerWithTimeout(100), new AsyncEvent("2", 2L)), KEY);
    }

    @NotNull
    private AsyncEventListener createListenerWithTimeout(int timeout) {
        return new AsyncEventListener() {
            @Override
            public void handle(AsyncEvent event) {
                ThreadUtil.sleep(timeout);
                completedTasks.add(event.getObjectId());
            }

            @Override
            public String getUniqName() {
                return "listenerName";
            }
        };
    }
}