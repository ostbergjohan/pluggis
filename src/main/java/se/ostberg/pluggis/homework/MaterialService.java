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
                   String text, String kind, @JsonProperty("due_date") String dueDate, String note, List<Page> pages,
                   List<String> topics) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Page(Integer file, String text, @JsonProperty("book_page") Integer bookPage,
                @JsonProperty("book_page_to") Integer bookPageTo, List<String> headings) {
    }

    /** Högst så många filer per uppladdning (en läsning hos Claude). */
    public static final int MAX_FILES = 10;

    private final MaterialRepository materials;
    private final HomeworkService homework;
    private final PlanningService planning;
    private final FlashcardGenerator flashcards;
    private final ClaudeClient claude;
    private final Prompts prompts;
    private final PluggisProperties props;
    private final ObjectMapper mapper;

    private final MaterialImageRepository images;

    public MaterialService(MaterialRepository materials, HomeworkService homework, PlanningService planning,
                           FlashcardGenerator flashcards, ClaudeClient claude, Prompts prompts,
                           PluggisProperties props, ObjectMapper mapper,
                           MaterialImageRepository images) {
        this.images = images;
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
        return attach(homeworkId, List.of(doc), makeFlashcards);
    }

    /** En eller flera filer (t.ex. flera boksidor) – läses av Claude tillsammans, en materialrad per fil. */
    public AttachResult attach(long homeworkId, List<UploadedDocument> docs, boolean makeFlashcards) {
        if (docs == null || docs.isEmpty()) {
            throw new IllegalArgumentException("Ingen fil");
        }
        if (docs.size() > MAX_FILES) {
            throw new IllegalArgumentException("Högst " + MAX_FILES + " filer åt gången");
        }
        Homework h = homework.get(homeworkId)
                .orElseThrow(() -> new IllegalArgumentException("Hittade ingen läxa med id " + homeworkId));

        Reading reading;
        String note = null;
        try {
            reading = read(h, docs);
            note = reading.note();
        } catch (RuntimeException e) {
            // Word/text kan sparas ändå; bild/PDF kräver Claude.
            if (docs.stream().allMatch(d -> d.text() == null)) {
                throw new IllegalStateException("Claude kunde inte läsa materialet just nu – försök igen om en stund", e);
            }
            log.warn("Kunde inte läsa material med Claude för läxa {}: {}", homeworkId, e.toString());
            reading = new Reading(null, List.of(), List.of(), null, null, null, null, List.of(), List.of());
            note = "Materialet sparades, men Claude kunde inte läsa av det just nu – planen ändrades inte.";
        }

        java.util.Map<Integer, String> pageText = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> bookPages = new java.util.HashMap<>();   // fil → tryckt sidnummer i boken
        java.util.Map<Integer, Integer> bookPagesTo = new java.util.HashMap<>(); // fil → högra sidan i ett uppslag
        java.util.Map<Integer, List<String>> headings = new java.util.HashMap<>(); // fil → sidans rubriker
        if (reading.pages() != null) {
            for (Page pg : reading.pages()) {
                if (pg != null && pg.file() != null && pg.headings() != null && !pg.headings().isEmpty()) {
                    headings.put(pg.file(), pg.headings());
                }
                if (pg != null && pg.file() != null && pg.text() != null && !pg.text().isBlank()) {
                    pageText.put(pg.file(), pg.text());
                }
                if (pg != null && pg.file() != null && pg.bookPage() != null) {
                    bookPages.put(pg.file(), pg.bookPage());
                    if (pg.bookPageTo() != null) {
                        bookPagesTo.put(pg.file(), pg.bookPageTo());
                    }
                }
            }
        }
        Material saved = null;
        StringBuilder allText = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            UploadedDocument doc = docs.get(i);
            String text = doc.text() != null ? doc.text()
                    : pageText.getOrDefault(i + 1, docs.size() == 1 ? reading.text() : null);
            Material m = new Material(homeworkId, doc.filename(), doc.kind().name(),
                    i == 0 ? reading.summary() : null, text);
            if (docs.size() > 1) {
                m.setPage(i + 1);
            }
            m.setBookPages(bookPages.get(i + 1), bookPagesTo.get(i + 1));
            m.setHeadings(headings.get(i + 1));
            boolean keepImage = doc.kind() == UploadedDocument.Kind.IMAGE && doc.bytes() != null
                    && doc.bytes().length <= 3_000_000;
            if (keepImage) {
                m.markImage(doc.mediaType());
            }
            Material s1 = materials.save(m);
            if (keepImage) {
                images.save(new MaterialImage(s1.getId(), doc.bytes()));
            }
            if (saved == null) {
                saved = s1;
            }
            if (text != null) {
                allText.append(text).append("\n\n");
            }
        }
        String text = allText.isEmpty() ? reading.text() : allText.toString().trim();

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
        if (reading.topics() != null && !reading.topics().isEmpty()) {
            // delområden att öva på: de gamla först, sedan nya – högst 8
            List<String> topics = new ArrayList<>(new LinkedHashSet<>(concat(h.getTopics(),
                    reading.topics().stream().filter(t -> t != null && !t.isBlank()).map(String::trim).toList())));
            h = homework.setTopics(homeworkId, topics.size() > 8 ? topics.subList(0, 8) : topics).orElse(h);
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
        if ((changed || deckId != null) && h.getDueDate() != null && !h.isOther()) {  // "annat" planeras inte
            try {
                planning.plan(homeworkId);
                replanned = true;
            } catch (RuntimeException e) {
                log.warn("Kunde inte planera om läxa {}: {}", homeworkId, e.toString());
            }
        }
        return new AttachResult(homework.get(homeworkId).orElse(h), saved, sections, replanned, deckId, cards, note);
    }

    public record Image(String type, byte[] data) {
    }

    /** Sidbilden – bara om materialet hör till aktuell elev (materialet filtreras per elev). */
    @Transactional(readOnly = true)
    public java.util.Optional<Image> image(long materialId) {
        return materials.findById(materialId).filter(Material::isHasImage)
                .flatMap(m -> images.findById(m.getId()).map(i -> new Image(m.imageType(), i.getData())));
    }

    /** Föräldern anger sidorna i boken för ett material (null rensar). */
    @Transactional
    public java.util.Optional<Material> setBookPages(long materialId, Integer from, Integer to) {
        return materials.findById(materialId).map(m -> {
            m.setBookPages(from, to);
            log.info("Material {}: sidor i boken satta för hand till {}", materialId, pagesLabel(m));
            return materials.save(m);
        });
    }

    /**
     * Läser av tryckta sidnummer på läxans sparade foton (för material som laddades upp innan sidnumren
     * lästes av). Claude ser bara bilderna, tio åt gången. Sidor som redan har nummer lämnas om inte overwrite.
     */
    public List<Material> readBookPages(long homeworkId, boolean overwrite) {
        List<Material> todo = forHomework(homeworkId).stream()
                .filter(Material::isHasImage)
                .filter(m -> overwrite || m.getBookPage() == null)
                .toList();
        int found = 0;
        for (int start = 0; start < todo.size(); start += MAX_FILES) {
            List<Material> batch = todo.subList(start, Math.min(todo.size(), start + MAX_FILES));
            ObjectNode req = claude.newRequest(props.anthropic().vision(), prompts.pageNumbers());
            req.put("max_tokens", 1000);
            ObjectNode msg = req.putArray("messages").addObject();
            msg.put("role", "user");
            ArrayNode content = msg.putArray("content");
            List<Material> sent = new ArrayList<>();
            for (Material m : batch) {
                var img = image(m.getId());
                if (img.isEmpty()) {
                    continue;
                }
                sent.add(m);
                content.addObject().put("type", "text").put("text", "Fil " + sent.size());
                UploadedDocument.of(img.get().data(), "sida.jpg", img.get().type()).addTo(content, mapper);
            }
            if (sent.isEmpty()) {
                continue;
            }
            Reading r = JsonExtract.parse(claude.create(req, "material").text(), mapper, Reading.class);
            if (r == null || r.pages() == null) {
                continue;
            }
            for (Page pg : r.pages()) {
                if (pg == null || pg.file() == null || pg.file() < 1 || pg.file() > sent.size() || pg.bookPage() == null) {
                    continue;
                }
                Material m = sent.get(pg.file() - 1);
                m.setBookPages(pg.bookPage(), pg.bookPageTo());
                materials.save(m);
                found++;
            }
        }
        log.info("Läxa {}: sidnummer avlästa på {} av {} foton", homeworkId, found, todo.size());
        return forHomework(homeworkId);
    }

    static String pagesLabel(Material m) {
        return m.getBookPage() == null ? "inga" : m.getBookPage() + (m.getBookPageTo() != null ? "–" + m.getBookPageTo() : "");
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
        images.deleteById(materialId);
        return true;
    }

    @Transactional
    public void deleteForHomework(long homeworkId) {
        for (Material m : materials.findByHomeworkIdOrderByCreatedAtAsc(homeworkId)) {
            images.deleteById(m.getId());
        }
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

    private Reading read(Homework h, List<UploadedDocument> docs) {
        boolean visual = docs.stream().anyMatch(d -> d.kind() == UploadedDocument.Kind.IMAGE
                || d.kind() == UploadedDocument.Kind.PDF);
        ObjectNode req = claude.newRequest(visual ? props.anthropic().vision() : props.anthropic().planning(),
                prompts.material(), "Dagens datum: " + LocalDate.now(props.zone()));
        // Sidtexten för uppläsning tar plats: ge utrymme efter antal filer
        req.put("max_tokens", Math.min(16000, 3000 + 2500 * docs.size()));
        ObjectNode msg = req.putArray("messages").addObject();
        msg.put("role", "user");
        ArrayNode content = msg.putArray("content");
        for (int i = 0; i < docs.size(); i++) {
            if (docs.size() > 1) {
                content.addObject().put("type", "text").put("text", "Fil " + (i + 1) + ": " + docs.get(i).filename());
            }
            docs.get(i).addTo(content, mapper);
        }
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
