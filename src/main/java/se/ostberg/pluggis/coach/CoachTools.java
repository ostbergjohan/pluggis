package se.ostberg.pluggis.coach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import se.ostberg.pluggis.flashcards.Card;
import se.ostberg.pluggis.flashcards.FlashcardGenerator;
import se.ostberg.pluggis.flashcards.FlashcardService;
import se.ostberg.pluggis.homework.Homework;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.planning.PlanItem;
import se.ostberg.pluggis.planning.PlanningService;
import se.ostberg.pluggis.rewards.RewardsService;
import se.ostberg.pluggis.session.StudySession;
import se.ostberg.pluggis.session.StudySessionService;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Verktygen coachen (Claude) får anropa, och hur de körs. */
@Component
public class CoachTools {

    private final ObjectMapper mapper;
    private final StudySessionService sessions;
    private final HomeworkService homework;
    private final FlashcardService flashcards;
    private final FlashcardGenerator generator;
    private final RewardsService rewards;
    private final PlanningService planning;
    private final ArrayNode definitions;

    /** För open_practice: öppna övningen på elevens skärm (chatten/Tab5). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private se.ostberg.pluggis.device.DeviceRegistry registry;

    public CoachTools(ObjectMapper mapper, StudySessionService sessions, HomeworkService homework,
                      FlashcardService flashcards, FlashcardGenerator generator, RewardsService rewards,
                      PlanningService planning) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.homework = homework;
        this.flashcards = flashcards;
        this.generator = generator;
        this.rewards = rewards;
        this.planning = planning;
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
                        .<Object>map(s -> Map.of("ok", true, "session_id", s.getId(),
                                "points", rewards.summaryForPrompt(deviceId)))
                        .orElse(Map.of("ok", false, "error", "Inget pass pågår"));
                case "add_homework" -> {
                    Homework h = homework.add(text(input, "subject", null), text(input, "title", "Läxa"),
                            text(input, "description", null), date(input.path("due_date").asText(null)),
                            strings(input.path("steps")), Homework.Source.COACH);
                    yield Map.of("ok", true, "id", h.getId());
                }
                case "list_homework" -> Map.of("homework", homework.summaryForPrompt());
                case "complete_homework" -> homework.get(input.path("id").asLong()).filter(h -> !h.isOther())
                        .flatMap(h -> homework.complete(h.getId()))
                        .<Object>map(h -> {
                            rewards.onHomeworkDone(deviceId, h.getId(), h.getTitle());
                            return Map.of("ok", true, "id", h.getId(), "points", rewards.summaryForPrompt(deviceId));
                        })
                        .orElse(Map.of("ok", false, "error", "Hittade ingen läxa med det id:t"));
                case "list_decks" -> Map.of("decks", flashcards.summaryForPrompt());
                case "create_flashcards" -> {
                    FlashcardGenerator.Result r = generator.fromText(text(input, "topic", null),
                            text(input, "source_text", null), text(input, "subject", null),
                            input.path("count").asInt(12),
                            input.hasNonNull("homework_id") ? input.path("homework_id").asLong() : null);
                    if (input.hasNonNull("homework_id")) {
                        long hid = input.path("homework_id").asLong();
                        homework.linkDeck(hid, r.deck().getId());
                        planning.replanIfPlanned(hid);
                    }
                    yield Map.of("ok", true, "deck_id", r.deck().getId(), "deck_name", r.deck().getName(),
                            "cards", r.cardCount());
                }
                case "set_homework_content" -> {
                    long hid = input.path("id").asLong();
                    Homework.Kind kind = input.hasNonNull("kind")
                            ? ("test".equalsIgnoreCase(input.path("kind").asText()) ? Homework.Kind.TEST : Homework.Kind.HOMEWORK)
                            : null;
                    List<String> steps = input.has("steps") ? strings(input.path("steps")) : null;
                    if (homework.updateContent(hid, text(input, "content", null), steps, kind,
                            date(input.path("due_date").asText(null))).isEmpty()) {
                        yield Map.of("ok", false, "error", "Hittade ingen läxa med det id:t");
                    }
                    yield planForModel(planning.plan(hid));
                }
                case "plan_homework" -> planForModel(planning.plan(input.path("id").asLong()));
                case "todays_plan" -> Map.of("today", planning.summaryForPrompt());
                case "complete_plan_item" -> planning.complete(input.path("id").asLong(), deviceId)
                        .<Object>map(i -> Map.of("ok", true, "id", i.getId(),
                                "points", rewards.summaryForPrompt(deviceId)))
                        .orElse(Map.of("ok", false, "error", "Hittade inget moment med det id:t"));
                case "create_quiz" -> {
                    FlashcardGenerator.Result r = generator.quiz(text(input, "mode", "mix"), text(input, "topic", null),
                            text(input, "source_text", null), text(input, "subject", null),
                            input.path("count").asInt(8), input.hasNonNull("homework_id") ? input.path("homework_id").asLong() : null);
                    yield Map.of("ok", true, "deck_id", r.deck().getId(), "name", r.deck().getName(), "cards", r.cardCount());
                }
                case "open_practice" -> {
                    String kind = text(input, "kind", "quiz").toLowerCase(java.util.Locale.ROOT);
                    if (!java.util.List.of("quiz", "cloze", "explain", "exam", "match", "order").contains(kind)) {
                        kind = "quiz";
                    }
                    Long hid = input.hasNonNull("homework_id") ? input.path("homework_id").asLong() : null;
                    if (hid != null && homework.get(hid).isEmpty()) {
                        yield Map.of("ok", false, "error", "Hittade ingen läxa med det id:t");
                    }
                    if (registry == null) {
                        yield Map.of("ok", false, "error", "Kan inte öppna övningen just nu");
                    }
                    java.util.Map<String, Object> msg = new java.util.LinkedHashMap<>();
                    msg.put("type", "open_practice");
                    msg.put("kind", kind);
                    msg.put("homeworkId", hid);
                    registry.send(deviceId, msg);
                    yield Map.of("ok", true, "opened", kind,
                            "say", "Övningen öppnas nu i fliken Öva och körs där – säg kort att den startar, förhör inte i chatten.");
                }
                case "next_card" -> flashcards.next(input.path("deck_id").asLong(),
                                input.path("practice_anyway").asBoolean(false), deviceId)
                        .<Object>map(CoachTools::cardForModel)
                        .orElse(Map.of("ok", false, "done_for_now", true,
                                "message", "Inga kort att repetera just nu i den leken"));
                case "grade_card" -> flashcards.grade(input.path("card_id").asLong(),
                                input.path("correct").asBoolean(false), deviceId)
                        .<Object>map(c -> Map.of("ok", true, "box", c.getBox(),
                                "points", rewards.summaryForPrompt(deviceId)))
                        .orElse(Map.of("ok", false, "error", "Hittade inget kort med det id:t"));
                case "get_points" -> Map.of("points", rewards.summaryForPrompt(deviceId));
                default -> Map.of("ok", false, "error", "Okänt verktyg: " + name);
            };
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private static Map<String, Object> planForModel(PlanningService.PlanResult r) {
        List<String> lines = new ArrayList<>();
        for (PlanItem i : r.items()) {
            lines.add(i.getPlanDate() + ": " + i.getTask() + " (" + i.getMinutes() + " min) [moment " + i.getId() + "]");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("homework", r.homework().getTitle());
        m.put("plan", lines);
        m.put("note", r.note());
        return m;
    }

    private static Map<String, Object> cardForModel(Card c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("card_id", c.getId());
        m.put("front", c.getFront());
        m.put("hint", c.getHint());
        m.put("answer_secret", c.getBack());
        m.put("box", c.getBox());
        if (!"BASIC".equals(c.getKind())) {
            java.util.List<String> alts = new java.util.ArrayList<>(c.getWrongOptions());
            alts.add(c.getBack());
            java.util.Collections.shuffle(alts);
            m.put("type", c.getKind().equals("CLOZE") ? "lucktext" : "flerval");
            m.put("alternatives", alts);
        }
        return m;
    }

    private ArrayNode buildDefinitions() {
        ArrayNode tools = mapper.createArrayNode();

        ObjectNode start = tool(tools, "start_session",
                "Startar ett läxpass med timer på enheten. Använd när eleven ska börja jobba.");
        prop(start, "task", "string", "Vad passet gäller, kort, t.ex. 'Matte 4.12–4.18'");
        prop(start, "minutes", "integer", "Passets längd i minuter, normalt 10–15 (max 60)");
        required(start, "task", "minutes");

        ObjectNode end = tool(tools, "end_session", "Avslutar pågående läxpass. Ger poäng för tiden.");
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

        ObjectNode done = tool(tools, "complete_homework", "Markerar en läxa som klar. Ger poäng.");
        prop(done, "id", "integer", "Läxans id");
        required(done, "id");

        tool(tools, "list_decks", "Listar kortlekar med hur många kort som ska repeteras nu.");

        ObjectNode create = tool(tools, "create_flashcards",
                "Skapar en ny kortlek med flashkort om ett område, t.ex. inför ett prov eller glosförhör.");
        prop(create, "topic", "string", "Område, t.ex. 'Engelska glosor kapitel 3' eller 'Fotosyntesen'");
        prop(create, "subject", "string", "Ämne");
        prop(create, "count", "integer", "Antal kort, normalt 10–15");
        prop(create, "source_text", "string", "Material att utgå från om eleven gett det, t.ex. ordlistan");
        prop(create, "homework_id", "integer", "Läxa/prov som leken hör till, om någon");
        required(create, "topic");

        ObjectNode quiz = tool(tools, "create_quiz",
                "Gör ett snabbquiz (flerval) eller lucktext till en läxa/ett prov, som en kortlek. "
                        + "Förhör sedan med next_card som vanligt.");
        prop(quiz, "homework_id", "integer", "Läxans/provets id, om det gäller en");
        prop(quiz, "mode", "string", "quiz (flerval), cloze (lucktext) eller mix");
        prop(quiz, "topic", "string", "Område, t.ex. 'Fotosyntesen'");
        prop(quiz, "source_text", "string", "Elevens eget material, om du har det");
        prop(quiz, "count", "integer", "Antal frågor, 5–15");

        ObjectNode open = tool(tools, "open_practice",
                "Öppnar en övning i fliken Öva på elevens skärm och startar den där (inte i chatten). Använd när eleven "
                        + "vill göra ett quiz eller en övning – fråga först vilken sort om eleven inte sagt det.");
        prop(open, "kind", "string", "quiz (snabbquiz, flerval), cloze (lucktext), explain (Förklara för Pluggis), "
                + "exam (testprov), match (Matcha par) eller order (Rätt ordning)");
        prop(open, "homework_id", "integer", "Läxans/provets id");
        required(open, "kind");

        ObjectNode next = tool(tools, "next_card",
                "Hämtar nästa kort att förhöra och visar framsidan på skärmen. "
                        + "Läs upp framsidan. Säg ALDRIG answer_secret innan eleven svarat. "
                        + "Har kortet alternatives (flerval/lucktext): läs frågan och alternativen med bokstäver A–D.");
        prop(next, "deck_id", "integer", "Kortlekens id");
        prop(next, "practice_anyway", "boolean", "true = öva även om inga kort är förfallna");
        required(next, "deck_id");

        ObjectNode grade = tool(tools, "grade_card",
                "Rättar elevens svar på ett kort. Var generös med stavning, synonymer och ordföljd.");
        prop(grade, "card_id", "integer", "Kortets id");
        prop(grade, "correct", "boolean", "true om svaret i allt väsentligt var rätt");
        required(grade, "card_id", "correct");

        tool(tools, "get_points", "Hämtar elevens poäng och streak den här veckan.");

        ObjectNode content = tool(tools, "set_homework_content",
                "Sparar vad som ingår i en läxa eller ett prov (material, sidor, kapitel, begrepp) och "
                        + "gör en dag-för-dag-plan fram till datumet.");
        prop(content, "id", "integer", "Läxans id");
        prop(content, "content", "string", "Vad som ingår, så exakt som möjligt, t.ex. 'Utkik kap 4 s. 40–72 + begreppslistan'");
        ObjectNode csteps = props(content).putObject("steps");
        csteps.put("type", "array");
        csteps.putObject("items").put("type", "string");
        csteps.put("description", "Avsnitt/delar om eleven räknat upp dem");
        prop(content, "kind", "string", "'test' för prov/förhör, 'homework' för läxa/inlämning");
        prop(content, "due_date", "string", "Datum YYYY-MM-DD om det ändrats eller saknas");
        required(content, "id", "content");

        ObjectNode replan = tool(tools, "plan_homework", "Gör om planen för en läxa/ett prov, t.ex. om en dag missats.");
        prop(replan, "id", "integer", "Läxans id");
        required(replan, "id");

        tool(tools, "todays_plan", "Hämtar dagens planerade moment (och det som ligger efter).");

        ObjectNode doneItem = tool(tools, "complete_plan_item", "Bockar av ett moment i planen. Ger poäng.");
        prop(doneItem, "id", "integer", "Momentets id");
        required(doneItem, "id");

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
