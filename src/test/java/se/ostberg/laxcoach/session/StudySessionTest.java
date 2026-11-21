package se.ostberg.laxcoach.session;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StudySessionTest {

    @Test
    void countsDownAndFlagsTimeUp() {
        Instant start = Instant.parse("2026-11-21T15:00:00Z");
        StudySession s = new StudySession("tab5-1", "Matte", 15, start);

        assertThat(s.remainingSeconds(start)).isEqualTo(15 * 60);
        assertThat(s.remainingSeconds(start.plus(Duration.ofMinutes(10)))).isEqualTo(5 * 60);
        assertThat(s.isTimeUp(start.plus(Duration.ofMinutes(14)))).isFalse();
        assertThat(s.isTimeUp(start.plus(Duration.ofMinutes(15)))).isTrue();
        assertThat(s.remainingSeconds(start.plus(Duration.ofMinutes(20)))).isZero();
    }
}
