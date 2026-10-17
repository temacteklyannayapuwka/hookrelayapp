package dev.bakanov.hookrelay.webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WebhookDeliveryService {

    private final RestClient restClient;
    private final URI targetUrl;
    private final DeliveryRepository repository;

    public WebhookDeliveryService(RestClient.Builder builder,
            @Value("${hookrelay.delivery.target-url}") URI targetUrl,
            DeliveryRepository repository) {
        if (targetUrl.getHost() == null || !("http".equalsIgnoreCase(targetUrl.getScheme())
                || "https".equalsIgnoreCase(targetUrl.getScheme()))) {
            throw new IllegalArgumentException("Webhook target must be an absolute HTTP or HTTPS URL");
        }
        this.targetUrl = targetUrl;
        this.repository = repository;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = builder.requestFactory(factory).build();
    }

    public DeliveryDetails findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found"));
    }

    public DeliveryResponse enqueue(WebhookRequest request) {
        UUID id = repository.create(request, targetUrl);
        return new DeliveryResponse(id, "PENDING");
    }

    @Transactional
    public boolean processNext() {
        var next = repository.lockNext();
        if (next.isEmpty()) {
            return false;
        }
        PendingDelivery delivery = next.get();
        Integer httpStatus = null;
        String errorMessage = null;
        try {
            httpStatus = restClient.post()
                    .uri(URI.create(delivery.targetUrl()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-HookRelay-Delivery-Id", delivery.id().toString())
                    .body(delivery.body())
                    .exchange((httpRequest, response) -> response.getStatusCode().value());
            if (httpStatus < 200 || httpStatus >= 300) {
                errorMessage = "Webhook recipient returned HTTP " + httpStatus;
            }
        } catch (RestClientException exception) {
            errorMessage = "Webhook recipient is unavailable";
        }
        int attemptCount = repository.recordAttempt(delivery.id(), httpStatus, errorMessage);
        if (errorMessage == null) {
            repository.complete(delivery.id(), httpStatus);
        } else if (DeliveryRetryPolicy.shouldRetry(httpStatus, attemptCount)) {
            repository.scheduleRetry(delivery.id(), DeliveryRetryPolicy.delayAfter(attemptCount));
        } else {
            repository.fail(delivery.id(), httpStatus, errorMessage);
        }
        return true;
    }
}
