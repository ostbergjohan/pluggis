package se.ostberg.laxcoach.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@ConfigurationProperties("laxcoach")
public record LaxcoachProperties(
        String deviceToken,
        String timezone,
        int maxHistoryMessages,
        int conversationIdleMinutes,
        Anthropic anthropic,
        Rewards rewards,
        Planning planning,
        Calendar calendar,
        Reminders reminders) {

    /** Vilka veckodagar planen får lägga pluggmoment på, och max minuter per dag. */
    public record Planning(List<DayOfWeek> studyDays, int maxMinutesPerDay) {
    }

    /** Valfri iCal-länk (webcal/https) till en skolkalender. Tom = av. */
    public record Calendar(String icalUrl, int horizonDays) {
    }

    public record Reminders(boolean enabled) {
    }

    public Set<DayOfWeek> studyDays() {
        return planning == null || planning.studyDays() == null || planning.studyDays().isEmpty()
                ? EnumSet.allOf(DayOfWeek.class) : EnumSet.copyOf(planning.studyDays());
    }

    public int maxMinutesPerDay() {
        return planning == null || planning.maxMinutesPerDay() <= 0 ? 40 : planning.maxMinutesPerDay();
    }

    /**
     * Poäng → kronor. enabled=false: poäng räknas men coachen pratar inte om pengar.
     * weeklyCapSek: tak per vecka (0 eller tomt = inget tak).
     */
    public record Rewards(boolean enabled, BigDecimal sekPerPoint, BigDecimal weeklyCapSek) {
    }

    public record Anthropic(
            String apiKey,
            String baseUrl,
            String apiVersion,
            String model,
            String visionModel,
            int maxTokens) {
    }

    public ZoneId zone() {
        return ZoneId.of(timezone == null || timezone.isBlank() ? "Europe/Stockholm" : timezone);
    }

    public boolean tokenRequired() {
        return deviceToken != null && !deviceToken.isBlank();
    }

    public boolean tokenMatches(String candidate) {
        return !tokenRequired() || deviceToken.equals(candidate);
    }
}
