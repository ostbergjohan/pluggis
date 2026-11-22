package se.ostberg.pluggis.device;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import se.ostberg.pluggis.flashcards.FlashcardController;
import se.ostberg.pluggis.flashcards.FlashcardService;
import se.ostberg.pluggis.homework.Homework;
import se.ostberg.pluggis.homework.PhotoIntakeService;
import se.ostberg.pluggis.rewards.RewardsService;
import se.ostberg.pluggis.session.StudySessionService;

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

    @EventListener
    public void onDeckCreated(FlashcardController.DeckCreated e) {
        registry.send(e.deviceId(), new DeviceMessages.Say("Klart! Jag gjorde " + e.cardCount()
                + " kort i \"" + e.deck().getName() + "\". Tryck på blå knappen när du vill bli förhörd."));
    }

    @EventListener
    public void onCardPresented(FlashcardService.CardPresented e) {
        registry.send(e.deviceId(), new DeviceMessages.Card(e.card().getId(), e.card().getFront(), e.card().getHint()));
    }

    @EventListener
    public void onCardGraded(FlashcardService.CardGraded e) {
        if (e.quiet()) {
            return;
        }
        registry.send(e.deviceId(), new DeviceMessages.CardAnswer(
                e.card().getId(), e.card().getBack(), e.correct(), e.card().getBox()));
    }

    @EventListener
    public void onPoints(RewardsService.PointsAwarded e) {
        registry.send(e.deviceId(), new DeviceMessages.Points(e.points(), e.weekTotal(), e.reason()));
    }
}
