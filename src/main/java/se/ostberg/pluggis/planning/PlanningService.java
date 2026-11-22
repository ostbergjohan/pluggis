package se.ostberg.pluggis.planning;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.ostberg.pluggis.claude.ClaudeClient;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.homework.Homework;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.rewards.RewardsService;
import se.ostberg.pluggis.support.JsonExtract;
import se.ostberg.pluggis.support.Prompts;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Gör om "vad som ingår" i en läxa eller ett prov till en dag-för-dag-plan med små, konkreta moment.
 * Claude delar upp materialet (sidor, avsnitt, begrepp); finns inget svar används en enkel reservplan.
 * Planen görs om när innehåll eller datum ändras – avbockade moment ligger kvar.
 */
@Service
public class PlanningService {

    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.forLanguageTag("sv-SE"));

    public record PlanResult(Homework homework, List<PlanItem> items, String note) {
    }

    public record TodayItem(PlanItem item, String homeworkTitle, LocalDate homeworkDue,
                            Homework.Kind homeworkKind, boolean overdue) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeneratedPlan(List<GeneratedItem> items, String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeneratedItem(String date, String task, Integer minutes, String type) {
    }

    private final PlanItemRepository repo;
    private final HomeworkService homework;
    private final RewardsService rewards;
    private final ClaudeClient claude;
    private final Prompts prompts;
    private final PluggisProperties props;
    private final ObjectMapper mapper;

    private final se.ostberg.pluggis.student.StudentService students;

    public PlanningService(PlanItemRepository repo, HomeworkService homework, RewardsService rewards,
                           ClaudeClient claude, Prompts prompts, PluggisProperties props, ObjectMapper mapper,
                           se.ostberg.pluggis.student.StudentService students) {
        this.students = students;
        this.repo = repo;
        this.homework = homework;
        this.rewards = rewards;
        this.claude = claude;
        this.prompts = prompts;
        this.props = props;
        this.mapper = mapper;
    }

    /** Aktuell elevs pluggdagar, annars konfigurationens. */
    public java.util.Set<java.time.DayOfWeek> studyDays() {
        String d = students.current().getStudyDays();
        if (d != null && !d.isBlank()) {
            java.util.Set<java.time.DayOfWeek> out = java.util.EnumSet.noneOf(java.time.DayOfWeek.class);
            for (String part : d.split(",")) {
                try {
                    out.add(java.time.DayOfWeek.valueOf(part.trim()));
                } catch (IllegalArgumentException ignored) {
                    // okänd dag
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        return props.studyDays();
    }

    /** Aktuell elevs max minuter per dag, annars konfigurationens (40). */
    public int maxMinutesPerDay() {
        Integer m = students.current().getMaxMinutesPerDay();
        return m != null && m > 0 ? m : props.maxMinutesPerDay();
    }

    /** Gör om alla befintliga planer för aktuell elev (t.ex. efter ändrade pluggtider). Returnerar antal. */
    public int replanAll() {
        LocalDate today = todayDate();
        int n = 0;
        for (Homework h : homework.openSchoolwork()) {
            if (h.getDueDate() == null || h.getDueDate().isBefore(today)
                    || repo.findByHomeworkIdOrderByPlanDateAscIdAsc(h.getId()).isEmpty()) {
                continue;
            }
            try {
                plan(h.getId());
                n++;
            } catch (RuntimeException e) {
                log.warn("Kunde inte planera om läxa {}: {}", h.getId(), e.toString());
            }
        }
        return n;
    }

    /** Gör (om) planen. Avsiktligt utan omslutande transaktion – Claude-anropet tar några sekunder. */
    public PlanResult plan(long homeworkId) {
        Homework h = homework.get(homeworkId)
                .orElseThrow(() -> new IllegalArgumentException("Hittade ingen läxa med id " + homeworkId));
        if (h.isOther()) {
            throw new IllegalArgumentException("Det här är markerat som annat (inte läxa eller prov) och planeras inte");
        }
        if (h.getDueDate() == null) {
            throw new IllegalArgumentException("Läxan saknar datum – ange när den ska in eller när provet är");
        }
        LocalDate today = todayDate();
        List<PlanItem> done = repo.findByHomeworkIdOrderByPlanDateAscIdAsc(homeworkId).stream()
                .filter(PlanItem::isDone).toList();
        List<LocalDate> days = PlanDays.available(today, h.getDueDate(), studyDays());

        List<PlanDays.Draft> drafts;
        String note = null;
        try {
            GeneratedPlan generated = askClaude(h, days, done);
            drafts = validate(generated, days);
            note = generated.note();
        } catch (RuntimeException e) {
            log.warn("Kunde inte göra plan med Claude för läxa {}: {} – använder reservplan", homeworkId, e.toString());
            drafts = List.of();
        }
        if (drafts.isEmpty()) {
            drafts = PlanDays.fallback(h, remainingSteps(h, done), days, h.getDeckId() != null);
        }

        repo.deleteOpenForHomework(homeworkId);
        List<PlanItem> saved = repo.saveAll(drafts.stream()
                .map(d -> new PlanItem(homeworkId, d.date(), d.task(), d.minutes(), d.type()))
                .toList());
        return new PlanResult(h, saved, note);
    }

    public void deleteForHomework(long homeworkId) {
        repo.deleteAllForHomework(homeworkId);
    }

    /** Planera om bara om det redan finns en plan (t.ex. när kalendern flyttat datumet). */
    public void replanIfPlanned(long homeworkId) {
        if (!repo.findByHomeworkIdOrderByPlanDateAscIdAsc(homeworkId).isEmpty()) {
            plan(homeworkId);
        }
    }

    /** Dagens moment plus det som inte hanns med tidigare dagar. */
    @Transactional(readOnly = true)
    public List<TodayItem> today() {
        LocalDate today = todayDate();
        Map<Long, Optional<Homework>> cache = new HashMap<>();
        List<TodayItem> out = new ArrayList<>();
        for (PlanItem item : repo.findByDoneFalseAndPlanDateLessThanEqualOrderByPlanDateAscIdAsc(today)) {
            Optional<Homework> h = cache.computeIfAbsent(item.getHomeworkId(), homework::get);
            if (h.isEmpty() || h.get().getStatus() != Homework.Status.OPEN) {
                continue;
            }
            out.add(new TodayItem(item, h.get().getTitle(), h.get().getDueDate(), h.get().getKind(),
                    item.getPlanDate().isBefore(today)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<PlanItem> upcoming(int days) {
        LocalDate today = todayDate();
        return repo.findByPlanDateBetweenOrderByPlanDateAscIdAsc(today, today.plusDays(Math.max(0, days)));
    }

    @Transactional(readOnly = true)
    public List<PlanItem> forHomework(long homeworkId) {
        return repo.findByHomeworkIdOrderByPlanDateAscIdAsc(homeworkId);
    }

    @Transactional
    public Optional<PlanItem> complete(long itemId, String deviceId) {
        return repo.findById(itemId).map(item -> {
            if (!item.isDone()) {
                item.markDone(java.time.Instant.now());
                if (deviceId != null) {
                    rewards.onPlanItemDone(deviceId, item.getId(), item.getTask());
                }
            }
            return item;
        });
    }

    @Transactional(readOnly = true)
    public String summaryForPrompt() {
        List<TodayItem> items = today();
        if (items.isEmpty()) {
            return "Inget planerat idag.";
        }
        StringBuilder sb = new StringBuilder();
        for (TodayItem t : items) {
            sb.append("- [moment ").append(t.item().getId()).append("] ")
              .append(t.item().getTask())
              .append(" (").append(t.item().getMinutes()).append(" min, ")
              .append(t.homeworkKind() == Homework.Kind.TEST ? "prov " : "läxa ")
              .append(t.homeworkTitle());
            if (t.homeworkDue() != null) {
                sb.append(" ").append(DAY_LABEL.format(t.homeworkDue()));
            }
            sb.append(t.overdue() ? ", ligger efter" : "").append(")\n");
        }
        return sb.toString().trim();
    }

    // ---- intern ----

    private GeneratedPlan askClaude(Homework h, List<LocalDate> days, List<PlanItem> done) {
        StringBuilder ask = new StringBuilder();
        ask.append("Idag: ").append(todayDate()).append('\n');
        ask.append(h.getKind() == Homework.Kind.TEST ? "PROV: " : "LÄXA/INLÄMNING: ").append(h.getTitle());
        if (h.getSubject() != null) {
            ask.append(" (").append(h.getSubject()).append(')');
        }
        ask.append("\nDatum för ").append(h.getKind() == Homework.Kind.TEST ? "provet" : "inlämning")
           .append(": ").append(h.getDueDate()).append('\n');
        ask.append("Vad som ingår: ").append(h.getDescription() == null ? "(ej angivet)" : h.getDescription()).append('\n');
        if (!h.getSteps().isEmpty()) {
            ask.append("Delsteg/avsnitt: ").append(String.join(" | ", h.getSteps())).append('\n');
        }
        ask.append("Kortlek finns: ").append(h.getDeckId() != null ? "ja" : "nej").append('\n');
        ask.append("Max minuter per dag (allt plugg den dagen, även andra läxor): ").append(maxMinutesPerDay()).append('\n');
        ask.append("Dagar att använda: ");
        days.forEach(d -> ask.append(d).append(" (").append(DAY_LABEL.format(d)).append("), "));
        ask.append('\n');
        if (!days.isEmpty()) {
            // Andra läxors moment samma dagar räknas in i dagens max
            java.util.Map<LocalDate, Integer> busy = new java.util.TreeMap<>();
            for (PlanItem other : repo.findByPlanDateBetweenOrderByPlanDateAscIdAsc(days.get(0), days.get(days.size() - 1))) {
                if (!other.getHomeworkId().equals(h.getId()) && !other.isDone()) {
                    busy.merge(other.getPlanDate(), other.getMinutes(), Integer::sum);
                }
            }
            if (!busy.isEmpty()) {
                ask.append("Redan planerat för andra läxor (räknas in i max per dag): ");
                busy.forEach((d, m) -> ask.append(d).append(": ").append(m).append(" min, "));
                ask.append('\n');
            }
        }
        if (!done.isEmpty()) {
            ask.append("Redan gjort: ");
            done.forEach(d -> ask.append(d.getTask()).append("; "));
            ask.append('\n');
        }

        ObjectNode req = claude.newRequest(props.anthropic().planning(), prompts.studyPlan());
        ObjectNode msg = req.putArray("messages").addObject();
        msg.put("role", "user");
        msg.put("content", ask.toString());
        return JsonExtract.parse(claude.create(req, "planning").text(), mapper, GeneratedPlan.class);
    }

    /** Släpp bara igenom moment på tillåtna dagar, med rimlig längd och känd typ. */
    static List<PlanDays.Draft> validate(GeneratedPlan plan, List<LocalDate> days) {
        Set<LocalDate> allowed = new HashSet<>(days);
        List<PlanDays.Draft> out = new ArrayList<>();
        if (plan == null || plan.items() == null) {
            return out;
        }
        for (GeneratedItem g : plan.items()) {
            if (g.task() == null || g.task().isBlank() || g.date() == null) {
                continue;
            }
            LocalDate date;
            try {
                date = LocalDate.parse(g.date().trim());
            } catch (DateTimeParseException e) {
                continue;
            }
            if (!allowed.contains(date)) {
                continue;
            }
            int minutes = g.minutes() == null ? 15 : Math.max(5, Math.min(30, g.minutes()));
            out.add(new PlanDays.Draft(date, g.task().trim(), minutes, type(g.type())));
        }
        return out;
    }

    private static PlanItem.Type type(String raw) {
        if (raw == null) {
            return PlanItem.Type.OTHER;
        }
        try {
            return PlanItem.Type.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PlanItem.Type.OTHER;
        }
    }

    private static List<String> remainingSteps(Homework h, List<PlanItem> done) {
        Set<String> doneTasks = new HashSet<>();
        done.forEach(d -> doneTasks.add(d.getTask()));
        return h.getSteps().stream().filter(s -> !doneTasks.contains(s)).toList();
    }

    private LocalDate todayDate() {
        return LocalDate.now(props.zone());
    }
}
