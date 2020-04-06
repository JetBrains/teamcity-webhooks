package jetbrains.buildServer.webhook;

import jetbrains.buildServer.webhook.async.events.AsyncEvent;

/**
 * Implementation of this interface and mark it as {@link org.springframework.context.annotation.Primary}
 * allows to override webhooks data format and use custom one instead of {@link RestApiProducer}
 */
public interface WebhookDataProducer {

    /**
     * Generate payload for {@param event} includes fields describing in {@param fields}
     * @param event event triggered webhook sending
     * @param fields list of required fields in webhook
     * @return
     */
    String getJson(AsyncEvent event, String fields);

    /**
     * @return true if webhook payload can be generate for {@param event},
     *  or false if the payload cannot be generated
     */
    boolean support(AsyncEvent event);
}
