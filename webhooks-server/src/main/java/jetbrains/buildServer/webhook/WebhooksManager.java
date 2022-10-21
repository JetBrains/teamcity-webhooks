package jetbrains.buildServer.webhook;

import java.util.Arrays;
import jetbrains.buildServer.plugins.PluginLifecycleListenerAdapter;
import jetbrains.buildServer.plugins.impl.PluginLifecycleEventDispatcher;
import jetbrains.buildServer.webhook.async.AsyncEventDispatcher;
import org.springframework.stereotype.Component;

import static jetbrains.buildServer.webhook.WebhooksManager.EventNames.*;

@Component
public class WebhooksManager {

    public static final class EventNames {
        public static final String AGENT_REGISTRED = "AGENT_REGISTRED";
        public static final String AGENT_UNREGISTERED = "AGENT_UNREGISTERED";
        public static final String AGENT_REMOVED = "AGENT_REMOVED";
        public static final String BUILD_STARTED = "BUILD_STARTED";
        public static final String BUILD_FINISHED = "BUILD_FINISHED";
        public static final String BUILD_INTERRUPTED = "BUILD_INTERRUPTED";
        public static final String CHANGES_LOADED = "CHANGES_LOADED";
        public static final String BUILD_TYPE_ADDED_TO_QUEUE = "BUILD_TYPE_ADDED_TO_QUEUE";
        public static final String BUILD_REMOVED_FROM_QUEUE = "BUILD_REMOVED_FROM_QUEUE";
        public static final String BUILD_PROBLEMS_CHANGED = "BUILD_PROBLEMS_CHANGED";
        public static final String MARKED_AS_SUCCESSFUL = "MARKED_AS_SUCCESSFUL";
        public static final String FAILURE_DETECTED = "FAILURE_DETECTED";
    }

    public WebhooksManager(PluginLifecycleEventDispatcher dispatcher,
                           AsyncEventDispatcher asyncEventDispatcher,
                           WebhooksEventListener eventListener) {

        asyncEventDispatcher.subscribe(Arrays.asList(AGENT_REGISTRED, AGENT_UNREGISTERED, AGENT_REMOVED
          , BUILD_STARTED, BUILD_FINISHED, BUILD_INTERRUPTED, CHANGES_LOADED, BUILD_TYPE_ADDED_TO_QUEUE, BUILD_REMOVED_FROM_QUEUE, BUILD_PROBLEMS_CHANGED), eventListener);

        dispatcher.addListener(new PluginLifecycleListenerAdapter() {
            @Override
            public void beforePluginUnloaded() {
                asyncEventDispatcher.unsubscribe(eventListener);
            }
        });
    }
}
