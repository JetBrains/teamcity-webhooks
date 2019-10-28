package jetbrains.buildServer.webhook.async;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.webhook.async.events.AsyncEvent;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
public class OrderedExecutor {

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

    void execute(@NotNull Consumer<AsyncEvent> task, @NotNull AsyncEvent event, Object key) {
        synchronized (submittedKeys) {
            if (isShutdown) {
                shutdownRejectExecution();
            }

            if (!submittedKeys.contains(key)) {
                try {
                    executor.submit(new OrderedTask(task, event, key));
                    submittedKeys.add(key);
                } catch (RejectedExecutionException ex) {
                    if (executor.isShutdown())
                        shutdownRejectExecution();
                    tasks.add(new OrderedTask(task, event, key));
                }
            } else {
                tasks.add(new OrderedTask(task, event, key));
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

    public List<AsyncEvent> getUnprocessedEvents() {
        return tasks.stream()
                .map(OrderedTask::getEvent)
                .collect(Collectors.toList());
    }

    class OrderedTask implements Runnable {
        private final Consumer<AsyncEvent> runnable;
        private final AsyncEvent event;
        private final Object key;

        OrderedTask(Consumer<AsyncEvent> runnable, AsyncEvent event, Object key) {
            this.runnable = runnable;
            this.event = event;
            this.key = key;
        }

        @Override
        public void run() {
            try {
                runnable.accept(event);
            } catch (Throwable t) {
                LOG.warn(t.getMessage());
            }
            synchronized (submittedKeys) {
                submittedKeys.remove(key);
                if (!isShutdown) {
                    final OrderedTask nextTask = getNextTask();

                    if (nextTask != null) {
                        try {
                            executor.submit(nextTask);
                            submittedKeys.add(nextTask.key);
                            tasks.remove(nextTask);
                        } catch (RejectedExecutionException ex) {
                            if (executor.isShutdown())
                                shutdownRejectExecution();
                            LOG.warn(ex.getMessage());
                        }
                    }
                }
            }
        }

        public AsyncEvent getEvent() {
            return event;
        }

        private OrderedTask getNextTask() {
            return tasks.stream()
                    .filter(t -> !submittedKeys.contains(t.key))
                    .findFirst().orElse(null);
        }
    }

    private void shutdownRejectExecution() {
        throw new RejectedExecutionException("Executor shutdown in progress.");
    }
}
