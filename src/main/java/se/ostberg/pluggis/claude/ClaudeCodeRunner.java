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
            - "reply": DITT svar till eleven, som det ska sägas högt. Upprepa aldrig elevens meddelande.
              Kan vara tomt om du först behöver ett verktyg.
            - "tool_calls": verktyg du vill köra nu, med "name" och "input" enligt verktygets schema. Tom lista om inga.
            När du anropar verktyg får du resultaten i nästa meddelande och svarar sedan eleven.
            Hitta aldrig på verktygsresultat.

            """;

    private final ClaudeCodeProperties props;
    private final ObjectMapper mapper;
    private final Semaphore slots;
    /** Läge valt i admin (sparas i inställningarna); null = det som står i .env. */
    private volatile String override;
    private se.ostberg.pluggis.settings.SettingsService settings;

    // Status för admin
    private final java.util.concurrent.atomic.AtomicLong ok = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong failed = new java.util.concurrent.atomic.AtomicLong();
    private volatile java.time.Instant lastOk;
    private volatile java.time.Instant lastFailure;
    private volatile String lastError;
    private volatile String cliVersion;

    public static final String MODE_SETTING = "claude.backend";
    public static final List<String> MODES = List.of("api", "hybrid", "claude-code");

    public ClaudeCodeRunner(ClaudeCodeProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.slots = new Semaphore(props.concurrency());
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSettings(se.ostberg.pluggis.settings.SettingsService settings) {
        this.settings = settings;
        try {
            settings.get(MODE_SETTING).filter(MODES::contains).ifPresent(m -> override = m);
        } catch (RuntimeException e) {
            log.debug("Kunde inte läsa valt Claude-läge: {}", e.toString());
        }
    }

    /** Byt läge i drift (från admin). null = tillbaka till .env. */
    public void setMode(String mode) {
        if (mode != null && !MODES.contains(mode)) {
            throw new IllegalArgumentException("Okänt läge: " + mode);
        }
        override = mode;
        if (settings != null) {
            settings.put(MODE_SETTING, mode == null ? "" : mode);
        }
        log.info("Claude-läge ändrat till {}", mode());
    }

    public boolean enabled() {
        String m = mode();
        return m.equals("claude-code") || m.equals("hybrid");
    }

    public boolean useFor(String purpose) {
        return props.useFor(mode(), purpose);
    }

    public String mode() {
        return override != null ? override : props.mode();
    }

    public String configuredMode() {
        return props.mode();
    }

    public List<String> apiPurposes() {
        return props.apiPurposesOrDefault();
    }

    public boolean hasToken() {
        String t = System.getenv("CLAUDE_CODE_OAUTH_TOKEN");
        return t != null && !t.isBlank();
    }

    /** Version av claude-programmet, eller null om det inte finns. Cachas. */
    public String cliVersion() {
        if (cliVersion == null) {
            try {
                Process p = new ProcessBuilder(props.commandOrDefault(), "--version").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                cliVersion = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isBlank() ? out : "";
            } catch (IOException e) {
                cliVersion = "";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return cliVersion.isEmpty() ? null : cliVersion;
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ok", ok.get());
        m.put("failed", failed.get());
        m.put("lastOk", lastOk == null ? null : lastOk.toString());
        m.put("lastFailure", lastFailure == null ? null : lastFailure.toString());
        m.put("lastError", lastError);
        return m;
    }

    public ClaudeResponse run(ObjectNode request) {
        try {
            ClaudeResponse r = runOnce(request);
            ok.incrementAndGet();
            lastOk = java.time.Instant.now();
            return r;
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            lastFailure = java.time.Instant.now();
            lastError = e.getMessage();
            throw e;
        }
    }

    private ClaudeResponse runOnce(ObjectNode request) {
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
            return parse(Files.readString(outFile, StandardCharsets.UTF_8), p.withTools(), p.lastUserText());
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

    record Prepared(List<String> args, String stdin, boolean withTools, String lastUserText) {
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
                "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
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
        return new Prepared(args, mapper.writeValueAsString(line) + "\n", withTools, lastUserText(request.path("messages")));
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

    /** Elevens senaste egna text (inte verktygssvar). */
    private static String lastUserText(JsonNode messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode m = messages.get(i);
            if (!"user".equals(m.path("role").asText())) {
                continue;
            }
            JsonNode c = m.path("content");
            if (c.isTextual()) {
                return c.asText();
            }
            for (JsonNode b : c) {
                if ("text".equals(b.path("type").asText())) {
                    return b.path("text").asText();
                }
            }
        }
        return null;
    }

    private ObjectNode schema(List<String> toolNames) {
        ObjectNode s = mapper.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("reply").put("type", "string")
                .put("description", "Ditt eget svar till eleven (aldrig en upprepning av elevens meddelande)");
        ObjectNode calls = p.putObject("tool_calls");
        calls.put("type", "array");
        ObjectNode item = calls.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ArrayNode names = ip.putObject("name").put("type", "string").putArray("enum");
        toolNames.forEach(names::add);
        ip.putObject("input").put("type", "object");
        item.putArray("required").add("name").add("input");
        s.putArray("required").add("reply").add("tool_calls");
        return s;
    }

    /**
     * Om svaret börjar med elevens eget meddelande (modellen "ekar" det) tas det bort,
     * så att det varken visas eller läses upp.
     */
    static String stripEcho(String reply, String userText) {
        if (reply == null || userText == null || userText.isBlank()) {
            return reply;
        }
        String r = reply.trim();
        String u = userText.trim().replaceAll("[\\s\\p{Punct}]+$", "");
        // Korta meddelanden ("hej", "ok") liknar ofta början på ett vanligt svar ("Hej Fred!") – rör inte dem.
        if (u.length() < 12) {
            return reply;
        }
        if (r.replaceAll("[\\s\\p{Punct}]+$", "").equalsIgnoreCase(u)) {
            return "";
        }
        if (r.length() > u.length() && r.regionMatches(true, 0, u, 0, u.length())) {
            return r.substring(u.length()).replaceFirst("^[\\s\\p{Punct}–—]+", "").trim();
        }
        return reply;
    }

    ClaudeResponse parse(String stdout, boolean withTools) throws IOException {
        return parse(stdout, withTools, null);
    }

    /** Claude Codes JSON-resultat → ClaudeResponse. Paket-synlig för test. */
    ClaudeResponse parse(String stdout, boolean withTools, String lastUserText) throws IOException {
        JsonNode result = lastJson(stdout);
        if (result == null) {
            throw new IllegalStateException("Claude Code gav inget svar");
        }
        if (stdout.contains("\"authentication_failed\"")) {
            throw new IllegalStateException("Claude Code är inte inloggat – kontrollera CLAUDE_CODE_OAUTH_TOKEN "
                    + "(skapa en ny med \"claude setup-token\")");
        }
        rateLimitEvents(stdout);
        if (stdout.contains("\"rate_limit\"")) {
            throw SubscriptionLimit.reached(result.path("result").asText("") + " " + stdout.substring(Math.max(0, stdout.length() - 2000)));
        }
        if (result.path("is_error").asBoolean(false) || result.path("subtype").asText("").startsWith("error")
                || "api_error".equals(result.path("terminal_reason").asText(""))) {
            String why = result.path("result").asText("");
            throw new IllegalStateException("Claude Code: " + (why.isBlank()
                    ? result.path("terminal_reason").asText(result.path("subtype").asText("okänt fel")) : why));
        }
        SubscriptionLimit.ok();
        List<ClaudeResponse.ContentBlock> content = new ArrayList<>();
        boolean tools = false;
        JsonNode structured = result.path("structured_output");
        if (withTools && structured.isObject()) {
            String text = structured.path("reply").asText(structured.path("text").asText(""));
            text = stripEcho(text, lastUserText);
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

    /** Claude Codes rader om abonnemangets gräns (status, hur mycket som gått åt, när den släpper). */
    private void rateLimitEvents(String stdout) {
        for (String l : stdout.split("\n")) {
            if (!l.contains("rate_limit_event") && !l.contains("rate_limit_info")) {
                continue;
            }
            try {
                SubscriptionLimit.update(mapper.readTree(l.trim()));
            } catch (IOException | RuntimeException e) {
                log.debug("Kunde inte läsa gränsraden från Claude Code: {}", e.toString());
            }
        }
    }

    /** Resultatraden ({"type":"result",...}) i stream-json-utdata; annars sista JSON-raden. */
    private JsonNode lastJson(String stdout) {
        String[] lines = stdout.trim().split("\n");
        JsonNode lastObject = null;
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (l.startsWith("{")) {
                try {
                    JsonNode n = mapper.readTree(l);
                    if ("result".equals(n.path("type").asText())) {
                        return n;
                    }
                    if (lastObject == null) {
                        lastObject = n;
                    }
                } catch (IOException e) {
                    log.debug("Kunde inte läsa rad från Claude Code: {}", e.toString());
                }
            }
        }
        if (lastObject != null) {
            return lastObject;
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
