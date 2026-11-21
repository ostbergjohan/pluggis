package se.ostberg.pluggis.flashcards;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Duration;
import java.time.Instant;

/**
 * Ett flashkort med enkel Leitner-repetition (fack 1–5).
 * Rätt svar flyttar kortet ett fack upp och längre fram i tiden,
 * fel svar skickar tillbaka det till fack 1 och det kommer igen om en stund.
 */
@Entity
public class Card {

    public static final int MAX_BOX = 5;

    /** Tid till nästa repetition efter rätt svar, per fack (index = fack). */
    static final Duration[] INTERVAL = {
            Duration.ZERO,          // (oanvänt)
            Duration.ofMinutes(10), // fack 1
            Duration.ofDays(1),     // fack 2
            Duration.ofDays(3),     // fack 3
            Duration.ofDays(7),     // fack 4
            Duration.ofDays(21)     // fack 5
    };
    static final Duration RETRY_AFTER_WRONG = Duration.ofMinutes(2);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long deckId;

    @Column(length = 1000)
    private String front;

    @Column(length = 2000)
    private String back;

    @Column(length = 500)
    private String hint;

    private int box = 1;

    private Instant dueAt;

    private int timesCorrect;

    private int timesWrong;

    private Instant lastReviewedAt;

    protected Card() {
    }

    public Card(Long deckId, String front, String back, String hint, Instant now) {
        this.deckId = deckId;
        this.front = front;
        this.back = back;
        this.hint = hint;
        this.dueAt = now;
    }

    public void grade(boolean correct, Instant now) {
        lastReviewedAt = now;
        if (correct) {
            timesCorrect++;
            box = Math.min(MAX_BOX, box + 1);
            dueAt = now.plus(INTERVAL[box]);
        } else {
            timesWrong++;
            box = 1;
            dueAt = now.plus(RETRY_AFTER_WRONG);
        }
    }

    public void edit(String front, String back, String hint) {
        if (front != null && !front.isBlank()) {
            this.front = front.trim();
        }
        if (back != null && !back.isBlank()) {
            this.back = back.trim();
        }
        if (hint != null) {
            this.hint = hint.isBlank() ? null : hint.trim();
        }
    }

    public boolean isDue(Instant now) {
        return !dueAt.isAfter(now);
    }

    public Long getId() { return id; }
    public Long getDeckId() { return deckId; }
    public String getFront() { return front; }
    public String getBack() { return back; }
    public String getHint() { return hint; }
    public int getBox() { return box; }
    public Instant getDueAt() { return dueAt; }
    public int getTimesCorrect() { return timesCorrect; }
    public int getTimesWrong() { return timesWrong; }
    public Instant getLastReviewedAt() { return lastReviewedAt; }
}
