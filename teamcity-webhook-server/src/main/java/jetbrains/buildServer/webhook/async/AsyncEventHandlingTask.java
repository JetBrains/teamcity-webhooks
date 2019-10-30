package jetbrains.buildServer.webhook.async;

import jetbrains.buildServer.webhook.async.events.AsyncEvent;

public class AsyncEventHandlingTask implements Runnable {
    private final AsyncEventListener listener;
    private final AsyncEvent event;

    public AsyncEventHandlingTask(AsyncEventListener listener, AsyncEvent event) {
        this.listener = listener;
        this.event = event;
    }

    public AsyncEvent getEvent() {
        return event;
    }

    public AsyncEventListener getListener() {
        return listener;
    }

    @Override
    public void run() {
        listener.handle(event);
    }
}
