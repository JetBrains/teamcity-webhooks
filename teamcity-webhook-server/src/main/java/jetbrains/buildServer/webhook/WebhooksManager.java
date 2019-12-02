package jetbrains.buildServer.webhook;

import jetbrains.buildServer.BuildProblemData;
import jetbrains.buildServer.plugins.PluginLifecycleListenerAdapter;
import jetbrains.buildServer.plugins.impl.PluginLifecycleEventDispatcher;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.webhook.async.AsyncEventDispatcher;
import jetbrains.buildServer.webhook.async.events.AsyncEvent;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.List;

import static jetbrains.buildServer.webhook.WebhooksManager.EventNames.*;

@Component
public class WebhooksManager {

    static final class EventNames {
        static final String AGENT_REGISTRED = "AGENT_REGISTRED";
        static final String AGENT_UNREGISTERED = "AGENT_UNREGISTERED";
        static final String AGENT_REMOVED = "AGENT_REMOVED";
        static final String BUILD_STARTED = "BUILD_STARTED";
        static final String BUILD_FINISHED = "BUILD_FINISHED";
        static final String BUILD_INTERRUPTED = "BUILD_INTERRUPTED";
        static final String CHANGES_LOADED = "CHANGES_LOADED";
        static final String BUILD_TYPE_ADDED_TO_QUEUE = "BUILD_TYPE_ADDED_TO_QUEUE";
        static final String BUILD_PROBLEMS_CHANGED = "BUILD_PROBLEMS_CHANGED";
    }

    public WebhooksManager(PluginLifecycleEventDispatcher dispatcher,
                           EventDispatcher<BuildServerListener> serverEventDispatcher,
                           AsyncEventDispatcher asyncEventDispatcher,
                           WebhooksEventListener eventListener) {

        asyncEventDispatcher.subscribe(Arrays.asList(AGENT_REGISTRED, AGENT_UNREGISTERED, AGENT_REMOVED
                , BUILD_STARTED, BUILD_FINISHED, BUILD_INTERRUPTED, CHANGES_LOADED, BUILD_TYPE_ADDED_TO_QUEUE, BUILD_PROBLEMS_CHANGED), eventListener);

        dispatcher.addListener(new PluginLifecycleListenerAdapter() {
            @Override
            public void beforePluginUnloaded() {
                asyncEventDispatcher.unsubscribe(eventListener);
            }
        });

        serverEventDispatcher.addListener(new BuildServerAdapter() {
            @Override
            public void agentRegistered(@NotNull SBuildAgent agent, final long currentlyRunningBuildId) {
                asyncEventDispatcher.publish(new AsyncEvent(AGENT_REGISTRED, (long) agent.getId()));
            }

            @Override
            public void agentUnregistered(@NotNull SBuildAgent agent) {
                asyncEventDispatcher.publish(new AsyncEvent(AGENT_UNREGISTERED, (long) agent.getId()));
            }

            @Override
            public void agentRemoved(@NotNull SBuildAgent agent) {
                asyncEventDispatcher.publish(new AsyncEvent(AGENT_REMOVED, (long) agent.getId()));
            }

            @Override
            public void buildTypeAddedToQueue(@NotNull final SQueuedBuild queuedBuild) {
                final BuildPromotion buildPromotion = queuedBuild.getBuildPromotion();
                asyncEventDispatcher.publish(new AsyncEvent(BUILD_TYPE_ADDED_TO_QUEUE, buildPromotion.getId(), buildPromotion.getProjectId()));
            }

            @Override
            public void buildStarted(@NotNull SRunningBuild build) {
                asyncEventDispatcher.publish(new AsyncEvent(BUILD_STARTED, build.getBuildPromotion().getId(), build.getProjectId()));
            }

            @Override
            public void buildFinished(@NotNull SRunningBuild build) {
                asyncEventDispatcher.publish(new AsyncEvent(BUILD_FINISHED, build.getBuildPromotion().getId(), build.getProjectId()));
            }

            @Override
            public void buildInterrupted(@NotNull SRunningBuild build) {
                asyncEventDispatcher.publish(new AsyncEvent(BUILD_INTERRUPTED, build.getBuildPromotion().getId(), build.getProjectId()));
            }

            @Override
            public void changesLoaded(@NotNull SRunningBuild build) {
                asyncEventDispatcher.publish(new AsyncEvent(CHANGES_LOADED, build.getBuildPromotion().getId(), build.getProjectId()));
            }

            @Override
            public void buildProblemsChanged(@NotNull final SBuild build,
                                             @NotNull final List<BuildProblemData> before,
                                             @NotNull final List<BuildProblemData> after) {
                asyncEventDispatcher.publish(new AsyncEvent(BUILD_PROBLEMS_CHANGED, build.getBuildPromotion().getId(), build.getProjectId()));
            }
        });
    }
}
