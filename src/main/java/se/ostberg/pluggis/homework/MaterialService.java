package se.ostberg.pluggis.homework;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.ostberg.pluggis.claude.ClaudeClient;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.flashcards.FlashcardGenerator;
import se.ostberg.pluggis.planning.PlanningService;
import se.ostberg.pluggis.support.JsonExtract;
import se.ostberg.pluggis.support.Prompts;
import se.ostberg.pluggis.support.UploadedDocument;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Studiematerial till en befintlig läxa eller ett prov: Claude läser vad som ingår
 * (avsnitt, sidor, begrepp), läxan uppdateras, planen görs om och – om man vill – flashkort skapas.
 */
@Service
public class MaterialService {

    private static final Logger log = LoggerFactory.getLogger(MaterialService.class);

    public record AttachResult(Homework homework, Material material, List<String> sections,
                               boolean replanned, Long deckId, Integer cardCount, String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Reading(String summary, List<String> sections, @JsonProperty("key_terms") List<String> keyTerms,
                   String text, String kind, @JsonProperty("due_date") String dueDate, String note) {
    }

    private final MaterialRepository materials;
    private final HomeworkService homework;
    private final PlanningService planning;
    private final FlashcardGenerator flashcards;
    private final ClaudeClient claude;
    private final Prompts prompts;
    private final PluggisProperties props;
    private final ObjectMapper mapper;

    public MaterialService(MaterialRepository materials, HomeworkService homework, PlanningService planning,
                           FlashcardGenerator flashcards, ClaudeClient claude, Prompts prompts,
                           PluggisProperties props, ObjectMapper mapper) {
        this.materials = materials;
        this.homework = homework;
        this.planning = planning;
        this.flashcards = flashcards;
        this.claude = claude;
        this.prompts = prompts;
        this.props = props;
        this.mapper = mapper;
    }

    public AttachResult attach(long homeworkId, UploadedDocument doc, boolean makeFlashcards) {
        Homework h = homework.get(homeworkId)
                .orElseThrow(() -> new IllegalArgumentException("Hittade ingen läxa med id " + homeworkId));

        Reading reading;
        String note = null;
        try {
            reading = read(h, doc);
            note = reading.note();
        } catch (RuntimeException e) {
            // Word/text kan sparas ändå; bild/PDF kräver Claude.
            if (doc.text() == null) {
                throw new IllegalStateException("Claude kunde inte läsa materialet just nu – försök igen om en stund", e);
            }
            log.warn("Kunde inte läsa material med Claude för läxa {}: {}", homeworkId, e.toString());
            reading = new Reading(null, List.of(), List.of(), null, null, null, null);
            note = "Materialet sparades, men Claude kunde inte läsa av det just nu – planen ändrades inte.";
        }

        String text = doc.text() != null ? doc.text() : reading.text();
        Material saved = materials.save(new Material(homeworkId, doc.filename(), doc.kind().name(),
                reading.summary(), text));

        List<String> sections = reading.sections() == null ? List.of() : reading.sections();
        boolean changed = reading.summary() != null || !sections.isEmpty();
        if (changed) {
            String description = merge(h.getDescription(), reading.summary(), reading.keyTerms());
            List<String> steps = new ArrayList<>(new LinkedHashSet<>(concat(h.getSteps(), sections)));
            if (steps.size() > 12) {
                steps = steps.subList(0, 12);
            }
            Homework.Kind kind = h.getKind() == Homework.Kind.HOMEWORK && "test".equalsIgnoreCase(reading.kind())
                    ? Homework.Kind.TEST : null;
            LocalDate due = h.getDueDate() == null ? date(reading.dueDate()) : null;
            h = homework.updateContent(homeworkId, description, steps, kind, due).orElse(h);
        }

        Long deckId = null;
        Integer cards = null;
        if (makeFlashcards && text != null && !text.isBlank()) {
            try {
                var r = flashcards.fromText(h.getTitle(), text, h.getSubject(), 15, homeworkId);
                homework.linkDeck(homeworkId, r.deck().getId());
                deckId = r.deck().getId();
                cards = r.cardCount();
            } catch (RuntimeException e) {
                log.warn("Kunde inte göra flashkort av materialet: {}", e.toString());
                note = (note == null ? "" : note + " ") + "Flashkorten kunde inte skapas just nu.";
            }
        }

        boolean replanned = false;
        if ((changed || deckId != null) && h.getDueDate() != null) {
            try {
                planning.plan(homeworkId);
                replanned = true;
            } catch (RuntimeException e) {
                log.warn("Kunde inte planera om läxa {}: {}", homeworkId, e.toString());
            }
        }
        return new AttachResult(homework.get(homeworkId).orElse(h), saved, sections, replanned, deckId, cards, note);
    }

    @Transactional(readOnly = true)
    public List<Material> forHomework(long homeworkId) {
        return materials.findByHomeworkIdOrderByCreatedAtAsc(homeworkId);
    }

    @Transactional
    public boolean delete(long materialId) {
        if (!materials.existsById(materialId)) {
            return false;
        }
        materials.deleteById(materialId);
        return true;
    }

    @Transactional
    public void deleteForHomework(long homeworkId) {
        materials.deleteByHomeworkId(homeworkId);
    }

    /** All materialtext för en läxa – till flashkort och coachen. */
    @Transactional(readOnly = true)
    public String allText(long homeworkId) {
        StringBuilder sb = new StringBuilder();
        for (Material m : forHomework(homeworkId)) {
            if (m.getText() != null && !m.getText().isBlank()) {
                sb.append(m.getText()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    private Reading read(Homework h, UploadedDocument doc) {
        ObjectNode req = claude.newRequest(doc.kind() == UploadedDocument.Kind.IMAGE || doc.kind() == UploadedDocument.Kind.PDF
                        ? props.anthropic().vision() : props.anthropic().planning(),
                prompts.material(), "Dagens datum: " + LocalDate.now(props.zone()));
        ObjectNode msg = req.putArray("messages").addObject();
        msg.put("role", "user");
        ArrayNode content = msg.putArray("content");
        doc.addTo(content, mapper);
        content.addObject().put("type", "text").put("text",
                "Materialet hör till: " + (h.getKind() == Homework.Kind.TEST ? "PROV " : "LÄXA ") + h.getTitle()
                        + (h.getSubject() == null ? "" : " (" + h.getSubject() + ")")
                        + (h.getDescription() == null ? "" : ". Redan känt: " + h.getDescription()));
        return JsonExtract.parse(claude.create(req, "material").text(), mapper, Reading.class);
    }

    static String merge(String existing, String summary, List<String> keyTerms) {
        StringBuilder sb = new StringBuilder(existing == null ? "" : existing.trim());
        if (summary != null && !summary.isBlank() && !sb.toString().contains(summary.trim())) {
            sb.append(sb.isEmpty() ? "" : "\n").append(summary.trim());
        }
        if (keyTerms != null && !keyTerms.isEmpty()) {
            sb.append(sb.isEmpty() ? "" : "\n").append("Begrepp: ").append(String.join(", ", keyTerms));
        }
        String out = sb.toString().trim();
        return out.length() > 3900 ? out.substring(0, 3900) : out;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a == null ? List.of() : a);
        if (b != null) {
            b.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).forEach(out::add);
        }
        return out;
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
