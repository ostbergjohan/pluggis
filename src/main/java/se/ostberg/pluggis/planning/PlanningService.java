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
    private org.springframework.context.ApplicationEventPublisher events = e -> { };

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

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

    /** Hur "Planera om allt" går för en elev – visas i admin medan det pågår och efteråt. */
    public record ReplanStatus(boolean running, int total, int done, List<String> skipped, List<String> failed,
                               java.time.Instant finishedAt) {
        static ReplanStatus idle() {
            return new ReplanStatus(false, 0, 0, List.of(), List.of(), null);
        }
    }

    private final java.util.Map<Long, ReplanStatus> replanStatus = new java.util.concurrent.ConcurrentHashMap<>();

    public ReplanStatus replanStatus() {
        return replanStatus.getOrDefault(se.ostberg.pluggis.student.StudentContext.currentOrDefault(), ReplanStatus.idle());
    }

    public boolean replanRunning() {
        return replanStatus().running();
    }

    /** Gör om alla befintliga planer för aktuell elev (t.ex. efter ändrade pluggtider). Returnerar antal. */
    public int replanAll() {
        Long student = se.ostberg.pluggis.student.StudentContext.currentOrDefault();
        LocalDate today = todayDate();
        List<Homework> open = new ArrayList<>(homework.openSchoolwork());
        // Närmast deadline först; samma dag → viktigast först
        open.sort(java.util.Comparator.comparing(Homework::getDueDate, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(Homework::weightOrDefault, java.util.Comparator.reverseOrder()));
        List<Homework> todo = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Homework h : open) {
            if (h.getDueDate() == null) {
                skipped.add(h.getTitle() + " (saknar datum)");
            } else if (h.getDueDate().isBefore(today)) {
                continue;   // passerat – inget att planera
            } else if (h.isManualPlan()) {
                skipped.add(h.getTitle() + " (flyttad för hand – tryck Planera på läxan)");
            } else if (repo.findByHomeworkIdOrderByPlanDateAscIdAsc(h.getId()).isEmpty()) {
                skipped.add(h.getTitle() + " (inte planerad än – tryck Planera på läxan)");
            } else {
                todo.add(h);
            }
        }
        List<String> failed = new ArrayList<>();
        replanStatus.put(student, new ReplanStatus(true, todo.size(), 0, List.copyOf(skipped), List.of(), null));
        int n = 0;
        try {
            for (Homework h : todo) {
                try {
                    plan(h.getId());
                    n++;
                } catch (RuntimeException e) {
                    log.warn("Kunde inte planera om läxa {}: {}", h.getId(), e.toString());
                    failed.add(h.getTitle() + " (" + e.getMessage() + ")");
                }
                replanStatus.put(student, new ReplanStatus(true, todo.size(), n + failed.size(), List.copyOf(skipped),
                        List.copyOf(failed), null));
            }
        } finally {
            replanStatus.put(student, new ReplanStatus(false, todo.size(), n + failed.size(), List.copyOf(skipped),
                    List.copyOf(failed), java.time.Instant.now()));
        }
        log.info("Planerade om {} av {} läxor ({} hoppades över)", n, todo.size(), skipped.size());
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

        // Hård spärr: Claude får förslag, men koden ser till att max per dag håller
        PlanDays.Fitted fitted = PlanDays.fitToBudget(drafts, days, busyMinutes(h, days), maxMinutesPerDay());
        drafts = fitted.drafts();
        if (fitted.overflowMinutes() > 0) {
            String warn = "Det får inte plats inom max " + maxMinutesPerDay() + " min/dag – ca "
                    + fitted.overflowMinutes() + " min för mycket. Höj max eller välj fler pluggdagar.";
            note = note == null || note.isBlank() ? warn : note + " " + warn;
        }

        repo.deleteOpenForHomework(homeworkId);
        if (h.isManualPlan()) {
            homework.setManualPlan(homeworkId, false); // ny plan = Pluggis tar över igen
        }
        List<PlanItem> saved = repo.saveAll(drafts.stream()
                .map(d -> new PlanItem(homeworkId, d.date(), d.task(), d.minutes(), d.type()))
                .toList());
        changed();
        return new PlanResult(h, saved, note);
    }

    public void deleteForHomework(long homeworkId) {
        repo.deleteAllForHomework(homeworkId);
        changed();
    }

    private void changed() {
        events.publishEvent(new PlanChanged(se.ostberg.pluggis.student.StudentContext.currentOrDefault()));
    }

    /** Planera om bara om det redan finns en plan (t.ex. när kalendern flyttat datumet). */
    public void replanIfPlanned(long homeworkId) {
        if (homework.get(homeworkId).map(Homework::isOther).orElse(true)) {
            return;   // "annat" (t.ex. aktivitetsdag) planeras inte
        }
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
        // Det som ligger efter räknas till idag, så att samma läxa blir ett block (inte ett "efter"-block + ett idag-block)
        List<PlanItem> open = grouped(repo.findByDoneFalseAndPlanDateLessThanEqualOrderByPlanDateAscIdAsc(today), i -> today);
        for (PlanItem item : open) {
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
        return grouped(repo.findByPlanDateBetweenOrderByPlanDateAscIdAsc(today, today.plusDays(Math.max(0, days))));
    }

    @Transactional(readOnly = true)
    public List<PlanItem> forHomework(long homeworkId) {
        return repo.findByHomeworkIdOrderByPlanDateAscIdAsc(homeworkId);
    }

    /** Ångra ett avbockat moment (föräldern). Poängen för momentet tas bort. */
    @Transactional
    public Optional<PlanItem> undo(long itemId, String deviceId) {
        return repo.findById(itemId).map(item -> {
            if (item.isDone()) {
                item.markUndone();
                rewards.onPlanItemUndone(deviceId, item.getId());
                changed();
            }
            return item;
        });
    }

    @Transactional
    public Optional<PlanItem> complete(long itemId, String deviceId) {
        return repo.findById(itemId).map(item -> {
            if (!item.isDone()) {
                item.markDone(java.time.Instant.now());
                if (deviceId != null) {
                    rewards.onPlanItemDone(deviceId, item.getId(), item.getTask());
                    // Allt som skulle göras till och med idag är klart → dagen klar
                    LocalDate today = todayDate();
                    if (!item.getPlanDate().isAfter(today)
                            && repo.findByDoneFalseAndPlanDateLessThanEqualOrderByPlanDateAscIdAsc(today).isEmpty()) {
                        rewards.onDayCompleted(deviceId);
                    }
                }
                changed();
            }
            return item;
        });
    }

    @Transactional(readOnly = true)
    public String summaryForPrompt() {
        List<TodayItem> items = today();
        StringBuilder sb = new StringBuilder();
        for (Progress p : progress()) {
            sb.append("- ").append(p.kind() == Homework.Kind.TEST ? "Prov " : "Läxa ").append(p.title()).append(": ")
              .append(p.daysLeft() == null ? "" : p.daysLeft() == 0 ? "idag, " : p.daysLeft() == 1 ? "imorgon, "
                      : "om " + p.daysLeft() + " dagar, ")
              .append(p.percent()).append(" % klart").append(p.behind() > 0 ? ", " + p.behind() + " moment ligger efter" : "")
              .append('\n');
        }
        if (!sb.isEmpty()) {
            sb.insert(0, "Framsteg (berätta gärna hur långt eleven kommit – det motiverar):\n").append('\n');
        }
        if (items.isEmpty()) {
            return sb.append("Inget planerat idag.").toString();
        }
        sb.append("Idag:\n");
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

    /** Hur långt en läxa/ett prov har kommit i planen. */
    public record Progress(long homeworkId, String title, Homework.Kind kind, LocalDate dueDate, Long daysLeft,
                           int doneItems, int totalItems, int percent, int minutesLeft, int behind) {
    }

    /** Framsteg för aktuell elevs kommande läxor och prov som har en plan, närmast först. */
    @Transactional(readOnly = true)
    public List<Progress> progress() {
        LocalDate today = todayDate();
        List<Progress> out = new java.util.ArrayList<>();
        for (Homework h : homework.openSchoolwork()) {
            if (h.getDueDate() != null && h.getDueDate().isBefore(today)) {
                continue;
            }
            List<PlanItem> items = repo.findByHomeworkIdOrderByPlanDateAscIdAsc(h.getId());
            if (items.isEmpty()) {
                continue;
            }
            int total = items.stream().mapToInt(PlanItem::getMinutes).sum();
            int done = items.stream().filter(PlanItem::isDone).mapToInt(PlanItem::getMinutes).sum();
            int doneItems = (int) items.stream().filter(PlanItem::isDone).count();
            int behind = (int) items.stream().filter(i -> !i.isDone() && i.getPlanDate().isBefore(today)).count();
            out.add(new Progress(h.getId(), h.getTitle(), h.getKind(), h.getDueDate(),
                    h.getDueDate() == null ? null : java.time.temporal.ChronoUnit.DAYS.between(today, h.getDueDate()),
                    doneItems, items.size(), total == 0 ? 0 : Math.round(100f * done / total), total - done, behind));
        }
        out.sort(java.util.Comparator.comparing(Progress::dueDate, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return out;
    }

    /**
     * Varje natt: läxor och prov med moment som ligger efter (och datum kvar) planeras om, så att det
     * som missats sprids ut inom dagens max i stället för att staplas på dagen efter.
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "${pluggis.planning.replan-cron:0 30 3 * * *}",
            zone = "${pluggis.timezone:Europe/Stockholm}")
    public void nightlyReplan() {
        for (var student : students.all()) {
            se.ostberg.pluggis.student.StudentContext.run(student.getId(), () -> {
                int n = replanBehind();
                if (n > 0) {
                    log.info("Natt-omplanering elev {}: {} planer gjorda om", student.getId(), n);
                }
            });
        }
    }

    /** Planerar om aktuell elevs planer som har moment som ligger efter. Returnerar antal. */
    public int replanBehind() {
        LocalDate today = todayDate();
        int n = 0;
        for (Homework h : homework.openSchoolwork()) {
            if (h.getDueDate() == null || h.getDueDate().isBefore(today) || h.isManualPlan()) {
                continue;
            }
            boolean behind = repo.findByHomeworkIdOrderByPlanDateAscIdAsc(h.getId()).stream()
                    .anyMatch(i -> !i.isDone() && i.getPlanDate().isBefore(today));
            if (!behind) {
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

    /**
     * Föräldern drar moment till en dag: {@code ids} i den ordning de ska ligga den dagen.
     * Avbockade moment rörs inte. Inte före idag och inte på/efter provet eller inlämningen.
     * Läxor vars moment flyttats markeras som handplanerade (natten planerar inte om dem).
     */
    @Transactional
    public List<PlanItem> arrangeDay(LocalDate date, List<Long> ids) {
        LocalDate today = todayDate();
        if (date.isBefore(today)) {
            throw new IllegalArgumentException("Det går inte att flytta till en dag som har varit");
        }
        Map<Long, Optional<Homework>> cache = new HashMap<>();
        java.util.Set<Long> moved = new java.util.HashSet<>();
        int order = 0;
        for (Long id : ids) {
            PlanItem item = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Hittar inte momentet " + id));
            if (item.isDone()) {
                continue;
            }
            Optional<Homework> h = cache.computeIfAbsent(item.getHomeworkId(), homework::get);
            if (h.isPresent() && h.get().getDueDate() != null && !date.isBefore(h.get().getDueDate())) {
                throw new IllegalArgumentException("\"" + item.getTask() + "\" måste vara klart före "
                        + (h.get().getKind() == Homework.Kind.TEST ? "provet" : "inlämningen") + " (" + h.get().getDueDate() + ")");
            }
            if (!date.equals(item.getPlanDate())) {
                moved.add(item.getHomeworkId());
            }
            item.moveTo(date, order++);
        }
        moved.forEach(id -> homework.setManualPlan(id, true));
        changed();
        List<PlanItem> day = new ArrayList<>(repo.findByPlanDateBetweenOrderByPlanDateAscIdAsc(date, date));
        day.sort(PlanItem.ORDER);
        return day;
    }

    /**
     * Ordning inom varje dag: ett block per läxa/prov (ämne) så att eleven inte hoppar mellan ämnen –
     * närmast deadline först, sedan viktigast, och inom blocket i planens ordning.
     * Har föräldern ordnat dagen för hand gäller den ordningen.
     */
    public List<PlanItem> grouped(List<PlanItem> items) {
        return grouped(items, PlanItem::getPlanDate);
    }

    /** Med {@code dayOf} kan t.ex. sådant som ligger efter räknas till idag, så att läxan blir ett block. */
    List<PlanItem> grouped(List<PlanItem> items, java.util.function.Function<PlanItem, LocalDate> dayOf) {
        Map<Long, Optional<Homework>> cache = new HashMap<>();
        java.util.function.Function<Long, Optional<Homework>> hw = id -> cache.computeIfAbsent(id, homework::get);
        java.util.Comparator<Long> byId = java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder());
        java.util.Comparator<Integer> byOrder = java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder());

        Map<LocalDate, List<PlanItem>> days = new java.util.TreeMap<>();
        items.forEach(i -> days.computeIfAbsent(dayOf.apply(i), d -> new ArrayList<>()).add(i));
        List<PlanItem> out = new ArrayList<>(items.size());
        for (List<PlanItem> day : days.values()) {
            boolean manual = day.stream().anyMatch(i -> i.getSortOrder() != null);
            // Ett block per läxa/prov – alltid, även på handordnade dagar
            Map<Long, List<PlanItem>> blocks = new java.util.LinkedHashMap<>();
            day.forEach(i -> blocks.computeIfAbsent(i.getHomeworkId(), k -> new ArrayList<>()).add(i));
            List<Long> order = new ArrayList<>(blocks.keySet());
            if (manual) {
                // Föräldern bestämmer blockens ordning: där blockets första moment ligger
                java.util.function.Function<Long, Integer> first = id -> blocks.get(id).stream().map(PlanItem::getSortOrder)
                        .filter(java.util.Objects::nonNull).min(Integer::compare).orElse(null);
                order.sort(java.util.Comparator.comparing(first, byOrder).thenComparing(id -> id, byId));
                blocks.values().forEach(b -> b.sort(java.util.Comparator.comparing(PlanItem::getSortOrder, byOrder)
                        .thenComparing(PlanItem::getId, byId)));
            } else {
                // Samma ämne bredvid varandra (NO-läxan intill NO-provet); ämnet med närmast deadline/högst vikt först
                java.util.function.Function<Long, String> subj = id -> subjectKey(hw.apply(id).orElse(null), id);
                Map<String, LocalDate> subjDue = new HashMap<>();
                Map<String, Integer> subjWeight = new HashMap<>();
                for (Long id : order) {
                    hw.apply(id).map(Homework::getDueDate).ifPresent(d -> subjDue.merge(subj.apply(id), d, (x, y) -> x.isBefore(y) ? x : y));
                    subjWeight.merge(subj.apply(id), hw.apply(id).map(Homework::weightOrDefault).orElse(50), Math::max);
                }
                java.util.Comparator<LocalDate> dates = java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder());
                order.sort(java.util.Comparator.comparing((Long id) -> subjDue.get(subj.apply(id)), dates)
                        .thenComparing(id -> subjWeight.getOrDefault(subj.apply(id), 50), java.util.Comparator.reverseOrder())
                        .thenComparing(subj)
                        .thenComparing(id -> hw.apply(id).map(Homework::getDueDate).orElse(null), dates)
                        .thenComparing(id -> hw.apply(id).map(Homework::weightOrDefault).orElse(50), java.util.Comparator.reverseOrder())
                        .thenComparing(id -> id, byId));
                blocks.values().forEach(b -> b.sort(java.util.Comparator.comparing(PlanItem::getId, byId)));
            }
            order.forEach(id -> out.addAll(blocks.get(id)));
        }
        return out;
    }

    /** Ämnet som nyckel ("no", "spanska" …); utan ämne räknas läxan som sitt eget ämne. */
    public static String subjectKey(Homework h, Long homeworkId) {
        String s = h == null || h.getSubject() == null ? "" : h.getSubject().trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? "läxa-" + homeworkId : s;
    }

    /** Publiceras när en elevs plan har ändrats (för t.ex. Google Kalender). */
    public record PlanChanged(Long studentId) {
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
        ask.append("Vikt: ").append(h.weightOrDefault()).append(" % (hur viktigt föräldern tycker att det här är; 50 % = normalt)\n");
        ask.append("Kortlek finns: ").append(h.getDeckId() != null ? "ja" : "nej").append('\n');
        ask.append("Max minuter per dag (allt plugg den dagen, även andra läxor): ").append(maxMinutesPerDay()).append('\n');
        var st = students.current();
        ask.append("Elevens pluggtid: ").append(st.getStudyCue().isBlank() ? "" : st.getStudyCue() + ", ")
                .append("ca ").append(st.studyTimeOrDefault()).append('\n');
        ask.append("Dagar att använda: ");
        days.forEach(d -> ask.append(d).append(" (").append(DAY_LABEL.format(d)).append("), "));
        ask.append('\n');
        if (!days.isEmpty()) {
            java.util.Map<LocalDate, Integer> busy = busyMinutes(h, days);
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

    /** Andra läxors ogjorda moment per dag – räknas in i dagens max. */
    private java.util.Map<LocalDate, Integer> busyMinutes(Homework h, List<LocalDate> days) {
        java.util.Map<LocalDate, Integer> busy = new java.util.TreeMap<>();
        if (days.isEmpty()) {
            return busy;
        }
        for (PlanItem other : repo.findByPlanDateBetweenOrderByPlanDateAscIdAsc(days.get(0), days.get(days.size() - 1))) {
            if (!other.getHomeworkId().equals(h.getId()) && !other.isDone()) {
                busy.merge(other.getPlanDate(), other.getMinutes(), Integer::sum);
            }
        }
        return busy;
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
            if (SKIM_ONLY.matcher(g.task()).find()) {
                continue; // "läs bara rubrikerna" ger ingenting
            }
            int minutes = g.minutes() == null ? MIN_ITEM_MINUTES : Math.max(MIN_ITEM_MINUTES, Math.min(30, g.minutes()));
            out.add(new PlanDays.Draft(date, g.task().trim(), minutes, type(g.type())));
        }
        return out;
    }

    /** Kortaste moment i en plan – kortare pass blir för små för att komma någonstans. */
    public static final int MIN_ITEM_MINUTES = 15;
    private static final java.util.regex.Pattern SKIM_ONLY = java.util.regex.Pattern.compile(
            "(?i)(bara|endast|skumma)\\s+(igenom\\s+)?(rubrik|bild|sidorna)|läs\\s+(bara\\s+)?rubrikerna");

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
