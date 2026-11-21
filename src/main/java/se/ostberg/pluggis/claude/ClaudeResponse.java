package se.ostberg.pluggis.claude;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.stream.Collectors;

/** Den del av Messages API-svaret vi bryr oss om. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaudeResponse(
        String id,
        List<ContentBlock> content,
        @JsonProperty("stop_reason") String stopReason,
        Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentBlock(String type, String text, String id, String name, JsonNode input) {

        public boolean isToolUse() {
            return "tool_use".equals(type);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("output_tokens") long outputTokens) {
    }

    public String text() {
        if (content == null) {
            return "";
        }
        return content.stream()
                .filter(b -> "text".equals(b.type()) && b.text() != null)
                .map(ContentBlock::text)
                .collect(Collectors.joining("\n"))
                .trim();
    }

    public List<ContentBlock> toolUses() {
        return content == null ? List.of() : content.stream().filter(ContentBlock::isToolUse).toList();
    }

    public boolean wantsTools() {
        return "tool_use".equals(stopReason);
    }
}
