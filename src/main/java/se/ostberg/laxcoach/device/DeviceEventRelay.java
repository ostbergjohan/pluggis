package se.ostberg.laxcoach.device;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import se.ostberg.laxcoach.homework.Homework;
import se.ostberg.laxcoach.homework.PhotoIntakeService;
import se.ostberg.laxcoach.session.StudySessionService;

import java.util.stream.Collectors;

/** Skickar domänhändelser (timer, pass, nya läxor) vidare till rätt enhet. */
@Component
public class DeviceEventRelay {

    private final DeviceRegistry registry;

    public DeviceEventRelay(DeviceRegistry registry) {
        this.registry = registry;
    }

    @EventListener
    public void onTick(StudySessionService.SessionTick e) {
        registry.send(e.deviceId(), new DeviceMessages.Timer(e.remainingSeconds(), e.totalSeconds(), e.task()));
    }

    @EventListener
    public void onStarted(StudySessionService.SessionStarted e) {
        registry.send(e.deviceId(), DeviceMessages.SessionState.started(e.session().getTask(), e.session().getPlannedMinutes()));
    }

    @EventListener
    public void onTimeUp(StudySessionService.SessionTimeUp e) {
        registry.send(e.deviceId(), DeviceMessages.SessionState.timeUp(e.session().getTask(), e.session().getPlannedMinutes()));
        registry.send(e.deviceId(), new DeviceMessages.Say(
                "Tiden är ute! Bra kämpat. Vill du ta en kort paus eller köra ett pass till?"));
    }

    @EventListener
    public void onFinished(StudySessionService.SessionFinished e) {
        registry.send(e.deviceId(), DeviceMessages.SessionState.finished(e.session().getTask(), e.session().getPlannedMinutes()));
    }

    @EventListener
    public void onHomeworkAdded(PhotoIntakeService.HomeworkAddedEvent e) {
        if (e.saved().isEmpty()) {
            registry.send(e.deviceId(), new DeviceMessages.Say(
                    "Jag kunde inte läsa uppgiften. Håll pappret lite längre bort och stilla, så testar vi igen."));
            return;
        }
        String titles = e.saved().stream().map(Homework::getTitle).collect(Collectors.joining(", "));
        registry.send(e.deviceId(), new DeviceMessages.Say(
                "Jag har lagt till: " + titles + ". Ska vi börja med första steget?"));
    }
}
