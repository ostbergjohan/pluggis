package se.ostberg.pluggis.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hur mycket av Claude-abonnemangets gränser som gått åt: 5-timmarsgränsen och veckogränsen i procent, och när de
 * nollställs. Samma uppgift som Claude Code visar med /usage, hämtad med abonnemangets token
 * (CLAUDE_CODE_OAUTH_TOKEN). Det är inget dokumenterat API – svarar det inte visas bara det Claude Code själv
 * berättar ({@link SubscriptionLimit}). Hämtas högst en gång per minut.
 */
@Component
public class SubscriptionUsage {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionUsage.class);
    static final String URL = "https://api.anthropic.com/api/oauth/usage";
    private static final Duration CACHE = Duration.ofSeconds(60);

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private volatile Map<String, Object> cached;
    private volatile Instant cachedAt;

    public SubscriptionUsage(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** {"fiveHour": {"percent": 42, "resetsAt": "..."}, "sevenDay": {...}} eller {"error": "..."}; null utan token. */
    public synchronized Map<String, Object> get(boolean force) {
        String token = System.getenv("CLAUDE_CODE_OAUTH_TOKEN");
        if (token == null || token.isBlank()) {
            return null;
        }
        if (!force && cached != null && cachedAt != null && cachedAt.plus(CACHE).isAfter(Instant.now())) {
            return cached;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token.trim())
                    .header("anthropic-beta", "oauth-2025-04-20")
                    .header("User-Agent", "pluggis")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                out.put("error", "svarade " + r.statusCode());
            } else {
                parse(mapper.readTree(r.body()), out);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            out.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        if (out.containsKey("error")) {
            log.debug("Kunde inte hämta abonnemangets användning: {}", out.get("error"));
        }
        out.put("fetchedAt", Instant.now().toString());
        cached = out;
        cachedAt = Instant.now();
        return out;
    }

    static void parse(JsonNode body, Map<String, Object> out) {
        window(body.path("five_hour"), "fiveHour", out);
        window(body.path("seven_day"), "sevenDay", out);
        window(body.path("seven_day_opus"), "sevenDayOpus", out);
        if (!out.containsKey("fiveHour") && !out.containsKey("sevenDay")) {
            out.put("error", "okänt svar");
        }
    }

    private static void window(JsonNode w, String key, Map<String, Object> out) {
        if (w == null || !w.isObject() || !w.path("utilization").isNumber()) {
            return;
        }
        double u = w.path("utilization").asDouble();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("percent", (int) Math.round(u <= 1.0 && u > 0 && !w.path("utilization").isIntegralNumber() && u < 1.0 ? u * 100 : u));
        m.put("resetsAt", w.hasNonNull("resets_at") ? w.path("resets_at").asText() : null);
        out.put(key, m);
    }

    /** Högsta procenten just nu (för varningen), eller -1. */
    @SuppressWarnings("unchecked")
    public static int highest(Map<String, Object> usage) {
        if (usage == null) {
            return -1;
        }
        int max = -1;
        for (String k : new String[]{"fiveHour", "sevenDay", "sevenDayOpus"}) {
            if (usage.get(k) instanceof Map<?, ?> m && m.get("percent") instanceof Number n) {
                max = Math.max(max, n.intValue());
            }
        }
        return max;
    }
}
