package dev.bakanov.hookrelay.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record WebhookRequest(@NotBlank String event, @NotNull JsonNode payload) {
}
