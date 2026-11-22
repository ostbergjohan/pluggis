package se.ostberg.pluggis.speech;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import se.ostberg.pluggis.config.PluggisProperties;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AzureTtsTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Låtsas-Piper: svarar "PIPER" och kommer ihåg anropet. */
    private String startPiper(AtomicReference<String> lastBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tts", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "PIPER".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/voices", ex -> {
            byte[] out = "[{\"id\":\"sv_SE-nst-medium\",\"name\":\"nst\",\"quality\":\"medium\",\"default\":true}]".getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static PluggisProperties props(String speechUrl) {
        return new PluggisProperties(null, "Europe/Stockholm", 30, 30, null, null, null, null, null,
                new PluggisProperties.Speech(speechUrl, 5));
    }

    /** Azure som alltid svarar 429 (gratisgränsen nådd). */
    static class FailingAzure extends AzureTts {
        int calls;

        FailingAzure() {
            super(new AzureTtsProperties("nyckel", "swedencentral"), RestClient.builder(), new SimpleMeterRegistry());
        }

        @Override
        public byte[] synthesize(String text, String voice, double speed) {
            calls++;
            throw new IllegalStateException("429 Too Many Requests");
        }
    }

    @Test
    void fallsBackToPiperWhenAzureFails() throws Exception {
        AtomicReference<String> piperBody = new AtomicReference<>();
        String url = startPiper(piperBody);
        FailingAzure azure = new FailingAzure();
        SpeechClient speech = new SpeechClient(RestClient.builder(), new SimpleMeterRegistry(), props(url), azure);

        assertThat(speech.defaultVoice()).isEqualTo("azure:sv-SE-SofieNeural");
        byte[] wav = speech.synthesize("Hej Fred!", "azure:sv-SE-MattiasNeural", 1.0);
        assertThat(new String(wav, StandardCharsets.UTF_8)).isEqualTo("PIPER");
        assertThat(azure.calls).isEqualTo(1);
        assertThat(piperBody.get()).contains("Hej Fred!").doesNotContain("azure");

        // Röstlistan: Azure först (standard), sedan Piper
        var voices = speech.voices();
        assertThat(voices.get(0).path("id").asText()).isEqualTo("azure:sv-SE-SofieNeural");
        assertThat(voices.get(0).path("default").asBoolean()).isTrue();
        assertThat(voices.get(3).path("id").asText()).isEqualTo("sv_SE-nst-medium");
        assertThat(voices.get(3).path("default").asBoolean()).isFalse();
    }

    @Test
    void piperVoicesStillGoToPiper() throws Exception {
        AtomicReference<String> piperBody = new AtomicReference<>();
        String url = startPiper(piperBody);
        FailingAzure azure = new FailingAzure();
        SpeechClient speech = new SpeechClient(RestClient.builder(), new SimpleMeterRegistry(), props(url), azure);
        speech.synthesize("Hej", "sv_SE-lisa-medium", 1.0);
        assertThat(azure.calls).isZero();
        assertThat(piperBody.get()).contains("sv_SE-lisa-medium");
        assertThat(speech.ttsEnabled()).isTrue();
    }

    @Test
    void escapesTextForSsml() {
        assertThat(AzureTts.escape("Svar: 3 < 5 & \"rätt\" – 'bra'"))
                .isEqualTo("Svar: 3 &lt; 5 &amp; &quot;rätt&quot; – &apos;bra&apos;");
    }
}
