package jetbrains.buildServer.webhook.async;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.webhook.async.events.AsyncEvent;
import jetbrains.buildServer.webhook.WebhooksEventListener;
import org.springframework.stereotype.Component;

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

    /**
     * Create AsyncEventDispatcher with {@link Executors#newSingleThreadExecutor()} as default internal events handling executor
     */
    public AsyncEventDispatcher(EventDispatcher<BuildServerListener> serverEventDispatcher) {
        this(serverEventDispatcher, Executors.newSingleThreadExecutor(), true);
    }

    /**
     * Create AsyncEventDispatcher with particular {@link ExecutorService} implementation
     * @param executorService executor service witch will be used for event handling
     */
    public AsyncEventDispatcher(EventDispatcher<BuildServerListener> serverEventDispatcher,
                                ExecutorService executorService) {
        this(serverEventDispatcher, executorService, false);
    }

    /**
     * Create AsyncEventDispatcher with particular {@link ExecutorService} implementation
     * @param useInternalExecutor flag indicates the fact that executor was created inside of dispatcher and should be destroyed by them during server shutdown
     */
    private AsyncEventDispatcher(EventDispatcher<BuildServerListener> serverEventDispatcher,
                                 ExecutorService executorService,
                                 boolean useInternalExecutor) {
        this.orderedExecutor = new OrderedExecutor(executorService, TeamCityProperties.getInteger("teamcity.threadpool.ordered_executor.queue.capacity", 10_000));
        serverEventDispatcher.addListener(new BuildServerAdapter() {

            @Override
            public void serverShutdown() {
                if (useInternalExecutor)
                    orderedExecutor.shutdown();
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
}
