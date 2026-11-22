package se.ostberg.pluggis.gcal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google Kalender via ett tjänstekonto. Antingen sökväg till JSON-nyckeln ({@code serviceAccountFile})
 * eller själva JSON:en ({@code serviceAccountJson}). Utan någon av dem är Google Kalender avstängt.
 *
 * @param apiBase  för test – normalt https://www.googleapis.com/calendar/v3
 */
@ConfigurationProperties("pluggis.google")
public record GoogleProperties(String serviceAccountFile, String serviceAccountJson, String apiBase, Integer daysAhead) {

    public String apiBaseOrDefault() {
        return apiBase == null || apiBase.isBlank() ? "https://www.googleapis.com/calendar/v3" : apiBase;
    }

    public int days() {
        return daysAhead == null || daysAhead <= 0 ? 21 : daysAhead;
    }
}
