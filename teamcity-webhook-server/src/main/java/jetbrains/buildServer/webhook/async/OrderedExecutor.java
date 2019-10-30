package jetbrains.buildServer.webhook.async;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class OrderedExecutor<T extends Runnable> {

    private static final Logger LOG = Logger.getInstance(OrderedExecutor.class.getName());
    private final ExecutorService executor;
    private final Boolean useInternalExecutor;
    private final Queue<OrderedTask> tasks;
    private final Set<Object> submittedKeys;
    private volatile Boolean isShutdown = false;

    /**
     * Create OrderedExecutor with {@link Executors#newSingleThreadExecutor()} as default internal events handling executor
     */
    public OrderedExecutor() {
        this(Executors.newSingleThreadExecutor(), true, TeamCityProperties.getInteger("teamcity.threadpool.ordered_executor.queue.capacity", 10_000));
    }

    /**
     * Create OrderedExecutor with particular {@link ExecutorService} implementation
     *
     * @param executor executor service witch will be used for event handling
     */
    public OrderedExecutor(ExecutorService executor) {
        this(executor, false, TeamCityProperties.getInteger("teamcity.threadpool.ordered_executor.queue.capacity", 10_000));
    }

    public OrderedExecutor(ExecutorService executor, int internalQueueCapacity) {
        this(executor, false, internalQueueCapacity);
    }

    /**
     * Create OrderedExecutor with particular {@link ExecutorService} implementation
     *
     * @param useInternalExecutor flag indicates the fact that executor was created inside of OrderedExecutor and should be destroyed by them during shutdown
     */
    private OrderedExecutor(ExecutorService executor, Boolean useInternalExecutor, int internalQueueCapacity) {
        this.executor = executor;
        this.useInternalExecutor = useInternalExecutor;
        submittedKeys = new HashSet<>();
        tasks = new LinkedBlockingQueue<>(internalQueueCapacity);
    }

    void execute(@NotNull T task, Object key) {
        synchronized (submittedKeys) {
            if (isShutdown) {
                shutdownRejectExecution();
            }
            final OrderedTask orderedTask = new OrderedTask(task, key);
            tasks.add(orderedTask);
            if (!submittedKeys.contains(key)) {
                submitIfPossible( orderedTask);
            }
        }
    }

    public void shutdown() {
        synchronized (submittedKeys) {
            isShutdown = true;
            if (useInternalExecutor)
                executor.shutdown();
        }
    }

    public boolean isTerminated() {
        return executor.isTerminated();
    }

    public List<T> getUnprocessedTasks() {
        return tasks.stream()
                .map(OrderedTask::getRunnable)
                .collect(Collectors.toList());
    }

    class OrderedTask implements Runnable {
        private final T runnable;
        private final Object key;

        OrderedTask(T runnable, Object key) {
            this.runnable = runnable;
            this.key = key;
        }

        @Override
        public void run() {
            try {
                runnable.run();
            } catch (Throwable t) {
                LOG.warn(t.getMessage());
            }
            synchronized (submittedKeys) {
                submittedKeys.remove(key);
                if (!isShutdown) {
                    final OrderedTask nextTask = getNextTask();
                    if (nextTask != null) {
                        submitIfPossible(nextTask);
                    }
                }
            }
        }

        public T getRunnable() {
            return runnable;
        }

        private OrderedTask getNextTask() {
            return tasks.stream()
                    .filter(t -> !submittedKeys.contains(t.key))
                    .findFirst().orElse(null);
        }
    }

    private void submitIfPossible(OrderedTask orderedTask) {
        try {
            executor.submit(orderedTask);
            submittedKeys.add(orderedTask.key);
            tasks.remove(orderedTask);
        } catch (RejectedExecutionException ex) {
            if (executor.isShutdown())
                shutdownRejectExecution();
        }
    }

    private void shutdownRejectExecution() {
        throw new RejectedExecutionException("Executor shutdown in progress.");
    }
}
