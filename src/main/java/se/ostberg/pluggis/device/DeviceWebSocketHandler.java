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

    private final se.ostberg.pluggis.chat.ChatAccess chat;
    private final se.ostberg.pluggis.student.StudentService students;
    static final String STUDENT_ID = "studentId";

    public DeviceWebSocketHandler(DeviceRegistry registry, VoiceService voice, StudySessionService sessions,
                                  PluggisProperties props, ObjectMapper mapper,
                                  se.ostberg.pluggis.chat.ChatAccess chat,
                                  se.ostberg.pluggis.student.StudentService students) {
        this.chat = chat;
        this.students = students;
        this.registry = registry;
        this.voice = voice;
        this.sessions = sessions;
        this.props = props;
        this.mapper = mapper;
    }

    private se.ostberg.pluggis.config.AdminAuth adminAuth;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAdminAuth(se.ostberg.pluggis.config.AdminAuth adminAuth) {
        this.adminAuth = adminAuth;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        var query = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
        String deviceId = query.getFirst(DEVICE_ID);
        String token = query.getFirst("token");
        String from = session.getRemoteAddress() != null ? session.getRemoteAddress().getAddress().getHostAddress() : "?";

        Long studentId = null;
        boolean parentCookie = adminAuth != null
                && adminAuth.validCookieHeader(session.getHandshakeHeaders().getFirst("Cookie"));   // inloggad i föräldravyn
        if (parentCookie) {
            studentId = findDevice(session, deviceId, from).map(se.ostberg.pluggis.student.Student::getId).orElse(null);
        } else {
            var kid = chat.studentFor(token);
            if (kid.isPresent()) {
                // Eleven pratar alltid som sin egen enhet, aldrig som ett syskons
                studentId = kid.get();
                deviceId = students.byId(studentId).map(se.ostberg.pluggis.student.Student::getDeviceId).orElse(deviceId);
                log.debug("Enhet från {} loggar in med elevkod som {}", from, deviceId);
            } else {
                deviceId = null;
            }
        }
        if (deviceId == null || deviceId.isBlank()) {
            log.warn("Enhet nekad från {}: {} (deviceId {})", from,
                    token == null || token.isBlank() ? "elevkod saknas" : "fel elevkod", query.getFirst(DEVICE_ID));
            tell(session, "Servern nekade skärmen: fel kod. PLUGGIS_CODE i config.h ska vara elevens kod "
                    + "(admin -> Inställningar -> Elever).");
            session.close(CloseStatus.POLICY_VIOLATION.withReason("deviceId eller token saknas/fel"));
            return;
        }
        session.getAttributes().put(DEVICE_ID, deviceId);
        session.getAttributes().put(STUDENT_ID,
                studentId != null ? studentId : se.ostberg.pluggis.student.StudentContext.defaultId());
        registry.register(deviceId, session, query.getFirst("client"));
        log.info("Enhet ansluten: {} från {}", deviceId, from);

        String device = deviceId;
        sessions.active(device).ifPresent(s -> registry.send(device,
                DeviceMessages.SessionState.started(s.getTask(), s.getPlannedMinutes())));
    }

    /**
     * Eleven med det här enhets-id:t. Stavat med andra stora/små bokstäver ("Fred" i stället för "fred")
     * hittas ändå, men loggas. Finns det inte alls loggas vilka som finns.
     */
    private java.util.Optional<se.ostberg.pluggis.student.Student> findDevice(WebSocketSession session, String deviceId, String from) {
        if (deviceId == null || deviceId.isBlank()) {
            return java.util.Optional.empty();
        }
        String wanted = deviceId.trim();
        var found = students.byDeviceId(wanted);
        if (found.isPresent()) {
            if (!wanted.equals(found.get().getDeviceId())) {
                log.warn("Enhets-id '{}' från {} är stavat annorlunda än '{}' – fungerar, men skriv '{}' i PLUGGIS_DEVICE_ID i config.h.",
                        wanted, from, found.get().getDeviceId(), found.get().getDeviceId());
            }
            return found;
        }
        String known = students.all().stream().map(se.ostberg.pluggis.student.Student::getDeviceId).toList().toString();
        // Tillåtet (t.ex. webbsimulatorn), så bara i loggen: skärmen räknas som första eleven
        log.warn("Okänt enhets-id '{}' från {} – elevernas enhets-id: {}. Räknas som första eleven; "
                + "ändra PLUGGIS_DEVICE_ID i config.h om det är Tab5.", wanted, from, known);
        return found;
    }

    /** Skickar ett fel till skärmen direkt (innan den är registrerad). */
    private void tell(WebSocketSession session, String message) {
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(new DeviceMessages.Error(message))));
        } catch (IOException | RuntimeException e) {
            log.debug("Kunde inte skicka felet till skärmen: {}", e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        se.ostberg.pluggis.student.StudentContext.run((Long) session.getAttributes().get(STUDENT_ID),
                () -> onText(session, message));
    }

    private void onText(WebSocketSession session, TextMessage message) {
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
                // "audioChunk": små enheter (Tab5) får ljudet som 16 kHz mono i bitar om högst så många byte
                registry.setWantsAudio(deviceId, session, msg.path("audio").asBoolean(false), msg.path("audioChunk").asInt(0));
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
                boolean mainLong = "long".equalsIgnoreCase(msg.path("gesture").asText())
                        && ("main".equalsIgnoreCase(msg.path("button").asText()) || "blue".equalsIgnoreCase(msg.path("button").asText()));
                if (mainLong) {
                    // Avsluta: avsluta passet direkt (inte via coachen), och låt sedan coachen säga något kort
                    if (active) {
                        sessions.finish(deviceId, "Avslutat med knappen");
                        voice.onText(deviceId, "(Jag avslutade passet med knappen just nu. Säg något kort och peppande.)");
                    } else {
                        registry.send(deviceId, new DeviceMessages.Say("Inget pass pågår just nu. Tryck på Börja när du vill plugga."));
                    }
                    return;
                }
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
        se.ostberg.pluggis.student.StudentContext.run((Long) session.getAttributes().get(STUDENT_ID),
                () -> onBinary(session, message));
    }

    private void onBinary(WebSocketSession session, BinaryMessage message) {
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
            log.info("Enhet frånkopplad: {} ({})", deviceId, status.getCode() == 1006
                    ? "1006 – appen stängdes, låstes eller tappade nätet; ansluter igen själv" : status.getCode());
        }
    }
}
