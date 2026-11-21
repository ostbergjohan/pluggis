package se.ostberg.laxcoach.homework;

import org.springframework.stereotype.Service;
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
              .append(h.getSubject() == null ? "" : h.getSubject() + ": ")
              .append(h.getTitle());
            if (h.getDueDate() != null) {
                sb.append(" (till ").append(h.getDueDate()).append(')');
            }
            if (!h.getSteps().isEmpty()) {
                sb.append(" – steg: ").append(String.join(" | ", h.getSteps()));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
