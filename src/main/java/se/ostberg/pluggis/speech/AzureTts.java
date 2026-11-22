package se.ostberg.pluggis.speech;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Text till tal med Azures neurala svenska röster via REST (ingen SDK behövs).
 * Vid fel, t.ex. 429 när gratisnivåns gräns är nådd, pausas Azure en stund och Piper tar över.
 */
@Component
public class AzureTts {

    private static final Logger log = LoggerFactory.getLogger(AzureTts.class);
    public static final String PREFIX = "azure:";

    public record Voice(String id, String name, String gender) {
    }

    /** Svenska neurala röster. */
    public static final List<Voice> VOICES = List.of(
            new Voice("sv-SE-SofieNeural", "Sofie", "kvinna"),
            new Voice("sv-SE-HilleviNeural", "Hillevi", "kvinna"),
            new Voice("sv-SE-MattiasNeural", "Mattias", "man"));

    private final AzureTtsProperties props;
    private final RestClient rest;
    private final MeterRegistry meters;
    private volatile Instant pausedUntil = Instant.EPOCH;

    public AzureTts(AzureTtsProperties props, RestClient.Builder builder, MeterRegistry meters) {
        this.props = props;
        this.meters = meters;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(20_000);
        this.rest = builder.clone()
                .baseUrl("https://" + props.regionOrDefault() + ".tts.speech.microsoft.com")
                .requestFactory(factory)
                .build();
        if (props.enabled()) {
            log.info("Azure-röster påslagna (region {})", props.regionOrDefault());
        }
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** Tillgänglig just nu (påslagen och inte pausad efter fel). */
    public boolean available() {
        return enabled() && Instant.now().isAfter(pausedUntil);
    }

    public static boolean isAzureVoice(String voice) {
        return voice != null && voice.startsWith(PREFIX);
    }

    /**
     * @param voice  t.ex. "azure:sv-SE-SofieNeural" (eller utan prefix)
     * @param speed  1.0 = normalt, 0.7–1.4
     * @return wav (24 kHz, 16 bit, mono)
     */
    public byte[] synthesize(String text, String voice, double speed) {
        String name = voice == null || voice.isBlank() ? VOICES.get(0).id()
                : voice.startsWith(PREFIX) ? voice.substring(PREFIX.length()) : voice;
        int ratePercent = (int) Math.round((speed - 1.0) * 100);
        String ssml = "<speak version='1.0' xml:lang='sv-SE'><voice name='" + escape(name) + "'>"
                + "<prosody rate='" + (ratePercent >= 0 ? "+" : "") + ratePercent + "%'>" + escape(text)
                + "</prosody></voice></speak>";
        Timer.Sample sample = Timer.start(meters);
        String outcome = "error";
        try {
            byte[] wav = rest.post().uri("/cognitiveservices/v1")
                    .header("Ocp-Apim-Subscription-Key", props.key())
                    .header("Content-Type", "application/ssml+xml")
                    .header("X-Microsoft-OutputFormat", "riff-24khz-16bit-mono-pcm")
                    .header("User-Agent", "pluggis")
                    .body(ssml.getBytes(StandardCharsets.UTF_8))
                    .retrieve()
                    .body(byte[].class);
            outcome = "ok";
            meters.counter("pluggis.speech.azure.characters").increment(text.length());
            return wav;
        } catch (RuntimeException e) {
            // 429 = för många anrop eller månadens gratisgräns nådd. Pausa och låt Piper ta över.
            boolean quota = String.valueOf(e.getMessage()).contains("429");
            Duration pause = quota ? Duration.ofMinutes(10) : Duration.ofSeconds(60);
            pausedUntil = Instant.now().plus(pause);
            log.warn("Azure-rösten svarade inte ({}), Piper tar över i {} min", quota ? "gräns nådd" : e.getMessage(),
                    pause.toMinutes() == 0 ? 1 : pause.toMinutes());
            throw e;
        } finally {
            sample.stop(meters.timer("pluggis.speech.azure", "outcome", outcome));
        }
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
