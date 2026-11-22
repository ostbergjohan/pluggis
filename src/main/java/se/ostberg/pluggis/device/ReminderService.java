package se.ostberg.pluggis.device;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.homework.Homework;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.planning.PlanningService;
import se.ostberg.pluggis.session.StudySessionService;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Påminnelser på Tab5 och i mobilchatten: vid elevens pluggtid ("dags att börja") och en mjuk knuff
 * två timmar senare om dagens plan inte är påbörjad. Pluggtid och "efter ..." ställs per elev.
 */
@Service
public class ReminderService {

    private final DeviceRegistry registry;
    private final PlanningService planning;
    private final HomeworkService homework;
    private final StudySessionService sessions;
    private final PluggisProperties props;

    private final se.ostberg.pluggis.student.StudentService students;

    public ReminderService(DeviceRegistry registry, PlanningService planning, HomeworkService homework,
                           StudySessionService sessions, PluggisProperties props,
                           se.ostberg.pluggis.student.StudentService students) {
        this.students = students;
        this.registry = registry;
        this.planning = planning;
        this.homework = homework;
        this.sessions = sessions;
        this.props = props;
    }

    /** Skickat idag: "deviceId|datum|start/nudge" – så att varje påminnelse bara kommer en gång. */
    private final java.util.Set<String> sent = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Varje minut: för varje ansluten enhet, vid elevens pluggtid (standard 16:30) en påminnelse att börja,
     * och två timmar senare en mjuk knuff om inget pass startats. Bara på elevens pluggdagar.
     */
    @Scheduled(cron = "0 * * * * *", zone = "${pluggis.timezone:Europe/Stockholm}")
    public void tick() {
        if (!enabled()) {
            return;
        }
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(props.zone());
        java.time.LocalTime minute = now.toLocalTime().withSecond(0).withNano(0);
        LocalDate day = now.toLocalDate();
        for (String deviceId : registry.connected()) {
            se.ostberg.pluggis.student.StudentContext.run(studentFor(deviceId), () -> {
                var student = students.current();
                if (!planning.studyDays().contains(day.getDayOfWeek())) {
                    return;
                }
                java.time.LocalTime start = student.studyTimeOrDefault();
                if (minute.equals(start) && sent.add(deviceId + "|" + day + "|start")) {
                    startReminder(deviceId, student.getStudyCue());
                } else if (minute.equals(start.plusHours(2)) && sent.add(deviceId + "|" + day + "|nudge")) {
                    nudge(deviceId);
                }
            });
        }
        if (sent.size() > 500) {
            sent.removeIf(k -> !k.contains("|" + day + "|"));
        }
    }

    void startReminder(String deviceId, String cue) {
        String text = startText(planning.today(), dueTomorrow());
        if (text != null && !sessions.startedToday(deviceId, props.zone())) {
            String lead = cue == null || cue.isBlank() ? "" : "Nu är det dags, " + cue.trim() + " som vi sa. ";
            registry.send(deviceId, new DeviceMessages.Reminder(lead + text));
        }
    }

    void nudge(String deviceId) {
        if (!planning.today().isEmpty() && !sessions.startedToday(deviceId, props.zone())) {
            registry.send(deviceId, new DeviceMessages.Reminder(
                    "Hej! Ett litet pass på 10 minuter räcker för idag. Tryck på blå knappen så hjälper jag dig börja."));
        }
    }

    private Long studentFor(String deviceId) {
        return students.byDeviceId(deviceId).map(se.ostberg.pluggis.student.Student::getId)
                .orElse(se.ostberg.pluggis.student.StudentContext.defaultId());
    }

    /** Ren textlogik – testbar utan Spring. */
    static String startText(List<PlanningService.TodayItem> today, List<Homework> dueTomorrow) {
        StringBuilder sb = new StringBuilder();
        if (!today.isEmpty()) {
            PlanningService.TodayItem first = today.get(0);
            sb.append("Dags att plugga! Först på listan: ").append(first.item().getTask())
              .append(" (").append(first.item().getMinutes()).append(" min).");
            if (today.size() > 1) {
                sb.append(" Sen ").append(today.size() - 1).append(today.size() == 2 ? " sak till." : " saker till.");
            }
        }
        if (!dueTomorrow.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append("Glöm inte: ")
              .append(dueTomorrow.stream()
                      .map(h -> (h.getKind() == Homework.Kind.TEST ? "provet i " : "") + h.getTitle())
                      .collect(Collectors.joining(" och ")))
              .append(" är imorgon.");
        }
        if (sb.isEmpty()) {
            return null;
        }
        return sb.append(" Tryck på blå knappen så kör vi.").toString();
    }

    private List<Homework> dueTomorrow() {
        LocalDate tomorrow = LocalDate.now(props.zone()).plusDays(1);
        return homework.openSchoolwork().stream().filter(h -> tomorrow.equals(h.getDueDate())).toList();
    }

    private boolean enabled() {
        return props.reminders() == null || props.reminders().enabled();
    }
}
