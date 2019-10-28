package jetbrains.buildServer.webhook.async;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.webhook.async.events.AsyncEvent;
import jetbrains.buildServer.webhook.WebhooksEventListener;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/**
 * Allows to publish simple events to all subscribed listeners asynchronously
 * with keeping order of execution events per {@link AsyncEventListener#getSyncKey()}
 */
@Component
public class AsyncEventDispatcher {

    private static final Logger LOG = Logger.getInstance(AsyncEventDispatcher.class.getName());

    private final Map<String, List<AsyncEventListener>> listeners = new ConcurrentHashMap<>();
    private final OrderedExecutor orderedExecutor;
    private final Path unprocessedEventsFilePath;

    public AsyncEventDispatcher(EventDispatcher<BuildServerListener> serverEventDispatcher,
                                ServerPaths serverPaths,
                                OrderedExecutor orderedExecutor) {
        this.unprocessedEventsFilePath = Paths.get(serverPaths.getPluginDataDirectory() + "/webhooks/unprocessedAsyncEvents.bak");
        this.orderedExecutor = orderedExecutor;

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
                orderedExecutor.execute(listener::handle, event, listener.getSyncKey());
            } catch (IllegalStateException ex) {
                LOG.error("Async event executor capacity is full. Event cannot be send: " + event, ex);
            } catch (RejectedExecutionException ex) {
                LOG.error("Cannot handle event " + event, ex);
            }
        }
    }

    public void subscribe(String event, AsyncEventListener listener) {
        synchronized (listeners) {
            listeners.merge(event, new CopyOnWriteArrayList<>(new AsyncEventListener[] {listener}), (v1, v2) -> {
                v1.addAll(v2);
                return v1;
            });
        }
    }

    public void subscribe(List<String> events, AsyncEventListener listener) {
        synchronized (listeners) {
            for (String event : events) {
                listeners.merge(event, new CopyOnWriteArrayList<>(new AsyncEventListener[] {listener}), (v1, v2) -> {
                    v1.addAll(v2);
                    return v1;
                });
            }
        }
    }

    public void unsubscribe(WebhooksEventListener eventListener) {
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
            try (FileOutputStream fileOutputStream = new FileOutputStream(unprocessedEventsFilePath.toFile(), false);
                 ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream)) {
                objectOutputStream.writeObject(orderedExecutor.getUnprocessedEvents());
            } catch (Throwable ex) {
                LOG.error("Failed to save unprocessed events.", ex.getMessage());
            }
        }
    }

    private void replaySavedEvents() {
        synchronized (unprocessedEventsFilePath) {
            if (Files.exists(unprocessedEventsFilePath)) {
                try (FileInputStream fileInputStream = new FileInputStream(unprocessedEventsFilePath.toFile());
                     ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream)) {

                    List<AsyncEvent> queue = (List<AsyncEvent>) objectInputStream.readObject();
                    queue.forEach(AsyncEventDispatcher.this::publish);

                } catch (Exception ex) {
                    LOG.error("Failed to replay saved events. " + ex.getMessage());
                }
            }
        }
    }
}
