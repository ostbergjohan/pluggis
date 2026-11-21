package se.ostberg.pluggis.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import se.ostberg.pluggis.config.PluggisProperties;

/**
 * Tunn klient mot Anthropics Messages API (POST /v1/messages).
 * Medvetet utan SDK för full kontroll över JSON:en – går att byta mot
 * com.anthropic:anthropic-java senare om man vill.
 *
 * Mäter latens per syfte (coach/vision) i Micrometer: pluggis.claude.request.
 */
@Component
public class ClaudeClient {

    private final RestClient rest;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final PluggisProperties.Anthropic cfg;

    public ClaudeClient(RestClient.Builder builder, ObjectMapper mapper, MeterRegistry meters, PluggisProperties props) {
        this.cfg = props.anthropic();
        this.mapper = mapper;
        this.meters = meters;
        this.rest = builder
                .baseUrl(cfg.baseUrl())
                .defaultHeader("x-api-key", cfg.apiKey() == null ? "" : cfg.apiKey())
                .defaultHeader("anthropic-version", cfg.apiVersion())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public ObjectNode newRequest(String model, int maxTokens, String system) {
        ObjectNode req = mapper.createObjectNode();
        req.put("model", model);
        req.put("max_tokens", maxTokens);
        if (system != null && !system.isBlank()) {
            req.put("system", system);
        }
        return req;
    }

    public ClaudeResponse create(ObjectNode request, String purpose) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "error";
        try {
            ClaudeResponse response = rest.post()
                    .uri("/v1/messages")
                    .body(request)
                    .retrieve()
                    .body(ClaudeResponse.class);
            outcome = "ok";
            if (response != null && response.usage() != null) {
                meters.counter("pluggis.claude.tokens", "purpose", purpose, "kind", "input")
                        .increment(response.usage().inputTokens());
                meters.counter("pluggis.claude.tokens", "purpose", purpose, "kind", "output")
                        .increment(response.usage().outputTokens());
            }
            return response;
        } finally {
            sample.stop(meters.timer("pluggis.claude.request", "purpose", purpose, "outcome", outcome));
        }
    }

    public PluggisProperties.Anthropic config() {
        return cfg;
    }
}
