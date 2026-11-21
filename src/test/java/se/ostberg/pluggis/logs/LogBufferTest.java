package se.ostberg.pluggis.logs;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LogBufferTest {

    @Test
    void filtersByLevelTextAndSequenceAndKeepsCapacity() {
        LogBuffer buf = new LogBuffer();
        buf.add("DEBUG", "se.ostberg.pluggis.coach.CoachService", "main", "Verktyg start_session", Instant.now());
        buf.add("INFO", "se.ostberg.pluggis.device.DeviceWebSocketHandler", "main", "Enhet ansluten: tab5-1", Instant.now());
        buf.add("ERROR", "se.ostberg.pluggis.coach.CoachService", "main", "Claude svarade 400", Instant.now());

        assertThat(buf.since(0, "INFO", null, 100)).hasSize(2);
        assertThat(buf.since(0, "DEBUG", "claude", 100)).extracting(LogBuffer.LogLine::level).containsExactly("ERROR");
        assertThat(buf.since(2, "DEBUG", null, 100)).extracting(LogBuffer.LogLine::seq).containsExactly(3L);
        assertThat(buf.since(0, "INFO", null, 100).get(0).logger()).isEqualTo("s.o.p.device.DeviceWebSocketHandler");

        for (int i = 0; i < LogBuffer.CAPACITY + 50; i++) {
            buf.add("INFO", "x", "t", "rad " + i, Instant.now());
        }
        assertThat(buf.since(0, "DEBUG", null, LogBuffer.CAPACITY)).hasSize(LogBuffer.CAPACITY);
        assertThat(buf.lastSeq()).isEqualTo(3L + LogBuffer.CAPACITY + 50);
    }
}
