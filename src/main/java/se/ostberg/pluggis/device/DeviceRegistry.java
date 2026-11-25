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

    /**
     * {@code chunkBytes} &gt; 0: små enheter (Tab5) som vill ha ljudet som 16 kHz mono i bitar om högst så många
     * byte, i stället för en stor wav-ram som de inte hinner ta emot.
     */
    private record Conn(WebSocketSession session, boolean audio, int chunkBytes) {
    }

    public DeviceRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Flera anslutningar per enhet är ok: Tab5 och mobilen ser samma samtal. */
    void register(String deviceId, WebSocketSession raw) {
        register(deviceId, raw, null);
    }

    /**
     * {@code clientId}: samma flik/app som ansluter igen (t.ex. iPhone efter bakgrunden). Den gamla
     * anslutningen stängs då, annars kan den leva kvar och få varje svar en gång till.
     */
    void register(String deviceId, WebSocketSession raw, String clientId) {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES);
        var conns = devices.computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>());
        if (clientId != null && !clientId.isBlank()) {
            raw.getAttributes().put(CLIENT_ID, clientId);
            conns.entrySet().removeIf(e -> {
                WebSocketSession old = e.getValue().session();
                if (!e.getKey().equals(raw.getId()) && clientId.equals(old.getAttributes().get(CLIENT_ID))) {
                    try {
                        old.close(org.springframework.web.socket.CloseStatus.NORMAL.withReason("ersatt av ny anslutning"));
                    } catch (IOException | IllegalStateException ignored) {
                        // redan stängd
                    }
                    log.debug("Ersatte gammal anslutning för {} ({})", deviceId, clientId);
                    return true;
                }
                return false;
            });
        }
        conns.put(raw.getId(), new Conn(safe, false, 0));
    }

    static final String CLIENT_ID = "pluggis.clientId";

    /** Antal öppna anslutningar (för test). */
    int connections(String deviceId) {
        var c = devices.get(deviceId);
        return c == null ? 0 : c.size();
    }

    void unregister(String deviceId, WebSocketSession raw) {
        devices.computeIfPresent(deviceId, (id, conns) -> {
            conns.remove(raw.getId());
            return conns.isEmpty() ? null : conns;
        });
    }

    /** Anslutningen har sagt (i "hello") om den vill ha uppläst ljud. */
    void setWantsAudio(String deviceId, WebSocketSession raw, boolean wantsAudio) {
        setWantsAudio(deviceId, raw, wantsAudio, 0);
    }

    void setWantsAudio(String deviceId, WebSocketSession raw, boolean wantsAudio, int chunkBytes) {
        Map<String, Conn> conns = devices.get(deviceId);
        if (conns != null) {
            int chunk = chunkBytes <= 0 ? 0 : Math.max(1024, Math.min(chunkBytes, 64 * 1024));
            conns.computeIfPresent(raw.getId(), (k, c) -> new Conn(c.session(), wantsAudio, chunk));
        }
    }

    /**
     * Skickar en uppläst mening: först {@code audio}-meddelandet, sedan ljudet. Anslutningar som bett om bitar
     * får ljudet omgjort till 16 kHz mono och uppdelat; övriga får wav-filen som den är i en ram.
     */
    public void sendAudio(String deviceId, int seq, String text, byte[] wav) {
        byte[] small = null;
        for (Conn c : open(deviceId)) {
            if (!c.audio()) {
                continue;
            }
            try {
                byte[] data = wav;
                if (c.chunkBytes() > 0) {
                    if (small == null) {
                        small = se.ostberg.pluggis.speech.Wav.toMono16k(wav);
                    }
                    data = small;
                }
                c.session().sendMessage(new TextMessage(mapper.writeValueAsString(
                        new DeviceMessages.Audio("wav", seq, text, data.length))));
                int step = c.chunkBytes() > 0 ? c.chunkBytes() : data.length;
                for (int off = 0; off < data.length; off += step) {
                    int n = Math.min(step, data.length - off);
                    c.session().sendMessage(new BinaryMessage(java.nio.ByteBuffer.wrap(data, off, n), true));
                }
            } catch (IOException | IllegalStateException e) {
                drop(deviceId, c, "ljud", e);
            }
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
                drop(deviceId, c, "ljud", e);
            }
        }
    }

    /**
     * En anslutning som inte går att skicka till (t.ex. en iPhone som lagt appen i bakgrunden utan att
     * säga hejdå) tas bort direkt, så att vi inte fortsätter skicka in i tomma intet.
     */
    private void drop(String deviceId, Conn c, String what, Exception e) {
        Map<String, Conn> conns = devices.get(deviceId);
        if (conns != null) {
            conns.values().remove(c);
        }
        try {
            c.session().close();
        } catch (IOException | IllegalStateException ignored) {
            // redan stängd
        }
        log.info("Tog bort en död anslutning för {} (kunde inte skicka {}: {})", deviceId, what,
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
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
                drop(deviceId, c, "meddelande", e);
            }
        }
    }
}
