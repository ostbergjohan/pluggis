package se.ostberg.pluggis.coach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void trimmedHistoryAlwaysStartsWithPlainUserTurn() {
        Conversation conv = new Conversation(mapper, 4, Duration.ofMinutes(30));

        conv.addUserText("hej");
        conv.addAssistant(mapper.createArrayNode().add(mapper.createObjectNode().put("type", "tool_use")));
        conv.addToolResults(mapper.createArrayNode().add(mapper.createObjectNode().put("type", "tool_result")));
        conv.addAssistant(mapper.createArrayNode().add(mapper.createObjectNode().put("type", "text").put("text", "ok")));
        conv.addUserText("nästa fråga");

        ArrayNode json = conv.toJson();
        assertThat(json.get(0).path("role").asText()).isEqualTo("user");
        assertThat(json.get(0).path("content").isTextual()).isTrue();
        assertThat(json.get(0).path("content").asText()).isEqualTo("nästa fråga");
    }

    @Test
    void resetsAfterIdle() {
        Conversation conv = new Conversation(mapper, 30, Duration.ofMinutes(1));
        conv.addUserText("hej");

        conv.resetIfIdle(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(conv.size()).isZero();
    }
}
