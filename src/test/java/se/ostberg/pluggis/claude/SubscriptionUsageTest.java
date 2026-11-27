package se.ostberg.pluggis.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionUsageTest {

    @Test
    void timmarOchVecka() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        SubscriptionUsage.parse(new ObjectMapper().readTree("""
                {"five_hour":{"utilization":42.0,"resets_at":"2026-11-27T15:00:00+00:00"},
                 "seven_day":{"utilization":81,"resets_at":"2026-12-01T09:00:00+00:00"},"seven_day_opus":null}"""), out);
        assertThat(out.get("fiveHour")).isEqualTo(Map.of("percent", 42, "resetsAt", "2026-11-27T15:00:00+00:00"));
        assertThat(((Map<?, ?>) out.get("sevenDay")).get("percent")).isEqualTo(81);
        assertThat(SubscriptionUsage.highest(out)).isEqualTo(81);
    }

    @Test
    void okantSvar() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        SubscriptionUsage.parse(new ObjectMapper().readTree("{\"annat\":1}"), out);
        assertThat(out).containsKey("error");
        assertThat(SubscriptionUsage.highest(null)).isEqualTo(-1);
    }
}
