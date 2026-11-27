package se.ostberg.pluggis.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionLimitTest {

    @AfterEach
    void clean() {
        SubscriptionLimit.reset();
    }

    @Test
    void narDenSlapperLasesAvText() {
        Instant now = ZonedDateTime.of(2026, 11, 27, 12, 0, 0, 0, ZoneId.of("Europe/Stockholm")).toInstant();
        assertThat(SubscriptionLimit.parseReset("Claude AI usage limit reached|1764255600", now)).isEqualTo(Instant.ofEpochSecond(1764255600));
        assertThat(SubscriptionLimit.parseReset("5-hour limit reached ∙ resets 3pm", now).atZone(ZoneId.of("Europe/Stockholm")).getHour()).isEqualTo(15);
        // klockslag som redan varit idag → imorgon
        assertThat(SubscriptionLimit.parseReset("resets 9am", now).atZone(ZoneId.of("Europe/Stockholm")).getDayOfMonth()).isEqualTo(28);
        assertThat(SubscriptionLimit.parseReset("inget här", now)).isNull();
    }

    @Test
    void gransenNaddOchSlappt() throws Exception {
        long in2h = Instant.now().plusSeconds(7200).getEpochSecond();
        SubscriptionLimit.update(new ObjectMapper().readTree(
                "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"rejected\",\"rateLimitType\":\"five_hour\",\"resetsAt\":" + in2h + "}}"));
        assertThat(SubscriptionLimit.reachedNow()).isTrue();
        assertThat(SubscriptionLimit.message()).contains("det går igen");
        assertThat(SubscriptionLimit.snapshot()).containsEntry("type", "five_hour").containsEntry("reached", true);
        assertThat(SubscriptionLimit.userMessage(new RuntimeException(SubscriptionLimit.reached("limit")))).contains("Claude-tid");
        SubscriptionLimit.ok();
        assertThat(SubscriptionLimit.reachedNow()).isFalse();
    }

    @Test
    void naraGransenOchSparas() throws Exception {
        java.util.List<java.util.Map<String, Object>> saved = new java.util.ArrayList<>();
        SubscriptionLimit.onChange(saved::add);
        SubscriptionLimit.update(new ObjectMapper().readTree(
                "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed_warning\",\"rateLimitType\":\"seven_day\",\"utilization\":0.82}}"));
        assertThat(SubscriptionLimit.nearNow()).isTrue();
        assertThat(saved).hasSize(1);
        SubscriptionLimit.update(new ObjectMapper().readTree(
                "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed_warning\",\"rateLimitType\":\"seven_day\",\"utilization\":0.82}}"));
        assertThat(saved).hasSize(1);   // samma läge sparas inte igen
        var snap = SubscriptionLimit.snapshot();
        SubscriptionLimit.reset();
        SubscriptionLimit.restore(snap);
        assertThat(SubscriptionLimit.nearNow()).isTrue();
    }
}
