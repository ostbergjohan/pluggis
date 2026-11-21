package se.ostberg.pluggis.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.session.StudySessionService;
import se.ostberg.pluggis.speech.VoiceService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * WebSocket för Tab5 (och webbsimulatorn): ws://host:8080/ws/device?deviceId=tab5-1&token=...
 *
 * Text-ramar är JSON (se docs/protokoll.md). Binära ramar är ett helt yttrande som ljud
 * (wav 16 kHz mono eller webm/opus) – det skickas till tal till text och sedan till coachen.
 */
@Component
public class DeviceWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceWebSocketHandler.class);
    private static final String DEVICE_ID = "deviceId";
    private static final String AUDIO_FORMAT = "audioFormat";

    private final DeviceRegistry registry;
    private final VoiceService voice;
    private final StudySessionService sessions;
    private final PluggisProperties props;
    private final ObjectMapper mapper;

    public DeviceWebSocketHandler(DeviceRegistry registry, VoiceService voice, StudySessionService sessions,
                                  PluggisProperties props, ObjectMapper mapper) {
        this.registry = registry;
        this.voice = voice;
        this.sessions = sessions;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        var query = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
        String deviceId = query.getFirst(DEVICE_ID);
        String token = query.getFirst("token");

        if (deviceId == null || deviceId.isBlank() || !props.tokenMatches(token)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("deviceId eller token saknas/fel"));
            return;
        }
        session.getAttributes().put(DEVICE_ID, deviceId);
        registry.register(deviceId, session);
        log.info("Enhet ansluten: {}", deviceId);

        sessions.active(deviceId).ifPresent(s -> registry.send(deviceId,
                DeviceMessages.SessionState.started(s.getTask(), s.getPlannedMinutes())));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String deviceId = (String) session.getAttributes().get(DEVICE_ID);
        if (deviceId == null) {
            return;
        }
        JsonNode msg;
        try {
            msg = mapper.readTree(message.getPayload());
        } catch (IOException e) {
            registry.send(deviceId, new DeviceMessages.Error("Ogiltig JSON"));
            return;
        }

        switch (msg.path("type").asText()) {
            case "hello" -> {
                // {"type":"hello","audio":true,"audioFormat":"wav"} – vill enheten ha uppläst ljud?
                registry.setWantsAudio(deviceId, msg.path("audio").asBoolean(false));
                session.getAttributes().put(AUDIO_FORMAT, msg.path("audioFormat").asText("wav"));
                registry.send(deviceId, Map.of("type", "hello_ack", "audio", registry.wantsAudio(deviceId)));
            }
            case "text" -> {
                String text = msg.path("text").asText("").trim();
                if (!text.isEmpty()) {
                    voice.onText(deviceId, text);
                }
            }
            case "button" -> {
                boolean active = sessions.active(deviceId).isPresent();
                ButtonMapper.map(msg.path("button").asText(), msg.path("gesture").asText("click"), active)
                        .ifPresent(action -> {
                            switch (action) {
                                case ButtonMapper.TakePhoto p -> registry.send(deviceId, p.forFlashcards()
                                        ? DeviceMessages.TakePhoto.flashcards()
                                        : DeviceMessages.TakePhoto.homework());
                                case ButtonMapper.SayToCoach s -> voice.onText(deviceId, s.text());
                            }
                        });
            }
            case "ping" -> registry.send(deviceId, Map.of("type", "pong"));
            default -> registry.send(deviceId, new DeviceMessages.Error("Okänd typ: " + msg.path("type").asText()));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String deviceId = (String) session.getAttributes().get(DEVICE_ID);
        if (deviceId == null) {
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] audio = new byte[payload.remaining()];
        payload.get(audio);
        String format = (String) session.getAttributes().getOrDefault(AUDIO_FORMAT, "wav");
        voice.onAudio(deviceId, audio, "utterance." + format);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String deviceId = (String) session.getAttributes().get(DEVICE_ID);
        if (deviceId != null) {
            registry.unregister(deviceId, session);
            log.info("Enhet frånkopplad: {} ({})", deviceId, status.getCode());
        }
    }
}
