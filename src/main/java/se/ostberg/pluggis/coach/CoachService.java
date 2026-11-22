package se.ostberg.pluggis.coach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.ostberg.pluggis.claude.ClaudeClient;
import se.ostberg.pluggis.claude.ClaudeResponse;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.flashcards.FlashcardService;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.planning.PlanningService;
import se.ostberg.pluggis.rewards.RewardsService;
import se.ostberg.pluggis.session.StudySessionService;
import se.ostberg.pluggis.support.Prompts;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Coachen: tar elevens (transkriberade) yttrande, kör Claude med verktyg i en loop
 * och returnerar det som ska sägas.
 *
 * Konversationer hålls i minnet per enhet. Pass och läxor ligger i databasen och
 * skickas med i systemprompten varje gång, så coachen "minns" dem även efter omstart.
 * Avsiktligt loggas inget av elevens innehåll på INFO-nivå.
 */
@Service
public class CoachService {

    static final int MAX_TOOL_ROUNDS = 5;
    static final String FALLBACK = "Oj, nu tappade jag tråden. Kan du säga det igen?";

    private static final Logger log = LoggerFactory.getLogger(CoachService.class);
    private static final DateTimeFormatter NOW_FORMAT =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'kl.' HH:mm", Locale.forLanguageTag("sv-SE"));

    private final ClaudeClient claude;
    private final CoachTools tools;
    private final Prompts prompts;
    private final StudySessionService sessions;
    private final HomeworkService homework;
    private final FlashcardService flashcards;
    private final RewardsService rewards;
    private final PlanningService planning;
    private final PluggisProperties props;
    private final ObjectMapper mapper;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    private final se.ostberg.pluggis.settings.StudentProfile student;

    public CoachService(ClaudeClient claude, CoachTools tools, Prompts prompts, StudySessionService sessions,
                        HomeworkService homework, FlashcardService flashcards, RewardsService rewards,
                        PlanningService planning, PluggisProperties props, ObjectMapper mapper,
                        se.ostberg.pluggis.settings.StudentProfile student) {
        this.student = student;
        this.claude = claude;
        this.tools = tools;
        this.prompts = prompts;
        this.sessions = sessions;
        this.homework = homework;
        this.flashcards = flashcards;
        this.rewards = rewards;
        this.planning = planning;
        this.props = props;
        this.mapper = mapper;
    }

    public String chat(String deviceId, String userText) {
        return chat(deviceId, userText, null);
    }

    /**
     * Som {@link #chat(String, String)}, men strömmar svaret och anropar {@code onSentence}
     * för varje hel mening så fort den är klar – för uppläsning mening för mening.
     * Returnerar hela texten (alla rundor) för visning.
     */
    public String chat(String deviceId, String userText, Consumer<String> onSentence) {
        Conversation conv = conversations.computeIfAbsent(deviceId, id -> new Conversation(
                mapper, props.maxHistoryMessages(), Duration.ofMinutes(Math.max(1, props.conversationIdleMinutes()))));

        synchronized (conv) {
            conv.resetIfIdle(Instant.now());
            conv.addUserText(userText);
            StringBuilder spoken = new StringBuilder();
            try {
                for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                    ObjectNode req = claude.newRequest(props.anthropic().coach(),
                            prompts.coach() + student.promptSection(), statePrompt(deviceId));
                    req.set("tools", tools.definitions());
                    req.set("messages", conv.toJson());

                    ClaudeResponse resp;
                    if (onSentence == null) {
                        resp = claude.create(req, "coach");
                    } else {
                        SentenceSplitter splitter = new SentenceSplitter(onSentence);
                        resp = claude.stream(req, "coach", splitter::accept);
                        splitter.flush();
                    }
                    conv.addAssistant(mapper.valueToTree(resp.content()));
                    String text = resp.text();
                    if (!text.isBlank()) {
                        spoken.append(spoken.isEmpty() ? "" : " ").append(text);
                    }

                    if (!resp.wantsTools()) {
                        return spoken.isEmpty() ? fallback(onSentence) : spoken.toString();
                    }

                    ArrayNode results = mapper.createArrayNode();
                    for (ClaudeResponse.ContentBlock use : resp.toolUses()) {
                        log.debug("Verktyg {} för {}", use.name(), deviceId);
                        ObjectNode result = results.addObject();
                        result.put("type", "tool_result");
                        result.put("tool_use_id", use.id());
                        result.put("content", tools.execute(deviceId, use.name(),
                                use.input() == null ? mapper.createObjectNode() : use.input()));
                    }
                    conv.addToolResults(results);
                }
                log.warn("Coachen nådde max antal verktygsrundor för {}", deviceId);
            } catch (RuntimeException e) {
                log.error("Fel i samtal med Claude för {}: {}", deviceId, e.toString());
                // Börja om hellre än att riskera en trasig historik (t.ex. tool_use utan tool_result).
                conv.clear();
            }
            return spoken.isEmpty() ? fallback(onSentence) : spoken.toString();
        }
    }

    private static String fallback(Consumer<String> onSentence) {
        if (onSentence != null) {
            onSentence.accept(FALLBACK);
        }
        return FALLBACK;
    }

    public void forget(String deviceId) {
        conversations.remove(deviceId);
    }

    /** Läget just nu – ändras varje anrop och ligger därför efter cache-markeringen. */
    String statePrompt(String deviceId) {
        return prompts.coachState()
                .replace("{{now}}", NOW_FORMAT.format(ZonedDateTime.now(props.zone())))
                .replace("{{session}}", sessions.describeForPrompt(deviceId))
                .replace("{{homework}}", homework.summaryForPrompt())
                .replace("{{decks}}", flashcards.summaryForPrompt())
                .replace("{{points}}", rewards.summaryForPrompt(deviceId))
                .replace("{{plan}}", planning.summaryForPrompt());
    }
}
