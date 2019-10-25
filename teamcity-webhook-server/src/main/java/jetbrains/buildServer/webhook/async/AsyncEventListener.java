package jetbrains.buildServer.webhook.async;

import jetbrains.buildServer.webhook.async.events.AsyncEvent;
import java.util.EventListener;
import java.util.List;

public interface AsyncEventListener extends EventListener {

    void handle(AsyncEvent event);

    default void handle(List<AsyncEvent> events) {
        for (AsyncEvent event : events) {
            handle(event);
        }
    }

    /**
     * Overriding this method allows to define a list of listeners for which order of handling events is important
     * @return key witch will be used for synchronization ordering events during the processing in {@link AsyncEventDispatcher}
     */
    default Object getSyncKey() {
        return this.hashCode();
    }
}
