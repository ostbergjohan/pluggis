package se.ostberg.pluggis.speech;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.ostberg.pluggis.settings.SettingsService;

import java.util.LinkedHashMap;
import java.util.Map;

/** Röstval för föräldravyn: lista röster, välj röst och tempo, lyssna. */
@RestController
@RequestMapping("/api/speech")
public class SpeechController {

    static final String SAMPLE = "Hej! Jag är Pluggis. Ska vi ta första uppgiften tillsammans?";

    public record SpeechSettings(String voice, Double speed) {
    }

    public record PreviewRequest(String text, String voice, Double speed) {
    }

    private final SpeechClient speech;
    private final SettingsService settings;

    public SpeechController(SpeechClient speech, SettingsService settings) {
        this.speech = speech;
        this.settings = settings;
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", speech.ttsEnabled());
        out.put("voice", settings.voice() != null ? settings.voice() : speech.defaultVoice());
        out.put("speed", settings.speed());
        if (speech.ttsEnabled()) {
            try {
                JsonNode voices = speech.voices();
                out.put("voices", voices);
            } catch (RuntimeException e) {
                out.put("error", "Ljudtjänsten svarar inte: " + e.getMessage());
            }
        }
        return out;
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody SpeechSettings body) {
        if (body.voice() != null) {
            settings.put(SettingsService.VOICE, body.voice());
        }
        if (body.speed() != null) {
            settings.put(SettingsService.SPEED, String.valueOf(SettingsService.clampSpeed(body.speed())));
        }
        return status();
    }

    /** Provlyssna en röst innan den sparas. */
    @PostMapping(value = "/preview", produces = "audio/wav")
    public ResponseEntity<byte[]> preview(@RequestBody(required = false) PreviewRequest body,
                                          @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean small) {
        if (!speech.ttsEnabled()) {
            return ResponseEntity.status(503).contentType(MediaType.TEXT_PLAIN)
                    .body("Ljudtjänsten är inte påslagen".getBytes());
        }
        String text = body == null || body.text() == null || body.text().isBlank() ? SAMPLE : body.text();
        String voice = body != null && body.voice() != null ? body.voice() : settings.voice();
        double speed = body != null && body.speed() != null ? body.speed() : settings.speed();
        byte[] wav = speech.synthesize(text.length() > 300 ? text.substring(0, 300) : text, voice,
                SettingsService.lengthScale(speed));
        if (small) {
            wav = Wav.toMono16k(wav);   // Tab5: mindre att hämta och spela upp
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(wav);
    }

    /** Tal till text (lokal KB-Whisper) – Tab5 svarar på flashkort med rösten. Kroppen är en wav-fil. */
    @PostMapping(value = "/transcribe", consumes = "audio/wav")
    public ResponseEntity<java.util.Map<String, String>> transcribe(@RequestBody byte[] audio) {
        if (audio == null || audio.length < 1000) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "för kort inspelning"));
        }
        try {
            String text = speech.transcribe(audio, "svar.wav");
            return ResponseEntity.ok(java.util.Map.of("text", text == null ? "" : text.trim()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(503).body(java.util.Map.of("error", "Ljudtjänsten svarade inte"));
        }
    }
}
