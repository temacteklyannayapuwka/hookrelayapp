package dev.bakanov.hookrelay.webhook;

import java.util.UUID;

public record DeliveryResponse(UUID deliveryId, boolean delivered, int httpStatus) {
}
