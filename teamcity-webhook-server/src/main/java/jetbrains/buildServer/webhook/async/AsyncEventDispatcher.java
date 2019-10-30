package jetbrains.buildServer.webhook.async;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.messages.XStreamHolder;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.webhook.async.events.AsyncEvent;
import jetbrains.buildServer.xstream.XStreamFile;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static java.lang.String.format;

/**
 * Allows to publish simple events to all subscribed listeners asynchronously
 * with keeping order of execution events per {@link AsyncEventListener#getSyncKey()}
 */
@Component
public class AsyncEventDispatcher {

    private static final Logger LOG = Logger.getInstance(AsyncEventDispatcher.class.getName());

    private final Map<String, List<AsyncEventListener>> listeners = new ConcurrentHashMap<>();
    private final OrderedExecutor<AsyncEventHandlingTask> orderedExecutor;
    private final Path unprocessedEventsFilePath;

    public AsyncEventDispatcher(EventDispatcher<BuildServerListener> serverEventDispatcher,
                                ServerPaths serverPaths) {
        this.unprocessedEventsFilePath = Paths.get(serverPaths.getPluginDataDirectory() + "/webhooks/unprocessedAsyncEvents.bak");
        this.orderedExecutor = new OrderedExecutor<>();

        serverEventDispatcher.addListener(new BuildServerAdapter() {

            @Override
            public void serverStartup() {
                replaySavedEvents();
            }

            @Override
            public void serverShutdown() {
                shutdown();
            }
        });
    }

    public void publish(AsyncEvent event) {
        for (AsyncEventListener listener : listeners.getOrDefault(event.getEventType(), Collections.emptyList())) {
            try {
                orderedExecutor.execute(new AsyncEventHandlingTask(listener, event), listener.getSyncKey());
            } catch (IllegalStateException ex) {
                LOG.error("Async event executor capacity is full. Event cannot be send: " + event, ex);
            } catch (RejectedExecutionException ex) {
                LOG.error("Cannot handle event " + event, ex);
            }
        }
    }

    public void subscribe(String eventName, AsyncEventListener listener) {
        synchronized (listeners) {
            listeners.merge(eventName, new CopyOnWriteArrayList<>(new AsyncEventListener[]{listener}),
                    (l1, l2) -> {
                        if (listenerNameAlreadyRegistered(listener.getUniqName(), l1))
                            throw new IllegalStateException(format("Listener with unique name \"%s\" is already registered for event %s.", listener.getUniqName(), eventName));
                        l1.add(listener);
                        return l1;
                    });
        }
    }

    public void subscribe(List<String> eventNames, AsyncEventListener listener) {
        synchronized (listeners) {
            for (String eventName : eventNames) {
                try {
                    subscribe(eventName, listener);
                } catch (IllegalStateException ex) {
                    LOG.error(ex.getMessage());
                }
            }
        }
    }

    public void unsubscribe(AsyncEventListener eventListener) {
        synchronized (listeners) {
            listeners.forEach((event, list) -> list.remove(eventListener));
        }
    }

    public void shutdown() {
        orderedExecutor.shutdown();
        try {
            saveUnprocessedEvents();
        } catch (IOException ex) {
            LOG.error("Failed to create file with unprocessed events.", ex.getMessage());
        }
    }

    public boolean isTerminated() {
        return orderedExecutor.isTerminated();
    }

    private void saveUnprocessedEvents() throws IOException {
        synchronized (unprocessedEventsFilePath) {
            if (Files.notExists(unprocessedEventsFilePath)) {
                unprocessedEventsFilePath.getParent().toFile().mkdirs();
                Files.createFile(unprocessedEventsFilePath);
            }
            try {
                XStreamFile<List<TaskInfo>> file = new XStreamFile<>(unprocessedEventsFilePath.toFile(), new XStreamHolder());
                file.serialize(mapToTaskInfo(orderedExecutor.getUnprocessedTasks()), true);
            } catch (Throwable ex) {
                LOG.error("Failed to save unprocessed events.", ex.getMessage());
            }
        }
    }

    private void replaySavedEvents() {
        synchronized (unprocessedEventsFilePath) {
            if (Files.exists(unprocessedEventsFilePath)) {
                try {
                    XStreamFile<List<TaskInfo>> file = new XStreamFile<>(unprocessedEventsFilePath.toFile(), new XStreamHolder());
                    List<TaskInfo> queue = file.deserialize();
                    queue.forEach(this::executeTask);
                } catch (Throwable ex) {
                    LOG.error("Failed to replay saved events. " + ex.getMessage());
                }
            }
        }
    }

    private void executeTask(TaskInfo task) {
        for (AsyncEventListener listener : listeners.getOrDefault(task.getEvent().getEventType(), Collections.emptyList())) {
            if (listener.getUniqName().equals(task.getRunnerName())) {
                orderedExecutor.execute(new AsyncEventHandlingTask(listener, task.getEvent()), listener.getSyncKey());
                return;
            }
        }
    }

    private boolean listenerNameAlreadyRegistered(String listenerName, List<AsyncEventListener> list) {
        return list.stream().anyMatch(l -> l.getUniqName().equals(listenerName));
    }

    @NotNull
    private List<TaskInfo> mapToTaskInfo(List<AsyncEventHandlingTask> tasks) {
        return tasks.stream()
                .map(t -> new TaskInfo(t.getListener().getUniqName(), t.getEvent()))
                .collect(Collectors.toList());
    }

    static class TaskInfo {
        private final AsyncEvent event;
        private final String runnerName;

        public TaskInfo(@NotNull String runnerName, @NotNull AsyncEvent event) {
            this.event = event;
            this.runnerName = runnerName;
        }

        public AsyncEvent getEvent() {
            return event;
        }

        public String getRunnerName() {
            return runnerName;
        }
    }
}
