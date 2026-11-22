package se.ostberg.pluggis.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Håller koll på anslutna enheter och skickar JSON-meddelanden till dem. */
@Component
public class DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_LIMIT_BYTES = 4 * 1024 * 1024;

    /** Per enhet: alla öppna anslutningar (Tab5, simulatorn, elevchatten i mobilen) → vill den ha ljud? */
    private final Map<String, Map<String, Conn>> devices = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    private record Conn(WebSocketSession session, boolean audio) {
    }

    public DeviceRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Flera anslutningar per enhet är ok: Tab5 och mobilen ser samma samtal. */
    void register(String deviceId, WebSocketSession raw) {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES);
        devices.computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>()).put(raw.getId(), new Conn(safe, false));
    }

    void unregister(String deviceId, WebSocketSession raw) {
        devices.computeIfPresent(deviceId, (id, conns) -> {
            conns.remove(raw.getId());
            return conns.isEmpty() ? null : conns;
        });
    }

    /** Anslutningen har sagt (i "hello") om den vill ha uppläst ljud. */
    void setWantsAudio(String deviceId, WebSocketSession raw, boolean wantsAudio) {
        Map<String, Conn> conns = devices.get(deviceId);
        if (conns != null) {
            conns.computeIfPresent(raw.getId(), (k, c) -> new Conn(c.session(), wantsAudio));
        }
    }

    /** Vill någon anslutning för enheten ha ljud? */
    public boolean wantsAudio(String deviceId) {
        return open(deviceId).stream().anyMatch(Conn::audio);
    }

    public void sendBinary(String deviceId, byte[] data) {
        for (Conn c : open(deviceId)) {
            if (!c.audio()) {
                continue;
            }
            try {
                c.session().sendMessage(new BinaryMessage(data));
            } catch (IOException | IllegalStateException e) {
                log.warn("Kunde inte skicka ljud till {}: {}", deviceId, e.toString());
            }
        }
    }

    public boolean isConnected(String deviceId) {
        return !open(deviceId).isEmpty();
    }

    public Set<String> connected() {
        return devices.keySet().stream().filter(this::isConnected).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private java.util.List<Conn> open(String deviceId) {
        Map<String, Conn> conns = devices.get(deviceId);
        return conns == null ? java.util.List.of()
                : conns.values().stream().filter(c -> c.session().isOpen()).toList();
    }

    public void send(String deviceId, Object payload) {
        java.util.List<Conn> conns = open(deviceId);
        if (conns.isEmpty()) {
            return;
        }
        TextMessage text;
        try {
            text = new TextMessage(mapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.error("Kunde inte serialisera meddelande till {}", deviceId, e);
            return;
        }
        for (Conn c : conns) {
            try {
                c.session().sendMessage(text);
            } catch (IOException | IllegalStateException e) {
                log.warn("Kunde inte skicka till {}: {}", deviceId, e.toString());
            }
        }
    }
}
