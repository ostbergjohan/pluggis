package se.ostberg.pluggis.session;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Läxpass med timer. Timern ägs av backenden (inte av HA eller enheten), och
 * enheten får löpande "timer"-händelser att rita tidsstapeln/LED-listen efter.
 */
@Service
public class StudySessionService {

    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 60;

    /** Skickas ungefär var tionde sekund under ett aktivt pass. */
    public record SessionTick(String deviceId, long sessionId, long remainingSeconds, long totalSeconds, String task) {
    }

    public record SessionStarted(String deviceId, StudySession session) {
    }

    public record SessionTimeUp(String deviceId, StudySession session) {
    }

    /** Övningstid i Öva sparad som ett avslutat pass (ger minutpoäng och räknas i streak, men ingen "höll passet"). */
    public record PracticeRecorded(String deviceId, StudySession session) {
    }

    public record SessionFinished(String deviceId, StudySession session) {
    }

    private final StudySessionRepository repo;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public StudySessionService(StudySessionRepository repo, ApplicationEventPublisher events) {
        this(repo, events, Clock.systemUTC());
    }

    StudySessionService(StudySessionRepository repo, ApplicationEventPublisher events, Clock clock) {
        this.repo = repo;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public StudySession start(String deviceId, String task, int minutes) {
        int clamped = Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
        Instant now = clock.instant();
        active(deviceId).ifPresent(old -> old.finish("Ersattes av ett nytt pass", now));
        StudySession session = repo.save(new StudySession(deviceId, task, clamped, now));
        events.publishEvent(new SessionStarted(deviceId, session));
        return session;
    }

    /**
     * Sparar övningstid. Pågår ett vanligt pass räknas tiden redan där – då sparas inget extra.
     * Returnerar passet, eller tomt om inget sparades.
     */
    @Transactional
    public Optional<StudySession> recordPractice(String deviceId, String task, java.time.Duration time) {
        if (deviceId == null || time.toSeconds() < 60 || active(deviceId).isPresent()) {
            return Optional.empty();
        }
        Instant end = clock.instant();
        StudySession s = repo.save(StudySession.practice(deviceId, task, end.minus(time), end));
        events.publishEvent(new PracticeRecorded(deviceId, s));
        return Optional.of(s);
    }

    @Transactional
    public Optional<StudySession> finish(String deviceId, String summary) {
        Optional<StudySession> active = active(deviceId);
        active.ifPresent(s -> {
            s.finish(summary, clock.instant());
            events.publishEvent(new SessionFinished(deviceId, s));
        });
        return active;
    }

    @Transactional(readOnly = true)
    public Optional<StudySession> active(String deviceId) {
        return repo.findFirstByDeviceIdAndStatusOrderByStartedAtDesc(deviceId, StudySession.Status.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<StudySession> history(String deviceId) {
        return repo.findTop20ByDeviceIdOrderByStartedAtDesc(deviceId);
    }

    @Transactional(readOnly = true)
    public List<StudySession> since(String deviceId, Instant after) {
        return repo.findByDeviceIdAndStartedAtAfterOrderByStartedAtAsc(deviceId, after);
    }

    /** Har ett pass startats idag (lokal tid)? Används för att inte påminna i onödan. */
    @Transactional(readOnly = true)
    public boolean startedToday(String deviceId, java.time.ZoneId zone) {
        java.time.LocalDate today = java.time.LocalDate.now(clock.withZone(zone));
        return repo.findTop20ByDeviceIdOrderByStartedAtDesc(deviceId).stream()
                .anyMatch(s -> s.getStartedAt().atZone(zone).toLocalDate().equals(today));
    }

    /** Text till coachens systemprompt. */
    @Transactional(readOnly = true)
    public String describeForPrompt(String deviceId) {
        Instant now = clock.instant();
        return active(deviceId)
                .map(s -> "\"" + s.getTask() + "\", " + (s.remainingSeconds(now) / 60) + " av "
                        + s.getPlannedMinutes() + " minuter kvar" + (s.isTimeUp(now) ? " (tiden är ute)" : ""))
                .orElse("inget pass pågår");
    }

    @Scheduled(fixedRate = 10_000)
    @Transactional
    public void tick() {
        Instant now = clock.instant();
        for (StudySession s : repo.findByStatus(StudySession.Status.ACTIVE)) {
            long total = s.getPlannedMinutes() * 60L;
            events.publishEvent(new SessionTick(s.getDeviceId(), s.getId(), s.remainingSeconds(now), total, s.getTask()));
            if (s.isTimeUp(now) && !s.isTimeUpNotified()) {
                s.markTimeUpNotified();
                events.publishEvent(new SessionTimeUp(s.getDeviceId(), s));
            }
            // Ingen avslutade passet? Stäng det automatiskt så att poängen inte går förlorade.
            if (now.isAfter(s.getEndsAt().plus(AUTO_FINISH_AFTER))) {
                // Räkna passet som slut när timern gick ut, inte när vi märkte det.
                s.finish("Avslutades automatiskt", s.getEndsAt());
                events.publishEvent(new SessionFinished(s.getDeviceId(), s));
            }
        }
    }

    static final java.time.Duration AUTO_FINISH_AFTER = java.time.Duration.ofMinutes(15);
}
