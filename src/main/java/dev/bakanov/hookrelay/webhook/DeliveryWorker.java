package dev.bakanov.hookrelay.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hookrelay.delivery.worker-enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryWorker {

    private final WebhookDeliveryService deliveryService;

    public DeliveryWorker(WebhookDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @Scheduled(fixedDelayString = "${hookrelay.delivery.poll-interval-ms:1000}")
    public void processNext() {
        deliveryService.processNext();
    }
}
