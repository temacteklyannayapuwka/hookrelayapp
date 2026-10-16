package dev.bakanov.hookrelay.webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
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

    public WebhookDeliveryService(RestClient.Builder builder,
            @Value("${hookrelay.delivery.target-url}") URI targetUrl) {
        if (targetUrl.getHost() == null || !("http".equalsIgnoreCase(targetUrl.getScheme())
                || "https".equalsIgnoreCase(targetUrl.getScheme()))) {
            throw new IllegalArgumentException("Webhook target must be an absolute HTTP or HTTPS URL");
        }
        this.targetUrl = targetUrl;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = builder.requestFactory(factory).build();
    }

    public DeliveryResponse deliver(WebhookRequest request) {
        try {
            return restClient.post()
                    .uri(targetUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                    "Webhook recipient rejected the delivery");
                        }
                        return new DeliveryResponse(true, response.getStatusCode().value());
                    });
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Webhook recipient is unavailable", exception);
        }
    }
}
