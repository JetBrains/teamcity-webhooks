package jetbrains.buildServer.webhook.async;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.webhook.async.events.AsyncEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class OrderedExecutor {

    private static final Logger LOG = Logger.getInstance(OrderedExecutor.class.getName());
    private final ExecutorService executor;
    private final Queue<OrderedTask> tasks;
    private final Set<Object> submittedKeys;
    private AtomicBoolean isShutdown = new AtomicBoolean(false);

    public OrderedExecutor(ExecutorService executor, int internalQueueCapacity) {
        this.executor = executor;
        submittedKeys = new HashSet<>();
        tasks = new LinkedBlockingQueue<>(internalQueueCapacity);
    }

    void execute(@NotNull Consumer<AsyncEvent> task, @NotNull AsyncEvent event, Object key) {
        if(isShutdown.get()) {
            shutdownRejectExecution();
        }

        synchronized (submittedKeys) {
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
            isShutdown.set(true);
            submittedKeys.clear();
            tasks.clear();
            executor.shutdown();
        }
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
