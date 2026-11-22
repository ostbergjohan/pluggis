package se.ostberg.pluggis.gcal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Minimal klient mot Google Calendar API med ett tjänstekonto (ingen SDK): signerar en JWT med
 * tjänstekontots nyckel, byter den mot en åtkomsttoken och anropar REST-API:et.
 * Kalendern måste vara delad med tjänstekontots e-postadress ("Gör ändringar i händelser").
 */
@Component
public class GoogleCalendarClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarClient.class);
    static final String SCOPE = "https://www.googleapis.com/auth/calendar.events";

    private final GoogleProperties props;
    private final ObjectMapper mapper;
    private final RestClient rest;
    private final ServiceAccount account;
    private volatile String token;
    private volatile Instant tokenExpires = Instant.EPOCH;

    record ServiceAccount(String email, PrivateKey key, String tokenUri) {
    }

    public GoogleCalendarClient(GoogleProperties props, ObjectMapper mapper, RestClient.Builder builder) {
        this.props = props;
        this.mapper = mapper;
        this.rest = builder.clone().build();
        this.account = load(props, mapper);
        if (account != null) {
            log.info("Google Kalender påslagen (tjänstekonto {})", account.email());
        }
    }

    public boolean enabled() {
        return account != null;
    }

    /** E-postadressen kalendern ska delas med. */
    public String serviceAccountEmail() {
        return account == null ? null : account.email();
    }

    /** Pluggis egna händelser i kalendern för en elev, inom tidsintervallet. */
    public List<JsonNode> listOwn(String calendarId, String studentKey, Instant from, Instant to) {
        JsonNode r = rest.get()
                .uri(props.apiBaseOrDefault() + "/calendars/{cal}/events?privateExtendedProperty={p}&timeMin={a}&timeMax={b}"
                                + "&singleEvents=true&maxResults=250",
                        calendarId, "pluggisStudent=" + studentKey, from.toString(), to.toString())
                .header("Authorization", "Bearer " + token())
                .retrieve().body(JsonNode.class);
        List<JsonNode> out = new java.util.ArrayList<>();
        if (r != null) {
            r.path("items").forEach(out::add);
        }
        return out;
    }

    public String insert(String calendarId, ObjectNode event) {
        JsonNode r = rest.post().uri(props.apiBaseOrDefault() + "/calendars/{cal}/events", calendarId)
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON).body(event)
                .retrieve().body(JsonNode.class);
        return r == null ? null : r.path("id").asText(null);
    }

    public void update(String calendarId, String eventId, ObjectNode event) {
        rest.put().uri(props.apiBaseOrDefault() + "/calendars/{cal}/events/{id}", calendarId, eventId)
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON).body(event)
                .retrieve().toBodilessEntity();
    }

    public void delete(String calendarId, String eventId) {
        rest.delete().uri(props.apiBaseOrDefault() + "/calendars/{cal}/events/{id}", calendarId, eventId)
                .header("Authorization", "Bearer " + token())
                .retrieve().toBodilessEntity();
    }

    /** Åtkomsttoken (cachad tills strax före den går ut). */
    synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenExpires.minusSeconds(60))) {
            return token;
        }
        if (account == null) {
            throw new IllegalStateException("Google Kalender är inte konfigurerat");
        }
        long now = Instant.now().getEpochSecond();
        String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String claims;
        try {
            claims = b64(mapper.writeValueAsString(Map.of("iss", account.email(), "scope", SCOPE,
                    "aud", account.tokenUri(), "iat", now, "exp", now + 3600)));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(account.key());
            sig.update((header + "." + claims).getBytes(StandardCharsets.UTF_8));
            String jwt = header + "." + claims + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            form.add("assertion", jwt);
            JsonNode r = rest.post().uri(account.tokenUri()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(JsonNode.class);
            if (r == null || !r.hasNonNull("access_token")) {
                throw new IllegalStateException("Google gav ingen åtkomsttoken");
            }
            token = r.path("access_token").asText();
            tokenExpires = Instant.now().plusSeconds(r.path("expires_in").asLong(3600));
            return token;
        } catch (java.security.GeneralSecurityException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Kunde inte signera inloggningen mot Google: " + e.getMessage(), e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static ServiceAccount load(GoogleProperties props, ObjectMapper mapper) {
        try {
            String json = props.serviceAccountJson();
            if ((json == null || json.isBlank()) && props.serviceAccountFile() != null && !props.serviceAccountFile().isBlank()) {
                Path p = Path.of(props.serviceAccountFile().trim());
                if (!Files.exists(p)) {
                    log.info("Ingen Google-nyckel ({}) – Google Kalender är avstängt", p);
                    return null;
                }
                json = Files.readString(p);
            }
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonNode n = mapper.readTree(json.trim());
            String pem = n.path("private_key").asText("")
                    .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            String tokenUri = n.path("token_uri").asText("https://oauth2.googleapis.com/token");
            return new ServiceAccount(n.path("client_email").asText(), key, tokenUri);
        } catch (Exception e) {
            log.warn("Kunde inte läsa Google-nyckeln – Google Kalender är avstängt: {}", e.getMessage());
            return null;
        }
    }
}
