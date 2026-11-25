package se.ostberg.pluggis.explain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import se.ostberg.pluggis.claude.ClaudeClient;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.homework.MaterialService;
import se.ostberg.pluggis.rewards.RewardsService;
import se.ostberg.pluggis.student.StudentContext;
import se.ostberg.pluggis.support.JsonExtract;
import se.ostberg.pluggis.support.Prompts;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Förklara för Pluggis": eleven förklarar ett begrepp ur sitt material med egna ord, Pluggis ställer
 * högst två nyfikna följdfrågor och visar sedan vilka nyckelpunkter som kom med. Rundorna hålls i minnet.
 */
@Service
public class ExplainService {

    static final int MAX_FOLLOW_UPS = 2;
    private static final Duration TTL = Duration.ofHours(2);

    private final ClaudeClient claude;
    private final Prompts prompts;
    private final PluggisProperties props;
    private final HomeworkService homework;
    private final MaterialService materials;
    private final RewardsService rewards;
    private final ObjectMapper mapper;
    private final Map<String, Round> rounds = new ConcurrentHashMap<>();

    public ExplainService(ClaudeClient claude, Prompts prompts, PluggisProperties props, HomeworkService homework,
                          MaterialService materials, RewardsService rewards, ObjectMapper mapper) {
        this.claude = claude;
        this.prompts = prompts;
        this.props = props;
        this.homework = homework;
        this.materials = materials;
        this.rewards = rewards;
        this.mapper = mapper;
    }

    /** En pågående runda. {@code student} så att ingen annan elev kan fortsätta den. */
    static final class Round {
        final String id;
        final Long student;
        final Long homeworkId;
        final String concept;
        final String question;
        final List<String> keyPoints;
        final List<Map<String, String>> turns = new ArrayList<>();
        final java.util.Set<Integer> covered = new java.util.TreeSet<>();
        int followUps;
        boolean done;
        final Instant started = Instant.now();

        Round(Long student, Long homeworkId, String concept, String question, List<String> keyPoints) {
            this.id = UUID.randomUUID().toString();
            this.student = student;
            this.homeworkId = homeworkId;
            this.concept = concept;
            this.question = question;
            this.keyPoints = keyPoints;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Start(String concept, String question, @JsonProperty("key_points") List<String> keyPoints, String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Judged(List<Integer> covered, String reply, Boolean done) {
    }

    public record Turn(String id, String concept, String reply, boolean done, List<String> covered, List<String> missing,
                       String note) {
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private se.ostberg.pluggis.support.Variety variety;

    public Turn start(Long homeworkId) {
        prune();
        var h = homeworkId == null ? null : homework.get(homeworkId).orElseThrow(() -> new IllegalArgumentException("Hittar inte läxan"));
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("step", "start");
        if (h != null) {
            task.put("title", h.getTitle());
            task.put("subject", h.getSubject());
            task.put("content", h.getDescription());
            task.put("steps", h.getSteps());
            String mat = materials.allText(h.getId());
            task.put("material", mat.length() > 15000 ? mat.substring(0, 15000) : mat);
        }
        if (variety != null) {
            List<String> avoid = variety.recent("explain", homeworkId);
            if (!avoid.isEmpty()) {
                task.put("avoid", avoid);
            }
            task.put("variation", variety.focusHint());
        }
        Start s = ask(task, Start.class, "explain-start");
        List<String> points = s.keyPoints() == null ? List.of()
                : s.keyPoints().stream().filter(p -> p != null && !p.isBlank()).limit(5).toList();
        if (s.question() == null || s.question().isBlank() || points.isEmpty()) {
            throw new IllegalArgumentException(s.note() != null ? s.note() : "Pluggis hittade inget att fråga om");
        }
        Round r = new Round(StudentContext.currentOrDefault(), homeworkId,
                s.concept() == null ? "det här" : s.concept().trim(), s.question().trim(), points);
        r.turns.add(Map.of("role", "pluggis", "text", r.question));
        rounds.put(r.id, r);
        if (variety != null) {
            variety.remember("explain", homeworkId, List.of(r.concept));
        }
        return new Turn(r.id, r.concept, r.question, false, List.of(), List.of(), s.note());
    }

    public Optional<Turn> answer(String id, String text, String deviceId) {
        Round r = rounds.get(id);
        if (r == null || !java.util.Objects.equals(r.student, StudentContext.currentOrDefault())) {
            return Optional.empty();
        }
        if (r.done) {
            return Optional.of(summary(r, null));
        }
        r.turns.add(Map.of("role", "elev", "text", text == null ? "" : text.trim()));
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("step", "answer");
        task.put("question", r.question);
        task.put("key_points", indexed(r.keyPoints));
        task.put("follow_ups_asked", r.followUps);
        task.put("conversation", r.turns);
        Judged j = ask(task, Judged.class, "explain-answer");
        if (j.covered() != null) {
            j.covered().stream().filter(i -> i != null && i >= 0 && i < r.keyPoints.size()).forEach(r.covered::add);
        }
        boolean done = Boolean.TRUE.equals(j.done()) || r.followUps >= MAX_FOLLOW_UPS || r.covered.size() == r.keyPoints.size();
        String reply = j.reply() == null || j.reply().isBlank() ? (done ? "Tack, nu fattar jag mer!" : "Kan du berätta lite mer?") : j.reply().trim();
        if (!done) {
            r.followUps++;
        }
        r.turns.add(Map.of("role", "pluggis", "text", reply));
        if (done) {
            r.done = true;
            rewards.onExplained(deviceId, r.concept);
        }
        return Optional.of(done ? summary(r, reply) : new Turn(r.id, r.concept, reply, false, List.of(), List.of(), null));
    }

    private Turn summary(Round r, String reply) {
        List<String> covered = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < r.keyPoints.size(); i++) {
            (r.covered.contains(i) ? covered : missing).add(r.keyPoints.get(i));
        }
        return new Turn(r.id, r.concept, reply == null ? "Tack för förklaringen!" : reply, true, covered, missing, null);
    }

    private static List<Map<String, Object>> indexed(List<String> points) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            out.add(Map.of("index", i, "point", points.get(i)));
        }
        return out;
    }

    private <T> T ask(Map<String, Object> task, Class<T> type, String purpose) {
        try {
            ObjectNode req = claude.newRequest(props.anthropic().flashcards(), prompts.explain());
            ObjectNode msg = req.putArray("messages").addObject();
            msg.put("role", "user");
            msg.put("content", mapper.writeValueAsString(task));
            return JsonExtract.parse(claude.create(req, purpose).text(), mapper, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void prune() {
        Instant limit = Instant.now().minus(TTL);
        rounds.values().removeIf(r -> r.started.isBefore(limit));
    }
}
