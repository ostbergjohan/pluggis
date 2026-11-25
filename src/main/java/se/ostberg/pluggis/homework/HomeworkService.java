package se.ostberg.pluggis.homework;

import org.springframework.stereotype.Service;
import se.ostberg.pluggis.support.TextFix;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class HomeworkService {

    private final HomeworkRepository repo;
    /** Som det ska sägas – coachens svar läses upp. */
    private static final java.time.format.DateTimeFormatter SPOKEN_DAY =
            java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.forLanguageTag("sv-SE"));
    private java.time.ZoneId zone = java.time.ZoneId.of("Europe/Stockholm");

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProps(se.ostberg.pluggis.config.PluggisProperties props) {
        if (props != null) {
            zone = props.zone();
        }
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    /** Datumet har passerat (igår eller tidigare). */
    public boolean isPast(Homework h) {
        return h.getDueDate() != null && h.getDueDate().isBefore(today());
    }

    /**
     * Markerar öppna läxor och prov vars datum har passerat som klara (ger inga poäng).
     * Returnerar antal.
     */
    @Transactional
    public Optional<Homework> setTopics(long id, java.util.Collection<String> topics) {
        return repo.findById(id).map(h -> {
            h.setTopics(topics);
            return repo.save(h);
        });
    }

    @Transactional
    public int archivePast() {
        int n = 0;
        for (Homework h : repo.findByStatusOrderByDueDateAscCreatedAtAsc(Homework.Status.OPEN)) {
            if (isPast(h)) {
                h.markDone();
                n++;
            }
        }
        return n;
    }

    public HomeworkService(HomeworkRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Homework add(String subject, String title, String description, LocalDate dueDate,
                        List<String> steps, Homework.Source source) {
        return repo.save(new Homework(subject, title, description, dueDate, steps, source));
    }

    /** Skapa eller uppdatera en läxa från skolans kalender (matchas på UID). */
    @Transactional
    public Homework upsertFromCalendar(String externalId, String subject, String title, String description,
                                       LocalDate dueDate, Homework.Kind kind) {
        return repo.findByExternalId(externalId)
                // Vissa kalendrar (t.ex. InfoMentor) byter UID mellan hämtningar – känn då igen på titel + datum.
                .or(() -> findCalendarTwin(title, dueDate))
                .map(h -> {
                    h.updateFromCalendar(subject, TextFix.fixMojibake(title), TextFix.fixMojibake(description), dueDate, kind);
                    h.setExternalId(externalId);
                    return h;
                })
                .orElseGet(() -> {
                    Homework h = new Homework(subject, TextFix.fixMojibake(title), TextFix.fixMojibake(description),
                            dueDate, null, Homework.Source.CALENDAR);
                    h.updateContent(null, null, kind, null);
                    h.updateFromCalendar(subject, h.getTitle(), h.getDescription(), dueDate, kind);
                    h.setExternalId(externalId);
                    return repo.save(h);
                });
    }

    /** Kalenderimporterad läxa med samma titel (oavsett åäö-fel) och samma datum. */
    private Optional<Homework> findCalendarTwin(String title, LocalDate dueDate) {
        if (dueDate == null) {
            return Optional.empty();
        }
        String key = TextFix.key(title);
        return repo.findByExternalIdIsNotNull().stream()
                .filter(h -> dueDate.equals(h.getCalendarDate()) && TextFix.key(h.getTitle()).equals(key))
                .findFirst();
    }

    /** Alla läxor med datum – för att hitta dubbletter mellan kalender och egna. */
    @Transactional(readOnly = true)
    public List<Homework> withDueDate() {
        return repo.findAll().stream().filter(h -> h.getCalendarDate() != null).toList();
    }

    /** Låter en läxa ta över kalenderns UID (efter att dubbletten med UID:t tagits bort). */
    @Transactional
    public void adoptExternalId(long id, String externalId) {
        repo.findById(id).ifPresent(h -> {
            if (h.getExternalId() == null) {
                h.setExternalId(externalId);
            }
        });
    }

    @Transactional(readOnly = true)
    public List<Homework> calendarImported() {
        return repo.findByExternalIdIsNotNull();
    }

    @Transactional
    public void fixTitleEncoding(long id) {
        repo.findById(id).ifPresent(h -> h.edit(null, TextFix.fixMojibake(h.getTitle())));
    }

    @Transactional
    public Optional<Homework> updateContent(long id, String description, List<String> steps,
                                            Homework.Kind kind, LocalDate dueDate) {
        return repo.findById(id).map(h -> {
            h.updateContent(description, steps, kind, dueDate);
            return h;
        });
    }

    /** Fullständig redigering från föräldravyn. Null = lämna oförändrat. */
    @Transactional
    public Optional<Homework> edit(long id, String subject, String title, String description, List<String> steps,
                                   Homework.Kind kind, LocalDate dueDate, Boolean open, boolean clearDueDate) {
        return edit(id, subject, title, description, steps, kind, dueDate, open, clearDueDate, null);
    }

    @Transactional
    public Optional<Homework> edit(long id, String subject, String title, String description, List<String> steps,
                                   Homework.Kind kind, LocalDate dueDate, Boolean open, boolean clearDueDate, Integer weight) {
        return repo.findById(id).map(h -> {
            h.setWeight(weight);
            h.edit(subject, title);
            h.updateContent(description, steps, null, null);
            h.editKindAndDate(kind, dueDate, clearDueDate);
            if (Boolean.TRUE.equals(open)) {
                h.reopen();
            } else if (Boolean.FALSE.equals(open) && h.getStatus() == Homework.Status.OPEN) {
                h.markDone();
            }
            return h;
        });
    }

    @Transactional
    public Optional<Homework> setListening(long id, Long materialId, Integer sentence, java.util.Collection<Long> listened) {
        return repo.findById(id).map(h -> {
            h.setListening(materialId, sentence, listened);
            return h;
        });
    }

    @Transactional
    public void setManualPlan(long id, boolean manual) {
        repo.findById(id).ifPresent(h -> h.setManualPlan(manual));
    }

    @Transactional
    public boolean delete(long id) {
        if (!repo.existsById(id)) {
            return false;
        }
        repo.deleteById(id);
        return true;
    }

    @Transactional
    public Optional<Homework> linkDeck(long id, long deckId) {
        return repo.findById(id).map(h -> {
            h.linkDeck(deckId);
            return h;
        });
    }

    @Transactional(readOnly = true)
    public Optional<Homework> findByExternalId(String externalId) {
        return repo.findByExternalId(externalId);
    }

    @Transactional(readOnly = true)
    public Optional<Homework> get(long id) {
        return repo.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Homework> open() {
        return repo.findByStatusOrderByDueDateAscCreatedAtAsc(Homework.Status.OPEN);
    }

    /** Öppna läxor och prov – utan "annat" (aktivitetsdagar m.m.). Det coachen, påminnelser och statistik ser. */
    @Transactional(readOnly = true)
    public List<Homework> openSchoolwork() {
        return open().stream().filter(h -> !h.isOther()).toList();
    }

    @Transactional(readOnly = true)
    public List<Homework> all() {
        return repo.findAll();
    }

    @Transactional
    public Optional<Homework> complete(long id) {
        return repo.findById(id).map(h -> {
            h.markDone();
            return h;
        });
    }

    /** Kort text till coachens systemprompt. */
    @Transactional(readOnly = true)
    public String summaryForPrompt() {
        // Prov som redan varit är inget att plugga på; sena läxor tas med men märks.
        List<Homework> open = openSchoolwork().stream()
                .filter(h -> !(h.getKind() == Homework.Kind.TEST && isPast(h)))
                .toList();
        if (open.isEmpty()) {
            return "Inga öppna läxor registrerade.";
        }
        StringBuilder sb = new StringBuilder();
        for (Homework h : open) {
            sb.append("- [id ").append(h.getId()).append("] ")
              .append(h.getKind() == Homework.Kind.TEST ? "PROV " : "")
              .append(h.getSubject() == null ? "" : h.getSubject() + ": ")
              .append(h.getTitle());
            if (h.getDueDate() != null) {
                String day = SPOKEN_DAY.format(h.getDueDate());
                sb.append(isPast(h) ? " (datumet " + day + " har passerat – fråga om den är gjord)"
                        : " (till " + day + ")");
            }
            if (h.getDeckId() != null) {
                sb.append(" [kortlek ").append(h.getDeckId()).append(']');
            }
            if (h.getSteps().isEmpty() && (h.getDescription() == null || h.getDescription().isBlank())) {
                sb.append(" – innehåll saknas, fråga vad som ingår");
            }
            if (!h.getSteps().isEmpty()) {
                sb.append(" – steg: ").append(String.join(" | ", h.getSteps()));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
