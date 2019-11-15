package jetbrains.buildServer.webhook;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.http.SimpleCredentials;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.ProjectNotFoundException;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.impl.ProjectEx;
import jetbrains.buildServer.util.HTTPRequestBuilder;
import jetbrains.buildServer.util.http.HttpMethod;
import jetbrains.buildServer.util.ssl.SSLTrustStoreProvider;
import jetbrains.buildServer.webhook.async.AsyncEventListener;
import jetbrains.buildServer.webhook.async.events.AsyncEvent;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.lang.String.format;

/**
 * {@link AsyncEventListener} produces webhook information and send it regarding the {@link ProjectEx} configuration
 */
@Component
public class WebhooksEventListener implements AsyncEventListener {

    private static final Logger LOG = Logger.getInstance(WebhooksEventListener.class.getName());

    private final WebhookDataProducer jsonProducer;
    private final SBuildServer buildServer;
    private final SSLTrustStoreProvider sslTrustStoreProvider;
    private final HTTPRequestBuilder.RequestHandler requestHandler;

    public WebhooksEventListener(WebhookDataProducer jsonProducer,
                                 SBuildServer buildServer,
                                 SSLTrustStoreProvider sslTrustStoreProvider,
                                 HTTPRequestBuilder.RequestHandler requestHandler) {
        this.jsonProducer = jsonProducer;
        this.buildServer = buildServer;
        this.sslTrustStoreProvider = sslTrustStoreProvider;
        this.requestHandler = requestHandler;
    }

    @Override
    public void handle(AsyncEvent event) {
        try {
            ProjectEx project = getRelatedProject(event);
            if (isWebhooksEnabled(project, event.getEventType())) {
                String webhooksUrl = getWebhooksUrl(project);
                if (jsonProducer.support(event)) {
                    String eventData = jsonProducer.getJson(event, getResponseFields(project, event.getEventType()));
                    sendWithRetry(webhooksUrl, getWebhooksCredential(project), eventData, getRetryCount(project));
                } else {
                    LOG.warn("Unsupported event type " + event.getEventType());
                }
            }
        } catch (ProjectNotFoundException ex) {
            LOG.warn("Related project not found for event " + event);
        } catch (Throwable throwable) {
            LOG.error(throwable);
        }
    }

    private Integer getRetryCount(ProjectEx project) {
        Integer defaultRetryCount = 0;
        final String retryCountParameter = project.getInternalParameterValue("teamcity.internal.webhooks.retry_count", defaultRetryCount.toString());
        try {
            return Integer.valueOf(retryCountParameter);
        } catch (NumberFormatException ex) {
            LOG.warn(format("Project %s parameter teamcity.internal.webhooks.retry_count is not a number. Default value %s will be used.", project.getName(), defaultRetryCount));
        }
        return defaultRetryCount;
    }

    private ProjectEx getRelatedProject(AsyncEvent event) throws ProjectNotFoundException {
        ProjectManager myProjectManager = buildServer.getProjectManager();

        return event.getProjectId() == null
                ? (ProjectEx) myProjectManager.getRootProject()
                : (ProjectEx) myProjectManager.findProjectById(event.getProjectId());
    }

    private boolean isWebhooksEnabled(ProjectEx project, String eventType) {
        if (project.getBooleanInternalParameter("teamcity.internal.webhooks.enable")) {
            String eventsParameter = project.getInternalParameterValue("teamcity.internal.webhooks.events", "");
            List<String> events = parseEvents(eventsParameter);
            return events.contains(eventType);
        }
        return false;
    }

    @NotNull
    private List<String> parseEvents(String eventsString) {
        return Arrays.stream(eventsString.split(";"))
                .map(String::trim).collect(Collectors.toList());
    }

    private String getWebhooksUrl(ProjectEx project) {
        final String urlParameter = project.getInternalParameterValue("teamcity.internal.webhooks.url", "");
        if (urlParameter.isEmpty())
            throw new IllegalStateException("Webhooks url is not defined for project " + project.getName());
        return urlParameter;
    }

    private SimpleCredentials getWebhooksCredential(ProjectEx project) {
        String username = project.getInternalParameterValue("teamcity.internal.webhooks.username", "");
        String password = project.getParametersProvider().get("teamcity.internal.webhooks.password");

        if (username.isEmpty() || password == null) return null;

        return new SimpleCredentials(username, password);
    }

    private String getResponseFields(ProjectEx project, String event) {
        return project.getInternalParameterValue("teamcity.internal.webhooks." + event + ".fields", "");
    }

    private void sendWithRetry(String uri, SimpleCredentials simpleCredentials, String json, Integer retryCount) {
        AtomicInteger retryCountRef = new AtomicInteger(retryCount);

        Consumer<Exception> exception = (ex) -> {
            if (retryCountRef.get() > 0)
                sendWithRetry(uri, simpleCredentials, json, retryCountRef.decrementAndGet());
            else
                LOG.error(format("Sending webhook to %s failed with exception %s. Webhook info: %s.", uri, ex.getMessage(), json));
        };
        BiConsumer<Integer, String> error = (code, message) -> {
            if (retryCountRef.get() > 0)
                sendWithRetry(uri, simpleCredentials, json, retryCountRef.decrementAndGet());
            else
                LOG.error(format("Sending webhook to %s failed with HTTP code: %s %s. Webhook info: %s.", uri, code, message, json));
        };

        try {
            HTTPRequestBuilder request = new HTTPRequestBuilder(uri)
                    .withTimeout(60 * 1000)
                    .withAuthenticateHeader(simpleCredentials)
                    .withTrustStore(sslTrustStoreProvider.getTrustStore())
                    .allowNonSecureConnection(true)
                    .withEncodingInterceptor(true)
                    .withHeader(Collections.singletonMap("Content-Type", "application/json"))
                    .withMethod(HttpMethod.POST)
                    .withPostStringEntity(json, "application/json", StandardCharsets.UTF_8)
                    .onException(exception)
                    .onErrorResponse(error);

            requestHandler.doRequest(request.build());

        } catch (URISyntaxException ex) {
            LOG.error(format("Sending webhook to %s failed because of wrong URL syntax. Exception message: %s. Webhook info: %s.", uri, ex.getMessage(), json));
        }
    }
}
