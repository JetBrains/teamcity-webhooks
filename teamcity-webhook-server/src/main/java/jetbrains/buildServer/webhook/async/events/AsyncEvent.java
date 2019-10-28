package jetbrains.buildServer.webhook.async.events;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

import static java.lang.String.format;

/**
 * Simple event used by {@link jetbrains.buildServer.webhook.async.AsyncEventDispatcher}
 * to allows listeners {@link jetbrains.buildServer.webhook.async.AsyncEventListener} handle it asynchronously
 */
public class AsyncEvent implements Serializable {
    private final String eventType;
    private final Long objectId;
    private final String projectId;

    public AsyncEvent(@NotNull String eventType, @NotNull Long objectId) {
        this(eventType, objectId, null);
    }

    public AsyncEvent(@NotNull String eventType, @NotNull Long objectId, String projectId) {
        this.eventType = eventType;
        this.objectId = objectId;
        this.projectId = projectId;
    }

    @NotNull
    public String getEventType() {
        return eventType;
    }

    @NotNull
    public Long getObjectId() {
        return objectId;
    }

    @Nullable
    public String getProjectId() {
        return projectId;
    }

    @Override
    public String toString() {
        return format("%s (objectId: %s, projectId: %s)", eventType, objectId, projectId);
    }
}
