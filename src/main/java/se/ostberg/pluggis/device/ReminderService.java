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
 * Påminnelser på Tab5: en på eftermiddagen ("dags att börja") och en mjuk knuff på kvällen
 * om dagens plan inte är påbörjad. Tiderna styrs av pluggis.reminders.*-cron.
 */
@Service
public class ReminderService {

    private final DeviceRegistry registry;
    private final PlanningService planning;
    private final HomeworkService homework;
    private final StudySessionService sessions;
    private final PluggisProperties props;

    public ReminderService(DeviceRegistry registry, PlanningService planning, HomeworkService homework,
                           StudySessionService sessions, PluggisProperties props) {
        this.registry = registry;
        this.planning = planning;
        this.homework = homework;
        this.sessions = sessions;
        this.props = props;
    }

    @Scheduled(cron = "${pluggis.reminders.start-cron:0 30 16 * * MON-FRI}", zone = "${pluggis.timezone:Europe/Stockholm}")
    public void startReminder() {
        if (!enabled()) {
            return;
        }
        String text = startText(planning.today(), dueTomorrow());
        if (text == null) {
            return;
        }
        for (String deviceId : registry.connected()) {
            if (!sessions.startedToday(deviceId, props.zone())) {
                registry.send(deviceId, new DeviceMessages.Reminder(text));
            }
        }
    }

    @Scheduled(cron = "${pluggis.reminders.nudge-cron:0 30 18 * * MON-FRI}", zone = "${pluggis.timezone:Europe/Stockholm}")
    public void nudge() {
        if (!enabled() || planning.today().isEmpty()) {
            return;
        }
        for (String deviceId : registry.connected()) {
            if (!sessions.startedToday(deviceId, props.zone())) {
                registry.send(deviceId, new DeviceMessages.Reminder(
                        "Hej! Ett litet pass på 10 minuter räcker för idag. Tryck på blå knappen så hjälper jag dig börja."));
            }
        }
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
