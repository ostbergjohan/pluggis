package se.ostberg.pluggis.exam;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.ostberg.pluggis.claude.ClaudeClient;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.flashcards.CardCheck;
import se.ostberg.pluggis.flashcards.Deck;
import se.ostberg.pluggis.flashcards.FlashcardService;
import se.ostberg.pluggis.homework.Homework;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.homework.MaterialService;
import se.ostberg.pluggis.rewards.RewardsService;
import se.ostberg.pluggis.student.StudentContext;
import se.ostberg.pluggis.support.JsonExtract;
import se.ostberg.pluggis.support.Prompts;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Testprov: Claude gör ett övningsprov av provets material (flerval, lucktext och skrivfrågor). Eleven svarar på
 * allt och lämnar in; flerval och lucktext rättas här, skrivfrågorna av Claude (0–2 poäng). Missade frågor blir
 * en kortlek för repetition. Pågående prov hålls i minnet.
 */
@Service
public class MockExamService {

    private static final Logger log = LoggerFactory.getLogger(MockExamService.class);
    private static final Duration TTL = Duration.ofHours(4);

    private final ClaudeClient claude;
    private final Prompts prompts;
    private final PluggisProperties props;
    private final HomeworkService homework;
    private final MaterialService materials;
    private final FlashcardService flashcards;
    private final RewardsService rewards;
    private final ObjectMapper mapper;
    private final Map<String, Exam> exams = new ConcurrentHashMap<>();

