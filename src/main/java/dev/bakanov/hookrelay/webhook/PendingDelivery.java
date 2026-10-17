package dev.bakanov.hookrelay.webhook;

import java.util.UUID;

record PendingDelivery(UUID id, String targetUrl, String body) {
}
