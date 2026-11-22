package se.ostberg.pluggis.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeCodeRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode coachRequest() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"model":"claude-haiku-5-5","max_tokens":512,
                 "system":[{"type":"text","text":"Du är Pluggis.","cache_control":{"type":"ephemeral"}},
                           {"type":"text","text":"Läget: inga läxor."}],
                 "output_config":{"effort":"low"},
                 "tools":[{"name":"list_homework","description":"Hämtar öppna läxor.","input_schema":{"type":"object","properties":{}}}],
                 "messages":[
                   {"role":"user","content":"jag vill plugga"},
                   {"role":"assistant","content":[{"type":"text","text":"Kollar!"},{"type":"tool_use","id":"t1","name":"list_homework","input":{}}]},
                   {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"Matte till fredag"},
                                             {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAA"}}]}
                 ]}
                """);
    }

    @Test
    void buildsCommandSystemPromptAndInput(@TempDir Path dir) throws Exception {
        var runner = new ClaudeCodeRunner(new ClaudeCodeProperties("claude-code", null, 0, 0), mapper);
        var p = runner.prepare(coachRequest(), dir);

        assertThat(p.args()).startsWith("claude", "-p").contains("--tools", "", "--model", "claude-haiku-5-5",
                "--effort", "low", "--json-schema", "--system-prompt-file", "--no-session-persistence");
        String system = Files.readString(dir.resolve("system.md"));
        assertThat(system).contains("Du är Pluggis.", "Läget: inga läxor.", "list_homework", "tool_calls", "reply");

        JsonNode line = mapper.readTree(p.stdin());
        assertThat(line.path("type").asText()).isEqualTo("user");
        JsonNode content = line.path("message").path("content");
        assertThat(content.get(0).path("text").asText())
                .contains("jag vill plugga", "anropade verktyget list_homework");
        assertThat(content.get(1).path("text").asText()).contains("verktygssvar för t1: Matte till fredag");
        assertThat(content.get(2).path("type").asText()).isEqualTo("image");
        assertThat(p.withTools()).isTrue();
    }

    @Test
    void turnsStructuredOutputIntoToolUse() throws Exception {
        var runner = new ClaudeCodeRunner(new ClaudeCodeProperties("claude-code", null, 0, 0), mapper);
        ClaudeResponse r = runner.parse("""
                {"type":"result","subtype":"success","is_error":false,"result":"","session_id":"s1",
                 "structured_output":{"text":"Vi kollar läxorna.","tool_calls":[{"name":"list_homework","input":{}}]},
                 "usage":{"input_tokens":100,"output_tokens":20}}
                """, true);
        assertThat(r.text()).isEqualTo("Vi kollar läxorna.");
        assertThat(r.wantsTools()).isTrue();
        assertThat(r.toolUses()).singleElement().satisfies(t -> assertThat(t.name()).isEqualTo("list_homework"));
        assertThat(r.usage().inputTokens()).isEqualTo(100);

        ClaudeResponse plain = runner.parse("{\"type\":\"system\",\"subtype\":\"init\"}\n"
                + "{\"type\":\"assistant\",\"message\":{}}\n"
                + "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"{\\\"a\\\":1}\"}\n", false);
        assertThat(plain.text()).isEqualTo("{\"a\":1}");
        assertThat(plain.wantsTools()).isFalse();

        assertThatThrownBy(() -> runner.parse("{\"type\":\"result\",\"is_error\":true,\"result\":\"Something broke\"}", false))
                .hasMessageContaining("Something broke");
        // Så ser det ut utan giltig token (från CI)
        assertThatThrownBy(() -> runner.parse("""
                {"type":"assistant","message":{"content":[]},"error":"authentication_failed","is_api_error_message":true}
                {"type":"result","duration_api_ms":0,"total_cost_usd":0,"terminal_reason":"api_error","is_error":true}
                """, false)).hasMessageContaining("CLAUDE_CODE_OAUTH_TOKEN");
    }

    @Test
    void doesNotEchoTheStudentsOwnMessage() throws Exception {
        var runner = new ClaudeCodeRunner(new ClaudeCodeProperties("claude-code", null, 0, 0), mapper);
        ClaudeResponse r = runner.parse("""
                {"type":"result","subtype":"success","structured_output":{"reply":"jag vill plugga matte. Toppen! Vad ska vi börja med?","tool_calls":[]}}
                """, true, "jag vill plugga matte");
        assertThat(r.text()).isEqualTo("Toppen! Vad ska vi börja med?");
        ClaudeResponse only = runner.parse("""
                {"type":"result","subtype":"success","structured_output":{"reply":"Jag vill plugga matte","tool_calls":[{"name":"list_homework","input":{}}]}}
                """, true, "jag vill plugga matte");
        assertThat(only.text()).isEmpty();
        assertThat(only.wantsTools()).isTrue();
        // Korta meddelanden lämnas ifred
        assertThat(ClaudeCodeRunner.stripEcho("Hej Fred! Ska vi köra?", "hej")).isEqualTo("Hej Fred! Ska vi köra?");
        assertThat(ClaudeCodeRunner.stripEcho("Vad bra att du hör av dig.", "Kan du hjälpa mig med engelska?"))
                .isEqualTo("Vad bra att du hör av dig.");
    }

    @Test
    void hybridSendsVoiceToApiAndTheRestToSubscription() {
        var hybrid = new ClaudeCodeProperties("hybrid", null, 0, 0, java.util.List.of("coach"));
        assertThat(hybrid.useFor("coach")).isFalse();
        assertThat(hybrid.useFor("vision")).isTrue();
        assertThat(hybrid.useFor("planning")).isTrue();
        assertThat(hybrid.useFor("flashcards-vision")).isTrue();
        var noFlash = new ClaudeCodeProperties("hybrid", null, 0, 0, java.util.List.of("coach", "flashcards"));
        assertThat(noFlash.useFor("flashcards-document")).isFalse();
        assertThat(new ClaudeCodeProperties("claude-code", null, 0, 0).useFor("coach")).isTrue();
        assertThat(new ClaudeCodeProperties("api", null, 0, 0).useFor("vision")).isFalse();
    }

    @Test
    void runsTheProgramWithoutApiKey(@TempDir Path dir) throws Exception {
        // Låtsas-claude: skriver ut argument-antal, indata och om API-nyckeln läckte igenom
        Path fake = dir.resolve("fake-claude.sh");
        Files.writeString(fake, """
                #!/bin/sh
                input=$(cat)
                key=${ANTHROPIC_API_KEY:-none}
                printf '{"type":"result","subtype":"success","is_error":false,"result":"svar key=%s bytes=%s"}\\n' "$key" "${#input}"
                """);
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwxr-xr-x"));
        var runner = new ClaudeCodeRunner(new ClaudeCodeProperties("claude-code", fake.toString(), 10, 1), mapper);
        ObjectNode req = (ObjectNode) mapper.readTree("""
                {"model":"claude-sonnet-5-5","system":[{"type":"text","text":"Läs av läxorna."}],
                 "messages":[{"role":"user","content":[{"type":"text","text":"v.48 matte"}]}]}""");
        ClaudeResponse r = runner.run(req);
        assertThat(r.text()).startsWith("svar key=none bytes=");
    }
}
