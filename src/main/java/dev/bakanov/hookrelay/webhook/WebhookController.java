package dev.bakanov.hookrelay.webhook;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final WebhookDeliveryService deliveryService;

    public WebhookController(WebhookDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public DeliveryResponse deliver(@Valid @RequestBody WebhookRequest request) {
        return deliveryService.deliver(request);
    }
}
