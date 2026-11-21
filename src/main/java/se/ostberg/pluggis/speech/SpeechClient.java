package se.ostberg.pluggis.speech;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import se.ostberg.pluggis.config.PluggisProperties;

import java.util.Map;

/**
 * Klient mot pluggis-speech (se katalogen speech/): tal till text med KB-Whisper
 * och text till tal med Piper. Körs lokalt i hemlabbet – barnets röst lämnar aldrig huset.
 */
@Component
public class SpeechClient {

    private final RestClient rest;
    private final MeterRegistry meters;
    private final boolean enabled;

    public SpeechClient(RestClient.Builder builder, MeterRegistry meters, PluggisProperties props) {
        this.meters = meters;
        PluggisProperties.Speech cfg = props.speech();
        this.enabled = cfg != null && cfg.enabled();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (cfg == null || cfg.timeoutSeconds() <= 0 ? 30 : cfg.timeoutSeconds()) * 1000;
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(timeoutMs);
        this.rest = builder.clone()
                .baseUrl(enabled ? cfg.baseUrl() : "http://localhost")
                .requestFactory(factory)
                .build();
    }

    public boolean enabled() {
        return enabled;
    }

    /** Ljud (wav, webm/opus, ogg …) → text. */
    public String transcribe(byte[] audio, String filename) {
        requireEnabled();
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("audio", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename == null ? "audio.wav" : filename;
            }
        });
        Timer.Sample sample = Timer.start(meters);
        try {
            JsonNode res = rest.post().uri("/stt")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            return res == null ? "" : res.path("text").asText("").trim();
        } finally {
            sample.stop(meters.timer("pluggis.speech.stt"));
        }
    }

    /** Text → wav (22 kHz mono från Piper). */
    public byte[] synthesize(String text) {
        requireEnabled();
        Timer.Sample sample = Timer.start(meters);
        try {
            return rest.post().uri("/tts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text))
                    .retrieve()
                    .body(byte[].class);
        } finally {
            sample.stop(meters.timer("pluggis.speech.tts"));
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Ljudtjänsten är inte konfigurerad (pluggis.speech.base-url)");
        }
    }
}
