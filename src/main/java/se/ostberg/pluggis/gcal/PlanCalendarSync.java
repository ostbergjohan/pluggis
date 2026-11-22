package se.ostberg.pluggis.gcal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.homework.Homework;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.planning.PlanItem;
import se.ostberg.pluggis.planning.PlanningService;
import se.ostberg.pluggis.student.Student;
import se.ostberg.pluggis.student.StudentContext;
import se.ostberg.pluggis.student.StudentService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Lägger elevens plan i Google Kalender: ett pass per dag vid elevens pluggtid, lika långt som dagens
 * moment, med momenten (☐/✅) i beskrivningen och en notis när det är dags. Synkas en stund efter varje
 * ändring i planen och varje natt. Rör bara händelser som Pluggis själv skapat (märkta med eleven).
 */
@Service
public class PlanCalendarSync {

    private static final Logger log = LoggerFactory.getLogger(PlanCalendarSync.class);
    private static final DateTimeFormatter RFC = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    public record Result(int created, int updated, int deleted) {
    }

    private final GoogleCalendarClient google;
    private final GoogleProperties gprops;
    private final PlanningService planning;
    private final HomeworkService homework;
    private final StudentService students;
    private final PluggisProperties props;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gcal-sync");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final Map<Long, String> lastError = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastSync = new ConcurrentHashMap<>();
    /** Vänta så här länge efter en ändring (många avbockningar i rad blir en synk). */
    long debounceMillis = 15_000;

    public PlanCalendarSync(GoogleCalendarClient google, GoogleProperties gprops, PlanningService planning,
                            HomeworkService homework, StudentService students, PluggisProperties props, ObjectMapper mapper) {
        this.google = google;
        this.gprops = gprops;
        this.planning = planning;
        this.homework = homework;
        this.students = students;
        this.props = props;
        this.mapper = mapper;
    }

    public boolean enabled() {
        return google.enabled();
    }

    public String serviceAccountEmail() {
        return google.serviceAccountEmail();
    }

    public Map<String, Object> status() {
        Student s = students.current();
        Map<String, Object> m = new HashMap<>();
        m.put("enabled", enabled());
        m.put("serviceAccountEmail", serviceAccountEmail());
        m.put("calendarId", s.getGoogleCalendarId());
        m.put("lastSync", lastSync.get(s.getId()));
        m.put("lastError", lastError.get(s.getId()));
        m.put("upcoming", upcomingLinks(s));
        return m;
    }

