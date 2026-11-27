package se.ostberg.pluggis.stats;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;
import java.time.LocalDate;

/** Varje rättat flashkortsförsök – underlag för statistik (oberoende av poängtaket). */
@Entity
public class ReviewLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String deviceId;

    private Long cardId;

    private Long deckId;

    private boolean correct;

    private LocalDate reviewedOn;

    private Instant reviewedAt;

    /** Kortets fråga och svar (korten tas bort efter några timmar – veckorapporten behöver texten). */
    @jakarta.persistence.Column(length = 200)
    private String front;

    @jakarta.persistence.Column(length = 120)
    private String back;

    protected ReviewLog() {
    }

    public ReviewLog(String deviceId, Long cardId, Long deckId, boolean correct, LocalDate reviewedOn, Instant reviewedAt) {
        this.deviceId = deviceId;
        this.cardId = cardId;
        this.deckId = deckId;
        this.correct = correct;
        this.reviewedOn = reviewedOn;
        this.reviewedAt = reviewedAt;
    }

    public ReviewLog withText(String front, String back) {
        this.front = front == null ? null : front.length() > 200 ? front.substring(0, 200) : front;
        this.back = back == null ? null : back.length() > 120 ? back.substring(0, 120) : back;
        return this;
    }

    public String getFront() {
        return front;
    }

    public String getBack() {
        return back;
    }

    public Long getId() { return id; }
    public String getDeviceId() { return deviceId; }
    public Long getCardId() { return cardId; }
    public Long getDeckId() { return deckId; }
    public boolean isCorrect() { return correct; }
    public LocalDate getReviewedOn() { return reviewedOn; }
    public Instant getReviewedAt() { return reviewedAt; }
}
