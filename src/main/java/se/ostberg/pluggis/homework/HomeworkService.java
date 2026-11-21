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
        return repo.findByExternalIdIsNotNullAndDueDate(dueDate).stream()
                .filter(h -> TextFix.key(h.getTitle()).equals(key))
                .findFirst();
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
                                   Homework.Kind kind, LocalDate dueDate, Boolean open) {
        return repo.findById(id).map(h -> {
            h.edit(subject, title);
            h.updateContent(description, steps, kind, dueDate);
            if (Boolean.TRUE.equals(open)) {
                h.reopen();
            } else if (Boolean.FALSE.equals(open) && h.getStatus() == Homework.Status.OPEN) {
                h.markDone();
            }
            return h;
        });
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
        List<Homework> open = open();
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
                sb.append(" (till ").append(h.getDueDate()).append(')');
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
