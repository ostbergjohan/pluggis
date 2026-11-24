package se.ostberg.pluggis.session;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PracticeTimeTest {

    private final StudySessionRepository repo = mock(StudySessionRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final StudySessionService service = new StudySessionService(repo, events,
            Clock.fixed(Instant.parse("2026-11-24T16:30:00Z"), ZoneOffset.UTC));

    @Test
    void övningstidBlirEttAvslutatPass() {
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repo.findFirstByDeviceIdAndStatusOrderByStartedAtDesc(anyString(), any())).thenReturn(Optional.empty());
        var s = service.recordPractice("fred", "Övning: Spanska glosor", Duration.ofMinutes(7)).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(StudySession.Status.FINISHED);
        assertThat(Duration.between(s.getStartedAt(), s.getFinishedAt())).isEqualTo(Duration.ofMinutes(7));
        verify(events).publishEvent(any(StudySessionService.PracticeRecorded.class));
    }

    @Test
    void underEnMinutEllerUnderEttPågåendePassSparasInget() {
        when(repo.findFirstByDeviceIdAndStatusOrderByStartedAtDesc(anyString(), any())).thenReturn(Optional.empty());
        assertThat(service.recordPractice("fred", "x", Duration.ofSeconds(40))).isEmpty();
        when(repo.findFirstByDeviceIdAndStatusOrderByStartedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(new StudySession("fred", "Pass", 15, Instant.parse("2026-11-24T16:20:00Z"))));
        assertThat(service.recordPractice("fred", "x", Duration.ofMinutes(5))).isEmpty();
        verify(repo, never()).save(any());
    }
}
