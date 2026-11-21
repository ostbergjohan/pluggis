package se.ostberg.pluggis.rewards;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;
import java.time.LocalDate;

/** En poänghändelse. Poäng dras aldrig av – bara läggs till. */
@Entity
public class PointEvent {

    public enum Kind { CARD_ATTEMPT, CARD_CORRECT, SESSION_MINUTES, SESSION_COMPLETED, HOMEWORK_DONE, PLAN_ITEM_DONE, STREAK, BONUS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String deviceId;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    private int points;

    private String reason;

    /** T.ex. "card:42" – används för att begränsa poäng per sak och dag. */
    private String refKey;

    /** Lokalt datum (Europe/Stockholm) – förenklar dag- och veckosummor. */
    private LocalDate awardedOn;

    private Instant createdAt;

    protected PointEvent() {
    }

    public PointEvent(String deviceId, Kind kind, int points, String reason, String refKey, LocalDate awardedOn, Instant now) {
        this.deviceId = deviceId;
        this.kind = kind;
        this.points = points;
        this.reason = reason;
        this.refKey = refKey;
        this.awardedOn = awardedOn;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public String getDeviceId() { return deviceId; }
    public Kind getKind() { return kind; }
    public int getPoints() { return points; }
    public String getReason() { return reason; }
    public String getRefKey() { return refKey; }
    public LocalDate getAwardedOn() { return awardedOn; }
    public Instant getCreatedAt() { return createdAt; }
}