    /** De närmaste passen som Pluggis lagt in, med länk till Google Kalender (för att se att de finns). */
    private List<Map<String, String>> upcomingLinks(Student s) {
        String cal = s.getGoogleCalendarId();
        if (!enabled() || cal == null || cal.isBlank()) {
            return List.of();
        }
        try {
            LocalDate today = LocalDate.now(props.zone());
            return google.listOwn(cal.trim(), s.key(), start(today, LocalTime.MIN), start(today.plusDays(gprops.days() + 1), LocalTime.MIN))
                    .stream()
                    .sorted(java.util.Comparator.comparing(e -> e.path("start").path("dateTime").asText("")))
                    .limit(5)
                    .map(e -> Map.of("start", e.path("start").path("dateTime").asText(""),
                            "summary", e.path("summary").asText(""),
                            "link", e.path("htmlLink").asText("")))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @EventListener
    public void onPlanChanged(PlanningService.PlanChanged e) {
        if (!enabled() || e.studentId() == null) {
            return;
        }
        pending.compute(e.studentId(), (id, old) -> {
            if (old != null) {
                old.cancel(false);
            }
            return later.schedule(() -> {
                pending.remove(id);
                StudentContext.run(id, this::syncQuietly);
            }, debounceMillis, TimeUnit.MILLISECONDS);
        });
    }

    /** Varje natt (efter omplaneringen): rulla fram fönstret för alla elever. */
    @Scheduled(cron = "${pluggis.google.sync-cron:0 45 3 * * *}", zone = "${pluggis.timezone:Europe/Stockholm}")
    public void nightly() {
        if (!enabled()) {
            return;
        }
        for (Student s : students.all()) {
            StudentContext.run(s.getId(), this::syncQuietly);
        }
    }

    private void syncQuietly() {
        try {
            sync();
        } catch (RuntimeException e) {
            log.warn("Google Kalender-synk misslyckades för elev {}: {}", StudentContext.currentOrDefault(), e.getMessage());
        }
    }

    /** Synka aktuell elevs plan nu. */
    public Result sync() {
        Student s = students.current();
        String cal = s.getGoogleCalendarId();
        if (!enabled() || cal == null || cal.isBlank()) {
            return new Result(0, 0, 0);
        }
        try {
            Result r = doSync(s, cal.trim());
            lastSync.put(s.getId(), Instant.now());
            lastError.remove(s.getId());
            if (r.created() + r.updated() + r.deleted() > 0) {
                log.info("Google Kalender elev {}: {} nya, {} ändrade, {} borttagna", s.getId(), r.created(), r.updated(), r.deleted());
            }
            return r;
        } catch (RuntimeException e) {
            lastError.put(s.getId(), friendly(e));
            throw new IllegalStateException(friendly(e), e);
        }
    }

    /** Ta bort Pluggis alla kommande pass ur kalendern (när kopplingen stängs av). */
    public int clearUpcoming(Student s, String cal) {
        if (!enabled() || cal == null || cal.isBlank()) {
            return 0;
        }
        LocalDate today = LocalDate.now(props.zone());
        int n = 0;
        for (JsonNode ev : google.listOwn(cal, s.key(), start(today, LocalTime.MIN), start(today.plusDays(gprops.days() + 1), LocalTime.MIN))) {
            google.delete(cal, ev.path("id").asText());
            n++;
        }
        return n;
    }

    Result doSync(Student s, String cal) {
        LocalDate today = LocalDate.now(props.zone());
        LocalDate last = today.plusDays(gprops.days());
        Map<Long, Homework> hw = new HashMap<>();
        homework.all().forEach(h -> hw.put(h.getId(), h));

        // Önskat: ett pass per dag med moment
        Map<LocalDate, List<PlanItem>> byDay = new TreeMap<>();
        for (PlanItem i : planning.upcoming(gprops.days())) {
            if (!i.getPlanDate().isBefore(today) && !i.getPlanDate().isAfter(last)) {
                byDay.computeIfAbsent(i.getPlanDate(), d -> new java.util.ArrayList<>()).add(i);
            }
        }
        // Befintligt: Pluggis egna händelser för eleven
        Map<String, JsonNode> existing = new HashMap<>();
        for (JsonNode ev : google.listOwn(cal, s.key(), start(today, LocalTime.MIN), start(last.plusDays(1), LocalTime.MIN))) {
            String day = ev.path("extendedProperties").path("private").path("pluggisDay").asText("");
            if (existing.containsKey(day)) {
                google.delete(cal, ev.path("id").asText()); // dubblett
            } else {
                existing.put(day, ev);
            }
        }
        int created = 0;
        int updated = 0;
        int deleted = 0;
        for (var e : byDay.entrySet()) {
            ObjectNode want = event(s, e.getKey(), e.getValue(), hw);
            JsonNode have = existing.remove(e.getKey().toString());
            if (have == null) {
                google.insert(cal, want);
                created++;
            } else if (differs(have, want)) {
                google.update(cal, have.path("id").asText(), want);
                updated++;
            }
        }
        for (JsonNode left : existing.values()) {
            google.delete(cal, left.path("id").asText());
            deleted++;
        }
        return new Result(created, updated, deleted);
    }

    ObjectNode event(Student s, LocalDate day, List<PlanItem> items, Map<Long, Homework> hw) {
        int minutes = Math.max(10, items.stream().mapToInt(PlanItem::getMinutes).sum());
        boolean allDone = items.stream().allMatch(PlanItem::isDone);
        java.util.LinkedHashSet<String> titles = new java.util.LinkedHashSet<>();
        StringBuilder desc = new StringBuilder();
        for (PlanItem i : items) {
            Homework h = hw.get(i.getHomeworkId());
            String what = h == null ? "" : (h.getKind() == Homework.Kind.TEST ? "prov " : "") + h.getTitle();
            if (h != null) {
                titles.add(h.getTitle());
            }
            desc.append(i.isDone() ? "✅ " : "☐ ").append(i.getTask()).append(" (").append(i.getMinutes()).append(" min")
                    .append(what.isBlank() ? "" : ", " + what).append(")\n");
        }
        if (!s.getStudyCue().isBlank()) {
            desc.append("\nNär: ").append(s.getStudyCue());
        }
        desc.append("\nBocka av i Pluggis – kalendern uppdateras av sig själv.");
        ObjectNode ev = mapper.createObjectNode();
        ev.put("summary", (allDone ? "✅ " : "📚 ") + "Plugg " + minutes + " min" + (titles.isEmpty() ? "" : ": " + String.join(", ", titles)));
        ev.put("description", desc.toString().trim());
        ZonedDateTime from = day.atTime(s.studyTimeOrDefault()).atZone(props.zone());
        ev.putObject("start").put("dateTime", RFC.format(from)).put("timeZone", props.zone().getId());
        ev.putObject("end").put("dateTime", RFC.format(from.plusMinutes(minutes))).put("timeZone", props.zone().getId());
        ev.put("colorId", allDone ? "2" : "9");
        ObjectNode reminders = ev.putObject("reminders");
        reminders.put("useDefault", false);
        if (!allDone) {
            reminders.putArray("overrides").addObject().put("method", "popup").put("minutes", 0);
        } else {
            reminders.putArray("overrides");
        }
        ObjectNode priv = ev.putObject("extendedProperties").putObject("private");
        priv.put("pluggisStudent", s.key());
        priv.put("pluggisDay", day.toString());
        return ev;
    }

    private static boolean differs(JsonNode have, ObjectNode want) {
        return !have.path("summary").asText("").equals(want.path("summary").asText())
                || !have.path("description").asText("").equals(want.path("description").asText())
                || !sameInstant(have.path("start").path("dateTime").asText(""), want.path("start").path("dateTime").asText())
                || !sameInstant(have.path("end").path("dateTime").asText(""), want.path("end").path("dateTime").asText());
    }

    private static boolean sameInstant(String a, String b) {
        try {
            return ZonedDateTime.parse(a).toInstant().equals(ZonedDateTime.parse(b).toInstant());
        } catch (RuntimeException e) {
            return a.equals(b);
        }
    }

    private Instant start(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(props.zone()).toInstant();
    }

    private static String friendly(RuntimeException e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("404")) {
            return "Hittar inte kalendern – kolla kalender-id och att den är delad med tjänstekontot";
        }
        if (m.contains("403")) {
            return "Ingen behörighet – dela kalendern med tjänstekontot och välj \"Gör ändringar i händelser\"";
        }
        if (m.contains("401") || m.contains("invalid_grant")) {
            return "Google godtog inte nyckeln – skapa en ny JSON-nyckel för tjänstekontot";
        }
        return "Google Kalender svarade inte: " + m;
    }
}
