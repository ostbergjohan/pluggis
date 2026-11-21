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

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Set<String> audioDevices = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper;

    public DeviceRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void register(String deviceId, WebSocketSession raw) {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES);
        WebSocketSession previous = sessions.put(deviceId, safe);
        if (previous != null && previous.isOpen() && !previous.getId().equals(raw.getId())) {
            try {
                previous.close();
            } catch (IOException ignored) {
                // gammal anslutning, inget att göra
            }
        }
    }

    void unregister(String deviceId, WebSocketSession raw) {
        sessions.computeIfPresent(deviceId, (id, s) -> {
            if (s.getId().equals(raw.getId())) {
                audioDevices.remove(deviceId);
                return null;
            }
            return s;
        });
    }

    /** Enheten har sagt (i "hello") att den vill ha uppläst ljud. */
    void setWantsAudio(String deviceId, boolean wantsAudio) {
        if (wantsAudio) {
            audioDevices.add(deviceId);
        } else {
            audioDevices.remove(deviceId);
        }
    }

    public boolean wantsAudio(String deviceId) {
        return audioDevices.contains(deviceId);
    }

    public void sendBinary(String deviceId, byte[] data) {
        WebSocketSession s = sessions.get(deviceId);
        if (s == null || !s.isOpen()) {
            return;
        }
        try {
            s.sendMessage(new BinaryMessage(data));
        } catch (IOException | IllegalStateException e) {
            log.warn("Kunde inte skicka ljud till {}: {}", deviceId, e.toString());
        }
    }

    public boolean isConnected(String deviceId) {
        WebSocketSession s = sessions.get(deviceId);
        return s != null && s.isOpen();
    }

    public Set<String> connected() {
        return Set.copyOf(sessions.keySet());
    }

    public void send(String deviceId, Object payload) {
        WebSocketSession s = sessions.get(deviceId);
        if (s == null || !s.isOpen()) {
            return;
        }
        try {
            s.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            log.error("Kunde inte serialisera meddelande till {}", deviceId, e);
        } catch (IOException | IllegalStateException e) {
            log.warn("Kunde inte skicka till {}: {}", deviceId, e.toString());
        }
    }
}
