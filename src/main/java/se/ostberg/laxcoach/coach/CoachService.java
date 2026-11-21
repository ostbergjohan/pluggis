package se.ostberg.laxcoach.coach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.ostberg.laxcoach.claude.ClaudeClient;
import se.ostberg.laxcoach.claude.ClaudeResponse;
import se.ostberg.laxcoach.config.LaxcoachProperties;
import se.ostberg.laxcoach.homework.HomeworkService;
import se.ostberg.laxcoach.session.StudySessionService;
import se.ostberg.laxcoach.support.Prompts;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final LaxcoachProperties props;
    private final ObjectMapper mapper;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    public CoachService(ClaudeClient claude, CoachTools tools, Prompts prompts, StudySessionService sessions,
                        HomeworkService homework, LaxcoachProperties props, ObjectMapper mapper) {
        this.claude = claude;
        this.tools = tools;
        this.prompts = prompts;
        this.sessions = sessions;
        this.homework = homework;
        this.props = props;
        this.mapper = mapper;
    }

    public String chat(String deviceId, String userText) {
        Conversation conv = conversations.computeIfAbsent(deviceId, id -> new Conversation(
                mapper, props.maxHistoryMessages(), Duration.ofMinutes(Math.max(1, props.conversationIdleMinutes()))));

        synchronized (conv) {
            conv.resetIfIdle(Instant.now());
            conv.addUserText(userText);
            try {
                for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                    ObjectNode req = claude.newRequest(
                            props.anthropic().model(), props.anthropic().maxTokens(), systemPrompt(deviceId));
                    req.set("tools", tools.definitions());
                    req.set("messages", conv.toJson());

                    ClaudeResponse resp = claude.create(req, "coach");
                    conv.addAssistant(mapper.valueToTree(resp.content()));

                    if (!resp.wantsTools()) {
                        String text = resp.text();
                        return text.isBlank() ? FALLBACK : text;
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
            return FALLBACK;
        }
    }

    public void forget(String deviceId) {
        conversations.remove(deviceId);
    }

    String systemPrompt(String deviceId) {
        return prompts.coach()
                .replace("{{now}}", NOW_FORMAT.format(ZonedDateTime.now(props.zone())))
                .replace("{{session}}", sessions.describeForPrompt(deviceId))
                .replace("{{homework}}", homework.summaryForPrompt());
    }
}