    public MockExamService(ClaudeClient claude, Prompts prompts, PluggisProperties props, HomeworkService homework,
                           MaterialService materials, FlashcardService flashcards, RewardsService rewards, ObjectMapper mapper) {
        this.claude = claude;
        this.prompts = prompts;
        this.props = props;
        this.homework = homework;
        this.materials = materials;
        this.flashcards = flashcards;
        this.rewards = rewards;
        this.mapper = mapper;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GenQuestion(String type, String question, String answer, List<String> options,
                       @JsonProperty("key_points") List<String> keyPoints, String topic) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Generated(String title, List<GenQuestion> questions, String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Grade(Integer n, Integer points, String feedback) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Grades(List<Grade> grades) {
    }

    /** En fråga i ett pågående prov. {@code choices} = alternativen i den ordning eleven ser dem. */
    record Q(int n, String type, String question, String answer, List<String> wrong, List<String> choices,
             List<String> keyPoints, String topic) {
        int max() {
            return "open".equals(type) ? 2 : 1;
        }
    }

    static final class Exam {
        final String id = UUID.randomUUID().toString();
        final Long student;
        final Long homeworkId;
        final String title;
        final List<Q> questions;
        final Instant started = Instant.now();
        Result result;

        Exam(Long student, Long homeworkId, String title, List<Q> questions) {
            this.student = student;
            this.homeworkId = homeworkId;
            this.title = title;
            this.questions = questions;
        }
    }

    /** Det eleven ser: inga svar. */
    public record PublicQuestion(int n, String type, String question, List<String> choices) {
    }

    public record Started(String id, String title, List<PublicQuestion> questions, int maxPoints, String note) {
    }

    public record ItemResult(int n, String type, String question, String yourAnswer, String answer, int points, int max,
                             String feedback, String topic) {
    }

    public record Result(String id, String title, int points, int maxPoints, int percent, List<ItemResult> items,
                         List<String> review, Long reviewDeckId) {
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private se.ostberg.pluggis.support.Variety variety;

    public Started start(Long homeworkId, int count) {
        return start(homeworkId, count, null);
    }

    public Started start(Long homeworkId, int count, String focus) {
        prune();
        Homework h = homeworkId == null ? null
                : homework.get(homeworkId).orElseThrow(() -> new IllegalArgumentException("Hittar inte provet"));
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("step", "create");
        if (variety != null) {
            task.put("variation", variety.focusHint());
        }
        if (focus != null && !focus.isBlank()) {
            task.put("focus", focus.trim());
        }
        task.put("count", Math.max(5, Math.min(15, count <= 0 ? 10 : count)));
        if (h != null) {
            task.put("title", h.getTitle());
            task.put("subject", h.getSubject());
            task.put("content", h.getDescription());
            task.put("steps", h.getSteps());
            String mat = materials.allText(h.getId());
            task.put("material", mat.length() > 20000 ? mat.substring(0, 20000) : mat);
        }
        Generated g = ask(task, Generated.class, "exam");
        List<Q> qs = clean(g);
        if (qs.size() < 3) {
            throw new IllegalArgumentException(g.note() != null ? g.note() : "Det gick inte att göra ett testprov av materialet");
        }
        String title = g.title() == null || g.title().isBlank() ? "Testprov" + (h == null ? "" : ": " + h.getTitle()) : g.title().trim();
        Exam e = new Exam(StudentContext.currentOrDefault(), homeworkId, title, qs);
        exams.put(e.id, e);
        int max = qs.stream().mapToInt(Q::max).sum();
        return new Started(e.id, title, qs.stream().map(q -> new PublicQuestion(q.n(), q.type(), q.question(), q.choices())).toList(),
                max, g.note());
    }

    /** Sorterar bort frågor som inte går att använda och numrerar resten 1, 2, 3 … */
    static List<Q> clean(Generated g) {
        List<Q> out = new ArrayList<>();
        if (g == null || g.questions() == null) {
            return out;
        }
        for (GenQuestion q : g.questions()) {
            if (q == null || blank(q.question()) || blank(q.answer())) {
                continue;
            }
            String type = q.type() == null ? "choice" : q.type().trim().toLowerCase(Locale.ROOT);
            String question = q.question().trim().replaceAll("_{2,}|…+", "___");
            String answer = q.answer().trim();
            if (!List.of("choice", "cloze", "open").contains(type)) {
                type = "choice";
            }
            if (!type.equals("open") && CardCheck.leaks(question.replace("___", " "), answer)) {
                continue;
            }
            if (type.equals("cloze") && (!question.contains("___") || question.indexOf("___") != question.lastIndexOf("___"))) {
                continue;
            }
            List<String> wrong = new ArrayList<>();
            List<String> choices = null;
            if (!type.equals("open")) {
                LinkedHashSet<String> w = new LinkedHashSet<>();
                if (q.options() != null) {
                    q.options().stream().filter(o -> !blank(o) && !norm(o).equals(norm(answer))).map(String::trim).forEach(w::add);
                }
                if (w.size() < 2) {
                    continue;
                }
                wrong = new ArrayList<>(w).subList(0, Math.min(3, w.size()));
                choices = new ArrayList<>(wrong);
                choices.add(answer);
                Collections.shuffle(choices);
            }
            List<String> keys = q.keyPoints() == null ? List.of() : q.keyPoints().stream().filter(k -> !blank(k)).toList();
            out.add(new Q(out.size() + 1, type, question, answer, wrong, choices, keys, blank(q.topic()) ? null : q.topic().trim()));
        }
        return out;
    }

    public Optional<Result> submit(String id, Map<Integer, String> answers, String deviceId) {
        Exam e = exams.get(id);
        if (e == null || !java.util.Objects.equals(e.student, StudentContext.currentOrDefault())) {
            return Optional.empty();
        }
        if (e.result != null) {
            return Optional.of(e.result);
        }
        Map<Integer, Grade> openGrades = gradeOpen(e, answers);
        List<ItemResult> items = new ArrayList<>();
        int points = 0;
        for (Q q : e.questions) {
            String yours = answers.getOrDefault(q.n(), "");
            int p;
            String feedback = null;
            if (q.type().equals("open")) {
                Grade g = openGrades.get(q.n());
                p = blank(yours) ? 0 : g == null || g.points() == null ? 0 : Math.max(0, Math.min(2, g.points()));
                feedback = blank(yours) ? "Inget svar." : g == null ? "Kunde inte rättas automatiskt – jämför med modellsvaret." : g.feedback();
            } else {
                p = norm(yours).equals(norm(q.answer())) ? 1 : 0;
            }
            points += p;
            items.add(new ItemResult(q.n(), q.type(), q.question(), yours, q.answer(), p, q.max(), feedback, q.topic()));
        }
        int max = e.questions.stream().mapToInt(Q::max).sum();
        List<ItemResult> missed = items.stream().filter(i -> i.points() < i.max()).toList();
        List<String> review = missed.stream().map(i -> i.topic() == null ? i.question() : i.topic()).distinct().toList();
        Long deckId = missed.isEmpty() ? null : reviewDeck(e, missed);
        e.result = new Result(e.id, e.title, points, max, max == 0 ? 0 : Math.round(100f * points / max), items, review, deckId);
        rewards.onMockExam(deviceId, e.homeworkId, e.title);
        return Optional.of(e.result);
    }

    /** Skrivfrågorna rättas i ett enda anrop. Går det inte får de 0 poäng och modellsvaret visas. */
    private Map<Integer, Grade> gradeOpen(Exam e, Map<Integer, String> answers) {
        List<Map<String, Object>> open = new ArrayList<>();
        for (Q q : e.questions) {
            String a = answers.getOrDefault(q.n(), "");
            if (q.type().equals("open") && !blank(a)) {
                open.add(Map.of("n", q.n(), "question", q.question(), "model_answer", q.answer(),
                        "key_points", q.keyPoints(), "student_answer", a));
            }
        }
        Map<Integer, Grade> out = new java.util.HashMap<>();
        if (open.isEmpty()) {
            return out;
        }
        try {
            Grades g = ask(Map.of("step", "grade", "questions", open), Grades.class, "exam-grade");
            if (g.grades() != null) {
                g.grades().stream().filter(x -> x != null && x.n() != null).forEach(x -> out.put(x.n(), x));
            }
        } catch (RuntimeException ex) {
            log.warn("Kunde inte rätta skrivfrågorna: {}", ex.toString());
        }
        return out;
    }

    /** Missade frågor blir en lek – samma repetition som flashkorten. */
    private Long reviewDeck(Exam e, List<ItemResult> missed) {
        Map<Integer, Q> byN = new java.util.HashMap<>();
        e.questions.forEach(q -> byN.put(q.n(), q));
        List<FlashcardService.NewCard> cards = new ArrayList<>();
        for (ItemResult i : missed) {
            Q q = byN.get(i.n());
            if (q.type().equals("open")) {
                cards.add(new FlashcardService.NewCard(q.question(), q.answer(), null));
            } else {
                cards.add(new FlashcardService.NewCard(q.question(), q.answer(), null,
                        q.type().equals("cloze") ? "CLOZE" : "CHOICE", q.wrong()));
            }
        }
        String subject = e.homeworkId == null ? null : homework.get(e.homeworkId).map(Homework::getSubject).orElse(null);
        try {
            return flashcards.createDeck("🔁 Repetera: " + e.title.replaceFirst("^Testprov:\\s*", ""), subject, e.homeworkId,
                    Deck.Source.GENERATED, cards).getId();
        } catch (RuntimeException ex) {
            log.warn("Kunde inte spara repetitionsleken: {}", ex.toString());
            return null;
        }
    }

    private <T> T ask(Map<String, Object> task, Class<T> type, String purpose) {
        try {
            ObjectNode req = claude.newRequest(props.anthropic().examOrFlashcards(), prompts.exam());
            ObjectNode msg = req.putArray("messages").addObject();
            msg.put("role", "user");
            msg.put("content", mapper.writeValueAsString(task));
            return JsonExtract.parse(claude.create(req, purpose).text(), mapper, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String norm(String s) {
        return s == null ? "" : Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFC)
                .replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private void prune() {
        Instant limit = Instant.now().minus(TTL);
        exams.values().removeIf(x -> x.started.isBefore(limit));
    }
}
