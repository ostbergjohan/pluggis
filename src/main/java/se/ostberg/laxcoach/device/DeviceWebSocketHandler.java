package se.ostberg.laxcoach.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import se.ostberg.laxcoach.coach.CoachService;
import se.ostberg.laxcoach.config.LaxcoachProperties;
import se.ostberg.laxcoach.session.StudySessionService;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebSocket för Tab5: ws://host:8080/ws/device?deviceId=tab5-1&token=...
 *
 * Fas 1 (nu): text in/ut + knappar + timer. Tal till text och text till tal görs
 * på enheten eller i en separat tjänst och skickas hit som "text".
 * Fas 2: binära Opus-ramar för ljud (se docs/protokoll.md).
 */
@Component
public class DeviceWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceWebSocketHandler.class);
    private static final String DEVICE_ID = "deviceId";

    private final DeviceRegistry registry;
    private final CoachService coach;
    private final StudySessionService sessions;
    private final LaxcoachProperties props;
    private final ObjectMapper mapper;
    /** Claude-anrop tar sekunder – kör dem utanför WebSocket-tråden. */
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public DeviceWebSocketHandler(DeviceRegistry registry, CoachService coach, StudySessionService sessions,
                                  LaxcoachProperties props, ObjectMapper mapper) {
        this.registry = registry;
        this.coach = coach;
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
            case "text" -> {
                String text = msg.path("text").asText("").trim();
                if (!text.isEmpty()) {
                    askCoach(deviceId, text);
                }
            }
            case "button" -> {
                boolean active = sessions.active(deviceId).isPresent();
                ButtonMapper.map(msg.path("button").asText(), msg.path("gesture").asText("click"), active)
                        .ifPresent(action -> {
                            switch (action) {
                                case ButtonMapper.TakePhoto p -> registry.send(deviceId, new DeviceMessages.TakePhoto(3));
                                case ButtonMapper.SayToCoach s -> askCoach(deviceId, s.text());
                            }
                        });
            }
            case "ping" -> registry.send(deviceId, java.util.Map.of("type", "pong"));
            default -> registry.send(deviceId, new DeviceMessages.Error("Okänd typ: " + msg.path("type").asText()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String deviceId = (String) session.getAttributes().get(DEVICE_ID);
        if (deviceId != null) {
            registry.unregister(deviceId, session);
            log.info("Enhet frånkopplad: {} ({})", deviceId, status.getCode());
        }
    }

    private void askCoach(String deviceId, String text) {
        registry.send(deviceId, new DeviceMessages.Thinking());
        workers.submit(() -> registry.send(deviceId, new DeviceMessages.Say(coach.chat(deviceId, text))));
    }
}
