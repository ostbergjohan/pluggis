package se.ostberg.pluggis.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import se.ostberg.pluggis.config.PluggisProperties;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Tunn klient mot Anthropics Messages API (POST /v1/messages), med:
 * <ul>
 *   <li>modellprofil per jobb (modell, effort, thinking, max tokens)</li>
 *   <li>prompt caching: fasta regler i ett eget systemblock med cache-markering, läget i ett block efter</li>
 *   <li>strömning (SSE) så att första meningen kan läsas upp innan svaret är klart</li>
 * </ul>
 * Mätvärden: pluggis.claude.request (latens), pluggis.claude.first_text (tid till första text),
 * pluggis.claude.tokens (input/output/cache_read/cache_write).
 */
@Component
public class ClaudeClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClaudeClient.class);

    private final RestClient rest;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final PluggisProperties.Anthropic cfg;
    private final ClaudeCodeRunner claudeCode;
    private se.ostberg.pluggis.usage.UsageService usage;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUsage(se.ostberg.pluggis.usage.UsageService usage) {
        this.usage = usage;
    }

    public ClaudeClient(RestClient.Builder builder, ObjectMapper mapper, MeterRegistry meters, PluggisProperties props) {
        this(builder, mapper, meters, props, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ClaudeClient(RestClient.Builder builder, ObjectMapper mapper, MeterRegistry meters, PluggisProperties props,
                        ClaudeCodeRunner claudeCode) {
        this.claudeCode = claudeCode;
        org.slf4j.LoggerFactory.getLogger(ClaudeClient.class).info(this.claudeCode == null || !this.claudeCode.enabled()
                ? "Claude körs via API-nyckel"
                : this.claudeCode.mode().equals("hybrid")
                ? "Claude körs hybrid: rösten via API-nyckel, foto/planer/flashkort/material via Claude Code på ditt abonnemang"
                : "Claude körs via Claude Code (claude -p) på ditt abonnemang");
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

    /** Anrop med ett enda (fast) systemblock. */
    public ObjectNode newRequest(PluggisProperties.ModelProfile profile, String system) {
        return newRequest(profile, system, null);
    }

    /**
     * Anrop där {@code staticSystem} är identisk mellan anrop (regler) och cachas tillsammans med verktygen,
     * och {@code dynamicSystem} (läget just nu) läggs efter cache-markeringen.
     */
    public ObjectNode newRequest(PluggisProperties.ModelProfile profile, String staticSystem, String dynamicSystem) {
        ObjectNode req = mapper.createObjectNode();
        req.put("model", profile.model());
        req.put("max_tokens", profile.maxTokens() > 0 ? profile.maxTokens() : 1024);

        ArrayNode system = req.putArray("system");
        if (staticSystem != null && !staticSystem.isBlank()) {
            ObjectNode block = system.addObject();
            block.put("type", "text");
            block.put("text", staticSystem);
            block.putObject("cache_control").put("type", "ephemeral");
        }
        if (dynamicSystem != null && !dynamicSystem.isBlank()) {
            ObjectNode block = system.addObject();
            block.put("type", "text");
            block.put("text", dynamicSystem);
        }
        if (system.isEmpty()) {
            req.remove("system");
        }

        if (notBlank(profile.effort())) {
            req.putObject("output_config").put("effort", profile.effort().trim());
        }
        if (notBlank(profile.thinking())) {
            req.putObject("thinking").put("type", profile.thinking().trim());
        }
        return req;
    }

    public ClaudeResponse create(ObjectNode request, String purpose) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "error";
        long start = System.nanoTime();
        boolean[] subscription = {false};
        try {
            ClaudeResponse response = viaClaudeCode(purpose) ? runClaudeCode(request, purpose, subscription) : api(request);
            outcome = "ok";
            recordUsage(response, purpose);
            saveUsage(request, response, purpose, subscription[0]);
            logCall(request, response, purpose, subscription[0] ? "abonnemang" : "api", start);
            return response;
        } catch (RuntimeException e) {
            logFailure(request, purpose, start, e);
            throw e;
        } finally {
            sample.stop(meters.timer("pluggis.claude.request", "purpose", purpose, "outcome", outcome));
        }
    }

    /** Strömmande anrop. {@code onText} får textbitar i den ordning de kommer. */
    public ClaudeResponse stream(ObjectNode request, String purpose, Consumer<String> onText) {
        if (viaClaudeCode(purpose)) {
            // Claude Code-läget strömmar inte: hela svaret kommer på en gång och läses upp mening för mening.
            ClaudeResponse response = create(request, purpose);
            String text = response.text();
            if (onText != null && !text.isEmpty()) {
                onText.accept(text);
            }
            return response;
        }
        ObjectNode req = request.deepCopy();
        req.put("stream", true);
        long start = System.nanoTime();
        boolean[] first = {false};
        Consumer<String> timed = t -> {
            if (!first[0]) {
                first[0] = true;
                meters.timer("pluggis.claude.first_text", "purpose", purpose)
                        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
            if (onText != null) {
                onText.accept(t);
            }
        };
        StreamAccumulator acc = new StreamAccumulator(mapper, timed);
        String outcome = "error";
        try {
            ClaudeResponse response = rest.post()
                    .uri("/v1/messages")
                    .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(req)
                    .exchange((httpRequest, httpResponse) -> {
                        if (httpResponse.getStatusCode().isError()) {
                            String body = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            throw new IllegalStateException("Claude svarade " + httpResponse.getStatusCode() + ": " + body);
                        }
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(httpResponse.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("data:")) {
                                    String json = line.substring(5).trim();
                                    if (!json.isEmpty()) {
                                        acc.accept(mapper.readTree(json));
                                    }
                                }
                            }
                        }
                        return acc.result();
                    });
            outcome = "ok";
            recordUsage(response, purpose);
            saveUsage(request, response, purpose, false);
            logCall(request, response, purpose, "api, strömmat", start);
            return response;
        } catch (RuntimeException e) {
            logFailure(request, purpose, start, e);
            throw e;
        } finally {
            meters.timer("pluggis.claude.request", "purpose", purpose, "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** En rad per anrop: jobb, modell, väg, tid och tokens – aldrig innehållet. */
    private void logCall(ObjectNode request, ClaudeResponse response, String purpose, String via, long start) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        var u = response == null ? null : response.usage();
        String stop = response == null ? null : response.stopReason();
        String msg = "Claude {} ({}, {}): {} ms, {} in / {} ut tokens{}, stopp {}";
        Object[] args = {purpose, request.path("model").asText("?"), via, ms,
                u == null ? "?" : u.inputTokens() + u.cacheReadInputTokens() + u.cacheCreationInputTokens(),
                u == null ? "?" : u.outputTokens(),
                u == null || u.cacheReadInputTokens() == 0 ? "" : " (" + u.cacheReadInputTokens() + " från cache)",
                stop};
        if ("max_tokens".equals(stop)) {
            log.warn(msg + " – svaret klipptes av, höj max_tokens", args);
        } else {
            log.info(msg, args);
        }
    }

    private void logFailure(ObjectNode request, String purpose, long start, RuntimeException e) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        String err = String.valueOf(e.getMessage());
        log.warn("Claude {} ({}) misslyckades efter {} ms: {}", purpose, request.path("model").asText("?"), ms,
                err.length() > 300 ? err.substring(0, 300) + "…" : err);
    }

    private void recordUsage(ClaudeResponse response, String purpose) {
        if (response == null || response.usage() == null) {
            return;
        }
        var u = response.usage();
        meters.counter("pluggis.claude.tokens", "purpose", purpose, "kind", "input").increment(u.inputTokens());
        meters.counter("pluggis.claude.tokens", "purpose", purpose, "kind", "output").increment(u.outputTokens());
        meters.counter("pluggis.claude.tokens", "purpose", purpose, "kind", "cache_read").increment(u.cacheReadInputTokens());
        meters.counter("pluggis.claude.tokens", "purpose", purpose, "kind", "cache_write").increment(u.cacheCreationInputTokens());
    }

    private void saveUsage(ObjectNode request, ClaudeResponse response, String purpose, boolean subscription) {
        if (usage == null || response == null || response.usage() == null) {
            return;
        }
        var u = response.usage();
        usage.recordClaude(purpose, request.path("model").asText(null), subscription,
                u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens(), u.cacheCreationInputTokens());
    }

    /** "api", "claude-code" eller "hybrid". */
    public String backend() {
        return claudeCode != null ? claudeCode.mode() : "api";
    }

    public boolean hasApiKey() {
        return notBlank(cfg.apiKey());
    }

    public long fallbacks() {
        return fallbacks.get();
    }

    private final java.util.concurrent.atomic.AtomicLong fallbacks = new java.util.concurrent.atomic.AtomicLong();

    private boolean viaClaudeCode(String purpose) {
        return claudeCode != null && claudeCode.useFor(purpose);
    }

    private ClaudeResponse api(ObjectNode request) {
        return rest.post().uri("/v1/messages").body(request).retrieve().body(ClaudeResponse.class);
    }

    /**
     * Via abonnemanget. I hybridläget, om Claude Code inte går (gräns nådd, utloggat) och det finns
     * en API-nyckel, tas jobbet med API-nyckeln i stället så att eleven inte blir utan svar.
     */
    private ClaudeResponse runClaudeCode(ObjectNode request, String purpose, boolean[] subscription) {
        try {
            ClaudeResponse r = claudeCode.run(request);
            subscription[0] = true;
            return r;
        } catch (IllegalStateException e) {
            if (claudeCode.mode().equals("hybrid") && notBlank(cfg.apiKey())) {
                org.slf4j.LoggerFactory.getLogger(ClaudeClient.class)
                        .warn("Claude Code misslyckades för {} ({}) – tar det med API-nyckeln", purpose, e.getMessage());
                meters.counter("pluggis.claude.fallback", "purpose", purpose).increment();
                fallbacks.incrementAndGet();
                return api(request);
            }
            throw e;
        }
    }

    public PluggisProperties.Anthropic config() {
        return cfg;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
