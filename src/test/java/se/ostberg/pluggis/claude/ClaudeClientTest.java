package se.ostberg.pluggis.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import se.ostberg.pluggis.config.PluggisProperties;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ClaudeClient client() {
        var profile = new PluggisProperties.ModelProfile("claude-haiku-5-5", "low", "disabled", 400);
        var anthropic = new PluggisProperties.Anthropic("key", "https://api.anthropic.com", "2023-06-01",
                profile, profile, profile, profile);
        var props = new PluggisProperties(null, "Europe/Stockholm", 30, 30, anthropic,
                null, null, null, null, null);
        return new ClaudeClient(RestClient.builder(), mapper, new SimpleMeterRegistry(), props);
    }

    @Test
    void staticRulesAreCachedAndLiveStateComesAfter() {
        var profile = new PluggisProperties.ModelProfile("claude-haiku-5-5", "low", "disabled", 400);
        ObjectNode req = client().newRequest(profile, "Regler…", "Läget just nu…");

        assertThat(req.path("model").asText()).isEqualTo("claude-haiku-5-5");
        JsonNode system = req.path("system");
        assertThat(system.size()).isEqualTo(2);
        assertThat(system.get(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        assertThat(system.get(1).has("cache_control")).isFalse();
        assertThat(req.path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(req.path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    @Test
    void blankEffortAndThinkingAreLeftOut() {
        var profile = new PluggisProperties.ModelProfile("claude-sonnet-5-5", "", "", 2048);
        ObjectNode req = client().newRequest(profile, "Regler", null);

        assertThat(req.has("output_config")).isFalse();
        assertThat(req.has("thinking")).isFalse();
        assertThat(req.path("system").size()).isEqualTo(1);
    }

    @Test
    void streamAccumulatorRebuildsTextToolUseAndThinking() throws Exception {
        List<String> deltas = new ArrayList<>();
        StreamAccumulator acc = new StreamAccumulator(mapper, deltas::add);
        String[] events = {
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"usage\":{\"input_tokens\":12,\"cache_read_input_tokens\":900,\"output_tokens\":1}}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Eleven vill börja.\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig==\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Okej, \"}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"vi startar!\"}}",
                "{\"type\":\"content_block_stop\",\"index\":1}",
                "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"start_session\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"task\\\":\\\"Matte\\\",\"}}",
                "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"minutes\\\":15}\"}}",
                "{\"type\":\"content_block_stop\",\"index\":2}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":40}}",
                "{\"type\":\"message_stop\"}"
        };
        for (String e : events) {
            acc.accept(mapper.readTree(e));
        }

        ClaudeResponse r = acc.result();
        assertThat(deltas).containsExactly("Okej, ", "vi startar!");
        assertThat(r.text()).isEqualTo("Okej, vi startar!");
        assertThat(r.wantsTools()).isTrue();
        assertThat(r.toolUses().get(0).input().path("minutes").asInt()).isEqualTo(15);
        assertThat(r.usage().cacheReadInputTokens()).isEqualTo(900);
        assertThat(r.usage().outputTokens()).isEqualTo(40);

        // Thinking-blocket ska kunna skickas tillbaka orört
        JsonNode back = mapper.valueToTree(r.content());
        assertThat(back.get(0).path("type").asText()).isEqualTo("thinking");
        assertThat(back.get(0).path("signature").asText()).isEqualTo("sig==");
        assertThat(back.get(1).has("signature")).isFalse();
        // Bara fält som Messages API känner till får skickas tillbaka
        for (JsonNode block : back) {
            block.fieldNames().forEachRemaining(f -> assertThat(f)
                    .isIn("type", "text", "id", "name", "input", "thinking", "signature", "data"));
        }
    }
}
