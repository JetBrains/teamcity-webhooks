package jetbrains.buildServer.webhook;

import jetbrains.buildServer.web.impl.RestApiFacade;
import jetbrains.buildServer.webhook.async.events.AsyncEvent;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

import static java.lang.String.format;
import static jetbrains.buildServer.webhook.WebhooksManager.EventNames.*;
import static jetbrains.buildServer.webhook.WebhooksManager.EventNames.BUILD_INTERRUPTED;

/**
 * {@link WebhookDataProducer} uses TeamCity rest-api for building webhooks payload
 */
@Component
public class RestApiProducer implements WebhookDataProducer {

    private enum EventType {
        AGENT(Arrays.asList(AGENT_REGISTRED, AGENT_UNREGISTERED, AGENT_REMOVED), "/app/rest/agents/id:"),
        BUILD(Arrays.asList(BUILD_STARTED, BUILD_FINISHED, BUILD_INTERRUPTED, CHANGES_LOADED), "/app/rest/builds/promotionId:");

        private String restApiUrl;
        private List<String> events;

        EventType(List<String> events, String restApiUrl) {
            this.events = events;
            this.restApiUrl = restApiUrl;
        }

        static EventType getEventType(String event) {
            for(EventType type : EventType.values()) {
                if (type.events.contains(event))
                    return type;
            }
            throw new IllegalArgumentException(format("Event %s is not supported.", event));
        }

        String getRestApiUrl() {
            return restApiUrl;
        }
    }

    private final RestApiFacade restApiFacade;

    public RestApiProducer(RestApiFacade restApiFacade) {
        this.restApiFacade = restApiFacade;
    }

    @Override
    public String getJson(AsyncEvent event, String fields) {
        EventType eventType = EventType.getEventType(event.getEventType());
        String objectRestUrl = eventType.getRestApiUrl() + event.getObjectId();
        try {
            return format("{ \"eventType\" : \"%s\", \"payload\" : %s }", event.getEventType(), restApiFacade.getJson(objectRestUrl, fields));
        } catch (RestApiFacade.InternalRestApiCallException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean support(AsyncEvent event) {
        try {
            EventType.getEventType(event.getEventType());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return true;
    }
}
