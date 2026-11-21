package se.ostberg.pluggis.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Bygger ihop ett komplett {@link ClaudeResponse} av strömmade händelser (SSE),
 * och skickar textbitar vidare medan de kommer – så att första meningen kan läsas upp direkt.
 */
public class StreamAccumulator {

    private static final class Block {
        String type;
        String id;
        String name;
        String data;
        final StringBuilder text = new StringBuilder();
        final StringBuilder json = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final StringBuilder signature = new StringBuilder();
    }

    private final ObjectMapper mapper;
    private final Consumer<String> onText;
    private final TreeMap<Integer, Block> blocks = new TreeMap<>();
    private String messageId;
    private String stopReason;
    private long inputTokens;
    private long outputTokens;
    private long cacheCreation;
    private long cacheRead;
    private boolean sawText;

    public StreamAccumulator(ObjectMapper mapper, Consumer<String> onText) {
        this.mapper = mapper;
        this.onText = onText == null ? s -> { } : onText;
    }

    public void accept(JsonNode event) {
        switch (event.path("type").asText()) {
            case "message_start" -> {
                JsonNode msg = event.path("message");
                messageId = msg.path("id").asText(null);
                JsonNode u = msg.path("usage");
                inputTokens = u.path("input_tokens").asLong();
                cacheCreation = u.path("cache_creation_input_tokens").asLong();
                cacheRead = u.path("cache_read_input_tokens").asLong();
                outputTokens = u.path("output_tokens").asLong();
            }
            case "content_block_start" -> {
                JsonNode cb = event.path("content_block");
                Block b = new Block();
                b.type = cb.path("type").asText();
                b.id = cb.path("id").asText(null);
                b.name = cb.path("name").asText(null);
                b.data = cb.path("data").asText(null);
                if (cb.hasNonNull("text")) {
                    b.text.append(cb.path("text").asText());
                }
                blocks.put(event.path("index").asInt(), b);
            }
            case "content_block_delta" -> {
                Block b = blocks.get(event.path("index").asInt());
                if (b == null) {
                    return;
                }
                JsonNode d = event.path("delta");
                switch (d.path("type").asText()) {
                    case "text_delta" -> {
                        String t = d.path("text").asText("");
                        b.text.append(t);
                        if (!t.isEmpty()) {
                            sawText = true;
                            onText.accept(t);
                        }
                    }
                    case "input_json_delta" -> b.json.append(d.path("partial_json").asText(""));
                    case "thinking_delta" -> b.thinking.append(d.path("thinking").asText(""));
                    case "signature_delta" -> b.signature.append(d.path("signature").asText(""));
                    default -> { }
                }
            }
            case "message_delta" -> {
                if (event.path("delta").hasNonNull("stop_reason")) {
                    stopReason = event.path("delta").path("stop_reason").asText();
                }
                if (event.path("usage").has("output_tokens")) {
                    outputTokens = event.path("usage").path("output_tokens").asLong();
                }
            }
            case "error" -> throw new IllegalStateException("Claude-fel i strömmen: "
                    + event.path("error").path("type").asText() + " – " + event.path("error").path("message").asText());
            default -> { }
        }
    }

    public boolean sawText() {
        return sawText;
    }

    public ClaudeResponse result() {
        List<ClaudeResponse.ContentBlock> content = new ArrayList<>();
        for (Block b : blocks.values()) {
            switch (b.type) {
                case "text" -> content.add(ClaudeResponse.ContentBlock.text(b.text.toString()));
                case "tool_use" -> content.add(new ClaudeResponse.ContentBlock("tool_use", null, b.id, b.name,
                        parseInput(b.json.toString()), null, null, null));
                case "thinking" -> content.add(new ClaudeResponse.ContentBlock("thinking", null, null, null, null,
                        b.thinking.toString(), b.signature.toString(), null));
                case "redacted_thinking" -> content.add(new ClaudeResponse.ContentBlock("redacted_thinking", null,
                        null, null, null, null, null, b.data));
                default -> { }
            }
        }
        return new ClaudeResponse(messageId, content, stopReason,
                new ClaudeResponse.Usage(inputTokens, outputTokens, cacheCreation, cacheRead));
    }

    private JsonNode parseInput(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
