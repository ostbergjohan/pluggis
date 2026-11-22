package se.ostberg.pluggis.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Kör ett Messages API-anrop via Claude Code ({@code claude -p}) och översätter svaret tillbaka,
 * så att resten av Pluggis inte märker skillnad.
 * <ul>
 *   <li>Systemprompten ersätter Claude Codes egen ({@code --system-prompt-file}), och Claude Codes
 *       inbyggda verktyg stängs av ({@code --tools ""}).</li>
 *   <li>Pluggis verktyg (läxor, kort, plan …) beskrivs i systemprompten, och Claude svarar enligt ett
 *       JSON-schema ({@code --json-schema}) med {@code text} och {@code tool_calls}. Det blir vanliga
 *       {@code tool_use}-block, så coachens verktygsloop fungerar som förut.</li>
 *   <li>Bilder och PDF:er skickas som innehållsblock via {@code --input-format stream-json}.</li>
 * </ul>
 * Kräver {@code CLAUDE_CODE_OAUTH_TOKEN} (från {@code claude setup-token}) i miljön.
 */
@Component
public class ClaudeCodeRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeRunner.class);

    static final String TOOL_INSTRUCTIONS = """


            ## Verktyg
            Du kan använda verktygen nedan. Svara ALLTID enligt JSON-schemat:
            - "text": det du säger till eleven (kan vara tomt om du först behöver ett verktyg).
            - "tool_calls": verktyg du vill köra nu, med "name" och "input" enligt verktygets schema. Tom lista om inga.
            När du anropar verktyg får du resultaten i nästa meddelande och svarar sedan eleven.
            Hitta aldrig på verktygsresultat.

            """;

    private final ClaudeCodeProperties props;
    private final ObjectMapper mapper;
    private final Semaphore slots;

    public ClaudeCodeRunner(ClaudeCodeProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.slots = new Semaphore(props.concurrency());
    }

    public boolean enabled() {
        return props.enabled();
    }

    public ClaudeResponse run(ObjectNode request) {
        Path dir = null;
        try {
            slots.acquire();
            dir = Files.createTempDirectory("pluggis-cc");
            Prepared p = prepare(request, dir);
            ProcessBuilder pb = new ProcessBuilder(p.args()).directory(dir.toFile());
            Map<String, String> env = pb.environment();
            env.remove("ANTHROPIC_API_KEY"); // annars används API-nyckeln i stället för abonnemanget
            env.put("DISABLE_AUTOUPDATER", "1");
            env.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
            Path outFile = dir.resolve("out.json");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.redirectOutput(outFile.toFile());
            Process proc = pb.start();
            try (OutputStream in = proc.getOutputStream()) {
                in.write(p.stdin().getBytes(StandardCharsets.UTF_8));
            }
            if (!proc.waitFor(props.timeout(), TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IllegalStateException("Claude Code svarade inte inom " + props.timeout() + " s");
            }
            return parse(Files.readString(outFile, StandardCharsets.UTF_8), p.withTools());
        } catch (IOException e) {
            throw new IllegalStateException("Kunde inte köra Claude Code (" + props.commandOrDefault()
                    + "). Är det installerat och inloggat?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Avbrutet", e);
        } finally {
            slots.release();
            delete(dir);
        }
    }

    record Prepared(List<String> args, String stdin, boolean withTools) {
    }

    /** Bygger kommandorad och indata. Paket-synlig för test. */
    Prepared prepare(ObjectNode request, Path dir) throws IOException {
        StringBuilder system = new StringBuilder();
        for (JsonNode block : request.path("system")) {
            if (!system.isEmpty()) {
                system.append("\n\n");
            }
            system.append(block.path("text").asText());
        }
        if (request.path("system").isTextual()) {
            system.append(request.path("system").asText());
        }

        JsonNode tools = request.path("tools");
        boolean withTools = tools.isArray() && !tools.isEmpty();
        List<String> args = new ArrayList<>(List.of(props.commandOrDefault(), "-p",
                "--input-format", "stream-json", "--output-format", "json",
                "--tools", "", "--strict-mcp-config", "--no-session-persistence",
                "--permission-mode", "dontAsk",
                "--max-turns", withTools ? "3" : "2"));
        String model = request.path("model").asText("");
        if (!model.isBlank()) {
            args.add("--model");
            args.add(model);
        }
        String effort = request.path("output_config").path("effort").asText("");
        if (!effort.isBlank()) {
            args.add("--effort");
            args.add(effort);
        }
        if (withTools) {
            system.append(TOOL_INSTRUCTIONS);
            List<String> names = new ArrayList<>();
            for (JsonNode t : tools) {
                names.add(t.path("name").asText());
                system.append("- ").append(t.path("name").asText()).append(": ")
                        .append(t.path("description").asText()).append("\n  input_schema: ")
                        .append(mapper.writeValueAsString(t.path("input_schema"))).append('\n');
            }
            args.add("--json-schema");
            args.add(mapper.writeValueAsString(schema(names)));
        }
        Path systemFile = dir.resolve("system.md");
        Files.writeString(systemFile, system.toString(), StandardCharsets.UTF_8);
        args.add("--system-prompt-file");
        args.add(systemFile.toString());

        ObjectNode line = mapper.createObjectNode();
        line.put("type", "user");
        ObjectNode message = line.putObject("message");
        message.put("role", "user");
        message.set("content", content(request.path("messages")));
        return new Prepared(args, mapper.writeValueAsString(line) + "\n", withTools);
    }

    /**
     * Hela historiken som ett användarmeddelande: tidigare repliker som text, sista meddelandets
     * bilder/dokument som de är (så att Claude ser dem).
     */
    private ArrayNode content(JsonNode messages) {
        ArrayNode out = mapper.createArrayNode();
        StringBuilder transcript = new StringBuilder();
        int last = messages.size() - 1;
        for (int i = 0; i <= last; i++) {
            JsonNode m = messages.get(i);
            boolean user = "user".equals(m.path("role").asText());
            JsonNode c = m.path("content");
            if (i < last) {
                transcript.append(user ? "ELEVEN/SYSTEMET: " : "DU (Pluggis): ").append(asText(c)).append("\n\n");
                continue;
            }
            if (!transcript.isEmpty()) {
                out.addObject().put("type", "text").put("text",
                        "Tidigare i samtalet:\n\n" + transcript + "Nytt meddelande:");
            }
            if (c.isTextual()) {
                out.addObject().put("type", "text").put("text", c.asText());
            } else {
                for (JsonNode b : c) {
                    String type = b.path("type").asText();
                    if ("image".equals(type) || "document".equals(type)) {
                        out.add(b.deepCopy());
                    } else {
                        out.addObject().put("type", "text").put("text", asText(mapper.createArrayNode().add(b)));
                    }
                }
            }
        }
        if (out.isEmpty()) {
            out.addObject().put("type", "text").put("text", "(tomt)");
        }
        return out;
    }

    private String asText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : content) {
            switch (b.path("type").asText()) {
                case "text" -> sb.append(b.path("text").asText());
                case "tool_use" -> sb.append("[anropade verktyget ").append(b.path("name").asText()).append(' ')
                        .append(b.path("input").toString()).append(" (id ").append(b.path("id").asText()).append(")]");
                case "tool_result" -> sb.append("[verktygssvar för ").append(b.path("tool_use_id").asText()).append(": ")
                        .append(b.path("content").isTextual() ? b.path("content").asText() : b.path("content").toString())
                        .append(']');
                case "image" -> sb.append("[bild]");
                case "document" -> sb.append("[dokument]");
                default -> {
                    // thinking m.m. hoppas över
                }
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private ObjectNode schema(List<String> toolNames) {
        ObjectNode s = mapper.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("text").put("type", "string");
        ObjectNode calls = p.putObject("tool_calls");
        calls.put("type", "array");
        ObjectNode item = calls.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ArrayNode names = ip.putObject("name").put("type", "string").putArray("enum");
        toolNames.forEach(names::add);
        ip.putObject("input").put("type", "object");
        item.putArray("required").add("name").add("input");
        s.putArray("required").add("text").add("tool_calls");
        return s;
    }

    /** Claude Codes JSON-resultat → ClaudeResponse. Paket-synlig för test. */
    ClaudeResponse parse(String stdout, boolean withTools) throws IOException {
        JsonNode result = lastJson(stdout);
        if (result == null) {
            throw new IllegalStateException("Claude Code gav inget svar");
        }
        if (result.path("is_error").asBoolean(false) || "error".equals(result.path("subtype").asText(""))) {
            String why = result.path("result").asText(result.path("subtype").asText("okänt fel"));
            throw new IllegalStateException("Claude Code: " + why);
        }
        List<ClaudeResponse.ContentBlock> content = new ArrayList<>();
        boolean tools = false;
        JsonNode structured = result.path("structured_output");
        if (withTools && structured.isObject()) {
            String text = structured.path("text").asText("");
            if (!text.isBlank()) {
                content.add(ClaudeResponse.ContentBlock.text(text));
            }
            int n = 0;
            for (JsonNode call : structured.path("tool_calls")) {
                tools = true;
                content.add(new ClaudeResponse.ContentBlock("tool_use", null,
                        "cc_" + Long.toHexString(System.nanoTime()) + "_" + (n++), call.path("name").asText(),
                        call.path("input").isObject() ? call.path("input") : mapper.createObjectNode(),
                        null, null, null));
            }
        } else {
            content.add(ClaudeResponse.ContentBlock.text(result.path("result").asText("")));
        }
        JsonNode u = result.path("usage");
        var usage = new ClaudeResponse.Usage(u.path("input_tokens").asLong(), u.path("output_tokens").asLong(),
                u.path("cache_creation_input_tokens").asLong(), u.path("cache_read_input_tokens").asLong());
        return new ClaudeResponse(result.path("session_id").asText(null), content, tools ? "tool_use" : "end_turn", usage);
    }

    /** Sista raden som är ett JSON-objekt (Claude Code skriver normalt bara en). */
    private JsonNode lastJson(String stdout) {
        String[] lines = stdout.trim().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (l.startsWith("{")) {
                try {
                    return mapper.readTree(l);
                } catch (IOException e) {
                    log.debug("Kunde inte läsa rad från Claude Code: {}", e.toString());
                }
            }
        }
        try {
            return stdout.isBlank() ? null : mapper.readTree(stdout);
        } catch (IOException e) {
            return null;
        }
    }

    private static void delete(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
            // tempkatalog – inget att göra
        }
    }
}
