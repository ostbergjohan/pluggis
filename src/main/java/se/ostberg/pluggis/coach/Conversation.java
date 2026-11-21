package se.ostberg.pluggis.coach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Meddelandehistorik för en enhet, i Messages API-format.
 * Trimmas så att historiken alltid börjar med ett vanligt användarmeddelande
 * (aldrig med ett tool_result som saknar sitt tool_use).
 */
class Conversation {

    private record Entry(ObjectNode message, boolean plainUserTurn) {
    }

    private final ObjectMapper mapper;
    private final int maxMessages;
    private final Duration idleTimeout;
    private final List<Entry> entries = new ArrayList<>();
    private Instant lastActivity = Instant.now();

    Conversation(ObjectMapper mapper, int maxMessages, Duration idleTimeout) {
        this.mapper = mapper;
        this.maxMessages = Math.max(4, maxMessages);
        this.idleTimeout = idleTimeout;
    }

    void resetIfIdle(Instant now) {
        if (Duration.between(lastActivity, now).compareTo(idleTimeout) > 0) {
            entries.clear();
        }
        lastActivity = now;
    }

    void addUserText(String text) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        msg.put("content", text);
        entries.add(new Entry(msg, true));
        trim();
    }

    void addAssistant(JsonNode contentBlocks) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "assistant");
        msg.set("content", contentBlocks);
        entries.add(new Entry(msg, false));
    }

    void addToolResults(ArrayNode toolResults) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        msg.set("content", toolResults);
        entries.add(new Entry(msg, false));
    }

    void clear() {
        entries.clear();
    }

    ArrayNode toJson() {
        ArrayNode arr = mapper.createArrayNode();
        entries.forEach(e -> arr.add(e.message()));
        return arr;
    }

    int size() {
        return entries.size();
    }

    private void trim() {
        while (entries.size() > maxMessages) {
            entries.remove(0);
        }
        while (!entries.isEmpty() && !entries.get(0).plainUserTurn()) {
            entries.remove(0);
        }
    }
}
