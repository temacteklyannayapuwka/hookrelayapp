package dev.bakanov.hookrelay.webhook;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/{id}")
    public DeliveryDetails findById(@PathVariable UUID id) {
        return deliveryService.findById(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DeliveryResponse> enqueue(@Valid @RequestBody WebhookRequest request) {
        DeliveryResponse response = deliveryService.enqueue(request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/webhooks/" + response.deliveryId()))
                .body(response);
    }
}
