package dev.bakanov.hookrelay.webhook;

import java.time.Instant;
import java.util.UUID;

public record DeliveryDetails(UUID deliveryId, String event, String status, Integer httpStatus,
        String errorMessage, Instant createdAt, Instant completedAt) {
}
