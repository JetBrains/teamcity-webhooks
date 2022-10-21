package jetbrains.buildServer.webhook.async;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.messages.XStreamHolder;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.serverSide.impl.events.async.AsyncEvent;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.xstream.XStreamFile;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
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

    private final jetbrains.buildServer.serverSide.impl.events.async.AsyncEventDispatcher myDelegate;

    public AsyncEventDispatcher(@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
                                @Qualifier("coreAsyncEventDispatcher")
                                jetbrains.buildServer.serverSide.impl.events.async.AsyncEventDispatcher delegate) {
        myDelegate = delegate;
    }

    public void subscribe(String eventName, AsyncEventListener listener) {
        myDelegate.subscribe(eventName, wrapListener(listener));
    }

    public void subscribe(List<String> eventNames, AsyncEventListener listener) {
        myDelegate.subscribe(eventNames, wrapListener(listener));
    }

    public void unsubscribe(AsyncEventListener eventListener) {
        myDelegate.unsubscribe(wrapListener(eventListener));
    }

    private jetbrains.buildServer.serverSide.impl.events.async.AsyncEventListener wrapListener(AsyncEventListener listener) {
        return new jetbrains.buildServer.serverSide.impl.events.async.AsyncEventListener() {
            @Override
            public void handle(AsyncEvent event) {
                listener.handle(wrapEvent(event));
            }

            @NotNull
            private jetbrains.buildServer.webhook.async.events.AsyncEvent wrapEvent(AsyncEvent event) {
                return new jetbrains.buildServer.webhook.async.events.AsyncEvent(event.getEventType(), event.getObjectId(), event.getProjectId());
            }

            @Override
            public int hashCode() {
                return listener.hashCode();
            }

            @Override
            public boolean equals(Object obj) {
                return listener.equals(obj);
            }

            @Override
            public void handle(List<AsyncEvent> events) {
                listener.handle(events.stream().map(this::wrapEvent).collect(Collectors.toList()));
            }

            @Override
            public Object getSyncKey() {
                return listener.getSyncKey();
            }

            @NotNull
            @Override
            public String getUniqName() {
                return listener.getUniqName();
            }
        };
    }
}
