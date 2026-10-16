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

    public DeliveryResponse deliver(WebhookRequest request) {
        UUID id = repository.create(request, targetUrl);
        int httpStatus;
        try {
            httpStatus = restClient.post()
                    .uri(targetUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, response) -> response.getStatusCode().value());
        } catch (RestClientException exception) {
            repository.fail(id, null, "Webhook recipient is unavailable");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Webhook recipient is unavailable", exception);
        }
        if (httpStatus < 200 || httpStatus >= 300) {
            repository.fail(id, httpStatus, "Webhook recipient returned HTTP " + httpStatus);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Webhook recipient rejected the delivery");
        }
        repository.complete(id, httpStatus);
        return new DeliveryResponse(id, true, httpStatus);
    }
}
