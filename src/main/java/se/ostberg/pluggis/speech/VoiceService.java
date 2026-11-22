package se.ostberg.pluggis.speech;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.ostberg.pluggis.coach.CoachService;
import se.ostberg.pluggis.device.DeviceMessages;
import se.ostberg.pluggis.device.DeviceRegistry;
import se.ostberg.pluggis.settings.SettingsService;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Ett samtalsvarv med röst:
 * <pre>
 *  ljud → KB-Whisper → "heard" → Claude (strömmat) → mening 1 → Piper → ljud till enheten
 *                                                  → mening 2 → Piper → ljud …
 * </pre>
 * Meningarna läses upp i ordning medan Claude fortfarande skriver, så den första hörs snabbt.
 * Enheter som inte vill ha ljud (t.ex. webbsimulatorn utan serverröst) får bara text.
 */
@Service
public class VoiceService {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);
    private static final String END = "\u0000END";

    private final SpeechClient speech;
    private final CoachService coach;
    private final DeviceRegistry registry;
    private final MeterRegistry meters;
    private final SettingsService settings;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public VoiceService(SpeechClient speech, CoachService coach, DeviceRegistry registry, MeterRegistry meters,
                        SettingsService settings) {
        this.speech = speech;
        this.coach = coach;
        this.registry = registry;
        this.meters = meters;
        this.settings = settings;
    }

    /** Text → ljud med förälderns valda röst och tempo. */
    public byte[] speak(String text) {
        return speech.synthesize(text, settings.voice(), SettingsService.lengthScale(settings.speed()));
    }

    /** Ljud från enheten (ett helt yttrande, t.ex. tryck-och-prata). */
    public void onAudio(String deviceId, byte[] audio, String filename) {
        if (!speech.enabled()) {
            registry.send(deviceId, new DeviceMessages.Error("Ljudtjänsten är inte påslagen på servern"));
            return;
        }
        workers.submit(se.ostberg.pluggis.student.StudentContext.wrap(() -> {
            registry.send(deviceId, new DeviceMessages.Thinking());
            String text;
            try {
                text = speech.transcribe(audio, filename);
            } catch (RuntimeException e) {
                log.warn("Tal till text misslyckades för {}: {}", deviceId, e.toString());
                registry.send(deviceId, new DeviceMessages.Error("Kunde inte höra – testa igen"));
                return;
            }
            if (text.isBlank()) {
                respondTextOnly(deviceId, "Jag hörde inte riktigt. Kan du säga det igen?");
                return;
            }
            registry.send(deviceId, new DeviceMessages.Heard(text));
            reply(deviceId, text);
        }));
    }

    /** Text från enheten eller ett knapptryck. */
    public void onText(String deviceId, String text) {
        registry.send(deviceId, new DeviceMessages.Thinking());
        workers.submit(se.ostberg.pluggis.student.StudentContext.wrap(() -> reply(deviceId, text)));
    }

    private void reply(String deviceId, String userText) {
        boolean audio = speech.ttsEnabled() && registry.wantsAudio(deviceId);
        if (!audio) {
            registry.send(deviceId, new DeviceMessages.Say(coach.chat(deviceId, userText)));
            return;
        }
        long start = System.nanoTime();
        BlockingQueue<String> sentences = new LinkedBlockingQueue<>();
        var speaker = workers.submit(se.ostberg.pluggis.student.StudentContext.wrap(() -> speakInOrder(deviceId, sentences, start)));
        String full = coach.chat(deviceId, userText, sentences::add);
        sentences.add(END);
        registry.send(deviceId, new DeviceMessages.Say(full));
        try {
            speaker.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Uppläsningen avbröts för {}: {}", deviceId, e.toString());
        }
    }

    private void speakInOrder(String deviceId, BlockingQueue<String> sentences, long start) {
        int seq = 0;
        boolean first = true;
        try {
            while (true) {
                String sentence = sentences.poll(60, TimeUnit.SECONDS);
                if (sentence == null || END.equals(sentence)) {
                    break;
                }
                byte[] wav;
                try {
                    wav = speak(sentence);
                } catch (RuntimeException e) {
                    log.warn("Text till tal misslyckades: {}", e.toString());
                    continue;
                }
                if (first) {
                    first = false;
                    meters.timer("pluggis.voice.first_audio").record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                }
                registry.send(deviceId, new DeviceMessages.Audio("wav", seq++, sentence, wav.length));
                registry.sendBinary(deviceId, wav);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        registry.send(deviceId, new DeviceMessages.AudioEnd(seq));
    }

    private void respondTextOnly(String deviceId, String text) {
        registry.send(deviceId, new DeviceMessages.Say(text));
        if (speech.ttsEnabled() && registry.wantsAudio(deviceId)) {
            try {
                byte[] wav = speak(text);
                registry.send(deviceId, new DeviceMessages.Audio("wav", 0, text, wav.length));
                registry.sendBinary(deviceId, wav);
                registry.send(deviceId, new DeviceMessages.AudioEnd(1));
            } catch (RuntimeException e) {
                log.warn("Text till tal misslyckades: {}", e.toString());
            }
        }
    }
}
