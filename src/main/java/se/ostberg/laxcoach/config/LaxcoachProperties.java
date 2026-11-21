package se.ostberg.laxcoach.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

@ConfigurationProperties("laxcoach")
public record LaxcoachProperties(
        String deviceToken,
        String timezone,
        int maxHistoryMessages,
        int conversationIdleMinutes,
        Anthropic anthropic) {

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
