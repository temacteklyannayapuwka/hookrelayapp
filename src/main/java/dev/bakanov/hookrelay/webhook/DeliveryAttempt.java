package dev.bakanov.hookrelay.webhook;

import java.time.Instant;

public record DeliveryAttempt(int attemptNumber, Integer httpStatus, String errorMessage, Instant finishedAt) {
}
