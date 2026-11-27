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

import java.util.HashMap;
import java.util.Map;

/**
 * Klient mot pluggis-speech (se katalogen speech/): tal till text med KB-Whisper
 * och text till tal med Piper. Körs lokalt i hemlabbet – barnets röst lämnar aldrig huset.
 * Valfritt: Azures neurala svenska röster för uppläsning (bara coachens svarstext skickas dit);
 * vid fel tar Piper över.
 */
@Component
public class SpeechClient {

    private final RestClient rest;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final AzureTts azure;

    public SpeechClient(RestClient.Builder builder, MeterRegistry meters, PluggisProperties props) {
        this(builder, meters, props, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SpeechClient(RestClient.Builder builder, MeterRegistry meters, PluggisProperties props, AzureTts azure) {
        this.azure = azure != null && azure.enabled() ? azure : null;
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

    /** Ljudtjänsten (KB-Whisper + Piper) finns. Krävs för tal till text. */
    public boolean enabled() {
        return enabled;
    }

    /** Uppläsning finns – via Piper och/eller Azure. */
    public boolean ttsEnabled() {
        return enabled || azure != null;
    }

    /** Röst att använda när föräldern inte valt någon: Azure Sofie om Azure finns, annars Pipers standard. */
    public String defaultVoice() {
        return azure != null ? AzureTts.PREFIX + AzureTts.VOICES.get(0).id() : null;
    }

    /** Ljud (wav, webm/opus, ogg …) → text. */
    public String transcribe(byte[] audio, String filename) {
        return transcribe(audio, filename, null, null);
    }

    /**
     * Ljud → text på ett visst språk ("es", "de", "fr", "en" ger flerspråkig Whisper, annars svenska KB-Whisper).
     * {@code hint} är det väntade svaret, som hjälper Whisper med korta ord.
     */
    public String transcribe(byte[] audio, String filename, String language, String hint) {
        requireEnabled();
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("audio", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename == null ? "audio.wav" : filename;
            }
        });
        if (language != null && !language.isBlank()) {
            form.add("language", language);
        }
        if (hint != null && !hint.isBlank()) {
            form.add("hint", hint.length() > 200 ? hint.substring(0, 200) : hint);
        }
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

    /** Text → wav (22 kHz mono från Piper) med ljudtjänstens standardröst. */
    public byte[] synthesize(String text) {
        return synthesize(text, null, 1.0);
    }

    /**
     * Text → wav med vald röst (t.ex. "sv_SE-lisa-medium", null = standard) och
     * Piper-tempo (length_scale, större = långsammare).
     */
    /**
     * Uppläsning på ett annat språk (spanska, tyska, franska, engelska) – bara med Azure, eftersom de svenska
     * rösterna uttalar utländska ord fel.
     */
    public byte[] synthesizeLanguage(String text, String lang, double lengthScale) {
        String v = AzureTts.voiceFor(lang);
        if (v == null) {
            throw new IllegalArgumentException("Inget stöd för språket " + lang);
        }
        if (azure == null || !azure.available()) {
            throw new IllegalStateException("Uppläsning på andra språk kräver Azure-rösterna (AZURE_SPEECH_KEY)");
        }
        return azure.synthesize(text, v, 1.0 / (lengthScale <= 0 ? 1.0 : lengthScale));
    }

    public byte[] synthesize(String text, String voice, double lengthScale) {
        text = SpokenText.forSpeech(text);
        String chosen = voice == null || voice.isBlank() ? defaultVoice() : voice;
        if (AzureTts.isAzureVoice(chosen) && azure != null) {
            if (azure.available()) {
                try {
                    return azure.synthesize(text, chosen, 1.0 / (lengthScale <= 0 ? 1.0 : lengthScale));
                } catch (RuntimeException e) {
                    if (!enabled) {
                        throw e;
                    }
                }
            }
            if (!enabled) {
                throw new IllegalStateException("Azure-rösten är tillfälligt pausad och Piper finns inte");
            }
            voice = null; // Piper med standardrösten
        } else if (AzureTts.isAzureVoice(chosen)) {
            voice = null; // Azure-röst vald men ingen nyckel längre
        }
        requireEnabled();
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        body.put("length_scale", lengthScale);
        if (voice != null && !voice.isBlank()) {
            body.put("voice", voice);
        }
        Timer.Sample sample = Timer.start(meters);
        try {
            return rest.post().uri("/tts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } finally {
            sample.stop(meters.timer("pluggis.speech.tts"));
        }
    }

    /** Röster som ljudtjänsten har, t.ex. [{"id":"sv_SE-nst-medium","name":"nst",...}]. */
    public JsonNode voices() {
        com.fasterxml.jackson.databind.node.ArrayNode all = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if (azure != null) {
            boolean first = true;
            for (AzureTts.Voice v : AzureTts.VOICES) {
                all.addObject().put("id", AzureTts.PREFIX + v.id()).put("name", v.name())
                        .put("quality", "Azure, " + v.gender()).put("default", first);
                first = false;
            }
        }
        if (enabled) {
            JsonNode piper = rest.get().uri("/voices").retrieve().body(JsonNode.class);
            if (piper != null) {
                for (JsonNode v : piper) {
                    com.fasterxml.jackson.databind.node.ObjectNode copy = v.deepCopy();
                    if (azure != null) {
                        copy.put("default", false);
                    }
                    copy.put("quality", (v.path("quality").asText("").isBlank() ? "" : v.path("quality").asText() + ", ") + "Piper, lokal");
                    all.add(copy);
                }
            }
        }
        return all;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Ljudtjänsten är inte konfigurerad (pluggis.speech.base-url)");
        }
    }
}
