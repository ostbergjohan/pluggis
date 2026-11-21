package se.ostberg.laxcoach.coach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import se.ostberg.laxcoach.homework.Homework;
import se.ostberg.laxcoach.homework.HomeworkService;
import se.ostberg.laxcoach.session.StudySession;
import se.ostberg.laxcoach.session.StudySessionService;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Verktygen coachen (Claude) får anropa, och hur de körs. */
@Component
public class CoachTools {

    private final ObjectMapper mapper;
    private final StudySessionService sessions;
    private final HomeworkService homework;
    private final ArrayNode definitions;

    public CoachTools(ObjectMapper mapper, StudySessionService sessions, HomeworkService homework) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.homework = homework;
        this.definitions = buildDefinitions();
    }

    public ArrayNode definitions() {
        return definitions.deepCopy();
    }

    /** Kör ett verktyg och returnerar resultatet som JSON-text till modellen. */
    public String execute(String deviceId, String name, JsonNode input) {
        try {
            Object result = switch (name) {
                case "start_session" -> {
                    StudySession s = sessions.start(deviceId,
                            text(input, "task", "Läxor"), input.path("minutes").asInt(15));
                    yield Map.of("ok", true, "session_id", s.getId(), "minutes", s.getPlannedMinutes());
                }
                case "end_session" -> sessions.finish(deviceId, text(input, "summary", null))
                        .<Object>map(s -> Map.of("ok", true, "session_id", s.getId()))
                        .orElse(Map.of("ok", false, "error", "Inget pass pågår"));
                case "add_homework" -> {
                    Homework h = homework.add(text(input, "subject", null), text(input, "title", "Läxa"),
                            text(input, "description", null), date(input.path("due_date").asText(null)),
                            strings(input.path("steps")), Homework.Source.COACH);
                    yield Map.of("ok", true, "id", h.getId());
                }
                case "list_homework" -> Map.of("homework", homework.summaryForPrompt());
                case "complete_homework" -> homework.complete(input.path("id").asLong())
                        .<Object>map(h -> Map.of("ok", true, "id", h.getId()))
                        .orElse(Map.of("ok", false, "error", "Hittade ingen läxa med det id:t"));
                default -> Map.of("ok", false, "error", "Okänt verktyg: " + name);
            };
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private ArrayNode buildDefinitions() {
        ArrayNode tools = mapper.createArrayNode();

        ObjectNode start = tool(tools, "start_session",
                "Startar ett läxpass med timer på enheten. Använd när eleven ska börja jobba.");
        prop(start, "task", "string", "Vad passet gäller, kort, t.ex. 'Matte 4.12–4.18'");
        prop(start, "minutes", "integer", "Passets längd i minuter, normalt 10–15 (max 60)");
        required(start, "task", "minutes");

        ObjectNode end = tool(tools, "end_session", "Avslutar pågående läxpass.");
        prop(end, "summary", "string", "En mening om vad som gjordes");

        ObjectNode add = tool(tools, "add_homework", "Sparar en ny läxa, inlämning eller ett prov.");
        prop(add, "subject", "string", "Ämne");
        prop(add, "title", "string", "Kort titel");
        prop(add, "description", "string", "Vad som ska göras");
        prop(add, "due_date", "string", "Datum YYYY-MM-DD om känt");
        ObjectNode steps = props(add).putObject("steps");
        steps.put("type", "array");
        steps.putObject("items").put("type", "string");
        steps.put("description", "Små delsteg");
        required(add, "title");

        tool(tools, "list_homework", "Hämtar öppna läxor.");

        ObjectNode done = tool(tools, "complete_homework", "Markerar en läxa som klar.");
        prop(done, "id", "integer", "Läxans id");
        required(done, "id");

        return tools;
    }

    private ObjectNode tool(ArrayNode tools, String name, String description) {
        ObjectNode t = tools.addObject();
        t.put("name", name);
        t.put("description", description);
        ObjectNode schema = t.putObject("input_schema");
        schema.put("type", "object");
        schema.putObject("properties");
        return t;
    }

    private static ObjectNode props(ObjectNode tool) {
        return (ObjectNode) tool.get("input_schema").get("properties");
    }

    private static void prop(ObjectNode tool, String name, String type, String description) {
        ObjectNode p = props(tool).putObject(name);
        p.put("type", type);
        p.put("description", description);
    }

    private static void required(ObjectNode tool, String... names) {
        ArrayNode req = ((ObjectNode) tool.get("input_schema")).putArray("required");
        for (String n : names) {
            req.add(n);
        }
    }

    private static String text(JsonNode input, String field, String fallback) {
        JsonNode v = input.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : fallback;
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) {
            arr.forEach(n -> {
                if (n.isTextual() && !n.asText().isBlank()) {
                    out.add(n.asText());
                }
            });
        }
        return out;
    }
}
