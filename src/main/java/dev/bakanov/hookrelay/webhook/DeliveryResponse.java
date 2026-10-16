package dev.bakanov.hookrelay.webhook;

public record DeliveryResponse(boolean delivered, int httpStatus) {
}
